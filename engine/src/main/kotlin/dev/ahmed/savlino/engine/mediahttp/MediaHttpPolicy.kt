package dev.ahmed.savlino.engine.mediahttp

import dev.ahmed.savlino.engine.bluesky.BlueskyPostIdentity
import dev.ahmed.savlino.engine.linkedin.LinkedInPostIdentity
import java.net.InetAddress
import okhttp3.HttpUrl

internal object MediaHttpPolicy {
    /** Only a shortlink may establish the post; every later post/activity hop must keep that identity. */
    internal fun allowsLinkedInPageHop(from: HttpUrl, to: HttpUrl): Boolean {
        val hop = LinkedInPostIdentity.linkedInHopTarget(to)
        if (!LinkedInPostIdentity.isLinkedInPageEntry(from) || !LinkedInPostIdentity.isLinkedInPost(hop)) return false
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

    /**
     * Reddit's own image hosts: the original on `i.redd.it/<name>.<ext>` (no query) and its signed resized copies on
     * `preview.redd.it/<name>.<ext>?…`, never an animated mp4 rendition, external-preview or any other host.
     */
    internal fun isRedditImage(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
        redditImagePath.matches(url.encodedPath) && when (url.host) {
            "i.redd.it" -> url.encodedQuery == null
            "preview.redd.it" -> (url.encodedQuery?.length ?: 0) <= 1024 &&
                url.queryParameterValues("format").none { it == null || it.startsWith("mp4") || it == "webm" }
            else -> false
        }
    private val redditImagePath = Regex("/[A-Za-z0-9_-]{1,200}\\.(?:jpg|jpeg|png|webp|gif)")

    /**
     * Pinterest's image host only: `/originals/` or a sized folder (`736x`, `236x`, `136x136`, `75x75_RS`),
     * then the signature split into three folders. No query, no `webapp` or cover paths.
     */
    internal fun isPinterestImage(url: HttpUrl): Boolean {
        if (!url.isHttps || url.port != 443 || url.host != "i.pinimg.com" || url.encodedQuery != null) return false
        val parts = url.encodedPath.split('/')
        if (parts.size != 6 || !pinterestFolder.matches(parts[1])) return false
        val file = parts[5]
        val dot = file.lastIndexOf('.')
        if (dot <= 0 || file.substring(dot + 1).lowercase() !in pinterestExtensions) return false
        val signature = file.substring(0, dot).lowercase()
        return signature.length == 32 && signature.all { it in '0'..'9' || it in 'a'..'f' } &&
            (parts[2] + parts[3] + parts[4]).lowercase() == signature.take(6)
    }
    private val pinterestFolder = Regex("originals|[0-9]{2,4}x(?:[0-9]{2,4})?(?:_RS)?")
    private val pinterestExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")

    /** TikTok photo CDN, including p16-/p19- signed hosts; suffix boundaries reject lookalikes. */
    internal fun isTikTokImage(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
        (url.host.endsWith(".tiktokcdn.com") || url.host.endsWith(".tiktokcdn-us.com")) &&
        url.encodedPath != "/"

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
