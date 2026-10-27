package dev.ahmed.savlino.engine.tiktok

import java.security.MessageDigest
import java.util.Base64
import org.junit.Test
import kotlin.test.*

/** Synthetic «Please wait…» pages shaped like TikTok's (2026-10-27): padding stripped from `cs`, empty `rs`. */
internal fun wafPage(answer: Int = 4242, rs: String = "", digest: ByteArray? = null): ByteArray {
    val base = ByteArray(32) { it.toByte() }
    val expected = digest ?: MessageDigest.getInstance("SHA-256").digest(base + answer.toString().toByteArray())
    val b64 = Base64.getEncoder()
    val cs = b64.encodeToString("""{"v":{"a":"${b64.encodeToString(base)}","b":1793061631,"c":"${b64.encodeToString(expected)}"},"s":"c3ludGhldGlj"}"""
        .toByteArray()).trimEnd('=')
    return """<!DOCTYPE html><html><head></head><body>Please wait...
        <p id="wci" class="_wafchallengeid"></p><p id="cs" class="$cs"></p>
        <p id="rci" class="waforiginalreid"></p><p id="rs" class="$rs"></p></body></html>""".toByteArray()
}

class TikTokWafChallengeTest {
    private fun solve(page: ByteArray) = TikTokWafChallenge.solve(page, page.size)

    @Test fun answersWithTheCompactChallengeJsonAndTheFoundNumber() {
        val cookie = assertNotNull(solve(wafPage(4242)))
        assertTrue(cookie.startsWith("_wafchallengeid="))
        assertFalse(cookie.contains(";"))
        val json = String(Base64.getDecoder().decode(cookie.substringAfter('=')))
        // Key order and separators as yt-dlp's json.dumps(separators=(',', ':')).
        val base = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        assertTrue(json.startsWith("""{"v":{"a":"$base","b":1793061631,"c":"""))
        assertTrue(json.endsWith(""""s":"c3ludGhldGlj","d":"${Base64.getEncoder().encodeToString("4242".toByteArray())}"}"""))
    }

    @Test fun theOriginalRequestCookieRidesAlongOnlyWhenTheOriginalIdIsThere() {
        assertTrue(assertNotNull(solve(wafPage(rs = "abc123"))).endsWith("; waforiginalreid=abc123"))
        assertFalse(assertNotNull(solve(wafPage(rs = "a b;c"))).contains("waforiginalreid"))
    }

    @Test fun ordinaryPagesAndBrokenOrUnsolvableChallengesAreNotAnswered() {
        assertNull(solve(tikTokPage()))
        assertNull(solve(wafPage(digest = ByteArray(32))))
        assertNull(solve(wafPage(digest = ByteArray(5))))
        assertNull(solve("""<p id="wci" class="_wafchallengeid"></p><p id="cs" class="not base64!"></p>""".toByteArray()))
        assertNull(solve("""<p id="wci" class="bad name;"></p>""".toByteArray() + wafPage()))
        val padded = wafPage() + ByteArray(40 * 1024) { ' '.code.toByte() }
        assertNull(solve(padded))
    }

    @Test fun aStoppedReadEndsTheSearch() {
        val stopped = IllegalStateException("stopped")
        val page = wafPage(digest = ByteArray(32))
        assertSame(stopped, assertFailsWith<IllegalStateException> { TikTokWafChallenge.solve(page, page.size) { throw stopped } })
    }
}
