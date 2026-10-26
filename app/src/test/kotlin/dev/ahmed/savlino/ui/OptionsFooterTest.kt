package dev.ahmed.savlino.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Download row takes the placeholder's exact place: skeleton → qualities never moves the action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w384dp-h832dp-mdpi")
class OptionsFooterTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun englishActionKeepsItsPlace() = check()
    @Test @Config(qualifiers = "ar-w384dp-h832dp-mdpi") fun arabicActionKeepsItsPlace() = check()
    @Test @Config(qualifiers = "ar-w384dp-h832dp-mdpi", fontScale = 2f) fun arabicLargeTextActionKeepsItsPlace() = check()

    private fun check() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            val activity = controller.get()
            val gate = CompletableDeferred<Unit>()
            val engine = object : EngineAdapter by FakeEngine() {
                override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
                    gate.await()
                    return InspectResult.Success(Inspection(source, "fixture-v1",
                        listOf(Format("v720", "mp4", true, true, height = 720, size = Size.Exact(42_000_000)),
                            Format("v360", "mp4", true, true, height = 360, size = Size.Exact(12_000_000)),
                            Format("a", "m4a", false, true, size = Size.Exact(3_000_000))),
                        listOf(PresetChoice(Preset.BALANCED, "v720"), PresetChoice(Preset.SMALL, "v360"), PresetChoice(Preset.AUDIO_M4A, "a"))))
                }
            }
            val links = LinkViewModel(SavedStateHandle(), engine = engine)
            val language = activity.resources.configuration.locales[0].language
            activity.setContent {
                CompositionLocalProvider(LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    NezzelTheme(ThemePref.Light, false) {
                        // A bottom sheet hugs its content from the bottom edge.
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { OptionsSheetContent(links, language, {}) }
                    }
                }
            }
            compose.runOnIdle { links.paste("https://example.com/video") }
            compose.waitForIdle()
            assertTrue(links.optionsState is OptionsState.Checking)
            val placeholder = compose.onNodeWithTag("options_action", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val pill = compose.onNodeWithTag("download_placeholder", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            gate.complete(Unit)
            compose.waitUntil(5_000) { links.optionsState is OptionsState.Ready }
            compose.waitForIdle()
            val action: Rect = compose.onNodeWithTag("options_action", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val button = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("options_action")), useUnmergedTree = true)
                .fetchSemanticsNodes().single().boundsInRoot
            assertEquals("slot bottom", placeholder.bottom, action.bottom, 1f)
            assertEquals("slot top", placeholder.top, action.top, 1f)
            assertEquals("button takes the pill's place", pill.top, button.top, 1f)
            assertEquals(pill.bottom, button.bottom, 1f)
        } finally { controller.pause().stop().destroy() }
    }
}
