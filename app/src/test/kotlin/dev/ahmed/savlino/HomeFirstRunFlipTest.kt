package dev.ahmed.savlino

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.downloads.LibraryLoadState
import dev.ahmed.savlino.downloads.LibraryState
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.nav.TabContent
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * rc3 crash: «ArrayList cannot be cast to MutableState» at HomeRecents' `viewing` when the first download replaced the hero.
 * Sibling rememberSaveable calls in one function share a key, and the registry hands restored values out in call order.
 * HomeRecents returned early (no slots) before its row's rememberLazyListState, so a restored Home could consume only
 * `viewing`, leaving the row's saved [index, offset] list first in line for the next HomeRecents.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class HomeFirstRunFlipTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun job(id: String, waiting: Boolean = false) = DownloadProgress(id, "$id.mp4", DownloadPhase.PREPARING, waiting = waiting)

    @Test fun theFirstDownloadReplacesTheHeroAfterHomeWasSavedAndRestored() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val vm = LinkViewModel(SavedStateHandle(), engine = dev.ahmed.savlino.engine.FakeEngine()).apply { automaticOptions = false }
        var jobs by mutableStateOf(listOf(job("earlier")))
        var load by mutableStateOf<LibraryLoadState>(LibraryLoadState.Ready(emptyList()))
        // NezzelApp drops the whole tab host while a full-screen route shows (Other versions, AppUpdate): Home is saved, then restored.
        var tabs by mutableStateOf(true)
        try {
            controller.get().setContent {
                val holder = rememberSaveableStateHolder()
                val library = remember(load) { LibraryState(load, {}, {}, {}, null, {}, {}, {}, { _, _ -> }) }
                NezzelTheme(ThemePref.Light, false) {
                    if (tabs) TabContent("Home", stateHolder = holder) { HomeScreen(vm, jobs, library, "en", {}, {}, {}, {}) }
                }
            }
            compose.waitForIdle()
            // A job card in the recents row: both of HomeRecents' saveables are registered and saved with Home.
            compose.onNodeWithText("earlier", substring = true).assertExists()
            compose.runOnIdle { tabs = false }
            compose.waitForIdle()
            // Back with nothing listed yet (a resume lists again): HomeRecents shows with no slots.
            compose.runOnIdle { jobs = emptyList(); load = LibraryLoadState.Loading(); tabs = true }
            compose.waitForIdle()
            // Nothing saved: the hero.
            compose.runOnIdle { load = LibraryLoadState.Ready(emptyList()) }
            compose.waitForIdle()
            // «Download 8 videos»: the hero gives way to the recents row.
            compose.runOnIdle { jobs = (1..8).map { job("v$it", waiting = it > 2) } }
            compose.waitForIdle()
            compose.onNodeWithText("v1", substring = true).assertExists()
        } finally { controller.pause().stop().destroy() }
    }
}
