package dev.ahmed.savlino.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.ahmed.savlino.domain.PreviewMetadata
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One inspected HTTPS preview, memory only. Redirects are disabled; the preview host may differ from the video host. */
internal class ThumbnailLoader(
    private val decodeBytes: (ByteArray, BitmapFactory.Options) -> Bitmap? = { bytes, options ->
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    },
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    suspend fun load(url: String): Bitmap? {
        if (PreviewMetadata.thumbnail(url) == null || URI(url).port != -1) return null
        return withTimeoutOrNull(10_000) {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { continuation ->
                    val connection = AtomicReference<HttpURLConnection?>()
                    continuation.invokeOnCancellation { connection.get()?.disconnect() }
                    var bitmap: Bitmap? = null
                    try {
                        val current = open(URL(url))
                        connection.set(current)
                        if (continuation.isActive) {
                            current.instanceFollowRedirects = false
                            current.useCaches = false
                            current.connectTimeout = 3_000
                            current.readTimeout = 3_000
                            if (current.responseCode == HttpURLConnection.HTTP_OK && current.contentLengthLong <= MAX_BYTES) {
                                val bytes = current.inputStream.use { input ->
                                    val output = ByteArrayOutputStream()
                                    val buffer = ByteArray(8_192)
                                    var total = 0
                                    while (continuation.isActive) {
                                        val count = input.read(buffer)
                                        if (count == -1) break
                                        total += count
                                        if (total > MAX_BYTES) throw java.io.IOException("Preview exceeds limit")
                                        output.write(buffer, 0, count)
                                    }
                                    output.toByteArray()
                                }
                                if (continuation.isActive) bitmap = decode(bytes)
                            }
                        }
                    } catch (_: OutOfMemoryError) {
                        // Bounds, body allocation and pixel decode are optional; never crash the options UI.
                    } catch (_: Exception) {
                        // Optional preview failure is a tonal placeholder, never a URL-bearing log.
                    } finally {
                        connection.get()?.disconnect()
                    }
                    if (continuation.isActive) {
                        continuation.resume(bitmap) { _, value, _ -> value?.recycle() }
                    } else bitmap?.recycle()
                }
            }
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decodeBytes(bytes, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        // Bound both axes: malformed tall images must not allocate an enormous bitmap.
        while ((bounds.outWidth.toLong() + sample - 1) / sample > 480 ||
            (bounds.outHeight.toLong() + sample - 1) / sample > 480) sample *= 2
        val bitmap = decodeBytes(bytes, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        // Some sites store an all-black poster (a Reddit video's first frame); the placeholder reads better than a black box.
        if (blank(bitmap)) { bitmap.recycle(); return null }
        return bitmap
    }

    /** True when a 16x16 grid of samples finds no visible pixel: all near-black or near-transparent. */
    private fun blank(bitmap: Bitmap): Boolean {
        for (row in 0 until GRID) for (column in 0 until GRID) {
            val pixel = bitmap.getPixel(column * (bitmap.width - 1) / (GRID - 1), row * (bitmap.height - 1) / (GRID - 1))
            val alpha = pixel ushr 24
            val bright = maxOf(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF)
            if (alpha >= DIM && bright >= DIM) return false
        }
        return true
    }

    private companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        const val GRID = 16
        const val DIM = 16
    }
}
