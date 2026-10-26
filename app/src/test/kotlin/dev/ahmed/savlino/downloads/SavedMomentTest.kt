package dev.ahmed.savlino.downloads

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsNode
import dev.ahmed.savlino.R
import dev.ahmed.savlino.renderThemed
import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.texts
import dev.ahmed.savlino.ui.theme.Motion
import dev.ahmed.savlino.waitWhile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The coordinator drops a finished job from `progress`; Downloads still shows its Saved moment from `completed`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w400dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SavedMomentTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val video = SavedMedia(uri = Uri.parse("content://media/external_primary/downloads/1"),
        filename = "Movie.mp4", title = "Movie", mimeType = "video/mp4", size = null, addedSeconds = 100, height = 360,
        bitrateKbps = null, durationMillis = 84000, volumeName = "external_primary")
    private fun nodes(a: ComponentActivity): List<SemanticsNode> { compose.waitForIdle(); return semanticsNodes(a, rounds = 2, idleMillis = 50) }

    @Test fun realCoordinatorCompletionShowsSavedThenLeaves() {
        val app = context
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app, androidx.work.Configuration.Builder()
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(context: android.content.Context, name: String, params: androidx.work.WorkerParameters) =
                    object : androidx.work.Worker(context, params) { override fun doWork() = Result.success() }
            }).build())
        val provider = dev.ahmed.savlino.jobs.TestDownloadsProvider(java.io.File(app.cacheDir, "saved-moment-provider"), app.packageName)
        provider.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "media" })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", provider)
        val space = dev.ahmed.savlino.storage.SpaceBudget(app.filesDir, app.cacheDir,
            dev.ahmed.savlino.storage.FreeSpace { Long.MAX_VALUE })
        dev.ahmed.savlino.jobs.JobStore(app, null, dev.ahmed.savlino.storage.AesGcmLinkCipher(dev.ahmed.savlino.jobs.MemoryLinkKeys())).also { it.all() }.use { store ->
            val coordinator = dev.ahmed.savlino.jobs.DownloadCoordinator(app,
                dev.ahmed.savlino.engine.FakeEngine(steps = listOf(dev.ahmed.savlino.engine.FakeEngine.Step.Output("output.mp4", byteArrayOf(1, 2, 3, 4)))),
                store, dev.ahmed.savlino.storage.MediaStorePublisher(app.contentResolver, space, app.packageName) { }, space = space,
                probe = { dev.ahmed.savlino.domain.ProbeFacts(1, 0, 5000, 4) })
            val source = dev.ahmed.savlino.domain.LinkRevision(dev.ahmed.savlino.domain.LinkInput.parse("https://example.com/clip")!!, 7)
            val format = dev.ahmed.savlino.domain.Format("f720", "mp4", true, true, videoCodec = "avc1", audioCodec = "mp4a", width = 1280, height = 720,
                size = dev.ahmed.savlino.domain.Size.Exact(4))
            store.enqueue((dev.ahmed.savlino.domain.DownloadCommand.confirm("saved-moment",
                dev.ahmed.savlino.domain.Inspection(source, "engine-42", listOf(format)), source, format.id, "clip")
                as dev.ahmed.savlino.domain.ConfirmResult.Confirmed).command)
            val seenLive = CompletableDeferred<Unit>()
            renderThemed({ nodes(it) }, {
                val jobs by coordinator.progress.collectAsState()
                DownloadsScreen(jobs, "en", {}, {}, {}, {}, {}, null, {}, {},
                    libraryLoad = LibraryLoadState.Ready(listOf(video)), completed = coordinator.completed)
            }) { activity ->
                val saved = context.getString(R.string.download_saved)
                // Foreground promotion holds the attempt while the screen draws the live row.
                val run = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    coordinator.execute("saved-moment", { seenLive.await() }, { })
                }
                waitWhile({ }) { coordinator.progress.value.none { it.jobId == "saved-moment" } }
                fun all() = texts(nodes(activity))
                waitWhile({ }) { all().none { "clip" in it } }
                // "Saved" is also the tab name; the row's own word is one more.
                val live = all().count { saved in it }
                seenLive.complete(Unit)
                runBlocking { run.join() }
                assertTrue(coordinator.progress.value.none { it.jobId == "saved-moment" })
                waitWhile({ }) { all().count { saved in it } <= live }
                assertTrue(all().any { "clip" in it })
                // The moment is brief: after the dwell the row leaves and only the saved files remain.
                compose.mainClock.advanceTimeBy(Motion.SAVED_DWELL + 500)
                waitWhile({ }) { all().any { "clip" in it } }
            }
        }
    }

    /** The end arrives before the coordinator drops the live row; a recomposition in between must not wipe the Saved moment. */
    @Test fun staleLiveRowAfterCompletionKeepsSaved() {
        val live = dev.ahmed.savlino.jobs.DownloadProgress("stale", "clip.mp4", dev.ahmed.savlino.jobs.DownloadPhase.DOWNLOADING,
            bytes = 90, total = dev.ahmed.savlino.domain.Size.Exact(100))
        val jobs = androidx.compose.runtime.mutableStateOf(listOf(live))
        val completed = kotlinx.coroutines.flow.MutableSharedFlow<dev.ahmed.savlino.jobs.DownloadProgress>(extraBufferCapacity = 1)
        renderThemed({ nodes(it) }, {
            DownloadsScreen(jobs.value, "en", {}, {}, {}, {}, {}, null, {}, {},
                libraryLoad = LibraryLoadState.Ready(listOf(video)), completed = completed)
        }) { activity ->
            val saved = context.getString(R.string.download_saved)
            fun all() = texts(nodes(activity))
            waitWhile({ }) { all().none { "clip" in it } }
            val before = all().count { saved in it }
            assertTrue(completed.tryEmit(live.copy(phase = dev.ahmed.savlino.jobs.DownloadPhase.COMPLETED, bytes = 100)))
            waitWhile({ }) { all().count { saved in it } <= before }
            // Terminal accounting still lists the live row (a fresher byte count) before it is dropped.
            jobs.value = listOf(live.copy(bytes = 95))
            nodes(activity)
            assertTrue("Saved kept over the stale row", all().count { saved in it } > before)
            jobs.value = emptyList()
            nodes(activity)
            assertTrue("Saved kept once the row is dropped", all().count { saved in it } > before)
        }
    }
}
