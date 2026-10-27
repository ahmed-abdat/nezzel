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

    // graceMs: a probe that returns at once always lands inside the grace (capped at timeoutMs), however loaded the machine;
    // only tests whose probe is held by a latch use a short grace, so its expiry never races a verdict.
    private fun fixture(cipher: LinkCipher? = AesGcmLinkCipher(MemoryLinkKeys()), timeoutMs: Long = 4000, graceMs: Long = 10_000,
        probe: (Fixture) -> MediaProbe): Fixture {
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
            space = space, probe = { uri -> probe(fixture).probe(uri) }, checkTimeoutMs = timeoutMs, checkGraceMs = graceMs,
            probeThreads = SavedProbeExecutor.newExecutor(),
            parallelDownloads = kotlinx.coroutines.flow.MutableStateFlow(1))
        fixture = Fixture(store, coordinator, mutableListOf())
        return fixture
    }

    private suspend fun Fixture.finish(id: String): DownloadProgress = coroutineScope {
        val event = async(start = CoroutineStart.UNDISPATCHED) { coordinator.completed.first { it.jobId == id } }
        store.enqueue(command(id))
        assertEquals(JobStage.COMPLETED, withTimeout(10_000) { coordinator.execute(id, {}, {}) }!!.stage)
        withTimeout(5000) { event.await() }
    }

    @Test fun slowProbeReleasesTransferSlotAndCompletionPrecedesItsLateWarning() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val probes = java.util.concurrent.atomic.AtomicInteger()
        fixture(timeoutMs = 4000, graceMs = 2000) { MediaProbe {
            if (probes.incrementAndGet() == 1) {
                entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); ProbeFacts(1, 0, 5000, 4)
            } else ProbeFacts(1, 1, 5000, 4)
        } }.use { f ->
            try {
                val first = async(Dispatchers.Default) { f.finish("late-problem") }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val next = f.finish("next-while-check-blocked") // the first probe is still held: completing proves the slot was freed
                assertEquals(ResultCheck.Verified(true, true), next.check)
                val done = withTimeout(10_000) { first.await() }
                assertNull(done.check)
                assertTrue(f.coordinator.warnings.value.isEmpty())
                release.countDown()
                val warning = withTimeout(10_000) { f.coordinator.warnings.first { it.any { w -> w.jobId == "late-problem" } } }.single()
                assertEquals(ResultCheck.Problem.NO_SOUND, warning.problem)
                assertTrue(warning.canRetry)
                assertEquals(JobStage.COMPLETED, f.store.get("late-problem")!!.stage)
            } finally { release.countDown() }
        }
    }

    @Test fun cancelAndRemoveDuringCommittedGraceStillDeliverOneCompletedAndNoCancellation() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        fixture(graceMs = 300) { MediaProbe { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS); ProbeFacts(1, 1, 5000, 4) } }.use { f ->
            val events = java.util.concurrent.CopyOnWriteArrayList<DownloadProgress>()
            val cancelled = java.util.concurrent.CopyOnWriteArrayList<DownloadProgress>()
            val callbacks = java.util.concurrent.CopyOnWriteArrayList<DownloadProgress>()
            val completedCollector = launch(start = CoroutineStart.UNDISPATCHED) { f.coordinator.completed.collect { events += it } }
            val cancelledCollector = launch(start = CoroutineStart.UNDISPATCHED) { f.coordinator.cancelled.collect { cancelled += it } }
            try {
                val id = "cancel-committed-grace"
                f.store.enqueue(command(id))
                val executing = async(Dispatchers.Default) {
                    f.coordinator.execute(id, { androidx.work.WorkInfo.STOP_REASON_NOT_STOPPED }, {}, {}, { callbacks += it })
                }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                // Probe admission happens only after commit and transfer/writer release, while its grace is pending.
                assertEquals(JobStage.COMPLETED, f.store.get(id)!!.stage)
                assertTrue("Cancel must interleave with the pending completion grace",
                    f.coordinator.progress.value.any { it.jobId == id && it.phase == DownloadPhase.SAVING })
                f.coordinator.cancel(id)
                f.coordinator.removeFailed(id)
                assertEquals(JobStage.COMPLETED, f.store.get(id)!!.stage)
                assertEquals(JobStage.COMPLETED, withTimeout(10_000) { executing.await() }!!.stage)
                release.countDown()
                withTimeout(10_000) { while (events.isEmpty()) delay(1) }
                yield()
                completedCollector.cancelAndJoin(); cancelledCollector.cancelAndJoin()
                assertEquals(listOf(DownloadPhase.COMPLETED), events.map { it.phase })
                assertEquals(listOf(DownloadPhase.COMPLETED), callbacks.map { it.phase })
                assertTrue(cancelled.isEmpty())
                assertNull(f.store.get(id)!!.sourceUrl)
            } finally {
                release.countDown(); completedCollector.cancelAndJoin(); cancelledCollector.cancelAndJoin()
            }
        }
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
        val slowRelease = java.util.concurrent.CountDownLatch(1)
        fixture(timeoutMs = 200) { MediaProbe { slowRelease.await(5, java.util.concurrent.TimeUnit.SECONDS); ProbeFacts(0, 0) } }.use { f ->
            val started = System.nanoTime()
            val done = f.finish("slow")
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5000)
            assertNull(done.check)
            assertEquals(JobStage.COMPLETED, f.store.get("slow")!!.stage)
            assertTrue(f.coordinator.warnings.value.isEmpty())
            // A cancel after the check changes nothing: the row stays COMPLETED.
            f.coordinator.cancel("slow")
            assertEquals(JobStage.COMPLETED, f.store.get("slow")!!.stage)
            slowRelease.countDown()
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
