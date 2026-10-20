package dev.ahmed.savlino.engine.x

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class XInspectionTest : PublicImageTestBase() {
    private val json = mapOf("Content-Type" to "application/json; charset=utf-8")
    private val jpeg = mapOf("Content-Type" to "image/jpeg")
    private val photoTarget = "/media/SyntheticPhoto1?format=jpg&name=orig"

    /** Syndication JSON for this post's exact id+token, photos on pbs.twimg.com/media; everything else is 404. */
    private fun x(post: ByteArray = xTweet(), image: ByteArray = raster(40, 30, "jpg"),
        override: (RecordedMediaRequest, SSLSocket) -> Boolean = { _, _ -> false }): (RecordedMediaRequest, SSLSocket) -> Unit =
        { request, socket ->
            val host = request.headers["host"].orEmpty()
            if (!override(request, socket)) when {
                host == "cdn.syndication.twimg.com" && request.target == X_SYNDICATION_TARGET ->
                    socket.reply(body = post, headers = json)
                host == "pbs.twimg.com" && request.target.startsWith("/media/") -> socket.reply(body = image, headers = jpeg)
                else -> socket.reply(404)
            }
        }

    private fun PublicImageFixture.inspectX(url: String = X_POST, revision: Long = 1) = runBlocking {
        inspector.inspect(inspector.newOperation(imageSource(url, revision), IMAGE_IDENTITY), ImageSourceType.X)
    }

    @Test fun singlePhotoIsOneSyndicationReadAndOneOriginalPhotoWithoutCookies() {
        fixture(respond = x()) { f ->
            inspected(f.inspectX("https://twitter.com/someone/status/$X_ID/photo/1?s=20")).use { result ->
                assertEquals(1, result.inspection.items.size)
                assertEquals(ImageDiscovery(1, DiscoveryCompleteness.COMPLETE), result.discovery)
                assertIs<ImageSaveAvailability.Available>(result.saveAvailability)
                assertEquals(40, firstRendition(result).image.width)
                val metadata = result.inspection.items.single().metadata
                assertEquals("Synthetic caption line", metadata.title)
                assertEquals("Synthetic Author", metadata.uploader)
                assertEquals(listOf(X_SYNDICATION_TARGET, photoTarget), f.server.requests.map { it.target })
                assertEquals(listOf("cdn.syndication.twimg.com", "pbs.twimg.com"), f.server.requests.map { it.headers["host"] })
                assertEquals("application/json", f.server.requests[0].headers["accept"])
                for (request in f.server.requests) {
                    assertNull(request.headers["cookie"]); assertNull(request.headers["authorization"])
                    assertNull(request.headers["referer"]); assertEquals("identity", request.headers["accept-encoding"])
                }
            }
        }
    }

    @Test fun fourPhotosHaveFourSelectableRenditionsAndRealPreviews() {
        fixture(respond = x(post = xTweet((1..4).map { xPhoto("SyntheticPhoto$it") }))) { f ->
            inspected(f.inspectX()).use { result ->
                assertEquals(ImageDiscovery(4, DiscoveryCompleteness.COMPLETE), result.discovery)
                assertEquals(4, result.inspection.items.size)
                assertEquals(1, result.inspection.items[0].renditions.size)
                assertTrue(result.inspection.items.all { it.renditions.size == 1 })
                assertEquals(4, result.previews.size)
                assertEquals(5, f.server.requests.size, "each actual photo is fetched")
                assertEquals("/media/SyntheticPhoto4?format=jpg&name=small", f.server.requests.last().target)
            }
        }
    }

    @Test fun singlePhotoKeeps1024PreviewAndCarouselSavesOriginalInsteadOfSmallCopy() {
        fixture(respond = x(image = raster(1600, 1200, "jpg"))) { f ->
            inspected(f.inspectX()).use { assertEquals(1024, assertNotNull(it.preview).width) }
        }
        val original = raster(1200, 900, "jpg")
        fixture(respond = x(post = xTweet((1..10).map { xPhoto("SyntheticPhoto$it") }), override = { request, socket ->
            if (request.target.startsWith("/media/")) {
                socket.reply(body = if (request.target.endsWith("name=small")) raster(200, 150, "jpg") else original, headers = jpeg)
                true
            } else false
        })) { f ->
            inspected(f.inspectX()).use { result ->
                assertEquals(10, result.previews.size)
                assertTrue(f.server.requests.drop(1).all { it.target.endsWith("format=jpg&name=small") })
                val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
                val sink = ByteArrayOutputStream()
                assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertContentEquals(original, sink.toByteArray())
                assertEquals(photoTarget, f.server.requests.last().target)
            }
        }
    }

    @Test fun largeOriginalIsFetchedAsTheLargeCopyLabelledPreviewQuality() {
        fixture(respond = x(post = xTweet(listOf(xPhoto(width = 4096, height = 4096))))) { f ->
            inspected(f.inspectX()).use { result -> assertTrue(result.discovery.previewQuality) }
            assertEquals("/media/SyntheticPhoto1?format=jpg&name=large", f.server.requests.last().target)
        }
    }

    @Test fun videoAndGifOnlyPostsHandTheUsersOwnLinkBackWithoutImageRequests() {
        for (media in listOf(listOf(xVideo()), listOf(xVideo("animated_gif")))) {
            fixture(respond = x(post = xTweet(media))) { f ->
                val link = "https://x.com/someone/status/$X_ID?s=20"
                val redirect = assertIs<MediaInspectResult.VideoRedirect>(f.inspectX(link, revision = 7))
                assertEquals(link, redirect.source.link.rawUrl)
                assertEquals(7, redirect.source.revision)
                assertEquals(listOf(X_SYNDICATION_TARGET), f.server.requests.map { it.target })
            }
        }
    }

    @Test fun textAndLinkCardPostIsNoMediaWithoutAnImageRequest() {
        fixture(respond = x(post = xTextWithCard())) { f ->
            failed(f.inspectX(), ImageFailure.LINKED_ELSEWHERE, ImagePhase.PAGE)
            assertEquals(1, f.server.requests.size)
        }
        fixture(respond = x(post = xTombstone())) { f -> failed(f.inspectX(), ImageFailure.PRIVATE, ImagePhase.PAGE) }
    }

    @Test fun missingMalformedAndOversizeSyndicationReadsAreTypedFailures() {
        fixture(respond = x(override = { _, socket -> socket.reply(404); true })) { f ->
            assertEquals(404, failed(f.inspectX(), ImageFailure.HTTP, ImagePhase.PAGE).statusCode)
        }
        fixture(respond = x(post = "<html>changed</html>".toByteArray())) { f ->
            failed(f.inspectX(), ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
            assertEquals(1, f.server.requests.size)
        }
        fixture(respond = x(override = { request, socket ->
            if (request.headers["host"] == "cdn.syndication.twimg.com") { socket.reply(declared = 512L * 1024 + 1, headers = json); true }
            else false
        })) { f -> failed(f.inspectX(), ImageFailure.BUDGET, ImagePhase.PAGE) }
        fixture(respond = x(post = xTweet(listOf(xPhoto(), xPhoto("SyntheticPhoto2"))), override = { request, socket ->
            if (request.headers["host"] == "pbs.twimg.com") { socket.reply(declared = 2L * 1024 * 1024 + 1, headers = jpeg); true }
            else false
        })) { f -> failed(f.inspectX(), ImageFailure.PREVIEW_LIMIT, ImagePhase.IMAGE) }
    }

    /** Timeout, partial body and dropped connection on the syndication read are page-phase NETWORK (app falls back once). */
    @Test fun syndicationTransportFailuresArePagePhaseNetwork() {
        val release = java.util.concurrent.CountDownLatch(1)
        val stalls: List<(SSLSocket) -> Unit> = listOf(
            { it.reply(body = byteArrayOf(1, 2, 3), declared = 100, headers = json) },
            { it.close() },
            { release.await(20, java.util.concurrent.TimeUnit.SECONDS) })
        try {
            for (stall in stalls) fixture(respond = x(override = { request, socket ->
                if (request.headers["host"] == "cdn.syndication.twimg.com") { stall(socket); true } else false
            })) { f ->
                failed(f.inspectX(), ImageFailure.NETWORK, ImagePhase.PAGE)
                assertEquals(1, f.server.requests.size)
            }
        } finally { release.countDown() }
    }

    @Test fun anOffShapePhotoUrlInAPhotoOnlyPostIsAnImagePhaseFailureWithoutAnImageRequest() {
        fixture(respond = x(post = xTweet(listOf(xPhoto().replace("https://pbs.twimg.com/media/SyntheticPhoto1.jpg",
            "https://pbs.twimg.com/profile_images/A.jpg"))))) { f ->
            failed(f.inspectX(), ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE)
            assertEquals(1, f.server.requests.size)
        }
    }

    @Test fun syndicationRedirectsAreNeverFollowed() {
        for (location in listOf("https://cdn.syndication.twimg.com$X_SYNDICATION_TARGET&x=1", "https://evil.example/tweet",
            "https://x.com/someone/status/$X_ID")) {
            fixture(respond = x(override = { request, socket ->
                if (request.headers["host"] == "cdn.syndication.twimg.com") {
                    socket.reply(302, headers = mapOf("Location" to location)); true
                } else false
            })) { f ->
                failed(f.inspectX(), ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
                assertEquals(1, f.server.requests.size)
            }
        }
    }

    @Test fun photoRedirectsMayNotLeavePbsMedia() {
        for (location in listOf("https://video.twimg.com/media/SyntheticPhoto1.jpg",
            "https://pbs.twimg.com/profile_images/42/Synthetic.jpg", "https://evil.example/a.jpg")) {
            fixture(respond = x(override = { request, socket ->
                if (request.target == photoTarget) { socket.reply(302, headers = mapOf("Location" to location)); true } else false
            })) { f ->
                failed(f.inspectX(), ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE)
                assertEquals(2, f.server.requests.size)
            }
        }
    }

    @Test fun transportAllowsOnlyTheExactSyndicationReadAndPbsMedia() {
        fixture(respond = x()) { f ->
            val client = f.http.client
            client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
                assertFailure(client.fetch(token, PublicMediaRequest("https://cdn.syndication.twimg.com$X_SYNDICATION_TARGET",
                    MediaHttpPurpose.PAGE)), MediaHttpFailure.INVALID_CONTEXT)
            }
            client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
                assertFailure(client.fetch(token, PublicMediaRequest("https://pbs.twimg.com/profile_images/1/A.jpg",
                    MediaHttpPurpose.IMAGE, hostRule = MediaHostRule.X_MEDIA)), MediaHttpFailure.INVALID_CONTEXT)
            }
            client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
                assertFailure(client.fetch(token, PublicMediaRequest("https://media.test/media/A.jpg",
                    MediaHttpPurpose.IMAGE, hostRule = MediaHostRule.X_MEDIA)), MediaHttpFailure.INVALID_CONTEXT)
            }
            client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                assertFailure(client.fetchXPost(token, assertNotNull(XPostIdentity.parse(X_POST))), MediaHttpFailure.INVALID_CONTEXT)
            }
            client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
                assertSuccess(client.fetchXPost(token, assertNotNull(XPostIdentity.parse(X_POST)))).close()
                assertFailure(client.fetchXPost(token, assertNotNull(XPostIdentity.parse(X_POST))), MediaHttpFailure.RESOURCE_LIMIT)
            }
            assertEquals(1, f.server.requests.size, "refused shapes make no request")
        }
    }

    @Test fun saveRefetchesTheSamePhotoFromPbsOnly() {
        val bytes = raster(48, 30, "jpg")
        fixture(respond = x(image = bytes)) { f ->
            inspected(f.inspectX()).use { result ->
                val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
                val sink = ByteArrayOutputStream()
                val saved = assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertContentEquals(bytes, sink.toByteArray())
                assertEquals(imageDigest(bytes), assertNotNull(saved.image.proof).completeBodySha256)
                assertEquals(3, f.server.requests.size, "Save refetches the photo only")
                assertEquals(photoTarget, f.server.requests.last().target)
                assertEquals("pbs.twimg.com", f.server.requests.last().headers["host"])
            }
        }
    }

    @Test fun saveStaysOnPbsMediaWhenTheCdnRedirectsAway() {
        val redirectSave = AtomicBoolean()
        fixture(respond = x(override = { request, socket ->
            if (request.target == photoTarget && redirectSave.get()) {
                socket.reply(302, headers = mapOf("Location" to "https://video.twimg.com/x.jpg")); true
            } else false
        })) { f ->
            inspected(f.inspectX()).use { result ->
                val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
                redirectSave.set(true)
                val sink = ByteArrayOutputStream()
                assertIs<ImageSaveResult.Failure>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertEquals(0, sink.size())
                assertTrue(f.server.requests.none { it.headers["host"] == "video.twimg.com" })
            }
        }
    }

    @Test fun facadeRoutesXToTheReaderAndOtherRoutesCannotBorrowIt() {
        fixture(respond = x()) { f ->
            val facade = MediaSourceInspector(f.inspector, LegacyMediaInspectionAdapter(FakeEngine(InspectResult.Failure(DownloadError.Unsupported))))
            inspected(runBlocking { facade.inspect(imageSource(X_POST), "x", MediaSourceRoute.X) }).close()
            assertEquals(2, f.server.requests.size)
            failed(runBlocking { facade.inspect(imageSource(POST, 2), "li-as-x", MediaSourceRoute.X) },
                ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
            failed(runBlocking { facade.inspect(imageSource(X_POST, 3), "x-as-li", MediaSourceRoute.LINKEDIN) },
                ImageFailure.SOURCE_REFUSED)
            failed(runBlocking { facade.inspect(imageSource(X_POST, 4), "x-as-fb", MediaSourceRoute.FACEBOOK) },
                ImageFailure.SOURCE_REFUSED)
            failed(runBlocking { facade.inspect(imageSource("https://x.com/someone", 5), "profile-as-x", MediaSourceRoute.X) },
                ImageFailure.SOURCE_REFUSED)
            assertEquals(2, f.server.requests.size, "mismatched routes make no request")
        }
    }
}
