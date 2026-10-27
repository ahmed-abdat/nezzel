package dev.ahmed.savlino.engine.tiktok

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

/** TikTok photo previews load up to three at a time; order and the shared 16 MiB budget stay as in the serial loop. */
class TikTokParallelPhotosTest : PublicImageTestBase() {
    private val post = "https://www.tiktok.com/@synthetic/photo/900000001"
    private val html = mapOf("Content-Type" to "text/html")
    private val jpeg = mapOf("Content-Type" to "image/jpeg")
    // Hand-made attachment data, not a real post.
    private fun page(count: Int): ByteArray {
        val item = """{"imageURL":{"urlList":["https://p16-sign.example.tiktokcdn.com/synthetic%s~tplv-photomode-image.jpeg?sig=fake"]}}"""
        val images = (1..count).joinToString(",") { item.format(it) }
        return tikTokPage("""{"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{"id":"900000001","imagePost":{"images":[$images]}}}}}}""")
    }
    private fun photo(target: String) = Regex("synthetic(\\d+)~").find(target)!!.groupValues[1].toInt()
    private fun PublicImageFixture.inspectPost(): MediaInspectResult = runBlocking {
        MediaSourceInspector(inspector, LegacyMediaInspectionAdapter(FakeEngine())).inspect(imageSource(post), "post", MediaSourceRoute.TIKTOK)
    }

    @Test fun sixSlowPhotosLoadThreeAtATime() {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        fun timed(fetches: Int): Long {
            var elapsed = 0L
            fixture(respond = { request, socket ->
                if (request.headers["host"] == "www.tiktok.com") socket.reply(body = page(6), headers = html) else {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    Thread.sleep(300)
                    inFlight.decrementAndGet()
                    socket.reply(body = raster(40, 30, "jpg"), headers = jpeg)
                }
            }) { f ->
                f.inspector.carouselFetches = fetches
                val started = System.nanoTime()
                inspected(f.inspectPost()).use { assertEquals(6, it.inspection.items.size) }
                elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            }
            return elapsed
        }
        val serial = timed(1)
        assertEquals(1, peak.getAndSet(0))
        val parallel = timed(CAROUSEL_PARALLEL_FETCHES)
        assertEquals(3, peak.get())
        assertTrue(serial >= 1800, "serial $serial")
        assertTrue(parallel < serial * 6 / 10, "serial $serial parallel $parallel")
    }

    @Test fun photosKeepThePostOrderWhenTheFirstFinishesLast() {
        val firstRelease = CountDownLatch(1)
        val others = AtomicInteger()
        fixture(respond = { request, socket ->
            if (request.headers["host"] == "www.tiktok.com") socket.reply(body = page(3), headers = html) else {
                val n = photo(request.target)
                if (n == 1) firstRelease.await(5, TimeUnit.SECONDS)
                socket.reply(body = raster(30 + n, 20, "jpg"), headers = jpeg)
                if (n != 1 && others.incrementAndGet() == 2) firstRelease.countDown()
            }
        }) { f -> inspected(f.inspectPost()).use { result ->
            assertEquals(listOf(31, 32, 33), result.inspection.items.map { item ->
                assertNotNull(result.previews[item.renditions.single().ref]).width
            })
            assertEquals(0, firstRelease.count) // Photos 2 and 3 were answered before photo 1.
        } }
    }

    @Test fun parallelPreviewsNeverPassTheSixteenMiBBudgetAndUnloadedPhotosStaySelectable() {
        val jpegBody = raster(40, 30, "jpg")
        val comment = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0xff.toByte(), 0xff.toByte()) + ByteArray(65533)
        // About 1.25 MiB each: 20 photos are 25 MiB, so the budget runs out part way.
        val large = jpegBody.copyOfRange(0, 2) + (1..20).fold(ByteArray(0)) { acc, _ -> acc + comment } +
            jpegBody.copyOfRange(2, jpegBody.size)
        fixture(respond = { request, socket -> when {
            request.headers["host"] == "www.tiktok.com" -> socket.reply(body = page(20), headers = html)
            request.target.contains("zoomcover") -> socket.reply(403)
            else -> socket.reply(body = large, headers = jpeg)
        } }) { f -> inspected(f.inspectPost()).use { result ->
            assertEquals(20, result.inspection.items.size)
            assertEquals((0 until 20).toList(), result.inspection.items.map { it.position })
            assertTrue(result.discovery.notPreviewedCount > 0)
            assertTrue(result.previews.size.toLong() * large.size <= 16L * 1024 * 1024, "previews ${result.previews.size}")
            // A refused resize flips later photos to originals; only photos already in flight tried their resize.
            assertTrue(f.server.requests.count { it.target.contains("zoomcover") } <= CAROUSEL_PARALLEL_FETCHES)
            val ref = assertIs<MediaRendition.Image>(result.inspection.items.last().renditions.single()).ref
            assertNotNull(result.inspection.selection(ref))
        } }
    }
}
