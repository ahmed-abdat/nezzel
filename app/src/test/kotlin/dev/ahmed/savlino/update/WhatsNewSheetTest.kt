package dev.ahmed.savlino.update

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.*
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.*
import dev.ahmed.savlino.R
import dev.ahmed.savlino.jobs.NezzelApplication
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.AboutSection
import dev.ahmed.savlino.ui.LinkViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WhatsNewSheetTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as NezzelApplication
    private fun nodes(a: ComponentActivity): List<SemanticsNode> { compose.waitForIdle(); return semanticsNodes(a) }
    private val language get() = app.resources.configuration.locales[0].language
    @Before fun reset() {
        app.getSharedPreferences("app-updates", Context.MODE_PRIVATE).edit().clear().commit()
        app.appUpdates.refreshWhatsNew()
    }
    private fun record(updates: AppUpdates = app.appUpdates, count: Int = 1) {
        assertTrue(updates.recordInstall(123, Release("0.4.5", BuildConfig.VERSION_CODE.toLong(), "update.apk", 100,
            "https://github.com/asset", "a".repeat(64), (1..count).map { "Improvement $it" }, (1..count).map { "تحسين $it" })))
        updates.refreshWhatsNew()
    }
    private fun click(a: ComponentActivity, label: String) {
        val button = nodes(a).last { it.config.getOrNull(SemanticsActions.OnClick) != null && label in texts(descendants(it)) }
        assertTrue(button.layoutInfo.height / app.resources.displayMetrics.density >= 48f)
        assertEquals(true, button.config[SemanticsActions.OnClick].action?.invoke())
    }
    private fun home() {
        record()
        var rationale by mutableStateOf(true)
        val links = LinkViewModel(SavedStateHandle())
        renderThemed({ nodes(it) }, {
            NezzelApp(links, language, ThemePref.Light, false, false,
                {}, {}, {}, {}, {}, {}, {}, notificationRationaleShowing = rationale)
        }) { a ->
            fun visible() = nodes(a).any { it.config.getOrNull(SemanticsProperties.TestTag) == "whats_new_sheet" }
            assertFalse(visible())
            assertNotNull(app.appUpdates.state.value.whatsNew)
            rationale = false
            // An update opens straight to Home: no sheet, the notes are consumed and stay in About.
            assertFalse(visible())
            assertNull(app.appUpdates.state.value.whatsNew)
            assertNotNull(app.appUpdates.currentNotes())
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try { assertNull(AppUpdates(app, owner).state.value.whatsNew) } finally { owner.cancel() }
        }
    }
    @Test fun homeConsumesNotesWithoutASheetEnglish() = home()
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun homeConsumesNotesWithoutASheetArabic() = home()

    @Test fun homeWaitsForOptionsAndDoesNotConsumeNotesOnOtherRoutes() {
        record()
        val links = LinkViewModel(SavedStateHandle()).apply {
            edit("https://example.org/video")
            checkLink() // UnavailableEngine returns a local error; no network
        }
        renderThemed({ nodes(it) }, {
            NezzelApp(links, language, ThemePref.Light, false, false, {}, {}, {}, {}, {}, {}, {})
        }) { a ->
            fun visible() = nodes(a).any { it.config.getOrNull(SemanticsProperties.TestTag) == "whats_new_sheet" }
            assertFalse(visible())
            assertNotEquals(dev.ahmed.savlino.ui.OptionsState.Hidden, links.optionsState)
            links.navigate("Settings")
            links.dismissOptions()
            assertFalse(visible())
            assertNotNull(app.appUpdates.state.value.whatsNew)
            links.navigate("Home")
            assertFalse(visible())
            assertNull(app.appUpdates.state.value.whatsNew)
        }
    }

    @Test fun dismissingAboutSheetDoesNotConsumePendingAutomaticPrompt() {
        record()
        renderThemed({ nodes(it) }, { AboutSection("0.4.5", BuildConfig.VERSION_CODE) }) { a ->
            click(a, app.getString(R.string.update_whats_new))
            click(a, app.getString(R.string.whats_new_ok))
            assertNotNull(app.appUpdates.state.value.whatsNew)
        }
    }

    private fun about() {
        record()
        app.appUpdates.dismissWhatsNew()
        renderThemed({ nodes(it) }, { AboutSection("0.4.5", BuildConfig.VERSION_CODE, language = language) }) { a ->
            click(a, app.getString(R.string.update_whats_new))
            assertTrue(texts(nodes(a)).contains(if (language == "ar") "تحسين 1" else "Improvement 1"))
            click(a, app.getString(R.string.whats_new_ok))
            assertNotNull(app.appUpdates.currentNotes())
            assertNull(app.appUpdates.state.value.whatsNew)
            click(a, app.getString(R.string.update_whats_new))
            assertTrue(nodes(a).any { it.config.getOrNull(SemanticsProperties.TestTag) == "whats_new_sheet" })
        }
    }
    @Test fun aboutReopensRetainedNotesEnglish() = about()
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun aboutReopensRetainedNotesArabic() = about()

    @Test fun aboutHidesWhatsNewWhenUpdaterIsUnavailable() {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val disabled = AppUpdates(object : android.content.ContextWrapper(app) {
            override fun getPackageName() = "dev.ahmed.savlino.acceptance"
        }, owner)
        try {
            renderThemed({ nodes(it) }, { AboutSection("0.4.5", BuildConfig.VERSION_CODE, updates = disabled) }) { a ->
                assertFalse(texts(nodes(a)).contains(app.getString(R.string.update_whats_new)))
            }
        } finally { owner.cancel() }
    }

    private fun homeWithoutNotes(updated: Boolean) {
        if (updated) record(count = 0)
        val links = LinkViewModel(SavedStateHandle())
        renderThemed({ nodes(it) }, {
            NezzelApp(links, language, ThemePref.Light, false, false, {}, {}, {}, {}, {}, {}, {})
        }) { a ->
            val all = nodes(a)
            assertFalse(all.any { it.config.getOrNull(SemanticsProperties.TestTag) == "whats_new_sheet" })
            val message = app.getString(R.string.update_done, dev.ahmed.savlino.locale.NezzelFormat.isolate(BuildConfig.VERSION_NAME))
            assertEquals(updated, texts(all).contains(message))
        }
    }
    @Test fun freshInstallHasNoSheetOrUpdatedSnackbar() = homeWithoutNotes(false)
    @Test fun updateWithoutNotesKeepsUpdatedSnackbar() = homeWithoutNotes(true)

    private fun offeredSheet() {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val production = object : android.content.ContextWrapper(app) { override fun getPackageName() = UpdatePolicy.PACKAGE }
        val manifest = org.json.JSONObject().put("schema", 1).put("version", "0.5.0").put("versionCode", 99999).put("important", true)
            .put("apk", org.json.JSONObject().put("name", "update.apk").put("size", 100).put("sha256", "a".repeat(64)))
            .put("notes", org.json.JSONObject().put("en", org.json.JSONArray().put("New improvement"))
                .put("ar", org.json.JSONArray().put("تحسين جديد")))
        app.getSharedPreferences("app-updates", Context.MODE_PRIVATE).edit().putString("release", manifest.toString())
            .putString("release-format", "manifest").commit()
        val downloading = CompletableDeferred<Unit>()
        val updates = AppUpdates(production, owner) { object : UpdateHttp("test") {
            override suspend fun download(release: Release, file: java.io.File, progress: (Long) -> Unit) {
                downloading.complete(Unit)
                awaitCancellation()
            }
        } }
        val links = LinkViewModel(SavedStateHandle())
        var rationale by mutableStateOf(true)
        updates.onAppOpen()
        try {
            renderThemed({ nodes(it) }, {
                val state by updates.state.collectAsState()
                AppUpdateSheets(updates, state, links, language, rationale)
            }) { a ->
                fun visible() = nodes(a).any { it.config.getOrNull(SemanticsProperties.TestTag) == "whats_new_sheet" }
                assertFalse(visible())
                assertFalse(updates.prefs.getBoolean("sheet-99999", false))
                links.edit("https://example.org/video"); links.checkLink()
                rationale = false
                assertFalse(visible()) // Includes format selection and download confirmation.
                links.dismissOptions()
                links.navigate("Settings")
                assertFalse(visible()) // Settings has its own dialogs/sheets.
                links.navigate("Home")
                assertTrue(visible())
                assertTrue(updates.prefs.getBoolean("sheet-99999", false))
                click(a, app.getString(R.string.update_later))
                assertFalse(visible())
                updates.onAppClosed(); updates.onAppOpen()
                assertTrue(visible())
                click(a, app.getString(R.string.update_action))
                assertFalse(visible()) // Waits for the hide animation that precedes navigation.
                assertEquals("AppUpdate", links.destination)
                runBlocking { withTimeout(5000) { downloading.await() } }
                assertEquals(UpdateStage.DOWNLOADING, updates.state.value.stage)
            }
        } finally { owner.cancel() }
    }
    @Test fun offeredSheetWaitsForModalsAndLaterDefersUntilNextOpenEnglish() = offeredSheet()
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi")
    fun offeredSheetWaitsForModalsAndLaterDefersUntilNextOpenArabic() = offeredSheet()

    private fun sheet() {
        val notes = WhatsNew("0.4.5", (1..8).map { "Improvement $it" }, (1..8).map { "تحسين $it" })
        var visible by mutableStateOf(true)
        var selectedLanguage by mutableStateOf("en")
        renderThemed({ nodes(it) }, {
            if (visible) WhatsNewSheet(notes, selectedLanguage) { visible = false }
        }) { a ->
            assertTrue(texts(nodes(a)).contains("Improvement 6"))
            assertTrue(texts(nodes(a)).contains("Improvement 7"))
            selectedLanguage = "ar"
            assertTrue(texts(nodes(a)).contains("تحسين 6"))
            assertFalse(texts(nodes(a)).contains("Improvement 1"))
            if (app.resources.configuration.screenHeightDp < 480) {
                val scrolling = nodes(a).first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
                assertTrue(scrolling.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f)
                assertEquals(true, scrolling.config[SemanticsActions.ScrollBy].action?.invoke(0f, 2000f))
                nodes(a)
            }
            assertFalse(texts(nodes(a)).any { "GitHub" in it || Regex("\\d+\\.\\d+").containsMatchIn(it) })
            assertNull(org.robolectric.Shadows.shadowOf(a).nextStartedActivity)
            // The sheet's accessibility dismiss action shares Back/swipe/scrim's callback.
            val dismiss = nodes(a).first { it.config.getOrNull(SemanticsActions.Dismiss) != null }
            assertEquals(true, dismiss.config[SemanticsActions.Dismiss].action?.invoke())
            nodes(a) // The Material sheet completes its hide animation before notifying dismissal
            assertFalse(visible)
        }
    }
    @Test fun sheetShowsAllNotesSwitchesLanguageAndDismisses() = sheet()
    @Test @Config(qualifiers = "ar-w640dp-h360dp-mdpi") fun sheetScrollsInArabicLandscape() = sheet()
}
