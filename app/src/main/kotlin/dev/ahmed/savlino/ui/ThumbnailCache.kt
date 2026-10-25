package dev.ahmed.savlino.ui

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import dev.ahmed.savlino.ui.nav.rememberTabResource

/**
 * One in-memory picture cache for list artwork (Home recents, Downloads, Statuses), sized by bytes. A picture already
 * decoded draws in the first frame, so returning to a tab or scrolling a row back never flashes blank. Memory only.
 */
class ThumbnailCache(val maxBytes: Int) {
    private val cache = object : LruCache<String, Bitmap>(maxBytes.coerceAtLeast(1)) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    operator fun get(key: String): Bitmap? = cache.get(key)

    /** A picture bigger than an eighth of the budget (a full-screen viewer page) is not kept: it would evict the lists. */
    fun put(key: String, bitmap: Bitmap) { if (bitmap.allocationByteCount <= maxBytes / 8) cache.put(key, bitmap) }

    suspend fun getOrLoad(key: String, load: suspend () -> Bitmap?): Bitmap? = get(key) ?: load()?.also { put(key, it) }

    fun clear() = cache.evictAll()

    companion object {
        /** 1/16 of the app's heap class: 8 MB on a 128 MB phone, about 30 list thumbnails. */
        fun budget(memoryClassMb: Int): Int = memoryClassMb * 1024 * 1024 / 16

        fun key(uri: Uri, size: Int) = "$uri@$size"

        private var owner: Context? = null
        private var instance: ThumbnailCache? = null

        /** One per application; dropped when the app goes to the background under memory pressure. */
        @Synchronized
        fun shared(context: Context): ThumbnailCache {
            val app = context.applicationContext
            instance?.takeIf { owner === app }?.let { return it }
            val memoryClass = app.getSystemService(ActivityManager::class.java)?.memoryClass ?: 64
            return ThumbnailCache(budget(memoryClass)).also { cache ->
                owner = app; instance = cache
                app.registerComponentCallbacks(object : ComponentCallbacks2 {
                    @Suppress("DEPRECATION")
                    override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) cache.clear() }
                    override fun onConfigurationChanged(newConfig: Configuration) = Unit
                    @Deprecated("Deprecated in Java") override fun onLowMemory() = cache.clear()
                })
            }
        }
    }
}

/** Tests and captures pass their own cache; null uses the app's shared one. */
val LocalThumbnailCache = staticCompositionLocalOf<ThumbnailCache?> { null }

@Composable
fun rememberThumbnailCache(): ThumbnailCache {
    val context = LocalContext.current
    return LocalThumbnailCache.current ?: remember(context) { ThumbnailCache.shared(context) }
}

/** List artwork: a cached picture is there at once; a miss loads off main once the tab settles, and is kept. */
@Composable
fun rememberCachedThumbnail(key: String, load: suspend () -> Bitmap?): Bitmap? {
    val cache = rememberThumbnailCache()
    val cached = remember(cache, key) { cache[key] }
    return rememberTabResource(cached, cache, key) { cache.getOrLoad(key, load) }.value
}
