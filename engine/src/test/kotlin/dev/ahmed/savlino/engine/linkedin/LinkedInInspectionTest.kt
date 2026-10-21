package dev.ahmed.savlino.engine.linkedin

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.*

class LinkedInInspectionTest : PublicImageTestBase() {
    @Test fun actualShortlinkRedirectAndPageAssetShareBudgetAndUseResolvedPublicReferer() {
        fixture(respond = { request, socket -> when {
            request.target.startsWith("/p/") -> socket.reply(301, headers = mapOf("Location" to "$POST?tracking=synthetic"))
            request.target.startsWith("/posts/") -> socket.reply(body = linkedInHtml(), headers = mapOf("Content-Type" to "text/html; charset=utf-8"))
            else -> socket.reply(body = raster(), headers = mapOf("Content-Type" to "image/png"))
        } }) { f ->
            val operation = f.inspector.newOperation(imageSource("https://lnkd.in/p/synthetic"), IMAGE_IDENTITY)
            inspected(runBlocking { f.inspector.inspect(operation, ImageSourceType.LINKEDIN) }).use { result ->
                assertEquals(1, result.inspection.items.size)
                assertIs<ImageSaveAvailability.Available>(result.saveAvailability)
                assertEquals(3, f.server.requests.size)
                val asset = f.server.requests.last()
                assertEquals("/asset-one?sig=Ab%2fCD%2B%3d", asset.target)
                assertEquals(POST, asset.headers["referer"])
                assertTrue(asset.headers["user-agent"].orEmpty().contains("Mozilla/5.0"))
                assertTrue(asset.headers["accept"].orEmpty().contains("image/"))
                assertEquals("identity", asset.headers["accept-encoding"])
                assertNull(asset.headers["cookie"]); assertNull(asset.headers["authorization"])
                assertEquals(3, operation.token.budget.snapshot().requests)
                assertEquals(1, operation.token.budget.snapshot().redirects)
            }
        }
    }

