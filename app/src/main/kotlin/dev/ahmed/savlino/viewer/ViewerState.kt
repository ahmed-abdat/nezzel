package dev.ahmed.savlino.viewer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.ahmed.savlino.ui.ThumbnailKind
import dev.ahmed.savlino.ui.loadMediaThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One page of the viewer: Downloads and Statuses map their own rows into this. */
data class ViewerItem(val uri: Uri, val isVideo: Boolean, val mimeType: String, val durationMillis: Long? = null,
    val isAudio: Boolean = false,
    val resumePolicy: dev.ahmed.savlino.playback.ResumePolicy = dev.ahmed.savlino.playback.ResumePolicy.SESSION_ONLY)

/** The tapped item's page; an item that is gone opens the first one. */
fun viewerStart(items: List<ViewerItem>, uri: Uri): Int = items.indexOfFirst { it.uri == uri }.coerceAtLeast(0)

/** Pictures for the grid and the viewer; captures and tests swap in synthetic ones. */
fun interface MediaPictures {
    suspend fun load(uri: Uri, kind: ThumbnailKind, size: Int): Bitmap?
}

val LocalMediaPictures = staticCompositionLocalOf<MediaPictures?> { null }

fun systemPictures(context: Context) = MediaPictures { uri, kind, size ->
    withContext(Dispatchers.IO) { loadMediaThumbnail(context.applicationContext, uri, kind, size, size) }
}

/** Share and Open with for a file the app can read; the chooser gets a read grant for just that file. */
object ViewerIntents {
    fun share(item: ViewerItem): Intent = Intent.createChooser(Intent(Intent.ACTION_SEND).setType(item.mimeType)
        .putExtra(Intent.EXTRA_STREAM, item.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri("", item.uri) }, null)
    fun openWith(item: ViewerItem): Intent = Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("", item.uri) }, null)
}

/**
 * Pinch and double-tap zoom of one image. Scale is 1–[MAX]; the offset is clamped so the image never leaves its edges.
 * The pager swipes only while [zoomed] is false, so a pan inside a zoomed image never turns the page.
 */
class ZoomState {
    var scale by mutableFloatStateOf(1f); private set
    var offset by mutableStateOf(Offset.Zero); private set
    val zoomed get() = scale > 1.01f

    /** Zoom by [change] around [centroid] (container coordinates), then move by [pan]. */
    fun transform(change: Float, pan: Offset, centroid: Offset, size: Size) {
        val next = (scale * change).coerceIn(1f, MAX)
        val center = Offset(size.width / 2, size.height / 2)
        // Keep the point under the fingers where it is: t' = q − c − (s'/s)(q − c − t).
        val moved = centroid - center - (centroid - center - offset) * (next / scale) + pan
        scale = next
        offset = clamp(moved, size)
    }

    fun toggle(at: Offset, size: Size) {
        if (zoomed) reset() else transform(DOUBLE_TAP / scale, Offset.Zero, at, size)
    }

    fun reset() { scale = 1f; offset = Offset.Zero }

    private fun clamp(value: Offset, size: Size): Offset {
        val x = (scale - 1) * size.width / 2
        val y = (scale - 1) * size.height / 2
        return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    }

    companion object { const val MAX = 4f; const val DOUBLE_TAP = 2.5f }
}
