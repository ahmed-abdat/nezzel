package dev.ahmed.savlino

import dev.ahmed.savlino.locale.*
import dev.ahmed.savlino.ui.theme.*
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LocaleThemeTest {
    private fun prefs(): AppPreferences {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("nezzel", 0).edit().clear().commit()
        app.getSharedPreferences("nezzel-portable", 0).edit().clear().commit()
        return AppPreferences(app)
    }
    @Test fun malformedDismissalsFallBackToEmptyWithoutCrashingStartup() {
        val p = prefs()
        val raw = RuntimeEnvironment.getApplication().getSharedPreferences("nezzel", 0)
        for (malformed in listOf("{", "null", "{}", "[null]", "[{}]")) {
            raw.edit().putString("dismissed_ordered", malformed).commit()
            assertEquals("Malformed stored array", emptySet<String>(), p.dismissedHashes)
            assertEquals(ThemePref.System, p.theme)
        }
    }
    @Test fun onlyLanguageAndThemeMigrateToPortablePreferences() {
        prefs()
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("nezzel", 0).edit().putString("language", "ar")
            .putString("theme", "Dark").putBoolean("automatic_options", false)
            .putString("dismissed_ordered", "[\"private-hash\"]").commit()
        val p = AppPreferences(app)
        assertEquals("ar", p.storedLanguage); assertEquals(ThemePref.Dark, p.theme)
        assertEquals(mapOf("language" to "ar", "theme" to "Dark"),
            app.getSharedPreferences("nezzel-portable", 0).all)
        p.chooseLanguage("en"); p.theme = ThemePref.Light
        assertEquals("en", AppPreferences(app).storedLanguage)
        assertEquals(ThemePref.Light, AppPreferences(app).theme)
        assertFalse(p.automaticOptions)
        assertEquals(setOf("private-hash"), p.dismissedHashes)
    }
    @Test fun firstLaunchFollowsPhoneWithoutStoringChoice() {
        val p = prefs()
        assertEquals("ar", p.language("ar", null))
        assertEquals("en", p.language("fr", null))
        assertEquals("en", p.language("en", null))
        assertNull(p.storedLanguage)
    }
    @Test @Config(qualifiers = "ar") fun arabicPhoneReattachesWithArabicResourcesWithoutStoredChoice() {
        val p = prefs(); val app = RuntimeEnvironment.getApplication()
        assertEquals("ar", AppLocale.attach(app).resources.configuration.locales[0].language)
        assertNull(p.storedLanguage)
    }
    @Test @Config(qualifiers = "fr") fun otherPhoneReattachesWithEnglishResourcesWithoutStoredChoice() {
        val p = prefs(); val app = RuntimeEnvironment.getApplication()
        assertEquals("en", AppLocale.attach(app).resources.configuration.locales[0].language)
        assertNull(p.storedLanguage)
    }
    @Test fun explicitThemeOverridesSystemAndSystemTracksPhone() {
        assertTrue(ThemePref.System.isDark(true)); assertFalse(ThemePref.System.isDark(false))
        assertTrue(ThemePref.Dark.isDark(false)); assertFalse(ThemePref.Light.isDark(true))
    }
    @Test fun languageThemeAndDynamicSwitchesSurviveNewStore() {
        val p = prefs(); assertEquals(ThemePref.System, p.theme); assertFalse(p.matchWallpaper)
        p.chooseLanguage("ar"); p.theme = ThemePref.Dark; p.matchWallpaper = true
        val restored = AppPreferences(RuntimeEnvironment.getApplication())
        assertEquals("ar", restored.language("en", null))
        assertEquals(ThemePref.Dark, restored.theme); assertTrue(restored.matchWallpaper)
        restored.chooseLanguage("en")
        assertEquals("en", AppPreferences(RuntimeEnvironment.getApplication()).language("ar", null))
    }
    @Test fun platformLocaleChoiceWinsOverOldPreference() {
        val p = prefs(); p.chooseLanguage("en")
        assertEquals("ar", p.language("en", "ar"))
        assertEquals("en", p.language("ar", "en"))
    }
    @Test fun arabicNumbersAlwaysUseLatinDigits() {
        assertEquals("42.8\u00a0MB", NezzelFormat.size(42_800_000, "ar"))
        assertEquals("1.4\u00a0GB", NezzelFormat.size(1_400_000_000, "ar"))
        assertEquals("8:42", NezzelFormat.duration(522))
        assertEquals("1:02:15", NezzelFormat.duration(3735))
        assertEquals("63%", NezzelFormat.percent(63, "ar"))
    }
    @Test fun violetGuardUsesCam16HueAndChecksAllThreeAccents() {
        assertEquals(27.408, hctHue(0xffff0000.toInt()), 0.01)
        assertEquals(282.788, hctHue(0xff0000ff.toInt()), 0.01)
        assertTrue(passesVioletGuard(NezzelLightColors))
        for (candidate in listOf(
            NezzelLightColors.copy(primary = Color.Blue),
            NezzelLightColors.copy(secondary = Color.Blue),
            NezzelLightColors.copy(tertiary = Color.Blue))) {
            assertFalse(passesVioletGuard(candidate))
            assertEquals(NezzelLightColors, guardedScheme(candidate, false))
        }
    }
    @Test fun dismissalPersistenceKeepsNewestFiftyInOrderAcrossStores() {
        var p = prefs()
        repeat(55) { index ->
            p.dismissedHashes = p.dismissedHashes + index.toString().padStart(64, '0')
            p = AppPreferences(RuntimeEnvironment.getApplication())
        }
        assertEquals((5..54).map { it.toString().padStart(64, '0') }, p.dismissedHashes.toList())
        p.dismissedHashes = p.dismissedHashes + "newest"
        assertFalse(AppPreferences(RuntimeEnvironment.getApplication()).dismissedHashes.contains(5.toString().padStart(64, '0')))
    }
    @Test fun violetGuardRejectsContainersTintAndInverseAccent() {
        for (candidate in listOf(
            NezzelLightColors.copy(primaryContainer = Color.Blue),
            NezzelLightColors.copy(secondaryContainer = Color.Blue),
            NezzelLightColors.copy(tertiaryContainer = Color.Blue),
            NezzelLightColors.copy(surfaceTint = Color.Blue),
            NezzelLightColors.copy(inversePrimary = Color.Blue))) {
            assertEquals(NezzelLightColors, guardedScheme(candidate, false))
        }
    }
    @Test fun dynamicSchemeKeepsStaticErrorRoles() {
        val candidate = NezzelDarkColors.copy(error = Color.Green, onError = Color.Blue)
        assertEquals(NezzelDarkColors.error, guardedScheme(candidate, true).error)
        assertEquals(NezzelDarkColors.onError, guardedScheme(candidate, true).onError)
    }
}
