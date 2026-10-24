package dev.ahmed.savlino

import android.content.Intent
import dev.ahmed.savlino.entry.ShareInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ShareHintTest {
    private fun send(text: String?, subject: String? = null) = ShareInput.from(Intent(Intent.ACTION_SEND).setType("text/plain")
        .apply { text?.let { putExtra(Intent.EXTRA_TEXT, it) }; subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) } })

    @Test fun hintIsTheSharedTextWithoutLinksOrSeparators() {
        assertEquals("Great clip", send("Great clip https://youtu.be/one")?.hint)
        assertEquals("Great clip", send("Great clip - https://youtu.be/one")?.hint)
        assertEquals("Two words", send("Two \n words\twww.youtube.com/watch?v=x")?.hint)
        assertEquals("Line one two", send("  Line\none  two\nhttps://youtu.be/one  ")?.hint)
        assertEquals("Great clip https://youtu.be/one", send("Great clip https://youtu.be/one")?.text)
    }

    @Test fun linkOnlyOrShortTextHasNoHint() {
        assertNull(send("https://youtu.be/one")?.hint)
        assertNull(send("ab https://youtu.be/one")?.hint)
        assertNull(send("🎬🎬 https://youtu.be/one")?.hint)
        assertEquals("🎬🎬🎬", send("🎬🎬🎬 https://youtu.be/one")?.hint)
    }

    @Test fun arabicAndEmojiAreKeptWhole() {
        assertEquals("مقطع جميل جدا", send("مقطع جميل جدا https://youtu.be/one")?.hint)
        assertEquals("«مقطع» 🎬", send("«مقطع» 🎬 https://youtu.be/one")?.hint)
    }

    @Test fun longTextIsCutAt120CodePointsWithEllipsis() {
        val latin = send("a".repeat(300) + " https://youtu.be/one")!!.hint!!
        assertEquals("a".repeat(119) + "…", latin)
        val emoji = send("😀".repeat(200))!!.hint!!
        assertEquals(120, emoji.codePointCount(0, emoji.length))
        assertTrue(emoji.endsWith("…"))
        assertFalse("never splits a surrogate pair", emoji.dropLast(1).last().isHighSurrogate())
        assertEquals("b".repeat(120), send("b".repeat(120))!!.hint)
    }

    @Test fun subjectIsTheFallbackForSendOnly() {
        assertEquals("Shared title", send("https://youtu.be/one", subject = "Shared title")?.hint)
        assertEquals("Body words", send("Body words https://youtu.be/one", subject = "Shared title")?.hint)
        val processText = ShareInput.from(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, "https://youtu.be/one").putExtra(Intent.EXTRA_SUBJECT, "Shared title"))
        assertNull(processText?.hint)
        assertNull(send("https://youtu.be/one", subject = "x".repeat(8193))?.hint)
    }

    @Test fun inputNeverPrintsWhatWasShared() {
        val input = send("Private words https://youtu.be/secret")!!
        assertEquals("ShareInput(redacted)", input.toString())
        assertFalse(input.toString().contains("secret"))
    }
}
