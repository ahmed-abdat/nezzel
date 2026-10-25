package dev.ahmed.savlino.update

import android.app.Application
import android.content.res.Configuration
import dev.ahmed.savlino.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class PlainUpdateCopyTest {
    @Test fun releaseNotesHideAVersionOnlyFirstLine() {
        val release = Release("0.5.0", 99999, "update.apk", 100, "https://github.com/asset", "a".repeat(64),
            listOf("0.5.0", "Easier downloads"), listOf("0.5.0", "تنزيل أسهل"))
        assertEquals(listOf("Easier downloads"), release.notes("en"))
        assertEquals(listOf("تنزيل أسهل"), WhatsNew("0.5.0", emptyList(), release.arabic).notes("ar"))
    }
    @Test fun updateCopyDoesNotExposeVersionArgumentsOrGithub() {
        for (language in listOf("en", "ar", "fr")) {
            val app = RuntimeEnvironment.getApplication()
            val config = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val context = app.createConfigurationContext(config)
            for (id in listOf(R.string.update_done, R.string.update_notification_title, R.string.whats_new_title,
                R.string.update_card_version, R.string.update_current)) {
                val shown = context.getString(id, "0.5.0")
                assertFalse(shown, Regex("\\d+\\.\\d+").containsMatchIn(shown))
                assertFalse(shown, shown.contains("GitHub", ignoreCase = true))
            }
            assertEquals(0, context.resources.getIdentifier("update_full_notes", "string", app.packageName))
            assertEquals(0, context.resources.getIdentifier("update_open_release", "string", app.packageName))
        }
    }
}
