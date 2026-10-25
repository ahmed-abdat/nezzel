package dev.ahmed.savlino.ui.nav

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.NavigationScaffold
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w400dp-h800dp-mdpi")
class TabContentTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun eachTabKeepsItsScrollAndSaveableStateAndReselectsToTop() {
        var route by mutableStateOf("Home")
        lateinit var tabs: TabReselections
        val states = mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>()
        render {
            tabs = remember { TabReselections() }
            TabContent(route, tabs) { destination ->
                val list = rememberLazyListState()
                var count by rememberSaveable { mutableIntStateOf(0) }
                ScrollOnTabReselect { list.scrollToItem(0) }
                SideEffect { states[destination] = list }
                LazyColumn(Modifier.fillMaxSize().testTag(destination), state = list) {
                    item { androidx.compose.material3.TextButton({ count++ }) { Text("$destination:$count") } }
                    items(80, key = { it }, contentType = { "row" }) { Text("Row $it", Modifier.height(64.dp)) }
                }
            }
        }.use {
            for (destination in listOf("Home", "Downloads", "Statuses", "Settings")) {
                compose.runOnIdle { route = destination }
                compose.onNodeWithText("$destination:0").performClick()
                compose.onNodeWithTag(destination).performScrollToIndex(20)
                compose.runOnIdle { assertTrue(states.getValue(destination).firstVisibleItemIndex >= 20) }
            }
            for (destination in listOf("Home", "Downloads", "Statuses", "Settings")) {
                compose.runOnIdle { route = destination }
                compose.waitForIdle()
                compose.runOnIdle { assertTrue(states.getValue(destination).firstVisibleItemIndex >= 20) }
                compose.runOnIdle { tabs.reselect(destination) }
                compose.waitForIdle()
                compose.onNodeWithText("$destination:1").assertIsDisplayed()
                compose.runOnIdle { assertEquals(0, states.getValue(destination).firstVisibleItemIndex) }
            }
        }
    }

    @Test fun reducedMotionHidesOldTabWithoutClockAdvance() {
        var route by mutableStateOf("Home")
        val present = mutableSetOf<String>()
        render(reduced = true) {
            TabContent(route) { destination ->
                DisposableEffect(destination) { present += destination; onDispose { present -= destination } }
                Text(destination)
            }
        }.use {
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { route = "Settings"; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            // The old tab stays composed for a cheap return, but is gone from the screen and from TalkBack.
            compose.runOnIdle { assertEquals(setOf("Home", "Settings"), present) }
            compose.onNodeWithText("Settings").assertIsDisplayed()
            compose.onAllNodesWithText("Home").assertCountEquals(0)
        }
    }

    @Test fun returningToATabReusesItsCompositionInsteadOfComposingTheScreenAgain() {
        var route by mutableStateOf("Home")
        val entered = mutableMapOf<String, Int>()
        render {
            TabContent(route) { destination ->
                DisposableEffect(destination) { entered[destination] = (entered[destination] ?: 0) + 1; onDispose { } }
                Text(destination)
            }
        }.use {
            for (destination in listOf("Downloads", "Home", "Downloads", "Settings", "Downloads")) {
                compose.runOnIdle { route = destination }
                compose.waitForIdle()
                compose.onNodeWithText(destination).assertIsDisplayed()
            }
            compose.runOnIdle { assertEquals(mapOf("Home" to 1, "Downloads" to 1, "Settings" to 1), entered) }
        }
    }

    @Test fun hiddenTabsDoNotHandleBack() {
        var route by mutableStateOf("Downloads")
        var downloadsBacks = 0
        render {
            TabContent(route) { destination ->
                if (destination == "Downloads") androidx.activity.compose.BackHandler { downloadsBacks++ }
                Text(destination)
            }
        }.use {
            compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals(1, downloadsBacks)
            compose.runOnIdle { route = "Home" }
            compose.waitForIdle()
            compose.runOnIdle {
                assertFalse(activity.onBackPressedDispatcher.hasEnabledCallbacks())
                activity.onBackPressedDispatcher.onBackPressed()
            }
            assertEquals(1, downloadsBacks)
            compose.runOnIdle { route = "Downloads" }
            compose.waitForIdle()
            compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals(2, downloadsBacks)
        }
    }

    @Test fun automaticLoadsWaitForSettledTabAndCancelForOutgoingTab() {
        var route by mutableStateOf("Home")
        val loads = mutableListOf<String>()
        render {
            TabContent(route) { destination ->
                val result by rememberTabResource<String?>(null, destination) { loads += destination; destination }
                Text(result.orEmpty())
            }
        }.use {
            compose.waitForIdle()
            assertEquals(listOf("Home"), loads)
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { route = "Statuses" }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertEquals(listOf("Home"), loads) }
            compose.mainClock.advanceTimeBy(1500)
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(listOf("Home", "Statuses"), loads) }
        }
    }

    @Test fun loadedResourceSurvivesInactiveActiveWithoutAnotherReadButChangedKeysReload() {
        val active = mutableStateOf(true)
        var id by mutableStateOf("first")
        val loads = mutableListOf<String>()
        render {
            CompositionLocalProvider(LocalTabActive provides active) {
                val result by rememberTabResource<String?>(null, id) { loads += id; id }
                Text(result.orEmpty())
            }
        }.use {
            assertEquals(listOf("first"), loads)
            compose.runOnIdle { active.value = false }
            compose.waitForIdle()
            compose.onNodeWithText("first").assertExists()
            compose.runOnIdle { active.value = true }
            compose.waitForIdle()
            assertEquals(listOf("first"), loads)
            compose.runOnIdle { id = "second" }
            compose.waitForIdle()
            compose.onNodeWithText("second").assertExists()
            assertEquals(listOf("first", "second"), loads)
        }
    }

    @Test fun oldDownloadOfferRestoresBeforeTheDeferredProviderRefresh() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val old = dev.ahmed.savlino.storage.OldDownloads(context)
        old.prefs.edit().clear().commit()
        dev.ahmed.savlino.storage.OldDownloads.markFirstStart(context) { false }
        var route by mutableStateOf("Downloads")
        var card = false
        try {
            render {
                TabContent(route) { destination ->
                    if (destination == "Downloads") {
                        val offer = dev.ahmed.savlino.downloads.rememberOldDownloadsOffer(remember { androidx.compose.material3.SnackbarHostState() })
                        SideEffect { card = offer.card }
                        Text("Offer:${offer.card}")
                    } else Text(destination)
                }
            }.use {
                compose.waitUntil(5000) { card }
                compose.runOnIdle { route = "Home" }
                compose.waitForIdle()
                card = false
                compose.mainClock.autoAdvance = false
                compose.runOnIdle { route = "Downloads"; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                assertTrue("Saved offer must exist before the entering tab settles", card)
            }
        } finally { old.prefs.edit().clear().commit() }
    }

    @Test fun tabSwitchComposesNavigationOnceAndAnimationFramesNeverComposeIt() {
        var route by mutableStateOf("Home")
        var barCompositions = 0
        render {
            NavigationScaffold(route, emptyList(), "en", { route = it }, navigation = { selected, badges, onSelect, onReselect ->
                SideEffect { barCompositions++ }
                NezzelNavigation(TopLevelDestinations, selected, badges, onSelect, onReselect)
            }) { TabContent(route) { Text(it) } }
        }.use {
            compose.waitForIdle()
            for (destination in listOf("Downloads", "Statuses", "Settings", "Home")) {
                val before = barCompositions
                compose.runOnIdle { route = destination }
                compose.waitForIdle()
                assertEquals("One bar composition per switch", before + 1, barCompositions)
            }
        }
    }

    @Test fun activeTabUsesReselectAndBadgesKeepTheirAccessibleState() {
        var selected: String? = null
        var reselected: String? = null
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        render {
            NezzelNavigation(TopLevelDestinations, "Downloads", NavigationBadges(running = 2, update = true),
                { selected = it }, { reselected = it })
        }.use {
            compose.onNodeWithContentDescription(context.getString(dev.ahmed.savlino.R.string.downloads_running_accessibility, "2"))
                .assertIsSelected().performClick()
            assertEquals("Downloads", reselected)
            assertNull(selected)
            val settings = compose.onNodeWithContentDescription(context.getString(dev.ahmed.savlino.R.string.settings_update_accessibility))
            settings.assertIsNotSelected()
            settings.performClick()
            assertEquals("Settings", selected)
        }
    }

    private lateinit var activity: ComponentActivity

    private fun render(reduced: Boolean = false, content: @Composable () -> Unit): AutoCloseable {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        activity = controller.get()
        controller.get().setContent {
            NezzelTheme(ThemePref.Light, false) {
                CompositionLocalProvider(LocalReducedMotion provides reduced, content = content)
            }
        }
        compose.waitForIdle()
        return AutoCloseable { controller.pause().stop().destroy() }
    }
}
