package dev.ahmed.savlino.engine.mediaimage

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Build
import dev.ahmed.savlino.domain.MediaImageFormat
import java.nio.ByteBuffer

/**
 * Blocking still-image preview decode; the caller runs it off the main thread and serializes decodes.
 * The complete representation is validated first, and the header listener re-checks the bounds
 * BEFORE any pixel allocation. The codec then decodes with a power-of-two sample size chosen from the
 * validated dimensions, so the software sRGB raster it allocates is itself <=1024 px per axis: no
 * full-size raster and no separate scale-down buffer. Partial images are failures, never drawable success.
 */
class AndroidImagePreviewDecoder : ImagePreviewDecoder {
    private val validator = ImageRepresentationValidator()

    private class Rejected(val reason: ImageFailure) : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    override fun decode(bytes: ByteArray, mimeType: String, isActive: () -> Boolean, length: Int): ImageDecodeResult =
        decodeBounded(bytes, mimeType, isActive, length, PREVIEW_AXIS)

    override fun decodeThumbnail(bytes: ByteArray, mimeType: String, isActive: () -> Boolean, length: Int): ImageDecodeResult =
        decodeBounded(bytes, mimeType, isActive, length, 256)

    private fun decodeBounded(bytes: ByteArray, mimeType: String, isActive: () -> Boolean, length: Int, axis: Int): ImageDecodeResult {
        if (!isActive()) return ImageDecodeResult.Failure(ImageFailure.CANCELLED)
        val format = when (val validation = validator.validate(bytes, mimeType, length)) {
            is ImageValidationResult.Failure -> return ImageDecodeResult.Failure(validation.reason)
            is ImageValidationResult.Valid -> validation.image.format
        }
        if (format == MediaImageFormat.AVIF && Build.VERSION.SDK_INT < 31)
            return ImageDecodeResult.Failure(ImageFailure.UNSUPPORTED)
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes, 0, length))) { decoder, info, _ ->
                if (!isActive()) throw Rejected(ImageFailure.CANCELLED)
                val width = info.size.width.toLong()
                val height = info.size.height.toLong()
                dimensionFailure(width, height)?.let { throw Rejected(it) }
                if (info.isAnimated && format != dev.ahmed.savlino.domain.MediaImageFormat.GIF) throw Rejected(ImageFailure.ANIMATED)
                val reported = imageFormatFor(info.mimeType)
                if (reported != null && reported != format) throw Rejected(ImageFailure.INVALID_IMAGE)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.setTargetSampleSize(previewSampleSize(width, height, axis))
                decoder.isMutableRequired = false
                decoder.setOnPartialImageListener { false }
            }
        } catch (rejected: Rejected) {
            return ImageDecodeResult.Failure(rejected.reason)
        } catch (_: ImageDecoder.DecodeException) {
            return ImageDecodeResult.Failure(ImageFailure.INVALID_IMAGE)
        } catch (_: OutOfMemoryError) {
            return ImageDecodeResult.Failure(ImageFailure.DECODE)
        } catch (_: Exception) {
            return ImageDecodeResult.Failure(ImageFailure.DECODE)
        }
        if (!isActive()) {
            bitmap.recycle()
            return ImageDecodeResult.Failure(ImageFailure.CANCELLED)
        }
        if (bitmap.width > axis || bitmap.height > axis || bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.recycle()
            return ImageDecodeResult.Failure(ImageFailure.DECODE)
        }
        return ImageDecodeResult.Success(AndroidImagePreview(bitmap))
    }

    internal companion object {
        const val PREVIEW_AXIS = 1024

        /** Smallest power of two whose sampled size fits PREVIEW_AXIS on both axes (ceil division). */
        fun previewSampleSize(width: Long, height: Long, axis: Int = PREVIEW_AXIS): Int {
            var sample = 1
            while ((width + sample - 1) / sample > axis || (height + sample - 1) / sample > axis) sample *= 2
            return sample
        }
    }
}

class AndroidImagePreview internal constructor(val bitmap: Bitmap) : OwnedImagePreview {
    override val width = bitmap.width
    override val height = bitmap.height
    override val allocationBytes = bitmap.allocationByteCount.toLong()
    override val isClosed get() = bitmap.isRecycled
    @Synchronized override fun close() { if (!bitmap.isRecycled) bitmap.recycle() }
    override fun toString() = "AndroidImagePreview(redacted)"
}
