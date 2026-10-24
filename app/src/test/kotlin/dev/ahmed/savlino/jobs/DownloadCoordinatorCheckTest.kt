package dev.ahmed.savlino.jobs

import android.content.Context
import android.content.pm.ProviderInfo
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.FakeEngine
import dev.ahmed.savlino.media.MediaProbe
import dev.ahmed.savlino.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLog
import java.io.File

/** Result check after publish, with a fake probe (Robolectric cannot decode media). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DownloadCoordinatorCheckTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val link = "https://example.com/clip?token=private-token"
    private val format = Format("f720", "mp4", true, true, videoCodec = "avc1", audioCodec = "mp4a", width = 1280, height = 720,
        size = Size.Exact(4))

    @After fun tearDown() { app.deleteDatabase(JobStore.DATABASE_NAME) }

    private fun command(id: String): DownloadCommand {
        val source = LinkRevision(LinkInput.parse(link)!!, 1)
        return (DownloadCommand.confirm(id, Inspection(source, "engine-42", listOf(format)), source, format.id, "clip")
            as ConfirmResult.Confirmed).command
    }

    private class Fixture(val store: JobStore, val coordinator: DownloadCoordinator, val stagedSeen: MutableList<Boolean>) : AutoCloseable {
        override fun close() = store.close()
    }

    private fun fixture(cipher: LinkCipher? = AesGcmLinkCipher(MemoryLinkKeys()), timeoutMs: Long = 4000, probe: (Fixture) -> MediaProbe): Fixture {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder()
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, params: WorkerParameters) =
                    object : Worker(context, params) { override fun doWork() = Result.success() }
            }).build())
        val provider = TestDownloadsProvider(File(app.cacheDir, "provider.mp4"), app.packageName)
        provider.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val space = SpaceBudget(app.filesDir, app.cacheDir, FreeSpace { Long.MAX_VALUE })
        val store = JobStore(app, null, cipher).also { it.all() } // warm SQLite
        lateinit var fixture: Fixture
        val engine = FakeEngine(steps = listOf(FakeEngine.Step.Output("output.mp4", byteArrayOf(1, 2, 3, 4))))
        val coordinator = DownloadCoordinator(app, engine, store, MediaStorePublisher(app.contentResolver, space, app.packageName) { },
            space = space, probe = { uri -> probe(fixture).probe(uri) }, checkTimeoutMs = timeoutMs)
        fixture = Fixture(store, coordinator, mutableListOf())
        return fixture
    }

    private suspend fun Fixture.finish(id: String): DownloadProgress = coroutineScope {
        val event = async(start = CoroutineStart.UNDISPATCHED) { coordinator.completed.first { it.jobId == id } }
        store.enqueue(command(id))
        assertEquals(JobStage.COMPLETED, withTimeout(10_000) { coordinator.execute(id, {}, {}) }!!.stage)
        withTimeout(5000) { event.await() }
    }

    @Test fun matchingFileIsVerifiedOnTheCompletedEventAndStagingWasGoneOnlyAfterSizeWasTaken() = runBlocking {
        fixture { f -> MediaProbe { f.stagedSeen += File(app.filesDir, "downloads/ok").exists(); ProbeFacts(1, 1, 5000, 4) } }.use { f ->
            ShadowLog.clear()
            val done = f.finish("ok")
            assertEquals(ResultCheck.Verified(video = true, audio = true), done.check)
            assertEquals(JobStage.COMPLETED, f.store.get("ok")!!.stage)
            assertTrue(f.coordinator.warnings.value.isEmpty())
            assertEquals(listOf(false), f.stagedSeen) // probe ran after cleanup; staged size came from before it
            assertFalse(ShadowLog.getLogs().any { it.msg?.contains("private-token") == true || it.msg?.contains("example.com") == true })
        }
    }

    @Test fun sizeMismatchIsDamagedAndRaisesAWarningWithTryAgain() = runBlocking {
        fixture { MediaProbe { ProbeFacts(1, 1, 5000, 3) } }.use { f ->
            val done = f.finish("short")
            assertEquals(ResultCheck.Problem.DAMAGED, done.check)
            assertEquals(JobStage.COMPLETED, f.store.get("short")!!.stage)
            val warning = f.coordinator.warnings.value.single()
            assertEquals("short", warning.jobId); assertTrue(warning.canRetry)
            f.coordinator.dismissWarning("short")
            assertTrue(f.coordinator.warnings.value.isEmpty())
        }
    }

    @Test fun throwingOrSlowProbeIsUncheckedAndNeverHangs() = runBlocking {
        fixture { MediaProbe { error("cannot open") } }.use { f ->
            val done = f.finish("throws")
            assertNull(done.check)
            assertEquals(JobStage.COMPLETED, f.store.get("throws")!!.stage)
        }
        app.deleteDatabase(JobStore.DATABASE_NAME)
        fixture(timeoutMs = 200) { MediaProbe { Thread.sleep(10_000); ProbeFacts(0, 0) } }.use { f ->
            val started = System.nanoTime()
            val done = f.finish("slow")
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5000)
            assertNull(done.check)
            assertEquals(JobStage.COMPLETED, f.store.get("slow")!!.stage)
            assertTrue(f.coordinator.warnings.value.isEmpty())
            // A cancel after the check changes nothing: the row stays COMPLETED.
            f.coordinator.cancel("slow")
            assertEquals(JobStage.COMPLETED, f.store.get("slow")!!.stage)
        }
    }

    @Test fun tryAgainEnqueuesANewJobWithTheSameFormatAndLeavesTheOldRowAlone() = runBlocking {
        fixture { MediaProbe { ProbeFacts(1, 0, 5000, 4) } }.use { f ->
            assertEquals(ResultCheck.Problem.NO_SOUND, f.finish("silent").check)
            val before = f.store.get("silent")!!
            val newId = withTimeout(5000) { f.coordinator.tryAgain("silent") }!!
            assertNotEquals("silent", newId)
            val fresh = f.store.get(newId)!!
            assertEquals(JobStage.ENQUEUED, fresh.stage)
            assertEquals(format.id, fresh.format.id)
            val after = f.store.get("silent")!!
            assertEquals(JobStage.COMPLETED, after.stage)
            assertEquals(before.pendingUri, after.pendingUri); assertEquals(before.updatedAt, after.updatedAt)
            assertTrue(f.coordinator.warnings.value.none { it.jobId == "silent" })
        }
    }

    @Test fun noKeptLinkMeansNoTryAgain() = runBlocking {
        fixture(cipher = null) { MediaProbe { ProbeFacts(0, 1, 5000, 4) } }.use { f ->
            assertEquals(ResultCheck.Problem.NO_PICTURE, f.finish("nolink").check)
            assertFalse(f.coordinator.warnings.value.single().canRetry)
            assertNull(f.coordinator.tryAgain("nolink"))
        }
    }
}
