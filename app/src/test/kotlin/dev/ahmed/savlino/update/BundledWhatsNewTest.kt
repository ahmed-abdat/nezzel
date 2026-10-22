package dev.ahmed.savlino.update

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BundledWhatsNewTest {
    @Test fun parsesBothLanguagesAndSkipsBlankLines() {
        val notes = BundledWhatsNew.parse("0.4.6\n\n• First fix\n  \n• Second fix\n",
            "0.4.6\n• إصلاح أول\n\n• إصلاح ثان\n")!!
        assertEquals("0.4.6", notes.version)
        assertEquals(listOf("First fix", "Second fix"), notes.english)
        assertEquals(listOf("إصلاح أول", "إصلاح ثان"), notes.arabic)
        assertEquals(listOf("First fix", "Second fix"), notes.notes("fr"))
    }

    @Test fun missingEmptyOrVersionOnlyAssetHasNoNotes() {
        val valid = "0.4.6\n• Fixed\n"
        for (empty in listOf(null, "", " \n\n", "0.4.6\n\n• \n")) {
            assertNull(BundledWhatsNew.parse(empty, valid))
            assertNull(BundledWhatsNew.parse(valid, empty))
        }
    }

    @Test fun differentVersionsCannotMixTranslatedNotes() {
        assertNull(BundledWhatsNew.parse("0.4.6\n• Fixed", "0.4.5\n• إصلاح"))
    }

    @Test fun readsBothGeneratedAssets() {
        val assets = RuntimeEnvironment.getApplication().assets
        val notes = BundledWhatsNew.read(assets)!!
        val english = assets.open("whats-new/en.txt").bufferedReader().use { it.readLines() }
        val arabic = assets.open("whats-new/ar.txt").bufferedReader().use { it.readLines() }
        assertEquals(english.first(), notes.version)
        assertEquals(arabic.first(), notes.version)
        assertEquals(english.drop(1).filter { it.isNotBlank() }.map { it.removePrefix("• ") }, notes.english)
        assertEquals(arabic.drop(1).filter { it.isNotBlank() }.map { it.removePrefix("• ") }, notes.arabic)
        assertTrue(notes.english.isNotEmpty())
        assertTrue(notes.arabic.isNotEmpty())
    }
}
