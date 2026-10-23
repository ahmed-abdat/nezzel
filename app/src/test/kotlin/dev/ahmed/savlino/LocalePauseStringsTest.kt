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
}
