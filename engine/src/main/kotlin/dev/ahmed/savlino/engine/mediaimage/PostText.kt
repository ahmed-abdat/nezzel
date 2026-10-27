package dev.ahmed.savlino.engine.mediaimage

import dev.ahmed.savlino.domain.PreviewMetadata
import org.jsoup.parser.Parser

/**
 * Display/file-name text a fetched page already proves (post text, author name): the first non-blank
 * line, every link-like token removed, control/format characters stripped, at most [MAX_CODE_POINTS].
 * Untrusted; never a URL, host, address or e-mail.
 */
internal object PostText {
    const val MAX_CODE_POINTS = 120
    private const val MAX_SCAN = 4096
    private val lineBreak = Regex("[\\r\\n\\u0085\\u2028\\u2029]")
    private val space = Regex("\\s+")
    // Leading/trailing wrapping punctuation is ignored when classifying a token, e.g. "(https://x)." or "<x.org>".
    private val wrapping = "()[]{}<>\"'«»“”‘’.,;:!?"
    private val tail = "(?::\\d{1,5})?(?:[/?#].*)?"
    private val linkLike = listOf(
        Regex("(?i)^[a-z][a-z0-9+.-]*:\\S+"),              // any scheme: https:, mailto:, tel:, javascript:, data:
        Regex("^//\\S+"),                                   // protocol-relative
        Regex("^\\S+@\\S+\\.\\S+"),                         // bare e-mail / userinfo
        Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$tail$"),         // IPv4 with optional port/path
        Regex("^\\[[0-9a-fA-F:.]+]$tail$"),                // bracketed IPv6
        Regex("(?i)^(?:www\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,63}\\.?$tail$"), // domain[:port][/path]
        Regex("(?i)^(?=[^:]*[a-z])[a-z0-9-]+(?:\\.[a-z0-9-]+)*:\\d{1,5}(?:[/?#].*)?$"), // host:port[/path], not "10:30"
    )

    private val numericReference = Regex("&#([0-9]{1,7}|[xX][0-9a-fA-F]{1,6});")

    /**
     * TikTok can escape its emoji twice (`&amp;#128558;`, phone 2026-10-27): decode HTML once, then any numeric
     * reference left. A reference to no valid code point stays as written.
     */
    fun tiktokUnescaped(raw: String?): String? = raw?.let { Parser.unescapeEntities(it, false) }?.let { once ->
        numericReference.replace(once) { match ->
            val digits = match.groupValues[1]
            val cp = if (digits[0] == 'x' || digits[0] == 'X') digits.drop(1).toIntOrNull(16) else digits.toIntOrNull()
            cp?.takeIf { Character.isValidCodePoint(it) && it !in 0xD800..0xDFFF }?.let { String(Character.toChars(it)) } ?: match.value
        }
    }

    fun firstLine(raw: String?): String? {
        if (raw == null) return null
        val first = raw.take(MAX_SCAN).split(lineBreak).firstOrNull { it.isNotBlank() } ?: return null
        // Controls/format characters go first so they cannot hide a link from the token checks.
        val line = PreviewMetadata.text(first, MAX_SCAN) ?: return null
        val kept = line.split(space).filterNot { token ->
            val forms = listOf(token.trim { it in wrapping }, token.trimEnd { it in wrapping }).filter(String::isNotEmpty)
            forms.any { form -> linkLike.any { it.matches(form) } }
        }
        return PreviewMetadata.text(kept.joinToString(" "), MAX_CODE_POINTS)
    }
}
