package dev.ahmed.savlino.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.MediaMetadata
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.AppLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Read only the provider's filename and local cover pixels. File text tags never enter the session. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackMetadata(private val context: Context) {
    suspend fun load(uri: Uri): MediaMetadata = withContext(Dispatchers.IO) {
        ContentPlaybackSource.requireContent(uri)
        val strings = AppLocale.localized(context)
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getString(column) else null
            }
        } catch (_: Exception) { null }
        val title = name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: strings.getString(R.string.playback_audio)
        MediaMetadata.Builder().setTitle(title).setArtist(strings.getString(R.string.app_name))
            .setArtworkData(cover(uri) ?: brandTile(), MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()
    }

    private fun cover(uri: Uri): ByteArray? = try {
        val embedded = MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture
        }
        embedded?.takeIf { it.size <= MAX_BYTES }?.let(::coverPixels)
    } catch (_: Exception) { null }

    private fun coverPixels(bytes: ByteArray): ByteArray? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true; inSampleSize = 1 }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inJustDecodeBounds = false
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > MAX_PIXELS) options.inSampleSize *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        try {
            val edge = maxOf(decoded.width, decoded.height)
            val scaled = if (edge <= MAX_PIXELS) decoded else Bitmap.createScaledBitmap(decoded,
                (decoded.width.toLong() * MAX_PIXELS / edge).toInt().coerceAtLeast(1),
                (decoded.height.toLong() * MAX_PIXELS / edge).toInt().coerceAtLeast(1), true)
            try { return png(scaled) } finally { if (scaled !== decoded) scaled.recycle() }
        } finally { decoded.recycle() }
    }

    // A tile that can't be drawn leaves the artwork empty; it never fails playback.
    private fun brandTile(): ByteArray? = try {
        val bitmap = Bitmap.createBitmap(MAX_PIXELS, MAX_PIXELS, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            for (resource in listOf(R.drawable.nezzel_launcher_background, R.drawable.nezzel_launcher_foreground)) {
                requireNotNull(context.getDrawable(resource)).apply { setBounds(0, 0, MAX_PIXELS, MAX_PIXELS); draw(canvas) }
            }
            png(bitmap)
        } finally { bitmap.recycle() }
    } catch (_: Exception) { null }

    // Re-encoding retains pixels only, discarding comments/EXIF and other embedded image metadata.
    private fun png(bitmap: Bitmap): ByteArray? = ByteArrayOutputStream().use { output ->
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) || output.size() > MAX_BYTES) null else output.toByteArray()
    }

    private companion object {
        const val MAX_BYTES = 1_048_576
        const val MAX_PIXELS = 512
    }
}
