package dev.ahmed.savlino.downloads

import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.descendants
import dev.ahmed.savlino.texts
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.*
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.FakeEngine
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DownloadsTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val video = SavedMedia(Uri.parse("content://media/external/downloads/1"), "Family.mp4", "Family", "video/mp4", 68_000_000, 1700000000, 720, null, 84000)
    private val audio = SavedMedia(Uri.parse("content://media/external/downloads/2"), "Song.m4a", "Song", "audio/mp4", 8_100_000, 1700000000, null, 128, null)
    private fun nodes(a: ComponentActivity): List<SemanticsNode> {
        compose.waitForIdle()
        return semanticsNodes(a, rounds = 4, idleMillis = 200)
    }
    private fun click(a: ComponentActivity, label: String) {
        val all = nodes(a)
        val n = all.lastOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null &&
            (texts(descendants(it)).any { t -> t.contains(label) } ||
                descendants(it).flatMap { n -> n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.any { t -> t.contains(label) }) } ?: error("Missing $label: ${all.map { it.config }}")
        assertEquals(true, n.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke())
    }
    private fun screen(media: List<SavedMedia> = listOf(video, audio), jobs: List<DownloadProgress> = emptyList(),
        remove: (String) -> Unit = {}, delete: (SavedMedia) -> Unit = {}, check: (ComponentActivity) -> Unit) {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            c.get().setContent { NezzelTheme(ThemePref.Light, false) {
                DownloadsScreen(jobs, "en", {}, remove, {}, {}, {}, null, delete, {}, libraryLoad = LibraryLoadState.Ready(media))
            } }
            nodes(c.get()); check(c.get())
        } finally { c.pause().stop().destroy() }
    }
    @Test fun chipsFilterVisibleSavedRows() = screen { a ->
        click(a, "Audio")
        assertTrue(texts(nodes(a)).any { it.contains("Song") })
        assertFalse(texts(nodes(a)).any { it.contains("Family") })
        click(a, "Video")
        assertTrue(texts(nodes(a)).any { it.contains("Family") })
        assertFalse(texts(nodes(a)).any { it.contains("Song") })
        click(a, "All")
        assertTrue(texts(nodes(a)).any { it.contains("Song") })
    }
    @Test fun deletingLastAudioKeepsAllFilterReachable() {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var media by mutableStateOf(listOf(video, audio))
        try {
            c.get().setContent { NezzelTheme(ThemePref.Light, false) {
                DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(media))
            } }
            click(c.get(), "Audio")
            compose.runOnIdle { media = listOf(video) }
            click(c.get(), "All")
            assertTrue(texts(nodes(c.get())).any { it.contains("Family") })
        } finally { c.pause().stop().destroy() }
    }
    @Test fun savedRowAnnouncesOpenActionAndMergesItsTitle() = screen(media = listOf(video)) { a ->
        assertTrue(nodes(a).any { node ->
            node.config.getOrNull(SemanticsActions.OnClick)?.label == "Open" &&
                node.config.isMergingSemanticsOfDescendants && texts(descendants(node)).any { it.contains("Family") }
        })
    }
    @Test fun deleteNeedsConfirmationAndCancelLeavesProviderRow() {
        val provider = LibraryProvider()
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", provider)
        val repo = SavedMediaRepository(RuntimeEnvironment.getApplication().contentResolver, RuntimeEnvironment.getApplication().packageName)
        screen(media = listOf(video), delete = { repo.delete(it.uri) }) { a ->
            click(a, "More actions"); click(a, "Delete")
            assertEquals(1, repo.query().size)
            click(a, "Cancel")
            assertEquals(1, repo.query().size)
            click(a, "More actions"); click(a, "Delete")
            assertEquals(1, repo.query().size)
            click(a, "Delete")
            assertTrue(repo.query().isEmpty())
        }
    }
    @Test fun failedRowRemoveDeletesDurableRecordAndSurvivesReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(JobStore.DATABASE_NAME)
        val store = JobStore(context)
        val source = LinkRevision(LinkInput.parse("https://example.org/video")!!, 1)
        val format = Format("720", "mp4", true, true, height = 720)
        val command = (DownloadCommand.confirm("failed", Inspection(source, "v1", listOf(format)), source, "720", "Family") as ConfirmResult.Confirmed).command
        store.enqueue(command); store.fail("failed", DownloadError.Network)
        val coordinator = DownloadCoordinator(context, engine = FakeEngine(), store = store)
        coordinator.initialize()
        val staging = java.io.File(context.filesDir, "downloads/failed/partial").apply { parentFile!!.mkdirs(); writeText("old staging") }
        screen(media = emptyList(), jobs = coordinator.progress.value, remove = { runBlocking { coordinator.removeFailed(it) } }) { a ->
            assertTrue(texts(nodes(a)).contains("No internet connection"))
            click(a, "Remove")
            assertNull(store.get("failed"))
            assertTrue(coordinator.progress.value.isEmpty())
            assertFalse(staging.exists())
        }
        store.close()
        JobStore(context).use { assertTrue(it.all().isEmpty()) }
    }
    @Test fun navigationBadgeCountsWaitingAndRunningBeforeFailedDot() {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var jobs by mutableStateOf(listOf(
            DownloadProgress("a", "one", DownloadPhase.PREPARING, waiting = true),
            DownloadProgress("b", "two", DownloadPhase.DOWNLOADING),
            DownloadProgress("c", "three", DownloadPhase.FAILED)))
        var route = "Home"
        try {
            c.get().setContent { NezzelTheme(ThemePref.Light, false) { DownloadNavigation("Home", jobs, "en") { route = it } } }
            assertTrue(nodes(c.get()).any { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Downloads, 2 running") })
            click(c.get(), "Downloads"); assertEquals("Downloads", route)
            compose.runOnIdle { jobs = jobs.takeLast(1) }
            val updated = nodes(c.get())
            assertTrue(updated.map { it.config }.toString(), updated.any { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Downloads, a download failed") })
        } finally { c.pause().stop().destroy() }
    }
    @Test fun recentFilesAreCappedAtFourAndSeeAllNavigates() {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var seeAll = false
        var opened: SavedMedia? = null
        val media = (1..6).map { video.copy(uri = Uri.parse("content://media/external/downloads/$it"), title = "File $it") }
        try {
            c.get().setContent { NezzelTheme(ThemePref.Light, false) { RecentFiles(media, "en", { opened = it }) { seeAll = true } } }
            val labels = texts(nodes(c.get()))
            assertTrue(labels.any { it.contains("File 1") })
            assertFalse(labels.any { it.contains("File 5") || it.contains("File 6") })
            click(c.get(), "File 1"); assertEquals(media.first(), opened)
            click(c.get(), "See all"); assertTrue(seeAll)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun waitingRowUsesJournalStateAndCancelReachesThatJob() {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var cancelled: String? = null
        try {
            c.get().setContent { NezzelTheme(ThemePref.Light, false) {
                DownloadsScreen(listOf(DownloadProgress("waiting", "Family.mp4", DownloadPhase.PREPARING, waiting = true)), "en",
                    { cancelled = it }, {}, {}, {}, {}, null, {}, {})
            } }
            assertTrue(texts(nodes(c.get())).contains("Queued"))
            click(c.get(), "Cancel download"); assertEquals("waiting", cancelled)
            assertTrue("Pending cancel must remain visible until durable cancellation", texts(nodes(c.get())).contains("Queued"))
        } finally { c.pause().stop().destroy() }
    }
    @Test fun retryableFailureRetryUsesJournalIdAndStillOffersRemove() {
        var retried: String? = null
        var removed: String? = null
        val c = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            c.get().setContent { NezzelTheme(ThemePref.Light, false) {
                DownloadsScreen(listOf(DownloadProgress("original-confirmation", "Family.mp4", DownloadPhase.FAILED,
                    error = DownloadError.Network, retryable = true)), "en",
                    {}, { removed = it }, {}, {}, {}, null, {}, {}, { retried = it })
            } }
            click(c.get(), "Try again"); assertEquals("original-confirmation", retried)
            click(c.get(), "Remove"); assertEquals("original-confirmation", removed)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun waitingSchedulingFailureOffersExplicitRetry() {
        var retried: String? = null
        val c = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            c.get().setContent { NezzelTheme(ThemePref.Light, false) {
                DownloadsScreen(listOf(DownloadProgress("blocked", "Family.mp4", DownloadPhase.FAILED,
                    error = DownloadError.ExecutionRestricted, awaitingSchedule = true, waiting = true)), "en",
                    {}, {}, {}, {}, {}, null, {}, {}, { retried = it })
            } }
            click(c.get(), "Try again")
            assertEquals("blocked", retried)
        } finally { c.pause().stop().destroy() }
    }
    @Test @Config(qualifiers = "ar") fun arabicLargeTextUsesLatinDigitsAndRtl() {
        RuntimeEnvironment.setFontScale(2f)
        val c = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            c.get().setContent { NezzelTheme(ThemePref.Dark, false) {
                DownloadsScreen(emptyList(), "ar", {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(listOf(video)))
            } }
            val labels = texts(nodes(c.get()))
            assertTrue(labels.toString(), labels.any { it.contains("720p") && it.contains("68\u00a0MB") })
            assertFalse(labels.any { t -> t.any { it in '\u0660'..'\u0669' || it in '\u06f0'..'\u06f9' } })
            assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, c.get().resources.configuration.layoutDirection)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun cancellationSnackbarAppearsOnlyAfterDurableCancellation() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as IsolatedTestApplication
        app.deleteDatabase(JobStore.DATABASE_NAME)
        val store = JobStore(app)
        val source = LinkRevision(LinkInput.parse("https://example.org/video")!!, 1)
        val format = Format("720", "mp4", true, true, height = 720)
        val command = (DownloadCommand.confirm("cancel-event", Inspection(source, "v1", listOf(format)), source, "720", "Family") as ConfirmResult.Confirmed).command
        store.enqueue(command)
        val coordinator = DownloadCoordinator(app, FakeEngine(), store, workManager = { error("scheduler unavailable") })
        coordinator.initialize()
        app.coordinatorOverride = coordinator
        val c = Robolectric.buildActivity(dev.ahmed.savlino.MainActivity::class.java).create().start().resume().visible()
        try {
            c.get().links.navigate("Downloads"); nodes(c.get())
            assertFalse(texts(nodes(c.get())).contains("Download cancelled"))
            click(c.get(), "Cancel download")
            compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); store.get("cancel-event")!!.stage == JobStage.CANCELLED }
            assertTrue(texts(nodes(c.get())).contains("Download cancelled"))
            assertTrue(coordinator.progress.value.isEmpty())
        } finally { c.pause().stop().destroy(); store.close(); app.coordinatorOverride = null }
    }
    @Test fun finishOnSettingsHonorsAccessibilityTimeoutAndOpenUsesPublishedUri() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as IsolatedTestApplication
        app.deleteDatabase(JobStore.DATABASE_NAME)
        val store = JobStore(app)
        val provider = dev.ahmed.savlino.jobs.TestDownloadsProvider(java.io.File(app.cacheDir, "saved.mp4"), app.packageName)
        provider.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "media"; exported = true })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", provider)
        val space = dev.ahmed.savlino.storage.SpaceBudget(app.filesDir, app.cacheDir, dev.ahmed.savlino.storage.FreeSpace { Long.MAX_VALUE })
        val publisher = dev.ahmed.savlino.storage.MediaStorePublisher(app.contentResolver, space, app.packageName) { }
        val engine = FakeEngine(steps = listOf(FakeEngine.Step.Output("output.mp4", byteArrayOf(1, 2, 3, 4))))
        val coordinator = DownloadCoordinator(app, engine, store, publisher, space = space)
        coordinator.initialize()
        app.coordinatorOverride = coordinator
        shadowOf(app.getSystemService(android.view.accessibility.AccessibilityManager::class.java)).apply {
            setEnabled(true); setTouchExplorationEnabled(true); setInteractiveUiTimeout(20000)
        }
        val c = Robolectric.buildActivity(dev.ahmed.savlino.MainActivity::class.java).create().start().resume().visible()
        try {
            c.get().links.navigate("Settings")
            nodes(c.get())
            val source = LinkRevision(LinkInput.parse("https://example.org/video")!!, 1)
            val format = Format("720", "mp4", true, true, height = 720)
            val command = (DownloadCommand.confirm("finished", Inspection(source, "v1", listOf(format)), source, "720", "Family") as ConfirmResult.Confirmed).command
            store.enqueue(command)
            coordinator.execute("finished", {}, {})
            val labels = texts(nodes(c.get()))
            assertTrue(labels.toString(), labels.any { it.contains("Saved to") && it.contains("Downloads/Nezzel") })
            assertEquals("Settings", c.get().links.destination)
            val shownAt = System.nanoTime()
            // Compose's coroutine deadline uses wall time; pumping only Robolectric time misses it.
            compose.waitUntil(8000) {
                shadowOf(Looper.getMainLooper()).idle()
                System.nanoTime() - shownAt >= java.util.concurrent.TimeUnit.SECONDS.toNanos(7)
            }
            assertTrue("Accessibility-extended Open action expired after 7 seconds", texts(nodes(c.get())).any { it.contains("Saved to") })
            click(c.get(), "Open")
            val intent = shadowOf(c.get()).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(store.get("finished")!!.pendingUri, intent.data)
            assertEquals("video/mp4", intent.type)
        } finally { c.pause().stop().destroy(); store.close(); app.coordinatorOverride = null }
    }
    class LibraryProvider : ContentProvider() {
        private var exists = true
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
            MatrixCursor(projection!!).apply { if (exists) addRow(projection.map { when(it) {
                "_id" -> 1L; "_display_name" -> "Family.mp4"; "title" -> "Family"; "mime_type" -> "video/mp4"
                "_size" -> 68_000_000L; "date_added" -> 1700000000L; "height" -> 720; "duration" -> 84000L
                else -> null
            } }) }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int { val result = if(exists) 1 else 0; exists = false; return result }
        override fun getType(uri: Uri) = "video/mp4"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }
}
