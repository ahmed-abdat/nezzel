package dev.ahmed.savlino.engine.reddit

import dev.ahmed.savlino.engine.mediahttp.HttpsUrl
import org.junit.Test
import kotlin.test.*

class RedditJsChallengeTest {
    private val page = assertNotNull(HttpsUrl.parse("https://www.reddit.com$PERMALINK"))
    private fun answer(html: ByteArray, at: okhttp3.HttpUrl = page) = RedditJsChallenge.answer(html, html.size, at)
    private fun edited(from: String, to: String) = String(challengePage()).also { assertTrue(from in it, from) }.replace(from, to).toByteArray()

    @Test fun theSavedCheckIsAnsweredWithTheDoubledSeedInFormOrder() {
        val token = "ab".repeat(32)
        assertEquals("https://www.reddit.com$PERMALINK?solution=0123456789abcdef0123456789abcdef&js_challenge=1&jsc_token=$token&jsc_orig_r=",
            answer(challengePage()).toString())
        // A tracking query on the asked page never travels with the answer.
        assertEquals(answer(challengePage()), answer(challengePage(), page.newBuilder().addQueryParameter("utm_source", "x").build()))
    }

    @Test fun anyOtherShapeIsNotAnswered() {
        assertNull(answer(challengePage("/r/other/comments/9zzzzzz/other/")))
        assertNull(answer(challengePage("https://evil.test$PERMALINK")))
        assertNull(answer(challengePage("$PERMALINK?next=1")))
        assertNull(answer(edited("e+e", "e+e+e")))
        assertNull(answer(edited("name=\"solution\"", "name=\"answer\"")))
        assertNull(answer(edited("name=\"jsc_token\"", "name=\"token\"")))
        assertNull(answer(edited("method=\"GET\"", "method=\"POST\"")))
        assertNull(answer(edited("</form>", "</form><form method=\"GET\" action=\"$PERMALINK\"></form>")))
        assertNull(answer(challengePage() + ByteArray(32 * 1024) { ' '.code.toByte() }))
        assertNull(answer(postPage()))
    }
}
