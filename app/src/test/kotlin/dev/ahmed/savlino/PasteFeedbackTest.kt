package dev.ahmed.savlino

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.OptionsState
import dev.ahmed.savlino.ui.theme.NezzelTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Paste with nothing on the clipboard says so, and changes nothing else: no draft loss, no network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w400dp-h800dp-mdpi")
class PasteFeedbackTest {
    @get:Rule val compose = createEmptyComposeRule()
    private class Engine : EngineAdapter by UnavailableEngine() {
        override val isAvailable = true
        val inspected = mutableListOf<LinkRevision>()
        override suspend fun installedVersion() = "fixture-v1"
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            inspected += source
            return InspectResult.Failure(DownloadError.Failed)
        }
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun emptyOrBlankClipboardSignalsOnceAndKeepsTheDraft() {
        val engine = Engine()
        val vm = LinkViewModel(SavedStateHandle(), engine = engine)
        vm.edit("my draft")
        for (clip in listOf(null, "", "   \n")) {
            vm.paste(clip); idle()
            runBlocking { withTimeout(1_000) { vm.emptyPasteEvents.first() } }
            assertEquals("my draft", vm.text)
            assertEquals(OptionsState.Hidden, vm.optionsState)
        }
        assertTrue(engine.inspected.isEmpty())
    }

    @Test fun emptyPasteShowsTheLocalizedNotice() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val links = LinkViewModel(SavedStateHandle())
        try {
            controller.get().setContent {
                NezzelTheme(ThemePref.Light, false) {
                    NezzelApp(links, "en", ThemePref.Light, false, true, onPaste = { links.paste(null) }, onDismissSuggestion = {},
                        onAcceptSuggestion = {}, onLanguage = {}, onTheme = {}, onWallpaper = {}, onSuggestions = {})
                }
            }
            compose.waitForIdle()
            compose.runOnIdle { links.paste(null) }
            compose.waitForIdle()
            val notice = RuntimeEnvironment.getApplication().getString(R.string.paste_empty)
            assertTrue(compose.onAllNodesWithText(notice).fetchSemanticsNodes().isNotEmpty())
        } finally { controller.pause().stop().destroy() }
    }
}
