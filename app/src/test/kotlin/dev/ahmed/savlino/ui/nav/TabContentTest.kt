package dev.ahmed.savlino.ui.nav

import androidx.activity.ComponentActivity
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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
import org.robolectric.Shadows.shadowOf
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

    // Lead condition: a hidden tab stays placed (its layers survive a switch) but off the window, so no touch reaches it,
    // keyboard focus can't enter it and TalkBack never sees it; showing it again puts it back in place.
    @Test fun hiddenTabStaysPlacedOffWindowWithoutTouchFocusOrSemantics() {
        var route by mutableStateOf("Home")
        var clicks = 0
        var home: androidx.compose.ui.layout.LayoutCoordinates? = null
        render {
            TabContent(route) { destination ->
                if (destination == "Home") Box(Modifier.fillMaxSize().testTag("home")
                    .onGloballyPositioned { home = it }.clickable { clicks++ })
                else Text(destination)
            }
        }.use {
            compose.runOnIdle { route = "Settings" }
            compose.waitForIdle()
            compose.runOnIdle {
                val coordinates = home!!
                assertTrue("hidden tab stays placed", coordinates.isAttached)
                assertTrue("hidden tab sits outside the window", coordinates.positionInWindow().x + coordinates.size.width <= 0f)
            }
            compose.onAllNodesWithTag("home").assertCountEquals(0)
            compose.onNodeWithText("Settings").performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(0, clicks) }
            compose.runOnIdle { route = "Home" }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(0f, home!!.positionInWindow().x) }
            compose.onNodeWithTag("home").performClick()
            compose.runOnIdle { assertEquals(1, clicks) }
        }
    }

    // Lead conditions for pre-warm: only after fully drawn, one tab per idle window, hidden and inactive (no loads, no
    // semantics), and its loads start only once it is shown.
    @Test fun prewarmComposesOneHiddenInactiveTabPerIdleWindowAfterFullyDrawn() {
        var route by mutableStateOf("Home")
        val composed = mutableListOf<String>()
        val loads = mutableListOf<String>()
        render {
            TabContent(route, prewarm = listOf("Home", "Downloads", "Settings")) { destination ->
                DisposableEffect(Unit) { composed += destination; onDispose {} }
                val result by rememberTabResource<String?>(null, destination) { loads += destination; destination }
                Text(destination + result.orEmpty(), Modifier.testTag(destination))
            }
        }.use {
            compose.mainClock.autoAdvance = false
            repeat(4) { idleWindow() }
            compose.runOnIdle { assertEquals(listOf("Home"), composed) }
            fullyDrawn()
            idleWindow()
            compose.runOnIdle { assertEquals(listOf("Home", "Downloads"), composed) }
            idleWindow()
            compose.runOnIdle { assertEquals(listOf("Home", "Downloads", "Settings"), composed) }
            repeat(4) { idleWindow() }
            compose.runOnIdle { assertEquals(listOf("Home"), loads) }
            compose.onAllNodesWithTag("Downloads").assertCountEquals(0)
            compose.onAllNodesWithTag("Settings").assertCountEquals(0)
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { route = "Downloads" }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(listOf("Home", "Downloads"), loads) }
            assertEquals(3, composed.size)
        }
    }

    @Test fun touchesPutPrewarmOff() {
        val composed = mutableListOf<String>()
        render {
            TabContent("Home", prewarm = listOf("Downloads")) { destination ->
                DisposableEffect(Unit) { composed += destination; onDispose {} }
                Box(Modifier.fillMaxSize().testTag(destination))
            }
        }.use {
            fullyDrawn()
            compose.mainClock.autoAdvance = false
            repeat(6) {
                compose.mainClock.advanceTimeBy(250)
                shadowOf(Looper.getMainLooper()).idle()
                compose.onNodeWithTag("Home").performTouchInput { click(center) }
            }
            compose.runOnIdle { assertEquals(listOf("Home"), composed) }
            // The quiet wait restarts when the one a touch interrupted ends, so a step comes 400-800 ms after the last touch.
            idleWindow()
            idleWindow()
            compose.runOnIdle { assertEquals(listOf("Home", "Downloads"), composed) }
        }
    }

    /** Robolectric never draws, so ReportDrawn never reports; the activity's own call reaches the same listeners. */
    private fun fullyDrawn() = compose.runOnUiThread { activity.reportFullyDrawn() }

    /** Quiet for longer than a pre-warm step waits, then the main thread idles and two frames pass. */
    private fun idleWindow() {
        compose.mainClock.advanceTimeBy(450)
        shadowOf(Looper.getMainLooper()).idle()
        repeat(3) { compose.mainClock.advanceTimeByFrame(); shadowOf(Looper.getMainLooper()).idle() }
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
            NavigationScaffold(route, { emptyList() }, "en", { route = it }, navigation = { selected, badges, onSelect, onReselect ->
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

    // A hidden tab's own requestFocus (a restored search field, say) neither takes focus nor raises the keyboard.
    @Test fun hiddenTabRefusesARequestedFocusAndTheKeyboard() {
        var route by mutableStateOf("Downloads")
        var ask by mutableIntStateOf(0)
        var focused = false
        render {
            TabContent(route) { destination ->
                if (destination == "Downloads") {
                    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
                    LaunchedEffect(ask) { if (ask > 0) requester.requestFocus() }
                    var text by remember { mutableStateOf("") }
                    androidx.compose.material3.TextField(text, { text = it }, Modifier.focusRequester(requester)
                        .onFocusChanged { focused = it.isFocused })
                } else Text(destination)
            }
        }.use {
            compose.runOnIdle { route = "Home" }
            compose.waitForIdle()
            compose.runOnIdle { ask++ }
            compose.waitForIdle()
            val ime = shadowOf(activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java))
            compose.runOnIdle {
                assertFalse("hidden field took focus", focused)
                assertFalse("keyboard shown for a hidden tab", ime.isSoftInputVisible)
            }
            // Shown again, the same request works.
            compose.runOnIdle { route = "Downloads" }
            compose.waitForIdle()
            compose.runOnIdle { ask++ }
            compose.waitForIdle()
            compose.runOnIdle { assertTrue(focused) }
        }
    }

    // Lead finding: a full page in front (update page, Other versions) must not drop the tabs or restart pre-warming.
    @Test fun aCoveringPageKeepsTabsComposedInactiveWithoutBackOrPrewarm() {
        var covered by mutableStateOf(false)
        val composed = mutableListOf<String>()
        val disposed = mutableListOf<String>()
        val active = mutableMapOf<String, State<Boolean>>()
        var backs = 0
        render {
            TabContent("Home", prewarm = listOf("Home", "Downloads"), covered = covered) { destination ->
                DisposableEffect(Unit) { composed += destination; onDispose { disposed += destination } }
                active[destination] = LocalTabActive.current
                androidx.activity.compose.BackHandler { backs++ }
                Text(destination)
            }
        }.use {
            compose.runOnIdle { covered = true }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            fullyDrawn()
            repeat(4) { idleWindow() }
            compose.runOnIdle {
                assertEquals(listOf("Home"), composed)
                assertFalse(active.getValue("Home").value)
                assertFalse(activity.onBackPressedDispatcher.hasEnabledCallbacks())
            }
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { covered = false }
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue(active.getValue("Home").value)
                assertEquals(emptyList<String>(), disposed)
                activity.onBackPressedDispatcher.onBackPressed()
            }
            assertEquals(1, backs)
            compose.mainClock.autoAdvance = false
            repeat(2) { idleWindow() }
            compose.runOnIdle { assertEquals(listOf("Home", "Downloads"), composed) }
        }
    }

    // A download tick reaches a hidden tab only when it is chosen again.
    @Test fun aHiddenTabSeesItsSourceOnlyOnceShownAgain() {
        var route by mutableStateOf("Downloads")
        val source = mutableStateOf(0)
        val seen = mutableListOf<Int>()
        render {
            TabContent(route) { destination ->
                if (destination == "Downloads") {
                    val value by rememberWhileShown(source)
                    SideEffect { seen += value }
                    Text("$value")
                } else Text(destination)
            }
        }.use {
            compose.runOnIdle { route = "Home" }
            compose.waitForIdle()
            val before = seen.size
            compose.runOnIdle { source.value = 1 }
            compose.waitForIdle()
            compose.runOnIdle { source.value = 2 }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals("hidden tab recomposed on a tick", before, seen.size) }
            compose.runOnIdle { route = "Downloads" }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(2, seen.last()) }
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
