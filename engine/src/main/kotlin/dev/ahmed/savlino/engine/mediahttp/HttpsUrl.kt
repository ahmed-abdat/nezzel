package dev.ahmed.savlino.engine.mediahttp

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object HttpsUrl {
    // Same literal grammar as OkHttp5.5 canParseAsIpAddress, plus numeric final DNS-label rejection.
    private val literal = Regex("([0-9a-fA-F]*:[0-9a-fA-F:.]*)|([\\d.]+)")
    private val numericLabel = Regex("(?:[0-9]+|0[xX][0-9a-fA-F]+)")
    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val hex = "0123456789abcdefABCDEF"

    internal fun withoutQuery(url: HttpUrl): String = url.newBuilder().query(null).fragment(null).build().toString()

    internal fun safeText(raw: String): Boolean = raw.length in 1..8192 && raw.none {
        it <= ' ' || Character.isISOControl(it) || it == '\\' || Character.getType(it) == Character.FORMAT.toInt() ||
            Character.isSurrogate(it)
    }

    fun parse(value: String): HttpUrl? {
        if (!safeText(value)) return null
        val raw = value.substringBefore('#')
        if (!raw.startsWith("https://", ignoreCase = true)) return null
        val authority = raw.substringAfter("://").substringBefore('/').substringBefore('?')
        if ('@' in authority) return null // Includes empty userinfo, which HttpUrl otherwise erases.
        val parsed = raw.toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps || parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        val host = parsed.host.trimEnd('.')
        if (literal.matches(host) || numericLabel.matches(host.substringAfterLast('.'))) return null
        val afterAuthority = raw.substringAfter("://").drop(authority.length)
        val rawPath = afterAuthority.substringBefore('?').ifEmpty { "/" }
        val rawQuery = if ('?' in afterAuthority) afterAuthority.substringAfter('?') else null
        if (rawPath != parsed.encodedPath || rawQuery != parsed.encodedQuery) return null
        var index = 0
        while (index < raw.length) {
            if (raw[index] == '%' && (index + 2 >= raw.length || raw[index + 1] !in hex || raw[index + 2] !in hex)) return null
            index++
        }
        return parsed
    }

    internal fun resolve(base: HttpUrl, value: String): HttpUrl? {
        if (!safeText(value)) return null
        val location = value.substringBefore('#')
        if (scheme.containsMatchIn(location)) return parse(location)
        if (location.startsWith("//")) return parse("https:$location")
        // Relative dot-segment resolution is intentional HTTP Location semantics.
        val resolved = base.resolve(location) ?: return null
        return parse(resolved.toString())
    }
}
