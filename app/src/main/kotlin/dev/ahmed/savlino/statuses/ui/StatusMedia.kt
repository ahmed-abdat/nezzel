package dev.ahmed.savlino.statuses.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.graphics.drawable.toBitmap
import dev.ahmed.savlino.statuses.StatusItem
import dev.ahmed.savlino.ui.ThumbnailCache
import dev.ahmed.savlino.ui.ThumbnailKind
import dev.ahmed.savlino.ui.loadMediaDuration
import dev.ahmed.savlino.ui.decodeMediaThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pictures and durations for the statuses screen; captures and tests swap in synthetic ones. */
interface StatusMedia {
    suspend fun thumbnail(item: StatusItem, size: Int): Bitmap?
    suspend fun durationMillis(item: StatusItem): Long?
    /** The installed app's own launcher icon (PackageManager), shown beside its label. */
    suspend fun appIcon(packageName: String): Bitmap?
}

val LocalStatusMedia = staticCompositionLocalOf<StatusMedia?> { null }

/** Decodes off the main thread; memory caches only, so nothing about the statuses is written anywhere. */
class SystemStatusMedia(context: Context) : StatusMedia {
    private val context = context.applicationContext
    private val thumbs = ThumbnailCache.shared(this.context)
    private val durations = LruCache<Uri, Long>(256)

    // Cancellable: a status scrolled away cancels its provider call, so it never holds a decode slot.
    override suspend fun thumbnail(item: StatusItem, size: Int): Bitmap? = thumbs.getOrLoad(ThumbnailCache.key(item.uri, size, item.sizeBytes, item.lastModifiedMillis)) {
        decodeMediaThumbnail(context, item.uri, if (item.isVideo) ThumbnailKind.VIDEO else ThumbnailKind.IMAGE, size, size)
    }

    override suspend fun durationMillis(item: StatusItem): Long? = if (!item.isVideo) null else durations.get(item.uri)
        ?: withContext(Dispatchers.IO) { loadMediaDuration(context, item.uri) }?.also { durations.put(item.uri, it) }

    override suspend fun appIcon(packageName: String): Bitmap? = withContext(Dispatchers.IO) {
        try { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96) } catch (_: Exception) { null }
    }
}
