package dev.ahmed.savlino.entry

import android.content.Intent
import dev.ahmed.savlino.engine.mediahttp.AcceptedLinkHosts

/**
 * Read only the supplied text; never dereference an inbound URI or ClipData.
 * [hint]: the sender's own words around the link (YouTube sends "Title + link"), shown only until the real title
 * arrives; never a filename, a format or a job, never logged or stored.
 */
class ShareInput private constructor(val text: String?, val hint: String? = null, val allQualities: Boolean = false) {
    override fun toString() = "ShareInput(redacted)"
    companion object {
        private const val HANDOFF_TOKEN = "dev.ahmed.savlino.SHARE_HANDOFF"
        private var allQualitiesHandoff: Pair<String, String>? = null

        /** Only the sheet can arm this memory-only, one-shot request; external extras grant nothing. */
        @Synchronized internal fun handoffAllQualities(intent: Intent) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val token = java.util.UUID.randomUUID().toString()
            allQualitiesHandoff = token to text
            intent.putExtra(HANDOFF_TOKEN, token)
        }

        @Synchronized private fun consumeAllQualities(intent: Intent, text: String?): Boolean {
            val pending = allQualitiesHandoff ?: return false
            val token = try { intent.getStringExtra(HANDOFF_TOKEN) } catch (_: RuntimeException) { null }
            if (intent.action != Intent.ACTION_SEND || token != pending.first || text != pending.second) return false
            allQualitiesHandoff = null
            return true
        }
        private const val HINT_MAX = 120
        private val URLS = Regex("(?:https?://|www\\.)[^\\s\\p{Z}<>\"“”«»]+", RegexOption.IGNORE_CASE)
        private val SPACES = Regex("[\\s\\p{Z}]+")

        /** Shared text without its links: whitespace collapsed, at most [HINT_MAX] code points; null when under 3 characters. */
        internal fun hint(text: String?, subject: String? = null): String? {
            fun clean(value: String?): String? {
                val plain = value?.replace(URLS, " ")?.replace(SPACES, " ")?.trim(' ', '-', '–', '—', '|', ':', '·', '•')
                    ?.trim() ?: return null
                if (plain.codePointCount(0, plain.length) < 3) return null
                if (plain.codePointCount(0, plain.length) <= HINT_MAX) return plain
                return plain.substring(0, plain.offsetByCodePoints(0, HINT_MAX - 1)).trimEnd() + "…"
            }
            return clean(text) ?: clean(subject)
        }

        val VIEW_HOSTS: Set<String> = AcceptedLinkHosts.exactHosts

        fun from(intent: Intent?): ShareInput? {
            if (intent == null) return null
            if (intent.action == Intent.ACTION_VIEW) {
                val uri = intent.data ?: return null
                if (uri.scheme?.lowercase(java.util.Locale.ROOT) !in setOf("http", "https") ||
                    uri.host?.let(AcceptedLinkHosts::accepts) != true) return null
                return uri.toString().takeIf { it.length <= 8192 }?.let(::ShareInput)
            }
            if (intent.type != "text/plain") return null
            val extra = when (intent.action) {
                Intent.ACTION_SEND -> Intent.EXTRA_TEXT
                Intent.ACTION_PROCESS_TEXT -> Intent.EXTRA_PROCESS_TEXT
                else -> return null
            }
            val text = try { intent.getCharSequenceExtra(extra)?.toString() }
                catch (_: RuntimeException) { null }
            val boundedText = text?.takeIf { it.length <= 8192 }
            if (extra == Intent.EXTRA_PROCESS_TEXT && boundedText == null) return null
            val subject = if (extra != Intent.EXTRA_TEXT) null else try {
                intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()?.takeIf { it.length <= 8192 }
            } catch (_: RuntimeException) { null }
            return ShareInput(boundedText, hint(boundedText, subject), consumeAllQualities(intent, boundedText))
        }
    }
}
