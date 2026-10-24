package dev.ahmed.savlino.engine.mediaimage

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.mediahttp.MediaHostRule
import dev.ahmed.savlino.engine.mediahttp.MediaHttpPostContext
import java.io.Closeable

/**
 * Process-local, memory-only binding from one exact returned selection to its fetch capability.
 * Holds at most the current inspection; replacing it retires every handle and closes its preview.
 * Issued LinkedIn post contexts are strongly held in a bounded issuance-ordered set of 64; issuing a
 * 65th evicts the oldest. A selection whose context was evicted is remembered (same bound) so its
 * Save fails as typed SAVE_UNAVAILABLE before any network instead of a generic STALE.
 */
class MediaReferenceStore : Closeable {
    private val lock = Any()
    private var entries: List<StoredImageReference> = emptyList()
    private var previews: List<OwnedImagePreview> = emptyList()
    private val postContexts = LinkedHashMap<MediaHttpPostContext, List<StoredImageReference>>()
    private val evicted = ArrayDeque<StoredImageReference>()
    private val saves = LinkedHashSet<SaveAdmission>()
    private var closed = false

    internal fun resolve(selection: MediaSelection): StoredImageReference? = synchronized(lock) {
        if (closed || selection.outputKind != MediaKind.IMAGE) return null
        entries.singleOrNull { it.matches(selection) }
    }

    /** True when this exact selection lost its LinkedIn post context to bounded eviction. */
    internal fun wasEvicted(selection: MediaSelection): Boolean = synchronized(lock) {
        !closed && evicted.any { it.matches(selection) }
    }

    internal fun isCurrent(reference: StoredImageReference): Boolean = synchronized(lock) {
        !closed && entries.any { it === reference }
    }

    /**
     * Atomic Save admission: registers [cancel] only while [reference] is still current, under the
     * same lock that retirement takes; null when already stale. Retirement clears the admission and
     * runs [cancel] INSIDE that lock, so once a replacement's critical section ends the Save's token
     * is already terminal and the transport's own pre-call token check refuses to start a request.
     * [cancel] must not take this store's lock (the admission flag is read lock-free for that reason).
     */
    internal fun admitSave(reference: StoredImageReference, cancel: () -> Unit): SaveAdmission? = synchronized(lock) {
        if (closed || entries.none { it === reference }) return null
        SaveAdmission(cancel).also { saves += it }
    }

    internal inner class SaveAdmission(private val cancel: () -> Unit) {
        @Volatile var current = true
            private set
        fun release() { synchronized(lock) { saves.remove(this) } }
        /** Caller holds the store lock. */
        fun retireLocked() {
            current = false
            runCatching { cancel() }
        }
        override fun toString() = "SaveAdmission(opaque)"
    }

    /**
     * Caller holds the inspector admission lock. Returns null when the store is closed, else the post
     * contexts evicted by this admission (the caller releases them from the transport).
     */
    internal fun admit(references: List<StoredImageReference>, preview: OwnedImagePreview?, ownedPreviews: List<OwnedImagePreview> = listOfNotNull(preview)): List<MediaHttpPostContext>? {
        val dropped = ArrayList<MediaHttpPostContext>()
        val retired = synchronized(lock) {
            if (closed) return null
            val old = previews
            // Admitting a new inspection retires the previous references and their in-flight Saves.
            retireSavesLocked()
            entries = references.toList()
            previews = ownedPreviews.toList()
            // Carousel siblings share one issuance slot and must be evicted together.
            for ((context, siblings) in references.filter { it.postContext != null }.groupBy { checkNotNull(it.postContext) }) {
                if (context !in postContexts) evictLocked(dropped)
                postContexts[context] = siblings
            }
            old
        }
        retired.forEach { runCatching { it.close() } }
        return dropped
    }

    /** Frees one slot (oldest issued first) before a new context is requested from the transport. */
    internal fun evictForNewContext(): List<MediaHttpPostContext> = synchronized(lock) {
        ArrayList<MediaHttpPostContext>().also(::evictLocked)
    }

    private fun evictLocked(dropped: MutableList<MediaHttpPostContext>) {
        while (postContexts.size >= MAX_POST_CONTEXTS) {
            val oldest = postContexts.entries.first()
            postContexts.remove(oldest.key)
            dropped += oldest.key
            evicted.addAll(oldest.value)
            while (evicted.size > MAX_POST_CONTEXTS) evicted.removeFirst()
        }
    }

    internal fun retire() {
        val retired = synchronized(lock) {
            entries = emptyList()
            retireSavesLocked()
            previews.also { previews = emptyList() }
        }
        retired.forEach { runCatching { it.close() } }
    }

    private fun retireSavesLocked() {
        val admitted = saves.toList()
        saves.clear()
        admitted.forEach { it.retireLocked() }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            postContexts.clear()
            evicted.clear()
        }
        retire()
    }

    override fun toString() = "MediaReferenceStore(redacted)"
    private companion object { const val MAX_POST_CONTEXTS = 64 }
}

internal class StoredImageReference(val source: LinkRevision, val identity: MediaAdapterIdentity,
    val validity: MediaInspectionValidity, val rendition: MediaRenditionRef, val url: String,
    val image: MediaImageMetadata, val linkedIn: Boolean, val postContext: MediaHttpPostContext?,
    val hostRule: MediaHostRule = MediaHostRule.ANY, val mirrors: List<String> = emptyList()) {
    fun matches(selection: MediaSelection): Boolean = source.matches(selection.source) &&
        identity == selection.identity && validity === selection.validity && rendition == selection.renditionRef
    override fun toString() = "StoredImageReference(opaque)"
}