    @Test fun actualActivityAndPostPagesFetchBothAttachmentsInDomOrder() {
        for (entry in listOf(POST, ACTIVITY)) fixture(respond = { request, socket ->
            if (request.target.startsWith("/posts/") || request.target.startsWith("/feed/"))
                socket.reply(body = linkedInHtml(listOf(ASSET, SECOND_ASSET), listOf(SECOND_ASSET, ASSET), og = null),
                    headers = mapOf("Content-Type" to "text/html"))
            else socket.reply(body = raster(), headers = mapOf("Content-Type" to "image/png"))
        }) { f ->
            inspected(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource(entry), IMAGE_IDENTITY), ImageSourceType.LINKEDIN) }).use { result ->
                assertEquals(2, result.discovery.discoveredCount)
                assertEquals(2, result.inspection.items.size)
                assertEquals(listOf(1, 1), result.inspection.items.map { it.renditions.size })
                assertEquals(listOf("/asset-two?sig=synthetic", "/asset-one?sig=Ab%2fCD%2B%3d"),
                    f.server.requests.drop(1).map { it.target })
                assertEquals(3, f.server.requests.size)
            }
        }
    }

    @Test fun image403StaysTypedImageFailureAndDoesNotRetryWithLegacyOrHeaders() {
        fixture(respond = { request, socket ->
            if (request.target.startsWith("/posts/")) socket.reply(body = linkedInHtml(), headers = mapOf("Content-Type" to "text/html"))
            else socket.reply(403)
        }) { f ->
            val failure = failed(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource(POST), IMAGE_IDENTITY), ImageSourceType.LINKEDIN) },
                ImageFailure.HTTP, ImagePhase.IMAGE)
            assertEquals(403, failure.statusCode)
            assertEquals(2, f.server.requests.size)
        }
    }

    @Test fun offProviderRedirectAndConflictingCanonicalStopBeforeAssetDns() {
        for (redirect in listOf(true, false)) fixture(respond = { _, socket ->
            if (redirect) socket.reply(302, headers = mapOf("Location" to "https://media.test/off-provider"))
            else socket.reply(body = linkedInHtml(canonical = "https://www.linkedin.com/posts/other-activity-999"), headers = mapOf("Content-Type" to "text/html"))
        }) { f ->
            failed(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource(POST), IMAGE_IDENTITY), ImageSourceType.LINKEDIN) }, ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
            assertEquals(1, f.http.dnsCalls.get())
            assertEquals(1, f.http.connects.get())
            assertEquals(1, f.server.requests.size)
        }
    }

    @Test fun postRedirectIntoADifferentPostStopsBeforeItsPageOrAnyAsset() {
        val other = "https://www.linkedin.com/posts/other-activity-999"
        for (location in listOf(other, "/feed/update/urn:li:activity:999")) fixture(respond = { request, socket ->
            if (request.target == "/posts/synthetic-activity-123") socket.reply(302, headers = mapOf("Location" to location))
            // Post B is internally consistent; it must still never be fetched, parsed or bound to post A.
            else if (request.target.startsWith("/posts/") || request.target.startsWith("/feed/"))
                socket.reply(body = linkedInHtml(canonical = other, jsonPost = other), headers = mapOf("Content-Type" to "text/html"))
            else socket.reply(body = raster(), headers = mapOf("Content-Type" to "image/png"))
        }) { f ->
            failed(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource(POST), IMAGE_IDENTITY), ImageSourceType.LINKEDIN) },
                ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
            assertEquals(1, f.server.requests.size, "redirect target B is never requested")
            assertEquals(1, f.http.dnsCalls.get())
        }
    }

    @Test fun pageAndAssetRedirectsCannotResetSharedThreeHopBudget() {
        fixture(respond = { request, socket -> when {
            request.target.startsWith("/p/") -> socket.reply(302, headers = mapOf("Location" to POST))
            request.target == "/posts/synthetic-activity-123" -> socket.reply(302, headers = mapOf("Location" to "$POST?step=2"))
            request.target.startsWith("/posts/") -> socket.reply(body = linkedInHtml(), headers = mapOf("Content-Type" to "text/html"))
            request.target.startsWith("/asset-one") -> socket.reply(302, headers = mapOf("Location" to SECOND_ASSET))
            else -> socket.reply(302, headers = mapOf("Location" to "https://media.licdn.com/never-fetched"))
        } }) { f ->
            failed(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource("https://lnkd.in/p/synthetic"), IMAGE_IDENTITY), ImageSourceType.LINKEDIN) },
                ImageFailure.BUDGET, ImagePhase.IMAGE)
            assertEquals(5, f.server.requests.size)
            assertTrue(f.server.requests.none { it.target.contains("never-fetched") })
        }
    }

    @Test fun pageOverSixMibIsRejectedBeforeParserAssetOrDecodeAndOffersTheCleanPostToYtDlp() {
        fixture(respond = { _, socket -> socket.reply(declared = 6L * 1024 * 1024 + 1, headers = mapOf("Content-Type" to "text/html")) }) { f ->
            val failure = failed(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource(POST), IMAGE_IDENTITY), ImageSourceType.LINKEDIN) }, ImageFailure.BUDGET, ImagePhase.PAGE)
            assertEquals(1, f.server.requests.size)
            assertEquals("$POST/", failure.fallback?.link?.rawUrl)
            assertEquals(1L, failure.fallback?.revision)
        }
    }

    @Test fun linkedinSaveReusesBoundPostContextOnFreshOperationAndExactAsset() {
        fixture(respond = { request, socket ->
            if (request.target.startsWith("/posts/")) socket.reply(body = linkedInHtml(), headers = mapOf("Content-Type" to "text/html"))
            else socket.reply(body = raster(), headers = mapOf("Content-Type" to "image/png"))
        }) { f ->
            inspected(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource(POST), IMAGE_IDENTITY), ImageSourceType.LINKEDIN) }).use { result ->
                val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
                val sink = ByteArrayOutputStream()
                assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertContentEquals(raster(), sink.toByteArray())
                assertEquals(3, f.server.requests.size, "Save refetches asset only, without page refresh")
                assertEquals(POST, f.server.requests.last().headers["referer"])
                assertEquals(f.server.requests[1].target, f.server.requests[2].target)
            }
        }
    }

    @Test fun sixtyFifthPostContextEvictsOldestAndKeepsEveryCarouselSiblingSaveable() {
        val carousel = (1..5).map { "https://media.licdn.com/synthetic-carousel-$it" }
        var assets = listOf(ASSET)
        fixture(respond = { request, socket ->
            if (request.target.startsWith("/posts/")) socket.reply(body = linkedInHtml(assets), headers = mapOf("Content-Type" to "text/html"))
            else socket.reply(body = raster(), headers = mapOf("Content-Type" to "image/png"))
        }) { f ->
            var oldest: MediaSelection? = null
            var newest = emptyList<MediaSelection>()
            repeat(65) { index ->
                if (index == 64) assets = carousel
                inspected(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource(POST, index.toLong()), IMAGE_IDENTITY), ImageSourceType.LINKEDIN) }).use { result ->
                    assertIs<ImageSaveAvailability.Available>(result.saveAvailability, "bounded store evicts instead of exhausting")
                    val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
                    if (index == 0) oldest = selection
                    if (index == 64) newest = result.inspection.items.map { item ->
                        assertNotNull(result.inspection.selection(item.renditions.single().ref))
                    }
                }
            }
            assertEquals(5, newest.size)
            val contexts = newest.map { assertNotNull(assertNotNull(f.store.resolve(it)).postContext) }
            assertTrue(contexts.all { it === contexts.first() })
            assertEquals(134, f.server.requests.size)
            val before = f.http.dnsCalls.get()
            val evictedSink = ByteArrayOutputStream()
            val evicted = assertIs<ImageSaveResult.Failure>(runBlocking { f.saver.transfer(f.saver.newOperation(oldest!!), evictedSink) })
            assertEquals(ImageFailure.SAVE_UNAVAILABLE, evicted.reason)
            assertEquals(0, evictedSink.size()); assertEquals(before, f.http.dnsCalls.get())
            assertEquals(134, f.server.requests.size, "evicted context never reaches the network")
            for (selection in newest) {
                val sink = ByteArrayOutputStream()
                assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertContentEquals(raster(), sink.toByteArray())
                assertEquals(POST, f.server.requests.last().headers["referer"])
            }
            assertEquals(139, f.server.requests.size)
        }
    }
}
