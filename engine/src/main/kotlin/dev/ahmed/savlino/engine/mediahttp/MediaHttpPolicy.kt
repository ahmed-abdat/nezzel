package dev.ahmed.savlino.engine.mediahttp

import dev.ahmed.savlino.engine.bluesky.BlueskyPostIdentity
import dev.ahmed.savlino.engine.linkedin.LinkedInPostIdentity
import java.net.InetAddress
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object MediaHttpPolicy {
    // Same literal grammar as OkHttp5.5 canParseAsIpAddress, plus numeric final DNS-label rejection.
    private val literal = Regex("([0-9a-fA-F]*:[0-9a-fA-F:.]*)|([\\d.]+)")
    private val numericLabel = Regex("(?:[0-9]+|0[xX][0-9a-fA-F]+)")
    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val hex = "0123456789abcdefABCDEF"

    /**
     * A post slug exactly as LinkedIn serves it: `[A-Za-z0-9_-]` plus percent-escapes (non-Latin names arrive
     * encoded), never an escaped `#`, `%`, `.`, `/`, `?`, `\` or control character.
     */
    internal const val LINKEDIN_SLUG = "(?:[A-Za-z0-9_-]|%(?![01][0-9A-Fa-f]|2[35EeFf]|3[Ff]|5[Cc]|7[Ff])[0-9A-Fa-f]{2})+"

    internal fun isLinkedInPost(url: HttpUrl): Boolean = url.host == "www.linkedin.com" &&
        (Regex("/posts/$LINKEDIN_SLUG/?").matches(url.encodedPath) ||
            Regex("/feed/update/urn:li:(?:activity|share|ugcPost):[0-9]+/?").matches(url.encodedPath))

    internal fun isLinkedInEntryHost(host: String): Boolean = host == "linkedin.com" || host == "www.linkedin.com" ||
        Regex("[a-z]{2}\\.linkedin\\.com").matches(host)

    /** www host; an `/embed/` or `%3A`-encoded update link becomes its plain `/feed/update/urn:li:…/` page. */
    internal fun normalizeLinkedInEntry(url: HttpUrl): HttpUrl {
        if (!isLinkedInEntryHost(url.host)) return url
        val path = LinkedInPostIdentity.updatePath(url.encodedPath).removePrefix("/embed")
            .takeIf { it.startsWith("/feed/update/") } ?: url.encodedPath
        return url.newBuilder().host("www.linkedin.com").encodedPath(path).build()
    }

    internal fun isLinkedInPageEntry(url: HttpUrl): Boolean = isLinkedInEntryHost(url.host) && isLinkedInPost(normalizeLinkedInEntry(url)) ||
        (url.host == "lnkd.in" && Regex("/p/[A-Za-z0-9_-]+/?").matches(url.encodedPath))

    /** LinkedIn's own sign-up or login wall; never followed, only named. */
    internal fun isLinkedInLoginWall(url: HttpUrl): Boolean = isLinkedInEntryHost(url.host) &&
        Regex("/(?:signup(?:/[a-z-]+)?|authwall|login|uas/login|checkpoint/[a-z/-]+)/?").matches(url.encodedPath)

    /** A www redirect target in its plain `/feed/update/urn:li:…/` form; other hosts stay as sent (and are refused). */
    internal fun linkedInHopTarget(to: HttpUrl): HttpUrl = if (to.host == "www.linkedin.com") normalizeLinkedInEntry(to) else to

    /** Only a shortlink may establish the post; every later post/activity hop must keep that identity. */
    internal fun allowsLinkedInPageHop(from: HttpUrl, to: HttpUrl): Boolean {
        val hop = linkedInHopTarget(to)
        if (!isLinkedInPageEntry(from) || !isLinkedInPost(hop)) return false
        if (from.host == "lnkd.in") return true
        val source = LinkedInPostIdentity.parse(from.toString()) ?: return false
        val target = LinkedInPostIdentity.parse(hop.toString()) ?: return false
        return source.sameAs(target)
    }

    /**
     * https scontent*.fbcdn.net only (not external/video/static CDN hosts). web.facebook.com in zero-rated
     * regions serves the same images from a `z-<tag>-scontent…` host.
     */
    internal fun isFacebookImageCdn(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
        url.host.replaceFirst(zeroRatedPrefix, "").startsWith("scontent") && url.host.endsWith(".fbcdn.net")
    private val zeroRatedPrefix = Regex("^z-[a-z0-9]{1,4}-")

    /** https pbs.twimg.com/media/<name>[.ext] only (not profile images, card images or video.twimg.com). */
    internal fun isXImage(url: HttpUrl): Boolean = url.isHttps && url.port == 443 && url.host == "pbs.twimg.com" &&
        Regex("/media/[A-Za-z0-9_-]{1,64}(?:\\.(?:jpg|png|webp|gif|avif))?").matches(url.encodedPath)

    /**
     * Instagram's image CDN as served to logged-out post pages: `instagram.f<pop>.fna.fbcdn.net` and
     * `scontent[-<pop>].cdninstagram.com`, https only, `/v/t<n>.<n>-<n>/…<name>.jpg|png|webp|gif|avif` paths only.
     */
    internal fun isInstagramImage(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
        (instagramFbcdn.matches(url.host) || instagramCdn.matches(url.host)) && instagramPath.matches(url.encodedPath)
    private val instagramFbcdn = Regex("instagram\\.f[a-z0-9]{2,12}(?:-[0-9]{1,3}){1,2}\\.fna\\.fbcdn\\.net")
    private val instagramCdn = Regex("scontent(?:-[a-z0-9]{2,12}(?:-[0-9]{1,3}){1,2})?\\.cdninstagram\\.com")
    private val instagramPath = Regex("/v/t[0-9]{1,3}\\.[0-9]{1,6}-[0-9]{1,3}/(?:[A-Za-z0-9_.-]{1,128}/){0,3}" +
        "[A-Za-z0-9_.-]{1,160}\\.(?:jpg|jpeg|png|webp|gif|avif)")

    /** Telegram widget attachment files, never its avatars or arbitrary public hosts. */
    internal fun isTelegramImage(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
        Regex("cdn[0-9]{1,5}\\.(?:telesco\\.pe|cdn-telegram\\.org)").matches(url.host) &&
        Regex("/file/[A-Za-z0-9_-]{1,2048}={0,2}(?:\\.(?:jpg|jpeg|png|webp|gif|avif))?").matches(url.encodedPath) && url.encodedQuery == null

    /** Bluesky's returned fullsize/thumbnail blob views, including its explicit JPEG conversion. */
    internal fun isBlueskyImage(url: HttpUrl): Boolean = url.isHttps && url.port == 443 && url.host == "cdn.bsky.app" &&
        Regex("/img/(?:feed_fullsize|feed_thumbnail)/plain/(?:${BlueskyPostIdentity.DID})/[A-Za-z0-9_-]{1,256}(?:@(?:jpeg|png|webp|avif))?")
            .matches(url.encodedPath) && url.encodedQuery == null

    /** TikTok photo CDN, including p16-/p19- signed hosts; suffix boundaries reject lookalikes. */
    internal fun isTikTokImage(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
        (url.host.endsWith(".tiktokcdn.com") || url.host.endsWith(".tiktokcdn-us.com")) &&
        url.encodedPath != "/"

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

    // Conservative policy from IANA special-purpose registries, reviewed for this boundary:
    // https://www.iana.org/assignments/iana-ipv4-special-registry/iana-ipv4-special-registry.xhtml
    // https://www.iana.org/assignments/iana-ipv6-special-registry/iana-ipv6-special-registry.xhtml
    private val ipv4Denied = listOf(
        0x00000000L to 8, 0x0a000000L to 8, 0x64400000L to 10, 0x7f000000L to 8,
        0xa9fe0000L to 16, 0xac100000L to 12, 0xc0000000L to 24, 0xc0000200L to 24,
        0xc0586300L to 24, 0xc0a80000L to 16, 0xc6120000L to 15, 0xc6336400L to 24,
        0xcb007100L to 24, 0xe0000000L to 4, 0xf0000000L to 4)

    fun allows(address: InetAddress): Boolean = allowsBytes(address.address)
    private fun allowsBytes(bytes: ByteArray): Boolean {
        if (bytes.size == 4) {
            val value = bytes.fold(0L) { number, byte -> (number shl 8) or (byte.toLong() and 255) }
            return ipv4Denied.none { (prefix, bits) ->
                val mask = (0xffffffffL shl (32 - bits)) and 0xffffffffL
                value and mask == prefix
            }
        }
        if (bytes.size != 16) return false
        val b = bytes.map { it.toInt() and 255 }
        // Java normally represents mapped IPv6 as Inet4Address; also handle explicit 16-byte inputs.
        if (b.take(10).all { it == 0 } && b[10] == 255 && b[11] == 255)
            return allowsBytes(bytes.copyOfRange(12, 16))
        // RFC6052 well-known translation prefix only. Network-specific prefixes cannot be inferred.
        if (b[0] == 0 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b && b.subList(4, 12).all { it == 0 })
            return allowsBytes(bytes.copyOfRange(12, 16))
        if (b[0] and 0xe0 != 0x20) return false // Only global 2000::/3; denies local NAT64/ULA/link-local/etc.
        if (b[0] == 0x20 && b[1] == 1 && b[2] and 0xfe == 0) return false // 2001::/23
        if (b.take(4) == listOf(0x20, 1, 0x0d, 0xb8)) return false // 2001:db8::/32
        if (b[0] == 0x20 && b[1] == 2) return false // 6to4 2002::/16
        if (b[0] == 0x3f && b[1] == 0xff && b[2] and 0xf0 == 0) return false // 3fff::/20
        if (b[0] == 0x5f && b[1] == 0) return false // 5f00::/16 (also outside global allowlist)
        return true
    }
}
