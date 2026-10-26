package dev.ahmed.savlino.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.CancellationSignal
import android.util.Size
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a thumbnail is decoded from: a still image, a video frame, or audio cover art. */
enum class ThumbnailKind { IMAGE, VIDEO, AUDIO }

/**
 * Shared by Downloads and Statuses. MediaStore rows (and document providers that support it) get system thumbnails;
 * custom-folder and status documents often don't, so decode one ourselves at about [width]×[height]. Call off the main thread.
 * A cancelled [signal] stops the provider's thumbnail and skips our own decode.
 */
fun loadMediaThumbnail(context: Context, uri: Uri, kind: ThumbnailKind, width: Int = 320, height: Int = 180,
                       signal: CancellationSignal? = null): Bitmap? {
    try { return context.contentResolver.loadThumbnail(uri, Size(width, height), signal) } catch (_: Exception) { }
    if (signal?.isCanceled == true) return null
    fun scaled(source: ImageDecoder.Source) = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val scale = maxOf(1, info.size.width / width, info.size.height / height)
        decoder.setTargetSize(maxOf(1, info.size.width / scale), maxOf(1, info.size.height / scale))
    }
    return try {
        if (kind == ThumbnailKind.IMAGE) scaled(ImageDecoder.createSource(context.contentResolver, uri))
        else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                if (kind == ThumbnailKind.VIDEO)
                    retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxOf(width, height), maxOf(width, height))
                else retriever.embeddedPicture?.let { scaled(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(it))) }
            } finally { retriever.release() }
        }
    } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
}

/** Container duration in milliseconds, or null when the file does not say. Call off the main thread. */
fun loadMediaDuration(context: Context, uri: Uri): Long? = try {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
    } finally { retriever.release() }
} catch (_: Exception) { null }

/** [loadMediaThumbnail] off main; cancelling the caller cancels the provider's work too. */
suspend fun decodeMediaThumbnail(context: Context, uri: Uri, kind: ThumbnailKind, width: Int = 320, height: Int = 180): Bitmap? =
    withContext(Dispatchers.IO) { withCancellationSignal { loadMediaThumbnail(context, uri, kind, width, height, it) } }

/** Runs blocking [work] with a signal that is cancelled as soon as the calling coroutine is. */
internal suspend fun <T> withCancellationSignal(work: (CancellationSignal) -> T): T = coroutineScope {
    val signal = CancellationSignal()
    val watch = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { signal.cancel() } }
    try { work(signal) } finally { watch.cancel() }
}
