package dev.ahmed.savlino.domain

import java.net.URI
import java.util.Locale

object UrlNormalizer {
    /** Do not rebuild URI components: that can change signed query bytes or percent escapes. */
    internal fun normalize(uri: URI): String {
        val authority = uri.rawAuthority
        val normalizedAuthority = uri.host.lowercase(Locale.ROOT) + authority.substring(uri.host.length)
        val raw = uri.toString()
        return "https://" + normalizedAuthority + raw.substring(raw.indexOf("//") + 2 + authority.length)
    }
}
