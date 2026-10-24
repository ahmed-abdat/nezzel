package dev.ahmed.savlino.statuses.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.statuses.FakeStatusLibrary
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.APP
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.BUSINESS
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.MAIN
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.SECOND
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.item
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.mixed
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.ready
import dev.ahmed.savlino.statuses.StatusAccess
import dev.ahmed.savlino.statuses.StatusLibrary
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w384dp-h832dp")
class StatusesScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
    private val activity get() = controller.get()
    private fun s(id: Int) = activity.getString(id)
    @After fun close() { controller.pause().stop().destroy() }

    private val media = FakeStatusMedia()
    private fun show(library: StatusLibrary?) {
        activity.setContent {
            NezzelTheme(ThemePref.Light, false) {
                CompositionLocalProvider(LocalStatusMedia provides media) { StatusesScreen(library, "en") }
            }
        }
        compose.waitForIdle()
    }

    private fun tiles(kind: Int) = compose.onAllNodes(hasContentDescription(s(kind), substring = true) and hasClickAction())

    @Test fun grantScreenShowsFoundAppsOneButtonAndOpensThePicker() {
        show(FakeStatusLibrary(StatusAccess.NeedsGrant(listOf(APP, BUSINESS))))
        compose.onNodeWithText(s(R.string.statuses_intro_title)).assertIsDisplayed()
        compose.onNodeWithText(APP.label).assertExists()
        compose.onNodeWithText(BUSINESS.label).assertExists()
        compose.onNodeWithContentDescription(s(R.string.statuses_grant_hint)).assertExists()
        compose.onNodeWithText(s(R.string.statuses_privacy)).assertExists()
        compose.onAllNodes(hasText(s(R.string.statuses_grant)) and hasClickAction()).assertCountEquals(1)
        compose.onNodeWithText(s(R.string.statuses_grant)).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, shadowOf(activity).nextStartedActivityForResult.intent.action)
    }

    @Test fun accessLostSaysSoAndOffersTheGrantAgain() {
        show(FakeStatusLibrary(StatusAccess.AccessLost(listOf(APP))))
        compose.onNodeWithText(s(R.string.statuses_access_lost)).assertExists()
        compose.onNodeWithText(s(R.string.statuses_grant)).assertExists()
    }

    @Test fun notInstalledHasNoGrantButton() {
        show(FakeStatusLibrary(StatusAccess.NotInstalled))
        compose.onNodeWithText(s(R.string.statuses_not_installed)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.statuses_grant)).assertDoesNotExist()
    }

    @Test fun unwiredLibraryShowsOnlyTheExplainer() {
        show(null)
        compose.onNodeWithText(s(R.string.statuses_intro_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.statuses_grant)).assertDoesNotExist()
    }

    @Test fun emptyGrantSaysHowToGetStatuses() {
        show(FakeStatusLibrary(ready(emptyList(), emptyList())))
        compose.onNodeWithText(s(R.string.statuses_empty)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.statuses_open_whatsapp)).assertHeightIsAtLeast(48.dp)
    }

    @Test fun gridMarksNewSavedAndDurationForTalkBack() {
        show(FakeStatusLibrary(ready()))
        compose.onNodeWithText(activity.resources.getQuantityString(R.plurals.statuses_new_n, 3, "3")).assertExists()
        tiles(R.string.statuses_video).assertCountEquals(5)
        tiles(R.string.statuses_photo).assertCountEquals(4)
        compose.onAllNodes(hasContentDescription(s(R.string.statuses_saved), substring = true)).assertCountEquals(3)
        compose.onAllNodes(hasContentDescription("0:15", substring = true)).onFirst().assertExists()
        listOf(R.string.statuses_filter_all, R.string.statuses_filter_photos, R.string.statuses_filter_videos, R.string.statuses_hide_saved)
            .forEach { val touch = compose.onNode(hasText(s(it)) and hasClickAction()).fetchSemanticsNode().touchBoundsInRoot
                assertTrue(touch.height >= 48 * activity.resources.displayMetrics.density - 1) }
        compose.onNodeWithText(s(R.string.statuses_filter_photos)).performClick()
        tiles(R.string.statuses_video).assertCountEquals(0)
    }

    @Test fun onlyPhotosHideTheFiltersAndOneAccountHidesAccountChips() {
        show(FakeStatusLibrary(ready(listOf(item(1, false), item(2, false)))))
        compose.onNodeWithText(s(R.string.statuses_filter_all)).assertDoesNotExist()
        compose.onNodeWithText(activity.getString(R.string.statuses_account_n, "1")).assertDoesNotExist()
    }

    @Test fun twoAccountsShowCardsThatOpenTheirGridAndBackReturns() {
        show(FakeStatusLibrary(ready(listOf(item(1, false, source = MAIN), item(2, true, source = SECOND), item(3, true, source = SECOND)))))
        tiles(R.string.statuses_photo).assertCountEquals(0)
        val second = compose.onNode(hasContentDescription(activity.getString(R.string.statuses_account_n, "2"), substring = true) and hasClickAction())
        assertTrue(second.fetchSemanticsNode().touchBoundsInRoot.height >= 48 * activity.resources.displayMetrics.density - 1)
        media.thumbnails.clear()
        second.performClick()
        compose.waitForIdle()
        assertTrue("the opened account's tiles load their thumbnails", media.thumbnails.any { it.source == SECOND })
        // A loaded value lands in the lazy tiles too: the duration joins the tile's description.
        compose.onAllNodes(hasContentDescription("0:30", substring = true)).onFirst().assertExists()
        tiles(R.string.statuses_photo).assertCountEquals(0)
        tiles(R.string.statuses_video).assertCountEquals(2)
        compose.onNodeWithContentDescription(s(R.string.back)).performClick()
        tiles(R.string.statuses_video).assertCountEquals(0)
        compose.onNode(hasContentDescription(activity.getString(R.string.statuses_account_n, "1"), substring = true) and hasClickAction()).performClick()
        tiles(R.string.statuses_photo).assertCountEquals(1)
    }

    @Test fun twoAppsNameTheCardsByTheAppLabel() {
        show(FakeStatusLibrary(ready(listOf(item(1, false, source = MAIN), item(2, true, source = FakeStatusLibrary.THIRD)), apps = listOf(APP, BUSINESS))))
        compose.onNode(hasContentDescription(BUSINESS.label, substring = true) and hasClickAction()).assertExists()
        compose.onNode(hasContentDescription(activity.getString(R.string.statuses_account_n, "1"), substring = true)).assertDoesNotExist()
    }

    @Test fun longPressSelectsAndSaveNSavesTheSelection() {
        val library = FakeStatusLibrary(ready())
        show(library)
        tiles(R.string.statuses_video).onFirst().performTouchInput { longClick() }
        compose.onNodeWithText(activity.resources.getQuantityString(R.plurals.saved_selected, 1, "1")).assertExists()
        tiles(R.string.statuses_photo).onFirst().performClick()
        compose.onNodeWithText(activity.resources.getQuantityString(R.plurals.statuses_save_n, 2, "2"))
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        assertEquals(2, library.saved.single().size)
        compose.onNodeWithText(s(R.string.statuses_saved)).assertExists()
        compose.onNodeWithText(s(R.string.open_file)).assertExists()
    }

    @Test fun tapPreviewsAndSaveThereSavesOne() {
        val library = FakeStatusLibrary(ready())
        show(library)
        tiles(R.string.statuses_photo).onFirst().performClick()
        compose.onNodeWithContentDescription(s(R.string.statuses_preview_close)).assertExists()
        compose.onAllNodes(hasText(s(R.string.statuses_save)) and hasClickAction()).onLast().performClick()
        compose.waitForIdle()
        assertEquals(1, library.saved.single().size)
        assertFalse(library.saved.single().single().isVideo)
    }
}
