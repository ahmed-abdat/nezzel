package dev.ahmed.savlino.downloads

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import dev.ahmed.savlino.R
import dev.ahmed.savlino.descendants
import dev.ahmed.savlino.domain.ResultCheck
import dev.ahmed.savlino.jobs.CheckWarning
import dev.ahmed.savlino.renderThemed
import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.texts
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** R4 "Needs a look" rows and B3 "Look for better quality" in the file sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w400dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadsScreenWarningsTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val video = SavedMedia(uri = Uri.parse("content://media/external_primary/downloads/1"),
        filename = "Movie.mp4", title = "Movie", mimeType = "video/mp4", size = null, addedSeconds = 100, height = 360,
        bitrateKbps = null, durationMillis = 84000, volumeName = "external_primary")
    private val audio = video.copy(uri = Uri.parse("content://media/external_primary/downloads/2"), filename = "Song.mp3",
        title = "Song", mimeType = "audio/mpeg")
    private val image = video.copy(uri = Uri.parse("content://media/external_primary/downloads/3"), filename = "Pic.jpg",
        title = "Pic", mimeType = "image/jpeg")
    private fun nodes(a: ComponentActivity): List<SemanticsNode> { compose.waitForIdle(); return semanticsNodes(a, rounds = 4, idleMillis = 200) }
    private fun clickable(a: ComponentActivity, label: String): SemanticsNode? = nodes(a).lastOrNull {
        it.config.getOrNull(SemanticsActions.OnClick) != null && (texts(descendants(it)).contains(label) ||
            descendants(it).any { c -> c.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label) })
    }
    private fun click(a: ComponentActivity, label: String) =
        assertEquals(true, (clickable(a, label) ?: error("Missing $label")).config[SemanticsActions.OnClick].action?.invoke())

    @Test fun lateProblemFromRealCoordinatorAppearsInNeedsALookAfterCompletion() = kotlinx.coroutines.runBlocking {
        val app = context
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app, androidx.work.Configuration.Builder()
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(context: android.content.Context, name: String, params: androidx.work.WorkerParameters) =
                    object : androidx.work.Worker(context, params) { override fun doWork() = Result.success() }
            }).build())
        val provider = dev.ahmed.savlino.jobs.TestDownloadsProvider(java.io.File(app.cacheDir, "late-warning-provider"), app.packageName)
        provider.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "media" })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", provider)
        val release = java.util.concurrent.CountDownLatch(1)
        val space = dev.ahmed.savlino.storage.SpaceBudget(app.filesDir, app.cacheDir,
            dev.ahmed.savlino.storage.FreeSpace { Long.MAX_VALUE })
        // Robolectric has no AndroidKeyStore: a memory key keeps Try again (reopen) working, like DownloadCoordinatorCheckTest.
        dev.ahmed.savlino.jobs.JobStore(app, null, dev.ahmed.savlino.storage.AesGcmLinkCipher(dev.ahmed.savlino.jobs.MemoryLinkKeys())).also { it.all() }.use { store ->
            val coordinator = dev.ahmed.savlino.jobs.DownloadCoordinator(app,
                dev.ahmed.savlino.engine.FakeEngine(steps = listOf(dev.ahmed.savlino.engine.FakeEngine.Step.Output("output.mp4", byteArrayOf(1, 2, 3, 4)))),
                store, dev.ahmed.savlino.storage.MediaStorePublisher(app.contentResolver, space, app.packageName) { }, space = space,
                probe = { release.await(5, java.util.concurrent.TimeUnit.SECONDS); dev.ahmed.savlino.domain.ProbeFacts(1, 0, 5000, 4) })
            try {
                // Known audio codec: the shared command() leaves it unreported, which judges as NoSoundExpected, not a Problem.
                val source = dev.ahmed.savlino.domain.LinkRevision(dev.ahmed.savlino.domain.LinkInput.parse("https://example.com/clip?token=private")!!, 7)
                val format = dev.ahmed.savlino.domain.Format("f720", "mp4", true, true, videoCodec = "avc1", audioCodec = "mp4a", width = 1280, height = 720,
                    size = dev.ahmed.savlino.domain.Size.Exact(4))
                store.enqueue((dev.ahmed.savlino.domain.DownloadCommand.confirm("late-ui-problem",
                    dev.ahmed.savlino.domain.Inspection(source, "engine-42", listOf(format)), source, format.id, "clip")
                    as dev.ahmed.savlino.domain.ConfirmResult.Confirmed).command)
                renderThemed({ nodes(it) }, {
                    val warnings by coordinator.warnings.collectAsState()
                    DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {},
                        libraryLoad = LibraryLoadState.Ready(listOf(video)), warnings = warnings)
                }) { activity ->
                    // Settle the real UI before starting the 4s probe budget; rendering is not part of that check.
                    var completed: dev.ahmed.savlino.jobs.DownloadProgress? = null
                    kotlinx.coroutines.runBlocking {
                        coordinator.execute("late-ui-problem", {}, { if (it.terminal) completed = it })
                    }
                    assertNull(completed!!.check)
                    assertTrue(coordinator.warnings.value.isEmpty())
                    release.countDown()
                    kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeout(2000) {
                            while (coordinator.warnings.value.isEmpty()) kotlinx.coroutines.delay(10)
                        }
                    }
                    click(activity, context.getString(R.string.dl_tab_running))
                    assertTrue(texts(nodes(activity)).contains(context.getString(R.string.check_problem_no_sound)))
                    assertTrue(texts(nodes(activity)).contains(context.getString(R.string.check_needs_look)))
                }
            } finally { release.countDown() }
        }
    }

    @Test fun warningRowShowsProblemTryAgainAndDismissRemovesIt() {
        val tried = mutableListOf<String>()
        val warnings = mutableStateOf(listOf(
            CheckWarning("a", "Movie.mp4", video.uri, ResultCheck.Problem.NO_SOUND, canRetry = true),
            CheckWarning("b", "Other.mp4", null, ResultCheck.Problem.DAMAGED, canRetry = false)))
        renderThemed({ nodes(it) }, {
            DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(listOf(video)),
                warnings = warnings.value, onTryAgain = { tried += it }, onDismissWarning = { id -> warnings.value = warnings.value.filterNot { it.jobId == id } })
        }) { a ->
            // Warnings alone keep the tabs with a dot on Downloading, but entry opens Saved.
            assertFalse(texts(nodes(a)).contains(context.getString(R.string.check_needs_look)))
            val tab = clickable(a, context.getString(R.string.dl_tab_running)) ?: error("Missing Downloading tab")
            assertEquals(context.getString(R.string.check_needs_look), tab.config.getOrNull(SemanticsProperties.StateDescription))
            click(a, context.getString(R.string.dl_tab_running))
            val all = texts(nodes(a))
            assertTrue(all.contains(context.getString(R.string.check_needs_look)))
            assertTrue(all.contains(context.getString(R.string.check_problem_no_sound)))
            assertTrue(all.contains(context.getString(R.string.check_problem_damaged)))
            // Same display title as the library row: no extension, also when the file is not listed.
            assertFalse(all.any { it.contains("Movie.mp4") || it.contains("Other.mp4") })
            assertTrue(all.contains(dev.ahmed.savlino.locale.NezzelFormat.firstStrong("Other")))
            // Try again only where the link is kept: one button for two rows.
            val tryAgain = nodes(a).filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(context.getString(R.string.try_again)) == true }
            assertEquals(1, tryAgain.size)
            assertTrue(tryAgain.single().size.width >= 48 && tryAgain.single().size.height >= 48)
            tryAgain.single().config[SemanticsActions.OnClick].action?.invoke()
            assertEquals(listOf("a"), tried)
            val dismiss = nodes(a).filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(context.getString(R.string.dismiss)) == true }
            assertEquals(2, dismiss.size)
            dismiss.forEach { it.config[SemanticsActions.OnClick].action?.invoke(); compose.waitForIdle() }
            val after = texts(nodes(a))
            assertFalse(after.contains(context.getString(R.string.check_needs_look)))
            // Nothing left in Downloading: the tabs fold away and the saved files are the screen.
            assertFalse(after.contains(context.getString(R.string.dl_tab_running)))
            val libraryTexts = texts(nodes(a))
            assertTrue(libraryTexts.contains(dev.ahmed.savlino.locale.NezzelFormat.firstStrong("Movie")))
        }
    }

    @Test @Config(qualifiers = "ar-w400dp-h1200dp-mdpi", fontScale = 2f)
    fun warningRowFitsArabicAtLargeText() = renderThemed({ nodes(it) }, {
        DownloadsScreen(emptyList(), "ar", {}, {}, {}, {}, {}, null, {}, {},
            warnings = listOf(CheckWarning("a", "Movie.mp4", null, ResultCheck.Problem.NO_PICTURE, true)))
    }) { a ->
        click(a, context.getString(R.string.dl_tab_running))
        assertTrue(texts(nodes(a)).contains(context.getString(R.string.check_problem_no_picture)))
        assertNotNull(clickable(a, context.getString(R.string.try_again)))
    }

    @Test fun warningUsesRenamedFilenameInsteadOfStaleProviderTitle() = renderThemed({ nodes(it) }, {
        CheckWarningRow(CheckWarning("a", "Movie.mp4", video.uri, ResultCheck.Problem.NO_SOUND, true),
            video.copy(filename = "Holiday.mp4"), {}, {})
    }) { a ->
        val all = texts(nodes(a))
        assertTrue(all.contains(dev.ahmed.savlino.locale.NezzelFormat.firstStrong("Holiday")))
        assertFalse(all.contains(dev.ahmed.savlino.locale.NezzelFormat.firstStrong("Movie")))
    }

    @Test fun betterQualityRowOnlyForEligibleVideoAndOpensIt() {
        val opened = mutableListOf<SavedMedia>()
        val better = BetterQualityLink(eligible = { it.uri == video.uri }, open = { opened += it })
        renderThemed({ nodes(it) }, {
            CompositionLocalProvider(LocalBetterQuality provides better) {
                DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(listOf(video, audio, image)))
            }
        }) { a ->
            val label = context.getString(R.string.better_quality_action)
            click(a, context.getString(R.string.saved_more, audio.title))
            assertNull(clickable(a, label))
            click(a, context.getString(R.string.share_file))
            click(a, context.getString(R.string.saved_more, video.title))
            val row = clickable(a, label) ?: error("Missing better quality row")
            assertTrue(texts(descendants(row)).contains(context.getString(R.string.better_quality_support)))
            assertTrue(row.size.height >= 48)
            click(a, label)
            assertEquals(listOf(video), opened)
        }
    }

    @Test fun noBetterQualityProviderOrNotEligibleHidesTheRow() {
        val better = BetterQualityLink(eligible = { false }, open = {})
        renderThemed({ nodes(it) }, {
            CompositionLocalProvider(LocalBetterQuality provides better) {
                DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(listOf(video)))
            }
        }) { a ->
            click(a, context.getString(R.string.saved_more, video.title))
            assertNotNull(clickable(a, context.getString(R.string.share_file)))
            assertNull(clickable(a, context.getString(R.string.better_quality_action)))
        }
    }
}
