package dev.ahmed.savlino

import android.app.LocaleManager
import android.os.LocaleList
import dev.ahmed.savlino.locale.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlatformLocaleTest {
    @Test @Config(sdk = [29, 33], qualifiers = "ar")
    fun activityArabicLocaleRetainsLatinNumberingSystem() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val locale = controller.get().resources.configuration.locales[0]
            assertEquals("ar", locale.language)
            assertEquals("latn", locale.getUnicodeLocaleType("nu"))
            assertEquals("123", java.text.NumberFormat.getIntegerInstance(locale).format(123))
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun platformSelectionSurvivesReattachAndSystemResetIgnoresStalePre33Choice() {
        val app = RuntimeEnvironment.getApplication()
        AppPreferences(app).chooseLanguage("ar") // stale pre-33 preference
        val manager = app.getSystemService(LocaleManager::class.java)
        manager.applicationLocales = LocaleList.forLanguageTags("en")
        assertEquals("en", AppLocale.current(app))
        AppLocale.choose(app, "ar")
        assertEquals("ar", manager.applicationLocales[0].language)
        assertEquals("ar", AppLocale.attach(app).resources.configuration.locales[0].language)
        manager.applicationLocales = LocaleList.getEmptyLocaleList()
        assertEquals(AppPreferences.phoneLanguage(manager.systemLocales[0].language), AppLocale.current(app))
    }
}
