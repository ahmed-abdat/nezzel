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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * One in-memory picture cache for list artwork (Home recents, Downloads, Statuses), sized by bytes. A picture already
 * decoded draws in the first frame, so returning to a tab or scrolling a row back never flashes blank. Memory only.
 * Loads of one key share a single run, at most [DECODES] run at once, a run stops once nobody waits for it, and a
 * picture that could not be made is not tried again for [MISS_MS] (audio without a cover, a broken file).
 */
class ThumbnailCache(val maxBytes: Int, private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private val cache = object : LruCache<String, Bitmap>(maxBytes.coerceAtLeast(1)) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private class Load(val result: Deferred<Bitmap?>) { var waiters = 0 }
    private val lock = Any()
    private val loads = HashMap<String, Load>()
    private val misses = object : LinkedHashMap<String, Long>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > MAX_MISSES
    }
    private val decodes = Semaphore(DECODES)
    /** Marks a shared run, so a loader that itself asks this cache for the same key (Statuses does) runs inline. */
    private object Loading : AbstractCoroutineContextElement(Key) { object Key : CoroutineContext.Key<Loading> }

    operator fun get(key: String): Bitmap? = cache.get(key)

    /** A picture bigger than an eighth of the budget (a full-screen viewer page) is not kept: it would evict the lists. */
    fun put(key: String, bitmap: Bitmap) { if (bitmap.allocationByteCount <= maxBytes / 8) cache.put(key, bitmap) }

    /** [retryMiss] skips the remembered miss, for a file that has only just been written. */
    suspend fun getOrLoad(key: String, retryMiss: Boolean = false, load: suspend () -> Bitmap?): Bitmap? {
        get(key)?.let { return it }
        val context = currentCoroutineContext()
        if (context[Loading.Key] != null) return load()?.also { put(key, it) }
        val shared = synchronized(lock) {
            if (!retryMiss && misses[key]?.let { now() - it < MISS_MS } == true) return null
            (loads[key]?.takeUnless { retryMiss && it.result.isCompleted } ?: Load(
                // The first caller's dispatcher, but not its Job: the run outlives that caller while others wait.
                CoroutineScope(context.minusKey(Job) + Job()).async(Loading, CoroutineStart.LAZY) {
                    val bitmap = decodes.withPermit { load() }
                    synchronized(lock) { if (bitmap == null) misses[key] = now() else misses.remove(key) }
                    bitmap?.also { put(key, it) }
                }).also { loads[key] = it }
            ).also { it.waiters++ }
        }
        try {
            shared.result.start()
            return shared.result.await()
        } finally {
            synchronized(lock) {
                shared.waiters--
                if (shared.waiters == 0 || shared.result.isCompleted) loads.remove(key, shared)
                if (shared.waiters == 0 && !shared.result.isCompleted) shared.result.cancel()
            }
        }
    }

    fun clear() { cache.evictAll(); synchronized(lock) { misses.clear() } }

    companion object {
        const val DECODES = 2
        const val MISS_MS = 30_000L
        private const val MAX_MISSES = 256
        /** 1/16 of the app's heap class: 8 MB on a 128 MB phone, about 30 list thumbnails. */
        fun budget(memoryClassMb: Int): Int = memoryClassMb * 1024 * 1024 / 16

        /** Revision comes from the listing, never a provider query during composition. Unknown fields stay null. */
        fun key(uri: Uri, size: Int, fileSize: Long? = null, modifiedMillis: Long? = null) =
            "$uri@$size:$fileSize:$modifiedMillis"

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
    return rememberTabResource(cached, cache, key) { cache.getOrLoad(key, load = load) }.value
}
