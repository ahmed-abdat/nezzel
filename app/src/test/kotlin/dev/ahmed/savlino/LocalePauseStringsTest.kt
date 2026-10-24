package dev.ahmed.savlino

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class LocalePauseStringsTest {
    private val ids = listOf(R.string.pause, R.string.resume, R.string.download_paused, R.string.download_waiting_network,
        R.string.download_restarting, R.string.download_pause_accessibility, R.string.download_resume_accessibility)
    private fun text(language: String, id: Int): String {
        val base = ApplicationProvider.getApplicationContext<android.app.Application>()
        val cfg = android.content.res.Configuration(base.resources.configuration).apply { setLocale(java.util.Locale(language)) }
        return base.createConfigurationContext(cfg).getString(id, "X")
    }
    @Test fun everyPauseStringExistsInThreeLocalesAndDiffersFromEnglishWhereItShould() {
        for (language in listOf("en", "ar", "fr")) for (id in ids) assertTrue("$language $id", text(language, id).isNotBlank())
        assertEquals("متوقف مؤقتًا", text("ar", R.string.download_paused))
        assertEquals("Reprendre", text("fr", R.string.resume))
        // The a11y labels name the file (one %1$s).
        for (language in listOf("en", "ar", "fr")) {
            assertTrue(text(language, R.string.download_pause_accessibility).contains("X"))
            assertTrue(text(language, R.string.download_resume_accessibility).contains("X"))
        }
    }

    @Test fun batchCountsUseEveryArabicPluralForm() {
        val base = ApplicationProvider.getApplicationContext<android.app.Application>()
        for (language in listOf("en", "ar", "fr")) {
            val cfg = android.content.res.Configuration(base.resources.configuration).apply { setLocale(java.util.Locale(language)) }
            val res = base.createConfigurationContext(cfg).resources
            for (id in listOf(R.plurals.downloads_paused_n, R.plurals.downloads_resumed_n, R.plurals.downloads_retried_n, R.plurals.downloads_count)) {
                // ar: zero, one, two, few (3), many (11), other (100) are six different sentences.
                val forms = listOf(0, 1, 2, 3, 11, 100).map { res.getQuantityString(id, it, it.toString()) }
                assertTrue("$language $id", forms.all { it.isNotBlank() })
                if (language == "ar") assertEquals("$id: $forms", 6, forms.toSet().size)
                else assertTrue("$language $id: $forms", forms[3].contains("3"))
            }
        }
    }
}
