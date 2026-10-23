package dev.ahmed.savlino.engine.instagram

import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

/** Carousel photos are fetched up to three at a time; order, failure counting, budgets and cancel stay serial-exact. */
class InstagramCarouselParallelTest : PublicImageTestBase() {
    private val html = mapOf("Content-Type" to "text/html; charset=utf-8")
    private val jpeg = mapOf("Content-Type" to "image/jpeg")
    private fun page(count: Int) = igPage(igPolaris(media = igMedia(8, children = (1..count).map { igPhoto(it) })))
    private fun photo(target: String) = (1..20).first { target == igImagePath(it, "_s640x640") }

    private fun PublicImageFixture.inspectIg(operation: ImageInspectionOperation =
        inspector.newOperation(imageSource(IG_POST), IMAGE_IDENTITY)) = runBlocking {
        inspector.inspect(operation, ImageSourceType.INSTAGRAM)
    }

    @Test fun sixSlowPhotosLoadAboutThreeTimesFasterThanOneAtATime() {
        val page = page(6)
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        fun timed(fetches: Int): Long {
            var elapsed = 0L
            fixture(respond = { request, socket ->
                if (request.target == IG_PAGE_TARGET) socket.reply(body = page, headers = html) else {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    Thread.sleep(300)
                    inFlight.decrementAndGet()
                    socket.reply(body = raster(40, 30, "jpg"), headers = jpeg)
                }
            }) { f ->
                f.inspector.carouselFetches = fetches
                val started = System.nanoTime()
                inspected(f.inspectIg()).use { assertEquals(6, it.inspection.items.size) }
                elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            }
            return elapsed
        }
        val serial = timed(1)
        assertEquals(1, peak.getAndSet(0))
        val parallel = timed(CAROUSEL_PARALLEL_FETCHES)
        assertEquals(3, peak.get())
        println("carousel timing: 6 photos x 300 ms, serial=${serial} ms parallel=${parallel} ms")
        assertTrue(serial >= 1800, "serial $serial")
        assertTrue(parallel < serial * 6 / 10, "serial $serial parallel $parallel")
    }

    @Test fun photosKeepTheirPostOrderWhenTheFirstFinishesLast() {
        val firstRelease = CountDownLatch(1)
        val others = AtomicInteger()
        fixture(respond = { request, socket ->
            if (request.target == IG_PAGE_TARGET) socket.reply(body = page(3), headers = html) else {
                val n = photo(request.target)
                if (n == 1) firstRelease.await(5, TimeUnit.SECONDS)
                socket.reply(body = raster(30 + n, 20, "jpg"), headers = jpeg)
                if (n != 1 && others.incrementAndGet() == 2) firstRelease.countDown()
            }
        }) { f ->
            inspected(f.inspectIg()).use { result ->
                assertEquals(listOf(0, 1, 2), result.inspection.items.map { it.position })
                assertEquals(listOf(31, 32, 33), result.inspection.items.map { item ->
                    assertNotNull(result.previews[item.renditions.single().ref]).width
                })
                // Photos 2 and 3 were answered before photo 1.
                assertEquals(0, firstRelease.count)
            }
        }
    }

    @Test fun neverMoreThanThreeInFlightAndCancelStopsAllOfThem() {
        val arrived = CountDownLatch(3)
        val release = CountDownLatch(1)
        val images = AtomicInteger()
        try {
            fixture(respond = { request, socket ->
                if (request.target == IG_PAGE_TARGET) socket.reply(body = page(6), headers = html) else {
                    images.incrementAndGet()
                    arrived.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    socket.reply(body = raster(40, 30, "jpg"), headers = jpeg)
                }
            }) { f ->
                val operation = f.inspector.newOperation(imageSource(IG_POST), IMAGE_IDENTITY)
                val result = java.util.concurrent.Executors.newSingleThreadExecutor().let { pool ->
                    try {
                        val pending = pool.submit<MediaInspectResult> { f.inspectIg(operation) }
                        assertTrue(arrived.await(5, TimeUnit.SECONDS), "three photos in flight")
                        Thread.sleep(300)
                        assertEquals(3, images.get())
                        f.inspector.cancel(operation)
                        pending.get(5, TimeUnit.SECONDS)
                    } finally { pool.shutdownNow() }
                }
                failed(result, ImageFailure.CANCELLED)
                release.countDown()
                Thread.sleep(200)
                assertEquals(3, images.get())
                val counters = operation.token.budget.snapshot()
                assertEquals(counters.socketsCreated, counters.socketsClosed)
            }
        } finally { release.countDown() }
    }

    @Test fun aFailedPhotoIsCountedUnavailableWhileItsParallelSiblingsLoad() {
        fixture(respond = { request, socket ->
            when {
                request.target == IG_PAGE_TARGET -> socket.reply(body = page(4), headers = html)
                photo(request.target) == 2 -> socket.reply(404)
                else -> socket.reply(body = raster(40, 30, "jpg"), headers = jpeg)
            }
        }) { f ->
            inspected(f.inspectIg()).use { result ->
                assertEquals(listOf(0, 2, 3), result.inspection.items.map { it.position })
                assertEquals(1, result.discovery.unavailableCount)
                assertEquals(0, result.discovery.notPreviewedCount)
            }
        }
    }
}
