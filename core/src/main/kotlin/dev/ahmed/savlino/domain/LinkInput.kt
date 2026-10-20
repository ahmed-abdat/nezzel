package dev.ahmed.savlino.domain

import java.net.URI
import java.net.URISyntaxException

/** Created by intake only. Raw reviewed bytes are retained for the eventual engine request. */
class ValidatedLink internal constructor(val rawUrl: String, val normalizedUrl: String, val host: Host) {
    // Avoid leaking a reviewed URL through generated data-class toString/logging.
    override fun toString(): String = "ValidatedLink(host=$host)"
}
enum class Host { YOUTUBE, TIKTOK, INSTAGRAM, FACEBOOK, X, LINKEDIN, OTHER }

object LinkInput {
    private val urls = Regex("https://[^\\s\\p{Z}<>\"“”«»]+", RegexOption.IGNORE_CASE)
    private val wrappers = mapOf('(' to ')', '[' to ']', '{' to '}', '\'' to '\'')
    private const val PROSE_PUNCTUATION = ".,;!،؛؟"

    fun parse(text: String?): ValidatedLink? = candidates(text).firstOrNull()

    /** HTTPS/public-DNS candidates in source order; scheme/host case alone does not create a second link. */
    fun candidates(text: String?): List<ValidatedLink> {
        if (text == null || text.length > 8192) return emptyList()
        return urls.findAll(text).mapNotNull { match ->
            validate(unwrap(match.value, text.getOrNull(match.range.first - 1)))
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
