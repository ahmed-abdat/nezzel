package dev.ahmed.savlino.domain

import java.net.URI
import java.net.URLDecoder
import java.net.URISyntaxException

/** Created by intake only. Raw reviewed bytes are retained for the eventual engine request. */
class ValidatedLink internal constructor(val rawUrl: String, val normalizedUrl: String, val host: Host) {
    // Avoid leaking a reviewed URL through generated data-class toString/logging.
    override fun toString(): String = "ValidatedLink(host=$host)"
}
enum class Host { YOUTUBE, TIKTOK, INSTAGRAM, FACEBOOK, X, LINKEDIN, OTHER }

object LinkInput {
    val FACEBOOK_WRAPPER_HOSTS: Set<String> = setOf("l.facebook.com", "lm.facebook.com")
    val FACEBOOK_ALIAS_HOSTS: Set<String> = setOf("fb.com", "www.fb.com")
    private val urls = Regex("https?://[^\\s\\p{Z}<>\"“”«»]+", RegexOption.IGNORE_CASE)
    private val wrappers = mapOf('(' to ')', '[' to ']', '{' to '}', '\'' to '\'')
    private const val PROSE_PUNCTUATION = ".,;!،؛؟"

    fun parse(text: String?): ValidatedLink? = candidates(text).firstOrNull()

    /** HTTPS/public-DNS candidates in source order; scheme/host case alone does not create a second link. */
    fun candidates(text: String?): List<ValidatedLink> {
        if (text == null || text.length > 8192) return emptyList()
        return urls.findAll(text).mapNotNull { match ->
            // A copied http:// link is read over HTTPS; nothing is ever fetched in cleartext.
            val secure = if (match.value.startsWith("http://", ignoreCase = true)) "https://" + match.value.substring(7) else match.value
            validate(alias(unwrap(secure, text.getOrNull(match.range.first - 1))))
        }.distinctBy { it.normalizedUrl }.toList()
    }

    private fun unwrap(value: String, preceding: Char?): String {
        var raw = value.trimEnd('،', '؛', '؟')
        wrappers[preceding]?.let { closing ->
            val end = raw.lastIndexOf(closing)
            if (end >= 0 && raw.substring(end + 1).all { it in PROSE_PUNCTUATION }) raw = raw.substring(0, end)
        }
        // Keep ASCII query/fragment punctuation: it can be signed. Never decode or re-encode URL bytes.
        if ('?' in raw || '#' in raw) return raw
        while (raw.isNotEmpty()) {
            val last = raw.last()
            val opening = wrappers.entries.firstOrNull { it.value == last && it.key != '\'' }?.key
            if (last in PROSE_PUNCTUATION || (opening != null && raw.count { it == last } > raw.count { it == opening })) {
                raw = raw.dropLast(1)
            } else break
        }
        return raw
    }

    /**
     * Network-free aliases: Facebook's outbound l.php wrapper becomes its u= target (validated like any pasted link),
     * fb.com becomes www.facebook.com (fb.com's own redirect drops the query, e.g. watch?v=).
     */
    private fun alias(raw: String): String {
        val uri = try { URI(raw) } catch (_: URISyntaxException) { return raw }
        val host = uri.host?.lowercase() ?: return raw
        if (uri.rawUserInfo != null || uri.port != -1) return raw
        if (host in FACEBOOK_WRAPPER_HOSTS && uri.rawPath == "/l.php") {
            val target = uri.rawQuery.orEmpty().split('&').filter { it.startsWith("u=") }.singleOrNull()?.substring(2) ?: return raw
            val decoded = try { URLDecoder.decode(target, "UTF-8") } catch (_: IllegalArgumentException) { return "" }
            val secure = if (decoded.startsWith("http://", ignoreCase = true)) "https://" + decoded.substring(7) else decoded
            return secure.takeIf { it.startsWith("https://", ignoreCase = true) && it.none(Char::isWhitespace) }.orEmpty()
        }
        if (host in FACEBOOK_ALIAS_HOSTS)
            return "https://www.facebook.com" + raw.substring(raw.indexOf(uri.rawAuthority) + uri.rawAuthority.length)
        // A Mix shared as /playlist?list=RD<video id> has no v=: it becomes the watch link of its seed video.
        if (host in setOf("youtube.com", "www.youtube.com", "m.youtube.com") && uri.rawPath.trimEnd('/') == "/playlist") {
            val params = uri.rawQuery.orEmpty().split('&')
            val list = params.filter { it.startsWith("list=") }.singleOrNull()?.substring(5) ?: return raw
            val seed = Regex("RD([A-Za-z0-9_-]{11})").matchEntire(list)?.groupValues?.get(1) ?: return raw
            if (params.none { it.startsWith("v=") }) return "https://www.youtube.com/watch?v=$seed&list=$list"
        }
        return raw
    }

    private fun validate(raw: String): ValidatedLink? {
        if (raw.any { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }) return null
        val uri = try { URI(raw) } catch (_: URISyntaxException) { return null }
        if (uri.rawUserInfo != null || uri.rawAuthority == null || uri.host == null) return null
        if (!HostDetector.isPublicDnsHost(uri.host)) return null
        if (uri.port != -1 && uri.port !in 1..65535) return null
        if (uri.rawAuthority.endsWith(":")) return null
        return ValidatedLink(raw, UrlNormalizer.normalize(uri), HostDetector.detect(uri.host))
    }
}
