package dev.ahmed.savlino.viewer

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.sharing.FileShare
import dev.ahmed.savlino.sharing.FileSharer
import dev.ahmed.savlino.sharing.LocalFileSharer
import dev.ahmed.savlino.usage.UsageEvents
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
class MediaViewerTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
    private val activity get() = controller.get()
    private val players = mutableListOf<dev.ahmed.savlino.playback.RecordingPlayer>()
    private val playback = dev.ahmed.savlino.playback.PlaybackEnvironment(activity,
        dev.ahmed.savlino.playback.PlayerFactory { _, _ -> dev.ahmed.savlino.playback.RecordingPlayer().also(players::add) }, sizeOf = { 10L })
    @After fun close() { controller.pause().stop().destroy() }

    private val items = (1..5).map { ViewerItem(Uri.parse("content://media/external/images/$it"), it == 5, if (it == 5) "video/mp4" else "image/jpeg") }
    private val pictures = MediaPictures { _, _, _ -> Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888) }
    private var open by mutableStateOf(true)
    private val shared = mutableListOf<ViewerItem>()
    private val shareMessages = mutableListOf<Int>()

    private fun show(start: Int, sharing: Boolean = false) {
        activity.setContent {
            NezzelTheme(ThemePref.Light, false) {
                val scope = rememberCoroutineScope()
                val sharer = remember { FileSharer(activity, UsageEvents.None, scope, { shareMessages += it }, kotlinx.coroutines.Dispatchers.Unconfined) }
                CompositionLocalProvider(LocalMediaPictures provides pictures, LocalPlaybackEnvironment provides playback,
                    LocalFileSharer provides sharer.takeIf { sharing }) {
                    if (open) MediaViewer(items, start, "en", { open = false }, onShare = { shared += it }, onOpenWith = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun page(kind: Int, n: Int) = compose.onNodeWithContentDescription(activity.getString(kind, "$n", "5"))

    @Test fun opensAtTheTappedItemAndNamesItsPlace() {
        show(2)
        page(R.string.viewer_image_n, 3).assertIsDisplayed()
        compose.onNodeWithContentDescription(activity.getString(R.string.share_file)).performClick()
        assertEquals(items[2], shared.single())
    }

    @Test fun shareOffersInstalledWhatsAppsForThisTypeAndOtherwiseUsesTheExistingChooser() {
        val pm = shadowOf(activity.packageManager)
        for ((pkg, label) in listOf(FileShare.WHATSAPP to "WhatsApp", FileShare.WHATSAPP_BUSINESS to "WhatsApp Business")) {
            pm.installPackage(android.content.pm.PackageInfo().apply {
                packageName = pkg
                applicationInfo = android.content.pm.ApplicationInfo().apply {
                    packageName = pkg; enabled = true; nonLocalizedLabel = label
                }
            })
            val send = android.content.ComponentName(pkg, "$pkg.Send")
            pm.addActivityIfNotPresent(send)
            pm.addIntentFilterForActivity(send, android.content.IntentFilter(android.content.Intent.ACTION_SEND, "image/jpeg")
                .apply { addCategory(android.content.Intent.CATEGORY_DEFAULT) })
        }
        show(2, sharing = true)
        compose.onNodeWithContentDescription(activity.getString(R.string.share_file)).performClick()
        compose.waitForIdle()
        for (label in listOf("WhatsApp", "WhatsApp Business", activity.getString(R.string.share_other_apps)))
            compose.onNodeWithText(label).assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        assertTrue(shared.isEmpty())
        // Like SavedShareSheetTest: exercise the row action; Robolectric's dialog touch injection misses this row.
        compose.onNodeWithText("WhatsApp Business").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitForIdle()
        // FileSharer checks this unavailable fixture before launching, rather than bypassing its read/grant rule.
        assertEquals(listOf(R.string.share_file_unavailable), shareMessages)
        assertTrue(shared.isEmpty())
        // Neither installed app accepts video: a different page goes straight to the original chooser.
        page(R.string.viewer_image_n, 3).performTouchInput { swipeLeft() }
        page(R.string.viewer_image_n, 4).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(activity.getString(R.string.share_file)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(items[4]), shared)
        compose.onNodeWithText(activity.getString(R.string.share_other_apps)).assertDoesNotExist()
    }

    @Test fun automaticThumbnailWaitsForTheTabToSettle() {
        val active = mutableStateOf(false)
        val loaded = mutableListOf<Uri>()
        val source = MediaPictures { uri, _, _ -> loaded += uri; Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888) }
        activity.setContent {
            NezzelTheme(ThemePref.Light, false) {
                CompositionLocalProvider(dev.ahmed.savlino.ui.nav.LocalTabActive provides active, LocalMediaPictures provides source) {
                    rememberMediaThumbnail(items.first().uri, dev.ahmed.savlino.ui.ThumbnailKind.IMAGE, 360)
                }
            }
        }
        compose.waitForIdle()
        assertTrue(loaded.isEmpty())
        compose.runOnIdle { active.value = true }
        compose.waitForIdle()
        assertEquals(listOf(items.first().uri), loaded)
    }

    @Test fun explicitViewerLoadsTheFullPictureWhileItsParentTabIsTransitioning() {
        val loaded = mutableListOf<Uri>()
        activity.setContent {
            NezzelTheme(ThemePref.Light, false) {
                CompositionLocalProvider(
                    dev.ahmed.savlino.ui.nav.LocalTabActive provides remember { mutableStateOf(false) },
                    LocalMediaPictures provides MediaPictures { uri, _, _ ->
                        loaded += uri
                        Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888)
                    },
                ) { MediaViewer(items.take(1), 0, "en", {}, onShare = {}, onOpenWith = {}) }
            }
        }
        compose.waitForIdle()
        assertTrue("Explicit viewer must load even while automatic tab IO is paused", items.first().uri in loaded)
    }

    @Test fun swipeTurnsThePageUnlessTheImageIsZoomed() {
        show(2)
        page(R.string.viewer_image_n, 3).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        page(R.string.viewer_image_n, 4).assertIsDisplayed()
        page(R.string.viewer_image_n, 4).performTouchInput { doubleClick() }
        compose.waitForIdle()
        page(R.string.viewer_image_n, 4).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        page(R.string.viewer_image_n, 4).assertIsDisplayed()
        page(R.string.viewer_video_n, 5).assertDoesNotExist()
    }

    @Test fun videoPageHasPlayerControls() {
        show(4)
        page(R.string.viewer_video_n, 5).assertIsDisplayed()
        compose.onNodeWithContentDescription(activity.getString(R.string.viewer_volume)).assertHasClickAction().performClick()
        compose.onNodeWithContentDescription(activity.getString(R.string.viewer_mute)).assertHasClickAction().performClick()
        compose.onNodeWithContentDescription(activity.getString(R.string.viewer_unmute)).assertExists()
        compose.onNodeWithContentDescription(activity.getString(R.string.viewer_pause)).performClick()
        compose.onNodeWithContentDescription(activity.getString(R.string.viewer_play)).assertExists()
    }

    @Test fun typedPlaybackFailureOffersRetryOpenWithAndClose() {
        show(4)
        compose.runOnIdle { players.single().failure(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) }
        compose.waitForIdle()
        compose.onNodeWithText(activity.getString(R.string.viewer_playback_unsupported)).assertIsDisplayed()
        compose.onNodeWithText(activity.getString(R.string.viewer_retry)).assertHasClickAction()
        compose.onNodeWithText(activity.getString(R.string.saved_open_with)).assertHasClickAction()
        compose.onNodeWithText(activity.getString(R.string.viewer_close)).assertHasClickAction()
    }

    @Test fun closeDismisses() {
        show(0)
        compose.onNodeWithContentDescription(activity.getString(R.string.viewer_close)).performClick()
        compose.waitForIdle()
        assertFalse(open)
    }
}
