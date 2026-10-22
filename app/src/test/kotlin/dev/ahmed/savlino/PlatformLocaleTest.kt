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
    @Test @Config(sdk = [29, 33], qualifiers = "fr")
    fun frenchPhoneUsesFrenchResourcesWithoutStoringChoice() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("nezzel", 0).edit().clear().commit()
        app.getSharedPreferences("nezzel-portable", 0).edit().clear().commit()
        assertEquals("fr", AppLocale.current(app))
        val localized = AppLocale.attach(app)
        assertEquals("Paramètres", localized.getString(R.string.settings))
        assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, localized.resources.configuration.layoutDirection)
        assertNull(AppPreferences(app).storedLanguage)
    }
    @Test @Config(sdk = [29, 33])
    fun choosingFrenchLocalizesResourcesAndRetainsLatinDigits() {
        val app = RuntimeEnvironment.getApplication()
        AppLocale.choose(app, "fr")
        assertEquals("fr", AppLocale.current(app))
        val localized = AppLocale.attach(app)
        val locale = localized.resources.configuration.locales[0]
        assertEquals("fr", locale.language)
        assertEquals("latn", locale.getUnicodeLocaleType("nu"))
        assertEquals("Téléchargements", localized.getString(R.string.downloads))
        assertEquals("123", java.text.NumberFormat.getIntegerInstance(locale).format(123))
        assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, localized.resources.configuration.layoutDirection)
        assertTrue(localized.getString(R.string.report_link_intro).startsWith("Nezzel n’a pas pu"))
    }
    @Test @Config(sdk = [29, 33])
    fun invalidAppLocaleChoiceDoesNotReplaceFrench() {
        val app = RuntimeEnvironment.getApplication()
        AppLocale.choose(app, "fr")
        assertThrows(IllegalArgumentException::class.java) { AppLocale.choose(app, "de") }
        assertEquals("fr", AppLocale.current(app))
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
