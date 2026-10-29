package dev.ahmed.savlino.engine.telegram

import dev.ahmed.savlino.engine.mediahttp.HttpsUrl
import okhttp3.HttpUrl

/**
 * Public channel posts only; private /c/, invite, share and profile URLs never enter the reader. The web preview
 * (`t.me/s/<channel>/<id>`) and a forum topic message (`t.me/<channel>/<topic>/<id>`) are that same post.
 */
class TelegramPostIdentity private constructor(val channel: String, val postId: String) {
    val postKey: String get() = "$channel/$postId"
    val fetchUrl: String get() = "https://t.me/$postKey?embed=1&mode=tme"
    override fun toString() = "TelegramPostIdentity(redacted)"

    companion object {
        internal val hosts = setOf("t.me", "telegram.me")
        private val path = Regex("/(?:s/)?([A-Za-z][A-Za-z0-9_]{4,31})/(?:[1-9][0-9]{0,19}/)?([1-9][0-9]{0,19})/?")
        private val reserved = setOf("share", "proxy", "socks", "joinchat", "addstickers", "addemoji", "login", "invoice")
        fun parse(raw: String): TelegramPostIdentity? {
            val url = HttpsUrl.parse(raw) ?: return null
            if (url.host !in hosts) return null
            val match = path.matchEntire(url.encodedPath) ?: return null
            if (match.groupValues[1].lowercase() in reserved) return null
            return TelegramPostIdentity(match.groupValues[1], match.groupValues[2])
        }
        internal fun isPageRequest(url: HttpUrl): Boolean = parse(url.toString())?.fetchUrl == url.toString()
    }
}
