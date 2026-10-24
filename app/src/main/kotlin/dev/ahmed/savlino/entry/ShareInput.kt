package dev.ahmed.savlino.entry

import android.content.Intent

/**
 * Read only the supplied text; never dereference an inbound URI or ClipData.
 * [hint]: the sender's own words around the link (YouTube sends "Title + link"), shown only until the real title
 * arrives; never a filename, a format or a job, never logged or stored.
 */
class ShareInput private constructor(val text: String?, val hint: String? = null) {
    override fun toString() = "ShareInput(redacted)"
    companion object {
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

        val VIEW_HOSTS: Set<String> = setOf(
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be",
            "tiktok.com", "www.tiktok.com", "vm.tiktok.com", "vt.tiktok.com",
            "instagram.com", "www.instagram.com", "facebook.com", "www.facebook.com", "m.facebook.com",
            "fb.watch", "x.com", "twitter.com", "mobile.twitter.com",
            "www.x.com", "www.twitter.com", "mobile.x.com", "m.tiktok.com", "m.instagram.com", "instagr.am",
            "web.facebook.com", "t.me", "bsky.app", "www.linkedin.com")

        fun from(intent: Intent?): ShareInput? {
            if (intent == null) return null
            if (intent.action == Intent.ACTION_VIEW) {
                val uri = intent.data ?: return null
                if (uri.scheme?.lowercase(java.util.Locale.ROOT) !in setOf("http", "https") ||
                    uri.host?.lowercase(java.util.Locale.ROOT) !in VIEW_HOSTS) return null
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
            return ShareInput(boundedText, hint(boundedText, subject))
        }
    }
}
