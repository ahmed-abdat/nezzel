package dev.ahmed.savlino.engine.download

import okhttp3.Cookie
import okhttp3.HttpUrl

/** yt-dlp info JSON serializes scoped cookies separately from http_headers. Memory-only. */
internal object InfoCookies {
    private val attributes = setOf("domain", "path", "secure", "expires", "version", "httponly", "samesite")
    private val name = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

    /** Null is unsupported/malformed syntax; empty means no cookie applies to this URL. */
    fun header(raw: String, url: HttpUrl, nowSeconds: Long = System.currentTimeMillis() / 1000): String? {
        if (raw.isEmpty()) return ""
        if (raw.length > 16384 || raw.any { it < ' ' || it >= '\u007f' }) return null
        // Quoted values with delimiters need a fuller cookie parser; leave those jobs on yt-dlp.
        val tokens = raw.split(';').map(String::trim)
        val groups = mutableListOf<MutableList<String>>()
        for (token in tokens) {
            if (token.isEmpty()) return null
            val key = token.substringBefore('=').lowercase()
            if (key in attributes) groups.lastOrNull()?.add(token) ?: return null
            else {
                if ('=' !in token || !name.matches(token.substringBefore('='))) return null
                groups += mutableListOf(token)
            }
        }
        val result = mutableListOf<String>()
        for (group in groups) {
            val attrs = linkedMapOf<String, String>()
            for (token in group.drop(1)) {
                val key = token.substringBefore('=').lowercase()
                if (attrs.put(key, token.substringAfter('=', "")) != null) return null
            }
            val domain = attrs["domain"]?.trimStart('.')?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            val path = attrs["path"] ?: "/"
            if (!path.startsWith('/') || path.any { it == '"' || it == ',' }) return null
            val expiry = attrs["expires"]?.toLongOrNull()
            if (attrs.containsKey("expires") && expiry == null) return null
            if (expiry != null && expiry <= nowSeconds) continue
            val pair = group.first()
            val value = pair.substringAfter('=')
            if (value.any { it == '"' || it == ',' || it == '\\' }) return null
            // Cookie.parse enforces public-suffix/domain syntax; matches checks domain, path and HTTPS.
            val cookie = Cookie.parse(url, "$pair; Domain=$domain; Path=$path${if ("secure" in attrs) "; Secure" else ""}")
                ?: continue
            if (cookie.matches(url)) result += pair
        }
        return result.joinToString("; ")
    }
}
