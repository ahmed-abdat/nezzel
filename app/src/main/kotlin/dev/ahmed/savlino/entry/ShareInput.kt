package dev.ahmed.savlino.entry

import android.content.Intent

/** Read only the supplied text; never dereference an inbound URI or ClipData. */
class ShareInput private constructor(val text: String?) {
    companion object {
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
            return ShareInput(boundedText)
        }
    }
}
