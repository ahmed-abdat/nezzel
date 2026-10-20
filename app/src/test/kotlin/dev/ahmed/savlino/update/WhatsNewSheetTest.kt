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
            NezzelApp(links, language, ThemePref.Light, false, false, false,
                {}, {}, {}, {}, {}, {}, {}, notificationRationaleShowing = rationale)
        }) { a ->
            fun visible() = nodes(a).any { it.config.getOrNull(SemanticsProperties.TestTag) == "whats_new_sheet" }
            assertFalse(visible())
            assertNotNull(app.appUpdates.state.value.whatsNew)
            rationale = false
            assertTrue(visible())
            val displayed = texts(nodes(a))
            assertTrue(displayed.contains(if (language == "ar") "تحسين 1" else "Improvement 1"))
            assertFalse(displayed.contains(if (language == "ar") "Improvement 1" else "تحسين 1"))
            assertFalse(displayed.contains(app.getString(R.string.update_full_notes)))
            click(a, app.getString(R.string.whats_new_ok))
            assertFalse(visible())
            assertNull(app.appUpdates.state.value.whatsNew)
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try { assertNull(AppUpdates(app, owner).state.value.whatsNew) } finally { owner.cancel() }
        }
    }
    @Test fun homeDefersForDialogThenDismissesPermanentlyEnglish() = home()
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun homeDefersForDialogThenDismissesPermanentlyArabic() = home()

    @Test fun homeWaitsForOptionsAndDoesNotConsumeNotesOnOtherRoutes() {
        record()
        val links = LinkViewModel(SavedStateHandle()).apply {
            edit("https://example.org/video")
            checkLink() // UnavailableEngine returns a local error; no network
        }
        renderThemed({ nodes(it) }, {
            NezzelApp(links, language, ThemePref.Light, false, false, false, {}, {}, {}, {}, {}, {}, {})
        }) { a ->
            fun visible() = nodes(a).any { it.config.getOrNull(SemanticsProperties.TestTag) == "whats_new_sheet" }
            assertFalse(visible())
            assertNotEquals(dev.ahmed.savlino.ui.OptionsState.Hidden, links.optionsState)
            links.dismissOptions()
            assertTrue(visible())
            links.navigate("Settings")
            assertFalse(visible())
            assertNotNull(app.appUpdates.state.value.whatsNew)
            links.navigate("Home")
            assertTrue(visible())
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

    @Test fun aboutWithoutNotesOpensCurrentReleaseTag() = renderThemed({ nodes(it) }, {
        AboutSection("0.4.5", BuildConfig.VERSION_CODE)
    }) { a ->
        click(a, app.getString(R.string.update_whats_new))
        assertEquals("https://github.com/ahmed-abdat/nezzel/releases/tag/v0.4.5",
            org.robolectric.Shadows.shadowOf(a).nextStartedActivity?.dataString)
    }

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
            NezzelApp(links, language, ThemePref.Light, false, false, false, {}, {}, {}, {}, {}, {}, {})
        }) { a ->
            val all = nodes(a)
            assertFalse(all.any { it.config.getOrNull(SemanticsProperties.TestTag) == "whats_new_sheet" })
            val message = app.getString(R.string.update_done, dev.ahmed.savlino.locale.NezzelFormat.isolate(BuildConfig.VERSION_NAME))
            assertEquals(updated, texts(all).contains(message))
        }
    }
    @Test fun freshInstallHasNoSheetOrUpdatedSnackbar() = homeWithoutNotes(false)
    @Test fun updateWithoutNotesKeepsUpdatedSnackbar() = homeWithoutNotes(true)

    private fun sheet() {
        val notes = WhatsNew("0.4.5", (1..8).map { "Improvement $it" }, (1..8).map { "تحسين $it" })
        var visible by mutableStateOf(true)
        var selectedLanguage by mutableStateOf("en")
        renderThemed({ nodes(it) }, {
            if (visible) WhatsNewSheet(notes, selectedLanguage) { visible = false }
        }) { a ->
            assertTrue(texts(nodes(a)).contains("Improvement 6"))
            assertFalse(texts(nodes(a)).contains("Improvement 7"))
            selectedLanguage = "ar"
            assertTrue(texts(nodes(a)).contains("تحسين 6"))
            assertFalse(texts(nodes(a)).contains("Improvement 1"))
            if (app.resources.configuration.screenHeightDp < 480) {
                val scrolling = nodes(a).first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
                assertTrue(scrolling.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f)
                assertEquals(true, scrolling.config[SemanticsActions.ScrollBy].action?.invoke(0f, 2000f))
                nodes(a)
            }
            click(a, app.getString(R.string.update_full_notes))
            assertEquals("https://github.com/ahmed-abdat/nezzel/releases/tag/v0.4.5",
                org.robolectric.Shadows.shadowOf(a).nextStartedActivity?.dataString)
            // The sheet's accessibility dismiss action shares Back/swipe/scrim's callback.
            val dismiss = nodes(a).first { it.config.getOrNull(SemanticsActions.Dismiss) != null }
            assertEquals(true, dismiss.config[SemanticsActions.Dismiss].action?.invoke())
            nodes(a) // The Material sheet completes its hide animation before notifying dismissal
            assertFalse(visible)
        }
    }
    @Test fun sheetCapsNotesSwitchesLanguageAndDismisses() = sheet()
    @Test @Config(qualifiers = "ar-w640dp-h360dp-mdpi") fun sheetScrollsInArabicLandscape() = sheet()
}
