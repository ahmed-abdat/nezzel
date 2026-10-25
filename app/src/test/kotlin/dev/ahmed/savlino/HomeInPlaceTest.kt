package dev.ahmed.savlino

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.downloads.HomeRecents
import dev.ahmed.savlino.downloads.LibraryLoadState
import dev.ahmed.savlino.downloads.LibraryState
import dev.ahmed.savlino.downloads.SavedMedia
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A download lives in Home's recents row where its file lands: Home keeps its height while jobs start and finish. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class HomeInPlaceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()

    private fun item(id: Int, name: String, uri: String = "content://nezzel.fixture/thumb/$id") = SavedMedia(Uri.parse(uri), name,
        name.substringBeforeLast('.'), "video/mp4", 90_000_000, 1_790_000_000L - id * 3600L, 1080, null, 300_000, width = 1920)
    private val library = listOf("Big Buck Bunny", "Sintel", "Spring", "Tears of Steel", "Cosmos Laundromat", "Charge", "Wing It",
        "Coffee Run", "Agent 327", "Elephants Dream").mapIndexed { i, name -> item(i, "$name.mp4") }
    private val running = DownloadProgress("r1", "Caminandes Llamigos.mp4", DownloadPhase.DOWNLOADING, bytes = 42_000_000,
        total = Size.Exact(100_000_000), bytesPerSecond = 3_100_000.0, remainingTransferSeconds = 12, stalled = false, qualityHeight = 1080)
    // Published to external_primary; the library lists the same row under external.
    private val finished = running.copy(phase = DownloadPhase.COMPLETED, publishedUri = Uri.parse("content://media/external_primary/downloads/77"))
    private val landed = item(-1, "Caminandes Llamigos.mp4", "content://media/external/downloads/77").copy(addedSeconds = 1_800_000_000L)
    private fun waiting(id: String) = DownloadProgress(id, "$id.mp4", DownloadPhase.PREPARING, waiting = true)

    private fun show(content: @Composable () -> Unit): AutoCloseable {
        Robolectric.setupContentProvider(FixtureThumbnails::class.java, "nezzel.fixture")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        controller.get().setContent { content() }
        return AutoCloseable { controller.pause().stop().destroy() }
    }

    @Composable
    private fun Home(vm: LinkViewModel, jobs: List<DownloadProgress>, media: List<SavedMedia>) {
        val state = remember(media) { LibraryState(LibraryLoadState.Ready(media), {}, {}, {}, null, {}, {}, {}, { _, _ -> }) }
        NezzelTheme(ThemePref.Light, false) { HomeScreen(vm, jobs, state, "en", {}, {}, {}, {}) }
    }

    private fun model() = LinkViewModel(SavedStateHandle(), engine = dev.ahmed.savlino.engine.FakeEngine()).apply { automaticOptions = false }
    private fun card(text: String): Rect = compose.onNode(hasText(text, substring = true) and hasClickAction()).fetchSemanticsNode().boundsInRoot
    private fun earlierTop() = compose.onNodeWithText(context.getString(R.string.home_earlier)).fetchSemanticsNode().boundsInRoot.top

    // Wide enough that the row composes all four cards at once.
    @Test @Config(qualifiers = "w1200dp-h832dp-mdpi") fun aRunningJobIsTheFirstRecentsCardAndTheQueueFollowsInOrder() {
        show { NezzelTheme(ThemePref.Light, false) {
            HomeRecents(library, "en", {}, { listOf(running, waiting("Second"), waiting("Third")) }) {}
        } }.use {
            val job = card("Caminandes"); val second = card("Second"); val third = card("Third"); val file = card("Big Buck Bunny")
            assertTrue("running, then the queue, then files: $job $second $third $file", job.left < second.left && second.left < third.left && third.left < file.left)
            assertEquals(file.top, job.top, .5f)
            compose.onNodeWithText("Caminandes", substring = true).assertIsDisplayed()
            // TalkBack: title, time left, and the ring's percent as the card's state.
            compose.onNode(hasText("Caminandes", substring = true) and hasText("left", substring = true)
                and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "⁦42%⁩")).assertExists()
        }
    }

    @Test fun homeKeepsItsHeightWhileAJobStartsAndFinishes() {
        val vm = model()
        var jobs by mutableStateOf(emptyList<DownloadProgress>())
        var media by mutableStateOf(library)
        show { Home(vm, jobs, media) }.use {
            val before = earlierTop()
            compose.runOnIdle { jobs = listOf(running) }
            compose.waitForIdle()
            compose.onNodeWithText("Caminandes", substring = true).assertExists()
            assertEquals("A started download moves nothing", before, earlierTop(), .5f)
            compose.runOnIdle { jobs = listOf(running, waiting("Second"), waiting("Third")) }
            compose.waitForIdle()
            assertEquals("A batch moves nothing", before, earlierTop(), .5f)
            compose.runOnIdle { jobs = listOf(finished) }
            compose.waitForIdle()
            // Finished before the library lists the file: the slot says Saved.
            compose.onNodeWithTag("home-job-saved", useUnmergedTree = true).assertExists()
            assertEquals("The saved moment moves nothing", before, earlierTop(), .5f)
            compose.runOnIdle { media = listOf(landed) + library }
            compose.waitForIdle()
            assertEquals("The file landing in the slot moves nothing", before, earlierTop(), .5f)
        }
    }

    @Test fun theNotificationsOffNoteSitsBelowTheLibrary() {
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        val vm = model()
        var jobs by mutableStateOf(emptyList<DownloadProgress>())
        show { Home(vm, jobs, library) }.use {
            val before = earlierTop()
            compose.runOnIdle { jobs = listOf(running) }
            compose.waitForIdle()
            val note = compose.onNodeWithText(context.getString(R.string.notifications_off)).fetchSemanticsNode().boundsInRoot
            assertEquals("The note moves nothing above it", before, earlierTop(), .5f)
            assertTrue("below Earlier", note.top > before)
        }
    }

    @Test fun theFileKeepsItsJobKeyAfterTheJobLeavesTheList() {
        val fileJobs = HashMap<String, String>()
        val recent = listOf(landed) + library.take(3)
        val listed = recent.mapTo(HashSet()) { dev.ahmed.savlino.downloads.fileKey(it.uri) }
        val withJob = dev.ahmed.savlino.downloads.homeSlots(listOf(finished), recent, listed, emptyList(), emptySet(), fileJobs)
        val jobGone = dev.ahmed.savlino.downloads.homeSlots(emptyList(), recent, listed, emptyList(), emptySet(), fileJobs)
        assertEquals("job:r1", withJob.first().key)
        assertEquals("no remove + insert when the finished job is pruned", withJob.map { it.key }, jobGone.map { it.key })
    }

    @Test fun aFileListedBeforeItsJobSaysSavedIsNotShownTwice() {
        val finishing = running.copy(phase = DownloadPhase.SAVING, targetUri = finished.publishedUri)
        val recent = listOf(landed) + library.take(3)
        val listed = recent.mapTo(HashSet()) { dev.ahmed.savlino.downloads.fileKey(it.uri) }
        val fileJobs = HashMap<String, String>()
        val early = dev.ahmed.savlino.downloads.homeSlots(listOf(finishing), recent, listed, listOf("r1"), emptySet(), fileJobs)
        val saved = dev.ahmed.savlino.downloads.homeSlots(listOf(finished), recent, listed, emptyList(), emptySet(), fileJobs)
        assertEquals(listOf("job:r1") + library.take(3).map { it.uri.toString() }, early.map { it.key })
        assertEquals("the job's slot turns into the file, the rest stay", early.map { it.key }, saved.map { it.key })
    }

    @Test fun aFinishedJobBecomesItsSavedCardInTheSameSlot() {
        var jobs by mutableStateOf(listOf(running))
        var media by mutableStateOf(library)
        show { NezzelTheme(ThemePref.Light, false) { HomeRecents(media, "en", {}, { jobs }) {} } }.use {
            val slot = card("Caminandes")
            compose.runOnIdle { jobs = listOf(finished); media = listOf(landed) + library }
            compose.waitForIdle()
            compose.onAllNodesWithTag("home-job-dial", useUnmergedTree = true).assertCountEquals(0)
            compose.onAllNodesWithTag("home-job-saved", useUnmergedTree = true).assertCountEquals(0)
            // The saved file's own meta line, in the slot the job had.
            compose.onNode(hasText("Caminandes", substring = true) and hasText("1080p", substring = true) and hasClickAction()).assertExists()
            val saved = card("Caminandes")
            assertEquals(slot.left, saved.left, .5f); assertEquals(slot.height, saved.height, .5f)
            assertEquals(1, compose.onAllNodes(hasText("Caminandes", substring = true) and hasClickAction()).fetchSemanticsNodes().size)
        }
    }

    @Test fun theJobCardStaysUntilTheFilesPictureIsReady() {
        val picture = kotlinx.coroutines.CompletableDeferred<android.graphics.Bitmap>()
        val pictures = dev.ahmed.savlino.viewer.MediaPictures { uri, _, _ ->
            if (uri == landed.uri) picture.await() else android.graphics.Bitmap.createBitmap(32, 20, android.graphics.Bitmap.Config.ARGB_8888)
        }
        var jobs by mutableStateOf(listOf(running))
        var media by mutableStateOf(library)
        show { CompositionLocalProvider(dev.ahmed.savlino.ui.LocalThumbnailCache provides dev.ahmed.savlino.ui.ThumbnailCache(8_000_000),
            dev.ahmed.savlino.viewer.LocalMediaPictures provides pictures) {
            NezzelTheme(ThemePref.Light, false) { HomeRecents(media, "en", {}, { jobs }) {} }
        } }.use {
            compose.runOnIdle { jobs = listOf(finished); media = listOf(landed) + library }
            compose.waitForIdle()
            // Listed, but its picture is still decoding: the saved job card stays, no empty box.
            compose.onNodeWithTag("home-job-saved", useUnmergedTree = true).assertExists()
            compose.runOnIdle { picture.complete(android.graphics.Bitmap.createBitmap(32, 20, android.graphics.Bitmap.Config.ARGB_8888)) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("home-job-saved", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            compose.onNode(hasText("Caminandes", substring = true) and hasText("1080p", substring = true) and hasClickAction()).assertExists()
        }
    }

    @Test fun aJobCardIsAsTallAsAFileCardAtLargeText() {
        for (scale in listOf(1f, 2f)) {
            show { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                NezzelTheme(ThemePref.Light, false) { HomeRecents(library, "en", {}, { listOf(running) }) {} }
            } }.use {
                assertEquals("fontScale $scale", card("Big Buck Bunny").height, card("Caminandes").height, .5f)
            }
        }
    }

    @Test fun tappingARunningCardOpensDownloads() {
        val vm = model()
        show { Home(vm, listOf(running), library) }.use {
            compose.onNode(hasText("Caminandes", substring = true) and hasClickAction()).performClick()
            compose.waitForIdle()
            assertEquals("Downloads", vm.destination)
        }
    }

    @Test fun theFirstDownloadReplacesTheHeroWithTheRow() {
        val vm = model()
        var jobs by mutableStateOf(emptyList<DownloadProgress>())
        show { Home(vm, jobs, emptyList()) }.use {
            compose.onNodeWithText(context.getString(R.string.home_hero_title)).assertExists()
            compose.runOnIdle { jobs = listOf(running) }
            compose.waitForIdle()
            compose.onAllNodesWithText(context.getString(R.string.home_hero_title)).assertCountEquals(0)
            compose.onNode(hasText("Caminandes", substring = true) and hasClickAction()).assertIsDisplayed()
        }
    }
}
