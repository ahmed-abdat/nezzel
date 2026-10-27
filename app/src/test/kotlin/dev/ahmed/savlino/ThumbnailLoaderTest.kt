package dev.ahmed.savlino

import android.graphics.Bitmap
import dev.ahmed.savlino.ui.ThumbnailLoader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ThumbnailLoaderTest {
    private class Connection(private val bytes: ByteArray, private val status: Int = 200, private val length: Long = bytes.size.toLong()) : HttpURLConnection(URL("https://i.ytimg.com/image")) {
        var closed = false
        var bytesRead = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun getInputStream() = ByteArrayInputStream(bytes).also { bytesRead = true }
    }
    @Test fun decoderOomAtEitherPassReturnsNoThumbnailAndClosesConnection() = runBlocking {
        for (failAt in listOf(1, 2)) {
            val connection = Connection(byteArrayOf(1))
            var calls = 0
            val loader = ThumbnailLoader(decodeBytes = { _, options ->
                calls++
                if (calls == failAt) throw OutOfMemoryError("synthetic decoder pressure")
                options.outWidth = 640; options.outHeight = 480
                null
            }, open = { connection })
            assertNull(loader.load("https://i.ytimg.com/image"))
            assertEquals(failAt, calls); assertTrue(connection.closed)
        }
    }
    @Test fun imageDecodesInMemoryDownsampledAndConnectionCloses() = runBlocking {
        val output = ByteArrayOutputStream()
        Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(40, 120, 110)); compress(Bitmap.CompressFormat.PNG, 100, output); recycle()
        }
        val connection = Connection(output.toByteArray())
        val bitmap = ThumbnailLoader { connection }.load("https://i.ytimg.com/image")!!
        assertTrue(bitmap.width <= 480); assertTrue(bitmap.height <= 480)
        assertEquals(16.0 / 9, bitmap.width.toDouble() / bitmap.height, .02)
        assertTrue(connection.closed); assertFalse(connection.useCaches); assertFalse(connection.instanceFollowRedirects)
        bitmap.recycle()
    }
    @Test fun invalidRedirectOversizeAndDecodeFailureLeaveNoBitmap() = runBlocking {
        val loader = ThumbnailLoader { error("invalid URL must not connect") }
        assertNull(loader.load("http://i.ytimg.com/image")); assertNull(loader.load("https://localhost/image"))
        for (connection in listOf(Connection(byteArrayOf(1, 2)), Connection(byteArrayOf(1), 302),
            Connection(byteArrayOf(1), length = 2_097_153), Connection(ByteArray(2_097_153), length = -1))) {
            assertNull(ThumbnailLoader { connection }.load("https://i.ytimg.com/image"))
            assertTrue(connection.closed)
        }
    }
    @Test fun cancellationDisconnectsAnInFlightRead() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val disconnected = java.util.concurrent.CountDownLatch(1)
        val connection = object : HttpURLConnection(URL("https://i.ytimg.com/image")) {
            override fun connect() = Unit
            override fun disconnect() { disconnected.countDown() }
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getContentLengthLong() = -1L
            override fun getInputStream(): java.io.InputStream = object : java.io.InputStream() {
                override fun read(): Int {
                    reading.complete(Unit)
                    check(disconnected.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    throw java.io.IOException("closed")
                }
            }
        }
        val task = async { ThumbnailLoader { connection }.load("https://i.ytimg.com/image") }
        withTimeout(3000) { reading.await() }
        withTimeout(3000) { task.cancelAndJoin() }
        assertEquals(0, disconnected.count); assertTrue(task.isCancelled)
    }
    @Test fun slowDripCannotExtendTheWholeLoadBeyondTenSeconds() = runBlocking {
        val disconnected = java.util.concurrent.CountDownLatch(1)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val connection = object : HttpURLConnection(URL("https://i.ytimg.com/image")) {
            override fun connect() = Unit
            override fun disconnect() { disconnected.countDown() }
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getContentLengthLong() = -1L
            override fun getInputStream(): java.io.InputStream = object : java.io.InputStream() {
                override fun read(): Int = error("bulk reads expected")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    // Every read completes well inside the per-read timeout, but never reaches EOF.
                    if (disconnected.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) throw java.io.IOException("closed")
                    reads.incrementAndGet(); buffer[offset] = 0; return 1
                }
            }
        }
        val started = System.nanoTime()
        try {
            val result = withTimeout(13_000) { ThumbnailLoader { connection }.load("https://i.ytimg.com/image") }
            assertNull(result)
            assertTrue("load exceeded its total deadline", (System.nanoTime() - started) / 1_000_000 < 12_000)
            assertTrue(reads.get() > 1); assertEquals(0, disconnected.count)
        } finally { connection.disconnect() }
    }
    @Test fun explicitPortsAreRejectedBeforeOpeningAConnection() = runBlocking {
        var opens = 0
        val loader = ThumbnailLoader { opens++; Connection(byteArrayOf()) }
        assertNull(loader.load("https://i.ytimg.com:443/image"))
        assertNull(loader.load("https://i.ytimg.com:8443/image"))
        assertEquals(0, opens)
    }
    @Test fun allBlackOrTransparentPosterIsNoThumbnailButADarkSceneWithDetailStays() = runBlocking {
        fun png(fill: Int, dot: Int? = null): ByteArray = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(640, 1137, Bitmap.Config.ARGB_8888).apply {
                eraseColor(fill); dot?.let { for (x in 280 until 360) for (y in 0 until height) setPixel(x, y, it) }
                compress(Bitmap.CompressFormat.PNG, 100, output); recycle()
            }
        }.toByteArray()
        val black = Connection(png(android.graphics.Color.rgb(2, 2, 2)))
        assertNull(ThumbnailLoader { black }.load("https://i.ytimg.com/image")); assertTrue(black.closed)
        assertNull(ThumbnailLoader { Connection(png(android.graphics.Color.TRANSPARENT)) }.load("https://i.ytimg.com/image"))
        val dark = ThumbnailLoader { Connection(png(android.graphics.Color.rgb(2, 2, 2), android.graphics.Color.rgb(90, 90, 90))) }
            .load("https://i.ytimg.com/image")
        assertNotNull(dark); dark!!.recycle()
    }
}
