package dev.ahmed.savlino.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import dev.ahmed.savlino.domain.ProbeFacts

/** Opens a saved file and reports its tracks; blocking. Null (or a throw) when the file cannot be read. */
fun interface MediaProbe {
    fun probe(uri: Uri): ProbeFacts?
}

/** Platform track list (MediaExtractor) + duration (MediaMetadataRetriever) + provider size. Reads only [uri]; logs nothing. */
class AndroidMediaProbe(private val context: Context) : MediaProbe {
    override fun probe(uri: Uri): ProbeFacts? {
        var video = 0
        var audio = 0
        val durations = mutableListOf<Long>()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) video++ else if (mime.startsWith("audio/")) audio++
                if (format.containsKey(MediaFormat.KEY_DURATION)) durations += format.getLong(MediaFormat.KEY_DURATION) / 1000
            }
        } finally { extractor.release() }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { durations += it }
        } catch (_: Exception) { /* Duration is optional; the track list already answered. */ }
        finally { runCatching { retriever.release() } }
        // Any positive reading wins; an explicit zero only when every reader said zero; nothing read = unknown.
        val duration = durations.maxOrNull()
        return ProbeFacts(video, audio, duration, size(uri))
    }

    /** The file's own length (statSize), not the provider's SIZE column, which can lag the scan after publish. */
    private fun size(uri: Uri): Long? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize.takeIf { size -> size >= 0 } }
    } catch (_: Exception) { null }
}
