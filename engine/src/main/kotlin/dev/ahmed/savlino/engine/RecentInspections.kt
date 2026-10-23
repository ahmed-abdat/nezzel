package dev.ahmed.savlino.engine

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.domain.ValidatedLink

/**
 * Recent successful extractions, so a new sheet for the same link needs no network. Memory only; hashed keys, never logged.
 * [now] is wall time, read once per put to turn the signed expiry into a remaining span; age and expiry then run on [elapsed],
 * a monotonic clock, so a wall-clock rollback never serves an entry past either limit.
 */
internal class RecentInspections(private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 }) {
    enum class Provenance { YTDLP, NPE, READER }

    /** [signedUntil] = earliest signed URL expiry minus the margin, carried into the download TTL; both are wall time. */
    class Entry(val json: String, val provenance: Provenance, val engineVersion: String?, val createdAt: Long,
        val signedUntil: Long?, internal val expiresAtElapsed: Long) {
        /** NPE JSON carries every stream; only the chosen ones become the job file. */
        val trimToChosen get() = provenance == Provenance.NPE
        override fun toString() = "Entry($provenance)"
    }

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var chars = 0L
    /** Bumped by [clear]; a put that started before the clear is dropped. */
    @Volatile private var epoch = 0L

    fun epoch() = epoch

    /** yt-dlp output is tied to its version; NPE and reader JSON are not. */
    @Synchronized fun get(link: ValidatedLink, version: String): Entry? {
        val key = key(link)
        val entry = entries[key] ?: return null
        val current = entry.provenance != Provenance.YTDLP || entry.engineVersion == version
        if (elapsed() < entry.expiresAtElapsed && current) return entry
        drop(key)
        return null
    }

    /** [since] = the [epoch] read when the extraction started. */
    @Synchronized fun put(link: ValidatedLink, json: String, provenance: Provenance, engineVersion: String?, signedUntil: Long?,
        since: Long = epoch) {
        if (since != epoch) return
        val key = key(link)
        drop(key)
        val createdAt = now()
        val remaining = minOf(MAX_AGE_MS, (signedUntil ?: Long.MAX_VALUE) - createdAt)
        if (remaining <= 0 || json.length > MAX_CHARS) return
        val entry = Entry(json, provenance, engineVersion.takeIf { provenance == Provenance.YTDLP }, createdAt, signedUntil,
            elapsed() + remaining)
        while (entries.isNotEmpty() && (entries.size >= MAX_ENTRIES || chars + json.length > MAX_CHARS)) drop(entries.keys.first())
        entries[key] = entry
        chars += json.length
    }

    /** Native reader JSON (yt-dlp shaped). Unparsable input is dropped silently. */
    fun putPrefetched(link: ValidatedLink, json: String) {
        val signed = try { signedUntil(json) } catch (_: Exception) { return }
        put(link, json, Provenance.READER, null, signed)
    }

    @Synchronized fun remove(link: ValidatedLink) = drop(key(link))

    @Synchronized fun clear() { epoch++; entries.clear(); chars = 0 }

    private fun drop(key: String) { entries.remove(key)?.let { chars -= it.json.length } }

    override fun toString() = "RecentInspections(${entries.size})"

    companion object {
        const val MAX_AGE_MS = 15L * 60 * 1000
        const val MARGIN_MS = 5L * 60 * 1000
        const val MAX_ENTRIES = 8
        const val MAX_CHARS = 8_000_000

        fun key(link: ValidatedLink): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(link.normalizedUrl.toByteArray())
                .joinToString("") { "%02x".format(it) }

        /** Earliest `expire` / `x-expires` (decimal s) / `oe` (hex s) in the root, formats and requested_formats URLs, minus the margin. */
        fun signedUntil(json: String): Long? {
            val root = ObjectMapper().readTree(json)
            val urls = buildList {
                add(root.path("url"))
                for (list in listOf("formats", "requested_formats")) root.path(list).forEach { add(it.path("url")) }
            }.filter(JsonNode::isTextual).map(JsonNode::asText)
            return urls.mapNotNull(::expiry).minOrNull()?.let { it - MARGIN_MS }
        }

        private fun expiry(url: String): Long? {
            val query = url.substringAfter('?', "").substringBefore('#')
            return query.split('&').mapNotNull { pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                when (name) {
                    "expire", "x-expires" -> value.toLongOrNull()
                    "oe" -> value.toLongOrNull(16)
                    else -> null
                }?.takeIf { it > 0 && it < Long.MAX_VALUE / 1000 }?.times(1000)
            }.minOrNull()
        }
    }
}
