package dev.ahmed.savlino.engine.mediaimage

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.mediahttp.MediaHostRule
import dev.ahmed.savlino.engine.mediahttp.MediaHttpPostContext
import java.io.Closeable

/**
 * Process-local, memory-only binding from one exact returned selection to its fetch capability.
 * Each link keeps its own current inspection. Replacing that same link retires its handle and closes
 * its preview; a different link keeps its handle, so saving one photo is not invalidated when another
 * screen inspects a different photo. At most four links are held; the oldest is dropped.
 * Issued LinkedIn post contexts are strongly held in a bounded issuance-ordered set of 64; issuing a
 * 65th evicts the oldest. A selection whose context was evicted is remembered (same bound) so its
 * Save fails as typed SAVE_UNAVAILABLE before any network instead of a generic STALE.
 */
class MediaReferenceStore : Closeable {
    private class Slot(val link: String, val entries: List<StoredImageReference>, val previews: List<OwnedImagePreview>)
    private val lock = Any()
    private val slots = ArrayDeque<Slot>()
    private val postContexts = LinkedHashMap<MediaHttpPostContext, List<StoredImageReference>>()
    private val evicted = ArrayDeque<StoredImageReference>()
    private val saves = LinkedHashMap<SaveAdmission, StoredImageReference>()
    private var closed = false

    internal fun resolve(selection: MediaSelection): StoredImageReference? = synchronized(lock) {
        if (closed || selection.outputKind != MediaKind.IMAGE) return null
        slots.flatMap { it.entries }.singleOrNull { it.matches(selection) }
    }

    /** True when this exact selection lost its LinkedIn post context to bounded eviction. */
    internal fun wasEvicted(selection: MediaSelection): Boolean = synchronized(lock) {
        !closed && evicted.any { it.matches(selection) }
    }

    internal fun isCurrent(reference: StoredImageReference): Boolean = synchronized(lock) {
        !closed && slots.any { slot -> slot.entries.any { it === reference } }
    }

    /**
     * Atomic Save admission: registers [cancel] only while [reference] is still current, under the
     * same lock that retirement takes; null when already stale. Retirement clears the admission and
     * runs [cancel] INSIDE that lock, so once a replacement's critical section ends the Save's token
     * is already terminal and the transport's own pre-call token check refuses to start a request.
     * [cancel] must not take this store's lock (the admission flag is read lock-free for that reason).
     */
    internal fun admitSave(reference: StoredImageReference, cancel: () -> Unit): SaveAdmission? = synchronized(lock) {
        if (closed || slots.none { slot -> slot.entries.any { it === reference } }) return null
        SaveAdmission(cancel).also { saves[it] = reference }
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
            val link = references.firstOrNull()?.source?.link?.rawUrl
            val closedPreviews = ArrayList<OwnedImagePreview>()
            if (link != null) {
                // Only this link's previous handle is retired. Another link's Save stays current.
                val previous = slots.filter { it.link == link }
                slots.removeAll(previous)
                retireSavesLocked(previous.flatMap { it.entries })
                previous.flatMapTo(closedPreviews) { it.previews }
                slots.addLast(Slot(link, references.toList(), ownedPreviews.toList()))
                while (slots.size > MAX_LINKS) {
                    val oldest = slots.removeFirst()
                    retireSavesLocked(oldest.entries)
                    closedPreviews.addAll(oldest.previews)
                }
            }
            // Carousel siblings share one issuance slot and must be evicted together.
            for ((context, siblings) in references.filter { it.postContext != null }.groupBy { checkNotNull(it.postContext) }) {
                if (context !in postContexts) evictLocked(dropped)
                postContexts[context] = siblings
            }
            closedPreviews
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

    /** [link] null retires every handle. A link retires only that URL's current inspection. */
    internal fun retire(link: String? = null) {
        val retired = synchronized(lock) {
            val dropped = if (link == null) slots.toList().also { slots.clear() } else {
                val matched = slots.filter { it.link == link }
                slots.removeAll(matched)
                matched
            }
            retireSavesLocked(if (link == null) null else dropped.flatMap { it.entries })
            dropped.flatMap { it.previews }
        }
        retired.forEach { runCatching { it.close() } }
    }

    /** [dropped] null retires every admitted Save. Otherwise only Saves of those references. */
    private fun retireSavesLocked(dropped: List<StoredImageReference>?) {
        val admitted = if (dropped == null) saves.keys.toList()
            else saves.filter { (_, reference) -> dropped.any { it === reference } }.keys.toList()
        admitted.forEach { admission ->
            saves.remove(admission)
            admission.retireLocked()
        }
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
    private companion object {
        const val MAX_POST_CONTEXTS = 64
        const val MAX_LINKS = 4
    }
}

internal class StoredImageReference(val source: LinkRevision, val identity: MediaAdapterIdentity,
    val validity: MediaInspectionValidity, val rendition: MediaRenditionRef, val url: String,
    val image: MediaImageMetadata, val linkedIn: Boolean, val postContext: MediaHttpPostContext?,
    val hostRule: MediaHostRule = MediaHostRule.ANY, val mirrors: List<String> = emptyList()) {
    fun matches(selection: MediaSelection): Boolean = source.matches(selection.source) &&
        identity == selection.identity && validity === selection.validity && rendition == selection.renditionRef
    override fun toString() = "StoredImageReference(opaque)"
}
