package dev.ahmed.savlino.engine.tiktok

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.util.Base64
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * TikTok's «Please wait…» page: a sha256 proof of work whose answer goes back as one cookie on a single refetch.
 * Mirrors yt-dlp's `_solve_challenge_and_set_cookies` (Unlicense). The cookie lives only in that refetch's header.
 */
internal object TikTokWafChallenge {
    /** The real challenge page is ~1.5 KB; anything near a post page's size is never parsed twice. */
    private const val MAX_PAGE = 32 * 1024
    private const val MAX_ATTEMPTS = 1_000_000
    private val cookieName = Regex("[A-Za-z0-9_-]{1,64}")
    private val cookieValue = Regex("[A-Za-z0-9+/=_.-]{1,4096}")
    private val json = ObjectMapper()

    /** The `Cookie` header that answers [html]'s challenge, or null when it isn't one or can't be solved. */
    fun solve(html: ByteArray, length: Int, checkActive: () -> Unit = {}): String? {
        if (length !in 1..minOf(MAX_PAGE, html.size)) return null
        return try {
            val document = Jsoup.parse(String(html, 0, length, Charsets.UTF_8), "", Parser.htmlParser().setMaxDepth(16))
            fun attribute(id: String) = document.getElementById(id)?.attr("class")?.trim()?.takeIf { it.isNotEmpty() }
            val name = attribute("wci")?.takeIf(cookieName::matches) ?: return null
            val encoded = attribute("cs")?.takeIf { it.length <= 4096 } ?: return null
            val challenge = json.readTree(decode(encoded)) as? ObjectNode ?: return null
            val base = decode(challenge.path("v").path("a").textValue() ?: return null)
            val expected = decode(challenge.path("v").path("c").textValue() ?: return null)
            if (expected.size != 32) return null
            val digest = MessageDigest.getInstance("SHA-256")
            val answer = (0..MAX_ATTEMPTS).firstOrNull { number ->
                if (number % 10_000 == 0) checkActive()
                digest.update(base)
                digest.digest(number.toString().toByteArray()).contentEquals(expected)
            } ?: return null
            challenge.put("d", Base64.getEncoder().encodeToString(answer.toString().toByteArray()))
            val value = Base64.getEncoder().encodeToString(json.writeValueAsBytes(challenge))
            val original = attribute("rci")?.takeIf(cookieName::matches)?.let { rci ->
                attribute("rs")?.takeIf(cookieValue::matches)?.let { "; $rci=$it" }
            }.orEmpty()
            "$name=$value$original"
        } catch (_: IllegalArgumentException) { null }
        catch (_: java.io.IOException) { null }
    }

    /** The page drops base64 padding; restore it as yt-dlp does. */
    private fun decode(value: String): ByteArray =
        Base64.getDecoder().decode(value.trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) })
}
