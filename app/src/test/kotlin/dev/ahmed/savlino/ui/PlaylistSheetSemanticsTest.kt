package dev.ahmed.savlino.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w384dp-h1600dp-mdpi")
class PlaylistSheetSemanticsTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private fun rows() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun listPlaylist(source: LinkRevision, operationId: String, page: PlaylistPageToken?) = PlaylistResult.Success(
                PlaylistPage("List", listOf(PlaylistItem("a", "https://youtube.com/watch?v=abcdefghijk", "Morning", 60),
                    PlaylistItem("b", "https://youtube.com/watch?v=bcdefghijkl", "Live stream", null, unavailable = PlaylistUnavailable.Live)), 2, null))
        }
        val vm = PlaylistViewModel(engine, scope, { false }, { _, _, _ -> true })
        try {
            vm.open(LinkRevision(LinkInput.parse("https://youtube.com/playlist?list=PLabcdefghij")!!, 1))
            controller.get().setContent { NezzelTheme(ThemePref.Light, false) { PlaylistSheetContent(vm, "en") } }
            compose.waitForIdle()
            val row = compose.onNodeWithTag("playlist_item_a").assertIsOn().assertHasClickAction()
            val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
            val bounds = row.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height / density >= 48f)
            row.performClick().assertIsOff()
            compose.onNodeWithTag("playlist_item_b").assertIsNotEnabled().assertIsOff()
            compose.onNodeWithTag("playlist_download").assertIsNotEnabled()
        } finally { vm.close(); scope.cancel(); controller.pause().stop().destroy() }
    }
    @Test fun checkboxLabelsTargetsAndDisabledRows() = rows()
    @Test fun arabicPlaylistAndItemTitlesUseFirstStrongDirection() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun listPlaylist(source: LinkRevision, operationId: String, page: PlaylistPageToken?) = PlaylistResult.Success(
                PlaylistPage("قائمة التشغيل", listOf(PlaylistItem("a", "https://youtube.com/watch?v=abcdefghijk", "صباح الخير", 60)), 1, null))
        }
        val vm = PlaylistViewModel(engine, scope, { false }, { _, _, _ -> true })
        try {
            vm.open(LinkRevision(LinkInput.parse("https://youtube.com/playlist?list=PLabcdefghij")!!, 1))
            controller.get().setContent { NezzelTheme(ThemePref.Light, false) { PlaylistSheetContent(vm, "en") } }
            compose.waitForIdle()
            // First-strong isolation lets an Arabic title read right to left; an LTR isolate would not.
            listOf("قائمة التشغيل", "صباح الخير").forEach { compose.onNodeWithText(NezzelFormat.firstStrong(it), useUnmergedTree = true).assertExists() }
        } finally { vm.close(); scope.cancel(); controller.pause().stop().destroy() }
    }
    @Test fun longPlaylistKeepsQualityVisibleAndSubmitRetryConfirmsAgain() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var submissions = 0
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun listPlaylist(source: LinkRevision, operationId: String, page: PlaylistPageToken?) = PlaylistResult.Success(
                PlaylistPage("List", (1..200).map { PlaylistItem("v$it", "https://youtube.com/watch?v=abcdefghijk", "Video $it", 60) }, 200, null))
        }
        val vm = PlaylistViewModel(engine, scope, { false }, { _, _, _ -> ++submissions > 1 })
        try {
            vm.open(LinkRevision(LinkInput.parse("https://youtube.com/playlist?list=PLabcdefghij")!!, 1))
            controller.get().setContent { NezzelTheme(ThemePref.Light, false) { PlaylistSheetContent(vm, "en") } }
            compose.waitForIdle()
            val context = RuntimeEnvironment.getApplication()
            compose.onNodeWithText(context.getString(R.string.preset_best)).assertIsDisplayed()
            compose.onNodeWithTag("playlist_download").performClick()
            compose.onNodeWithText(context.getString(R.string.playlist_submit_failed)).assertIsDisplayed()
            compose.onNodeWithTag("playlist_submit_retry").performClick()
            compose.waitForIdle()
            assertEquals(2, submissions); assertFalse(vm.visible)
        } finally { vm.close(); scope.cancel(); controller.pause().stop().destroy() }
    }
    @Test @Config(qualifiers = "ar-w384dp-h1600dp-mdpi") fun arabicRows() = rows()
    @Test fun largeTextRows() { RuntimeEnvironment.setFontScale(2f); rows() }
    @Test @Config(qualifiers = "ar-w384dp-h1600dp-mdpi") fun largeArabicRows() { RuntimeEnvironment.setFontScale(2f); rows() }
}
