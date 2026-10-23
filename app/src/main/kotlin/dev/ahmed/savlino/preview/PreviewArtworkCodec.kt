package dev.ahmed.savlino.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.roundToInt

internal class EncodedPreviewArtwork(val bytes: ByteArray, val pixels: Bitmap)

/** The caller keeps its bitmap. Only newly owned sampled pixels and their fresh encoding (PNG by default) leave this boundary. */
internal open class PreviewArtworkCodec {
    open fun encodeSampled(bitmap: Bitmap, maxAxis: Int, maxBytes: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG, quality: Int = 100): EncodedPreviewArtwork? {
        var pixels: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            if (bitmap.isRecycled || maxAxis !in 1..480 || maxBytes !in 1..2 * 1024 * 1024) return null
            val scale = minOf(1.0, maxAxis.toDouble() / maxOf(bitmap.width, bitmap.height))
            val width = (bitmap.width * scale).roundToInt().coerceIn(1, maxAxis)
            val height = (bitmap.height * scale).roundToInt().coerceIn(1, maxAxis)
            scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            val owned = scaled.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            pixels = owned
            if (owned === bitmap || owned === scaled) return null
            val output = object : ByteArrayOutputStream() {
                override fun write(value: Int) {
                    if (count >= maxBytes) throw IOException("Artwork exceeds cache limit")
                    super.write(value)
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (length > maxBytes - count) throw IOException("Artwork exceeds cache limit")
                    super.write(bytes, offset, length)
                }
            }
            if (!owned.compress(format, quality, output) || output.size() == 0) return null
            return EncodedPreviewArtwork(output.toByteArray(), owned).also { pixels = null }
        } catch (_: Exception) {
            return null
        } finally {
            pixels?.takeIf { it !== bitmap }?.recycle()
            scaled?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
        }
    }

    open fun decode(bytes: ByteArray, maxAxis: Int, maxAllocationBytes: Long): Bitmap? {
        if (bytes.isEmpty() || bytes.size > 2 * 1024 * 1024 || maxAxis !in 1..480) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..maxAxis || bounds.outHeight !in 1..maxAxis ||
            bounds.outWidth.toLong() * bounds.outHeight * 4 > maxAllocationBytes) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }) ?: return null
        if (bitmap.width != bounds.outWidth || bitmap.height != bounds.outHeight || bitmap.allocationByteCount > maxAllocationBytes) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }
}
