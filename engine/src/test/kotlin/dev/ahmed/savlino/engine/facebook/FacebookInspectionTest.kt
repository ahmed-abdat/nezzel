package dev.ahmed.savlino.engine.facebook

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class FacebookInspectionTest : PublicImageTestBase() {
    private val html = mapOf("Content-Type" to "text/html; charset=utf-8")
    private val png = mapOf("Content-Type" to "image/png")
    private val jpeg = mapOf("Content-Type" to "image/jpeg")
    private val storyTarget = "/story.php?story_fbid=900000000000001&id=42"

    /** Share link → story.php (desktop page) → CDN image, as recorded live; everything else is 404. */
    private fun facebook(page: String = "photo-post.html", image: ByteArray = raster(), imageType: Map<String, String> = png,
        override: (RecordedMediaRequest, SSLSocket) -> Boolean = { _, _ -> false }): (RecordedMediaRequest, SSLSocket) -> Unit =
        { request, socket ->
            val host = request.headers["host"].orEmpty()
            if (!override(request, socket)) when {
                host == "www.facebook.com" && request.target.startsWith("/share/p/") ->
                    socket.reply(302, headers = mapOf("Location" to "https://www.facebook.com$storyTarget&rdid=synthetic"))
                host == "www.facebook.com" && request.target == storyTarget ->
                    socket.reply(body = facebookFixture(page).toByteArray(), headers = html)
                host == "scontent.example.fbcdn.net" -> socket.reply(body = image, headers = imageType)
                else -> socket.reply(404)
            }
        }

    private fun PublicImageFixture.inspectFacebook(url: String, revision: Long = 1) = runBlocking {
        inspector.inspect(inspector.newOperation(imageSource(url, revision), IMAGE_IDENTITY), ImageSourceType.FACEBOOK)
    }

    @Test fun loginNextStoryIsADistinctTerminalFailureWithoutFetchingIt() {
        val next = URLEncoder.encode("https://web.facebook.com/stories/900000000000001/Synthetic/", "UTF-8")
        fixture(respond = { _, socket ->
            socket.reply(302, headers = mapOf("Location" to "https://www.facebook.com/login.php?next=$next"))
        }) { f ->
            failed(f.inspectFacebook(FB_SHARE), ImageFailure.FACEBOOK_STORY, ImagePhase.PAGE)
            assertEquals(1, f.server.requests.size)
        }
    }

    @Test fun videoShareLandingOnAMixedPostReturnsTheCanonicalWatchLinkWithoutFetchingPhotos() {
        val target = "/synthetic/posts/pfbid0Synthetic"
        for (kind in listOf("v", "r")) fixture(respond = facebook("mixed-post.html", override = { request, socket ->
            when {
                request.target.startsWith("/share/$kind/") -> {
                    socket.reply(302, headers = mapOf("Location" to "https://www.facebook.com$target"))
                    true
                }
                request.target == target -> {
                    socket.reply(body = facebookFixture("mixed-post.html").toByteArray(), headers = html)
                    true
                }
                else -> false
            }
        })) { f ->
            val result = assertIs<MediaInspectResult.VideoRedirect>(f.inspectFacebook("https://www.facebook.com/share/$kind/Synthetic/", 7))
            assertEquals("https://www.facebook.com/watch/?v=900000000000003", result.source.link.rawUrl)
            assertEquals(7, result.source.revision)
            assertEquals(listOf("/share/$kind/Synthetic/", target), f.server.requests.map { it.target })
        }
    }

    @Test fun mixedPostPhotosCarryItsCanonicalVideoLinkAtTheOperationsRevision() {
        val target = "/synthetic/posts/900000000000001"
        for (firstUnavailable in listOf(false, true)) fixture(respond = facebook("mixed-post.html", override = { request, socket ->
            when {
                request.target == target -> {
                    socket.reply(body = facebookFixture("mixed-post.html").toByteArray(), headers = html)
                    true
                }
                firstUnavailable && request.target == "/4.jpg" -> { socket.reply(404); true }
                else -> false
            }
        })) { f ->
            val result = assertIs<MediaInspectResult.Success>(f.inspectFacebook("https://www.facebook.com$target", 7))
            try {
                assertTrue(result.discovery.hasVideo)
                assertEquals("https://www.facebook.com/watch/?v=900000000000003", result.videoSource?.link?.rawUrl)
                assertEquals(7L, result.videoSource?.revision)
                val count = if (firstUnavailable) 3 else 4
                assertEquals(count, result.inspection.items.size)
                assertEquals(count, result.previews.size)
                assertEquals(ImageDiscovery(count, DiscoveryCompleteness.PARTIAL, 11,
                    unavailableCount = if (firstUnavailable) 1 else 0, hasVideo = true), result.discovery)
                assertTrue(result.inspection.items.all { it.renditions.size == 1 })
                // Photos load in parallel: the page first, then each photo once in any order.
                assertEquals(listOf(target, "/10.jpg", "/4.jpg", "/6.jpg", "/8.jpg"), f.server.requests.map { it.target }.let { it.take(1) + it.drop(1).sorted() })
            } finally { result.close() }
        }
    }

    @Test fun avifPhotoKeepsItsSignedUrlFormatAndExactBytesThroughSave() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/avif/still-64x48.avif")).use { it.readBytes() }
        // JDK ImageIO has no AVIF codec; this seam tests format admission and Save, not pixel decoding.
        val decoder = ImagePreviewDecoder { _, mime, _, _ ->
            assertEquals("image/avif", mime)
            ImageDecodeResult.Success(object : OwnedImagePreview {
                override val width = 64
                override val height = 48
                override val allocationBytes = 64L * 48 * 4
                override var isClosed = false; private set
                override fun close() { isClosed = true }
            })
        }
        val asset = "/synthetic.avif?stp=dst-avif&sig=synthetic%2Fsignature"
        fixture(decoder = decoder, respond = facebook(override = { request, socket ->
            when {
                request.target == storyTarget -> {
                    socket.reply(body = facebookFixture("photo-post.html").replace(cdn(1),
                        "https://scontent.example.fbcdn.net$asset").toByteArray(), headers = html)
                    true
                }
                request.headers["host"] == "scontent.example.fbcdn.net" -> {
                    socket.reply(body = bytes, headers = mapOf("Content-Type" to "image/avif"))
                    true
                }
                else -> false
            }
        })) { f ->
            inspected(f.inspectFacebook(FB_STORY)).use { result ->
                val rendition = firstRendition(result)
                assertEquals(MediaImageFormat.AVIF, rendition.image.format)
                assertEquals(64, rendition.image.width); assertEquals(48, rendition.image.height)
                val selection = assertNotNull(result.inspection.selection(rendition.ref))
                val sink = ByteArrayOutputStream()
                val saved = assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertEquals(MediaImageFormat.AVIF, saved.image.format)
                assertEquals(imageDigest(bytes), assertNotNull(saved.image.proof).completeBodySha256)
                assertContentEquals(bytes, sink.toByteArray())
                assertEquals(listOf(asset, asset), f.server.requests.filter {
                    it.headers["host"] == "scontent.example.fbcdn.net"
                }.map { it.target })
            }
        }
    }

    @Test fun shareLinkRedirectsToStoryAndFetchesTheAttachmentWithBrowserHeaders() {
        fixture(respond = facebook()) { f ->
            inspected(f.inspectFacebook(FB_SHARE)).use { result ->
                assertEquals(1, result.inspection.items.size)
                assertIs<ImageSaveAvailability.Available>(result.saveAvailability)
                assertTrue(result.discovery.previewQuality, "photo page 404 → truthful smaller copy")
                assertEquals(listOf("/share/p/1SyntheticAb/", storyTarget, photoTarget, "/1.jpg"), f.server.requests.map { it.target })
                val page = f.server.requests[1]
                assertEquals("www.facebook.com", page.headers["host"])
                val agent = page.headers["user-agent"].orEmpty()
                assertTrue(agent.contains("Chrome/") && !agent.contains("Mobile") && !agent.contains("okhttp"), "desktop Chrome UA")
                assertTrue(page.headers["accept"].orEmpty().startsWith("text/html"))
                assertTrue(page.headers["accept-language"].orEmpty().isNotBlank())
                assertEquals("navigate", page.headers["sec-fetch-mode"])
                assertEquals("none", page.headers["sec-fetch-site"])
                assertEquals("document", page.headers["sec-fetch-dest"])
                assertEquals("identity", page.headers["accept-encoding"])
                for (request in f.server.requests) {
                    assertNull(request.headers["cookie"]); assertNull(request.headers["authorization"])
                }
                assertEquals("scontent.example.fbcdn.net", f.server.requests[3].headers["host"])
                assertNull(f.server.requests[3].headers["referer"])
            }
        }
    }

    @Test fun mobileAndWebHostsAreNormalisedToWwwBeforeTheFirstRequest() {
        fixture(respond = facebook()) { f ->
            inspected(f.inspectFacebook("https://m.facebook.com/story.php?story_fbid=900000000000001&id=42&mibextid=x")).close()
            assertEquals("www.facebook.com", f.server.requests.first().headers["host"])
            assertEquals(storyTarget, f.server.requests.first().target)
        }
    }

    private fun regionalFacebook(): (RecordedMediaRequest, SSLSocket) -> Unit = { request, socket ->
        val host = request.headers["host"].orEmpty()
        when {
            host == "www.facebook.com" && (request.target.startsWith("/share/") || request.target == storyTarget) ->
                socket.reply(302, headers = mapOf("Location" to "https://web.facebook.com${request.target}" +
                    (if ('?' in request.target) "&" else "?") + "_rdc=1&_rdr"))
            host == "web.facebook.com" && request.target.startsWith("/share/") ->
                socket.reply(302, headers = mapOf("Location" to "https://web.facebook.com$storyTarget&post_id=synthetic&rdid=synthetic"))
            host == "web.facebook.com" && request.target == storyTarget ->
                socket.reply(body = facebookFixture("photo-post.html")
                    .replace("scontent.example.fbcdn.net", "z-p3-scontent.example.fbcdn.net").toByteArray(), headers = html)
            host == "z-p3-scontent.example.fbcdn.net" -> socket.reply(body = raster(), headers = png)
            else -> socket.reply(404)
        }
    }

    private fun regionalPhoto(url: String, shareTarget: String? = null) {
        fixture(respond = regionalFacebook()) { f ->
            inspected(f.inspectFacebook(url)).use { result ->
                assertEquals(1, result.inspection.items.size)
                assertIs<ImageSaveAvailability.Available>(result.saveAvailability)
                val expected = (shareTarget?.let { listOf("www.facebook.com" to it, "web.facebook.com" to it) }
                    ?: listOf("www.facebook.com" to storyTarget)) + listOf("web.facebook.com" to storyTarget,
                    "www.facebook.com" to photoTarget, "z-p3-scontent.example.fbcdn.net" to "/1.jpg")
                assertEquals(expected, f.server.requests.map { it.headers["host"] to it.target })
            }
        }
    }

    @Test fun regionalSharePostRedirectKeepsWebHostAndFetchesThePhoto() {
        regionalPhoto(FB_SHARE, "/share/p/1SyntheticAb/")
    }

    @Test fun regionalPlainShareRedirectKeepsWebHostAndFetchesThePhoto() {
        regionalPhoto("https://www.facebook.com/share/1SyntheticAb/", "/share/1SyntheticAb/")
    }

    @Test fun regionalStoryRedirectKeepsWebHostAndFetchesThePhoto() {
        regionalPhoto(FB_STORY)
    }

    @Test fun regionalShortlinkRedirectRejectsDifferentTokensAndKinds() {
        for (target in listOf("/share/p/2OtherShare/", "/share/v/1SyntheticAb/", "/share/1SyntheticAb/")) {
            fixture(respond = { _, socket ->
                socket.reply(302, headers = mapOf("Location" to "https://web.facebook.com$target?_rdc=1&_rdr"))
            }) { f ->
                f.http.client.newOperation(MediaHttpOperation.PAGES_AND_IMAGE).use { token ->
                    assertFailure(f.http.client.fetchFacebookPage(token, FB_SHARE), MediaHttpFailure.INVALID_CONTEXT)
                }
                assertEquals(1, f.server.requests.size)
            }
        }
    }

    @Test fun regionalSameShortlinkRedirectIsAllowedOnlyOnce() {
        fixture(respond = { _, socket ->
            socket.reply(302, headers = mapOf("Location" to "https://web.facebook.com/share/p/1SyntheticAb/?_rdc=1&_rdr"))
        }) { f ->
            f.http.client.newOperation(MediaHttpOperation.PAGES_AND_IMAGE).use { token ->
                assertFailure(f.http.client.fetchFacebookPage(token, FB_SHARE), MediaHttpFailure.INVALID_CONTEXT)
            }
            assertEquals(listOf("www.facebook.com", "web.facebook.com"), f.server.requests.map { it.headers["host"] })
        }
    }

    @Test fun regionalPostHostPingPongEndsInRedirectLoop() {
        fixture(respond = { request, socket ->
            val host = if (request.headers["host"] == "www.facebook.com") "web.facebook.com" else "www.facebook.com"
            socket.reply(302, headers = mapOf("Location" to "https://$host$storyTarget&_rdc=1&_rdr"))
        }) { f ->
            f.http.client.newOperation(MediaHttpOperation.PAGES_AND_IMAGE).use { token ->
                assertFailure(f.http.client.fetchFacebookPage(token, FB_STORY), MediaHttpFailure.REDIRECT_LOOP)
            }
            assertEquals(listOf("www.facebook.com", "web.facebook.com"), f.server.requests.map { it.headers["host"] })
        }
    }

    @Test fun regionalLoginNextUsesWallHostAndPersistentWallIsPrivate() {
        val next = URLEncoder.encode("https://www.facebook.com$storyTarget&rdid=synthetic", "UTF-8")
        val wall = "https://web.facebook.com/login/?next=$next"
        for (persistent in listOf(false, true)) fixture(respond = { request, socket ->
            when {
                request.headers["host"] == "www.facebook.com" && request.target == storyTarget ||
                    persistent && request.headers["host"] == "web.facebook.com" ->
                    socket.reply(302, headers = mapOf("Location" to wall))
                else -> regionalFacebook()(request, socket)
            }
        }) { f ->
            if (persistent) failed(f.inspectFacebook(FB_STORY), ImageFailure.PRIVATE, ImagePhase.PAGE)
            else inspected(f.inspectFacebook(FB_STORY)).use { assertEquals(1, it.inspection.items.size) }
            assertEquals(listOf("www.facebook.com", "web.facebook.com"),
                f.server.requests.take(2).map { it.headers["host"] })
            assertEquals(listOf(storyTarget, storyTarget), f.server.requests.take(2).map { it.target })
            if (persistent) assertEquals(2, f.server.requests.size)
        }
    }

    private val photoTarget = "/photo/?fbid=900000000000002"
    /** The photo-post attachment's own photo page: currMedia carries the larger copy (declared 2048). */
    private fun photoPage(id: String = "900000000000002") = facebookFixture("photo-page.html")
        .replace("\"id\":\"900000000000001\"", "\"id\":\"$id\"").replace(cdn(1), cdn(50)).toByteArray()
    private fun withPhotoPage(reply: (SSLSocket) -> Unit) = facebook(override = { request, socket ->
        if (request.target.startsWith("/photo/")) { reply(socket); true } else false
    })
    private fun pageRequests(f: PublicImageFixture) = f.server.requests.count { it.headers["host"] == "www.facebook.com" }

    @Test fun previewAttachmentFetchesItsOwnPhotoPageAndTakesTheLargerMatchedImage() {
        fixture(respond = withPhotoPage { it.reply(body = photoPage(), headers = html) }) { f ->
            inspected(f.inspectFacebook(FB_STORY)).use { result ->
                assertEquals(1, result.inspection.items.size)
                assertEquals(1, result.previews.size)
                assertFalse(result.discovery.previewQuality, "full-size copy from the photo page")
                assertEquals(ImageDiscovery(1, DiscoveryCompleteness.COMPLETE), result.discovery)
                assertEquals(listOf(storyTarget, photoTarget, "/50.jpg"), f.server.requests.map { it.target })
                val photo = f.server.requests[1]
                assertEquals("navigate", photo.headers["sec-fetch-mode"])
                assertTrue(photo.headers["user-agent"].orEmpty().contains("Chrome/"))
                assertNull(photo.headers["cookie"])
                assertEquals(2, pageRequests(f))
            }
        }
    }

    @Test fun failedOrUnmatchedPhotoPageFallsBackToTheSmallerCopyLabelledPreview() {
        val replies: List<(SSLSocket) -> Unit> = listOf(
            { it.reply(500) },
            { it.reply(302, headers = mapOf("Location" to "https://www.facebook.com/login/")) },
            { it.reply(302, headers = mapOf("Location" to "https://evil.example/photo")) },
            { it.reply(body = facebookFixture("unavailable.html").toByteArray(), headers = html) },
            { it.reply(body = photoPage("900000000000077"), headers = html) },
            { it.reply(declared = 10L * 1024 * 1024 + 1, headers = html) })
        for (reply in replies) fixture(respond = withPhotoPage(reply)) { f ->
            inspected(f.inspectFacebook(FB_SHARE)).use { result ->
                assertTrue(result.discovery.previewQuality, "truthful smaller-copy label")
                assertEquals("/1.jpg", f.server.requests.last().target)
                assertEquals(photoTarget, f.server.requests[2].target)
                assertTrue(pageRequests(f) <= 3, "share redirect + story + one photo page")
                assertEquals(2, f.server.requests.count { it.target == storyTarget || it.target == photoTarget })
            }
        }
    }

    @Test fun neverMoreThanTwoFacebookPageFetchesPerOperation() {
        fixture(respond = withPhotoPage { it.reply(body = photoPage(), headers = html) }) { f ->
            f.http.client.newOperation(MediaHttpOperation.PAGES_AND_IMAGE).use { token ->
                assertSuccess(f.http.client.fetchFacebookPage(token, "https://www.facebook.com$storyTarget")).close()
                assertSuccess(f.http.client.fetchFacebookPage(token, "https://www.facebook.com$photoTarget")).close()
                assertFailure(f.http.client.fetchFacebookPage(token, "https://www.facebook.com$storyTarget"), MediaHttpFailure.RESOURCE_LIMIT)
            }
            assertEquals(2, f.server.requests.size)
            f.http.client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
                assertFailure(f.http.client.fetchFacebookPage(token, "https://www.facebook.com$storyTarget"), MediaHttpFailure.INVALID_CONTEXT)
            }
            assertEquals(2, f.server.requests.size, "a one-page (LinkedIn) operation cannot fetch Facebook pages")
        }
    }

    @Test fun facebookPageCapIsTenMibAtTheBoundary() {
        fun padded(size: Int): ByteArray {
            val page = facebookFixture("album-post.html").toByteArray()
            return page + ByteArray(size - page.size) { ' '.code.toByte() }
        }
        for (size in listOf(3 * 1024 * 1024 + 1, 10 * 1024 * 1024)) fixture(respond = facebook(override = { request, socket ->
            if (request.target == storyTarget) { socket.reply(body = padded(size), headers = html); true } else false
        })) { f ->
            inspected(f.inspectFacebook(FB_STORY)).use { assertEquals(18, it.discovery.claimedCount) }
        }
        fixture(respond = facebook(override = { request, socket ->
            if (request.target == storyTarget) { socket.reply(declared = 10L * 1024 * 1024 + 1, headers = html); true } else false
        })) { f ->
            failed(f.inspectFacebook(FB_STORY), ImageFailure.BUDGET, ImagePhase.PAGE)
            assertEquals(1, f.server.requests.size)
        }
    }

    @Test fun albumPreviewsEveryExposedPhotoAndSavesEachOriginalWhileReportingPartialEighteen() {
        // Album originals are .jpg URLs, so their Save expects (and the CDN sends) JPEG.
        val photo = raster(format = "jpeg")
        fixture(respond = facebook("album-post.html", image = photo, imageType = jpeg)) { f ->
            inspected(f.inspectFacebook(FB_STORY)).use { result ->
                assertEquals(5, result.inspection.items.size)
                assertEquals(listOf(1, 1, 1, 1, 1), result.inspection.items.map { it.renditions.size })
                assertEquals(listOf(0, 1, 2, 3, 4), result.inspection.items.map { it.position })
                assertEquals(5, result.previews.size)
                assertTrue(result.inspection.items.all { it.renditions.single().ref in result.previews })
                assertEquals(ImageDiscovery(5, DiscoveryCompleteness.PARTIAL, 18), result.discovery)
                assertEquals(listOf(storyTarget, "/1.jpg", "/3.jpg", "/5.jpg", "/7.jpg", "/9.jpg"),
                    f.server.requests.map { it.target }.let { it.take(1) + it.drop(1).sorted() })
                for ((index, item) in result.inspection.items.withIndex()) {
                    val rendition = assertIs<MediaRendition.Image>(item.renditions.single())
                    assertEquals(Size.Unknown, rendition.image.size)
                    assertNull(rendition.image.proof)
                    val selection = assertNotNull(result.inspection.selection(rendition.ref))
                    val sink = ByteArrayOutputStream()
                    assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                    assertContentEquals(photo, sink.toByteArray())
                    assertEquals("/${(index + 1) * 2}.jpg", f.server.requests.last().target)
                }
                assertTrue(f.server.requests.drop(1).all { it.headers["host"] == "scontent.example.fbcdn.net" })
            }
        }
    }

    @Test fun albumKeepsOtherPhotosWhenOnePreviewIsUnavailableOrRedirectsOffCdn() {
        for (redirect in listOf(false, true)) fixture(respond = facebook("album-post.html", override = { request, socket ->
            if (request.target == "/1.jpg") {
                if (redirect) socket.reply(302, headers = mapOf("Location" to "https://external.example.fbcdn.net/1.jpg"))
                else socket.reply(404)
                true
            } else false
        })) { f ->
            inspected(f.inspectFacebook(FB_STORY)).use { result ->
                assertEquals(4, result.inspection.items.size)
                assertEquals(4, result.previews.size)
                assertEquals(listOf(1, 2, 3, 4), result.inspection.items.map { it.position })
                assertEquals(ImageDiscovery(4, DiscoveryCompleteness.PARTIAL, 18, unavailableCount = 1), result.discovery)
                assertEquals(listOf(storyTarget, "/1.jpg", "/3.jpg", "/5.jpg", "/7.jpg", "/9.jpg"),
                    f.server.requests.map { it.target }.let { it.take(1) + it.drop(1).sorted() })
                assertTrue(f.server.requests.none { it.headers["host"] == "external.example.fbcdn.net" })
            }
        }
    }

    @Test fun albumExhaustedImageBudgetKeepsCompletedPreviewAndCountsRemainingPhotos() {
        val bytes = raster()
        fixture(respond = facebook("album-post.html", image = bytes)) { f ->
            val operation = f.inspector.newOperation(imageSource(FB_STORY), IMAGE_IDENTITY)
            operation.token.budget.consumed(2 * 1024 * 1024 - bytes.size, image = true)
            inspected(runBlocking { f.inspector.inspect(operation, ImageSourceType.FACEBOOK) }).use { result ->
                assertEquals(1, result.inspection.items.size)
                assertEquals(1, result.previews.size)
                assertEquals(ImageDiscovery(1, DiscoveryCompleteness.PARTIAL, 18, notPreviewedCount = 4), result.discovery)
                assertEquals(listOf(storyTarget, "/1.jpg"), f.server.requests.map { it.target })
                assertEquals(0, operation.token.budget.imageLimit())
            }
        }
    }

    @Test fun albumCapsImageRequestsAtTwentyAndCountsUnfetchedCandidates() {
        val nodes = (1..25).joinToString(",") { index ->
            """{"media":{"__typename":"Photo","id":"${900000000000010L + index}","viewer_image":{"uri":"${cdn(index)}"}}}"""
        }
        val page = facebookFixture("album-post.html")
            .replace(Regex("\"count\":18,\"nodes\":\\[.*?]"), "\"count\":25,\"nodes\":[$nodes]")
        fixture(respond = facebook(override = { request, socket ->
            if (request.target == storyTarget) { socket.reply(body = page.toByteArray(), headers = html); true } else false
        })) { f ->
            inspected(f.inspectFacebook(FB_STORY)).use { result ->
                assertEquals(20, result.inspection.items.size)
                assertEquals(20, result.previews.size)
                assertEquals(ImageDiscovery(20, DiscoveryCompleteness.PARTIAL, 25, cappedCount = 5), result.discovery)
                assertEquals(listOf(storyTarget) + (1..20).map { "/$it.jpg" }.sorted(), f.server.requests.map { it.target }.let { it.take(1) + it.drop(1).sorted() })
            }
        }
    }

    @Test fun carouselSaveReferencesRejectRedirectsOutsideFacebookCdn() {
        fixture(respond = facebook("album-post.html", override = { request, socket ->
            if (request.target == "/4.jpg") {
                socket.reply(302, headers = mapOf("Location" to "https://external.example.fbcdn.net/4.jpg"))
                true
            } else false
        })) { f ->
            inspected(f.inspectFacebook(FB_STORY)).use { result ->
                val ref = result.inspection.items[1].renditions.single().ref
                val selection = assertNotNull(result.inspection.selection(ref))
                val sink = ByteArrayOutputStream()
                assertIs<ImageSaveResult.Failure>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertEquals(0, sink.size())
                assertEquals("/4.jpg", f.server.requests.last().target)
                assertTrue(f.server.requests.none { it.headers["host"] == "external.example.fbcdn.net" })
            }
        }
    }

    @Test fun offDomainRedirectsAreRejectedBeforeAnyFurtherRequest() {
        for (location in listOf("https://evil.example/share", "https://www.facebook.com.evil.example/story.php?story_fbid=1&id=2",
                "https://l.facebook.com/l.php?u=https%3A%2F%2Fevil.example", "http://www.facebook.com$storyTarget",
                "https://www.facebook.com/somepage")) {
            fixture(respond = facebook(override = { request, socket ->
                if (request.target.startsWith("/share/")) { socket.reply(302, headers = mapOf("Location" to location)); true } else false
            })) { f ->
                failed(f.inspectFacebook(FB_SHARE), ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
                assertEquals(1, f.server.requests.size)
                assertEquals(1, f.http.dnsCalls.get())
            }
        }
    }

    @Test fun loginNextIsUnwrappedOnceAndAPersistentLoginWallIsPrivate() {
        val next = URLEncoder.encode("https://www.facebook.com$storyTarget", "UTF-8")
        fixture(respond = facebook(override = { request, socket ->
            if (request.target.startsWith("/share/")) {
                socket.reply(302, headers = mapOf("Location" to "https://www.facebook.com/login/?next=$next")); true
            } else false
        })) { f ->
            inspected(f.inspectFacebook(FB_SHARE)).close()
            assertEquals(listOf("/share/p/1SyntheticAb/", storyTarget, photoTarget, "/1.jpg"), f.server.requests.map { it.target },
                "the login page itself is never requested")
        }
        for (location in listOf("https://www.facebook.com/login/", "https://m.facebook.com/login.php?next=$next",
                "https://www.facebook.com/login/?next=" + URLEncoder.encode("https://evil.example/x", "UTF-8"))) {
            fixture(respond = facebook(override = { request, socket ->
                if (request.target == storyTarget) { socket.reply(302, headers = mapOf("Location" to location)); true } else false
            })) { f ->
                val failure = failed(f.inspectFacebook(FB_STORY), ImageFailure.PRIVATE, ImagePhase.PAGE)
                assertNull(failure.statusCode)
                assertEquals(1, f.server.requests.size)
            }
        }
    }

    @Test fun loginPageWithCdnOgImageIsPrivateWithoutAnyImageRequest() {
        val wall = facebookFixture("login-page.html").replace("</head>", "<meta property=\"og:image\" content=\"${cdn(7)}\" /></head>")
        fixture(respond = facebook(override = { request, socket ->
            if (request.target == storyTarget) { socket.reply(body = wall.toByteArray(), headers = html); true } else false
        })) { f ->
            failed(f.inspectFacebook(FB_STORY), ImageFailure.PRIVATE, ImagePhase.PAGE)
            assertEquals(listOf(storyTarget), f.server.requests.map { it.target })
        }
    }

    @Test fun shortlinksAreNeverATerminalOrHopTargetAndLookalikeLoginPathsAreNotUnwrapped() {
        val next = URLEncoder.encode("https://www.facebook.com$storyTarget", "UTF-8")
        for (location in listOf("https://www.facebook.com/share/p/2OtherShare/", "https://fb.watch/other/",
                "https://www.facebook.com/login.evil?next=$next", "https://www.facebook.com/checkpoint.evil/?next=$next",
                "https://www.facebook.com/loginx/?next=$next")) {
            fixture(respond = facebook(override = { request, socket ->
                if (request.target.startsWith("/share/p/1")) { socket.reply(302, headers = mapOf("Location" to location)); true }
                else if (request.target.startsWith("/share/p/2")) { socket.reply(body = facebookFixture("photo-post.html").toByteArray(), headers = html); true }
                else false
            })) { f ->
                failed(f.inspectFacebook(FB_SHARE), ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
                assertEquals(1, f.server.requests.size, "no follow-up request")
            }
        }
        // A share page answered directly (no redirect) is not a resolved post either.
        fixture(respond = facebook(override = { request, socket ->
            if (request.target.startsWith("/share/")) { socket.reply(body = facebookFixture("photo-post.html").toByteArray(), headers = html); true } else false
        })) { f ->
            failed(f.inspectFacebook(FB_SHARE), ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
            assertTrue(f.server.requests.none { it.headers["host"] == "scontent.example.fbcdn.net" })
        }
    }

    @Test fun http400ShellUnavailableAndVideoPagesAreTypedFailures() {
        fixture(respond = facebook(override = { request, socket ->
            if (request.target == storyTarget) { socket.reply(400, body = facebookFixture("http400-shell.html").toByteArray(), headers = html); true } else false
        })) { f ->
            assertEquals(400, failed(f.inspectFacebook(FB_STORY), ImageFailure.HTTP, ImagePhase.PAGE).statusCode)
        }
        fixture(respond = facebook("unavailable.html")) { f ->
            failed(f.inspectFacebook(FB_STORY), ImageFailure.PRIVATE, ImagePhase.PAGE)
            assertEquals(1, f.server.requests.size)
        }
        fixture(respond = facebook("video-post.html")) { f ->
            failed(f.inspectFacebook(FB_STORY), ImageFailure.NO_MEDIA, ImagePhase.PAGE)
            assertEquals(1, f.server.requests.size, "a video thumbnail is never fetched as the image")
        }
    }

    @Test fun imageRedirectMayNotLeaveTheScontentCdn() {
        fixture(respond = facebook(override = { request, socket ->
            if (request.target == "/1.jpg") {
                socket.reply(302, headers = mapOf("Location" to "https://external.example.fbcdn.net/1.jpg")); true
            } else false
        })) { f ->
            failed(f.inspectFacebook(FB_STORY), ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE)
            assertTrue(f.server.requests.none { it.headers["host"] == "external.example.fbcdn.net" })
        }
    }

    @Test fun cdnRuleRejectsAnOffCdnFirstUrlAtTheTransport() {
        fixture { f ->
            f.http.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                assertFailure(f.http.client.fetch(token, PublicMediaRequest("https://media.test/1.jpg", MediaHttpPurpose.IMAGE,
                    hostRule = MediaHostRule.FACEBOOK_CDN)), MediaHttpFailure.INVALID_CONTEXT)
            }
            assertEquals(0, f.server.requests.size)
            f.http.client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
                assertFailure(f.http.client.fetchFacebookPage(token, "https://media.test/story.php"), MediaHttpFailure.INVALID_CONTEXT)
            }
            assertEquals(0, f.server.requests.size)
        }
    }

    @Test fun saveRefetchesTheExactFixturePhotoThroughTheRealValidator() {
        val bytes = raster(48, 30)
        fixture(respond = facebook(image = bytes)) { f ->
            inspected(f.inspectFacebook(FB_SHARE)).use { result ->
                val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
                val sink = ByteArrayOutputStream()
                val saved = assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertContentEquals(bytes, sink.toByteArray())
                assertEquals(imageDigest(bytes), assertNotNull(saved.image.proof).completeBodySha256)
                assertEquals(48, saved.image.width)
                assertEquals(5, f.server.requests.size, "Save refetches the asset only")
                assertEquals("/1.jpg", f.server.requests.last().target)
                assertEquals("scontent.example.fbcdn.net", f.server.requests.last().headers["host"])
            }
        }
    }

    @Test fun saveStaysOnTheCdnWhenTheCdnRedirectsAway() {
        val redirectSave = java.util.concurrent.atomic.AtomicBoolean()
        fixture(respond = facebook(override = { request, socket ->
            if (request.target == "/1.jpg" && redirectSave.get()) {
                socket.reply(302, headers = mapOf("Location" to "https://external.example.fbcdn.net/1.jpg")); true
            } else false
        })) { f ->
            inspected(f.inspectFacebook(FB_STORY)).use { result ->
                val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
                redirectSave.set(true)
                val sink = ByteArrayOutputStream()
                assertIs<ImageSaveResult.Failure>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertEquals(0, sink.size())
                assertTrue(f.server.requests.none { it.headers["host"] == "external.example.fbcdn.net" })
            }
        }
    }

    @Test fun facadeRoutesFacebookToTheReaderAndOtherTypesCannotBorrowIt() {
        fixture(respond = facebook()) { f ->
            val facade = MediaSourceInspector(f.inspector, LegacyMediaInspectionAdapter(FakeEngine(InspectResult.Failure(DownloadError.Unsupported))))
            inspected(runBlocking { facade.inspect(imageSource(FB_SHARE), "fb", MediaSourceRoute.FACEBOOK) }).close()
            assertEquals(4, f.server.requests.size)
            failed(runBlocking { facade.inspect(imageSource(POST, 2), "li-as-fb", MediaSourceRoute.FACEBOOK) },
                ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
            failed(runBlocking { facade.inspect(imageSource(FB_SHARE, 3), "fb-as-li", MediaSourceRoute.LINKEDIN) },
                ImageFailure.SOURCE_REFUSED)
            assertEquals(4, f.server.requests.size, "mismatched routes make no request")
        }
    }
}
