package dev.ahmed.savlino.jobs

import android.net.Uri
import android.os.PowerManager
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.storage.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BatchFeederTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var jobs: JobStore
    private lateinit var batches: BatchStore
    private lateinit var cipher: LinkCipher
    private lateinit var engine: Engine
    private lateinit var actions: BatchJobActions
    private val limit = MutableStateFlow(2)
    private val heat = MutableStateFlow(PowerManager.THERMAL_STATUS_NONE)
    private var now = System.currentTimeMillis()
    @Before fun warmSQLite() {
        app.deleteDatabase(JobStore.DATABASE_NAME)
        jobs = JobStore(app); jobs.writableDatabase
        cipher = batchCipher()
        batches = BatchStore(jobs, cipher) { now }
        engine = Engine()
        actions = object : BatchJobActions {
            override suspend fun enqueue(command: DownloadCommand, preset: Preset) = jobs.enqueue(command, preset)
            override suspend fun pause(id: String) = jobs.pause(id)
            override suspend fun resume(id: String) = jobs.resume(id)
            override suspend fun cancel(id: String) { jobs.cancel(id) }
            override suspend fun retry(id: String) = jobs.retry(id)
        }
    }
    @After fun close() { jobs.close() }
    private fun feeder() = BatchFeeder(batches, engine, actions, limit, heat)
    private fun create(count: Int = 12, preset: Preset = Preset.BALANCED) {
        assertTrue(batches.create("batch", "Fixture", preset, batchItems(count)))
    }
    private fun active() = jobs.all().count { !it.stage.terminal && it.stage != JobStage.PAUSED }

    @Test fun feedsInOrderAtEveryLimitAndThermalSevereUsesOneSlotPlusOne() = runBlocking {
        create()
        val f = feeder()
        limit.value = 1; f.tick(); assertEquals(2, active())
        assertEquals(listOf("v0000000000", "v0000000001"), jobs.all().map { it.sourceUrl!!.substringAfter("v=") })
        limit.value = 2; f.tick(); assertEquals(3, active())
        limit.value = 3; f.tick(); assertEquals(4, active())
        // Finish the admitted jobs, then heat must prevent filling four slots again.
        jobs.all().forEach { jobs.cancel(it.jobId) }
        heat.value = PowerManager.THERMAL_STATUS_SEVERE
        f.tick(); assertEquals(2, active())
        assertEquals(listOf(4, 5), batches.items("batch").filter { it.state == BatchItemState.QUEUED }.map { it.position })
    }

    @Test fun individualPausedJobsDoNotCountButPauseAllStopsFeeding() = runBlocking {
        create()
        val f = feeder(); f.tick()
        jobs.pause(jobs.all().first().jobId)
        f.tick(); assertEquals(4, jobs.all().size); assertEquals(3, active())
        f.pause("batch"); f.tick()
        assertEquals(BatchState.PAUSED, batches.get("batch")!!.state)
        assertEquals(4, jobs.all().size)
        assertTrue(jobs.all().all { it.stage == JobStage.PAUSED })
        f.resume("batch"); f.tick()
        assertEquals(BatchState.ACTIVE, batches.get("batch")!!.state)
        // Previously admitted jobs resume; shrinking admission never cancels them.
        assertEquals(4, jobs.all().size)
    }

    @Test fun restartAdoptsAJobInsertedBeforeTheItemWasMarkedQueuedAndPrunedDoneItemsStayDone() = runBlocking {
        create(6)
        val first = batches.items("batch").first()
        val source = LinkRevision(LinkInput.parse(batches.source(first))!!, 0)
        val inspection = (engine.inspect(source, "fixture") as InspectResult.Success).inspection
        val command = (DownloadCommand.confirm(first.jobId, inspection, source, "18", "Fixture") as ConfirmResult.Confirmed).command
        jobs.enqueue(command, Preset.BALANCED) // Simulated death between enqueue and marking QUEUED.
        jobs.close()
        jobs = JobStore(app); jobs.writableDatabase
        batches = BatchStore(jobs, cipher) { now }
        val f = feeder(); f.tick()
        assertEquals(3, jobs.all().size)
        assertEquals(first.jobId, batches.items("batch").first().jobId)
        complete(first.jobId)
        feeder().tick()
        assertEquals(BatchItemState.DONE, batches.items("batch").first().state)
        jobs.writableDatabase.delete("jobs", "job_id=?", arrayOf(first.jobId))
        feeder().tick()
        assertEquals(BatchItemState.DONE, batches.items("batch").first().state)
        assertEquals(3, active())
    }

    @Test fun presetFallbackUsesOnlyReturnedFormatsAndRecordsTheFallbackWithoutInventingSize() = runBlocking {
        create(1)
        engine.formats = listOf(Format("22", "mp4", true, true, height = 1080))
        engine.presets = listOf(PresetChoice(Preset.SMALL, "22"))
        feeder().tick()
        assertEquals("22", jobs.all().single().format.id)
        assertEquals(Size.Unknown, jobs.all().single().format.size)
        assertTrue(batches.items("batch").single().fallback)
    }

    @Test fun audioFallsBackToMp3AndNoMatchingPresetIsSkipped() = runBlocking {
        create(2, Preset.AUDIO_M4A)
        engine.formats = listOf(Format("audio", "mp3", false, true))
        engine.presets = listOf(PresetChoice(Preset.AUDIO_MP3, "audio"))
        engine.failAfter = 1
        engine.failure = null // Subsequent inspection succeeds but has no presets.
        feeder().tick()
        assertEquals("mp3", jobs.all().single().format.container)
        assertEquals(listOf(BatchItemState.QUEUED, BatchItemState.SKIPPED), batches.items("batch").map { it.state })
        assertEquals(DownloadError.NoFormats, batches.items("batch").last().error)
    }

    @Test fun fiveNetworkFailuresPauseAndConnectivityRetriesOnlyFailedItems() = runBlocking {
        create(10)
        engine.failure = DownloadError.Offline
        val f = feeder(); f.tick()
        assertEquals(BatchState.WAITING_NETWORK, batches.get("batch")!!.state)
        assertEquals(5, batches.items("batch").count { it.state == BatchItemState.FAILED })
        assertEquals(5, batches.items("batch").count { it.state == BatchItemState.WAITING })
        engine.failure = null
        f.networkAvailable(); f.tick()
        assertEquals(BatchState.ACTIVE, batches.get("batch")!!.state)
        assertEquals(3, jobs.all().size)
        assertEquals(listOf(0, 1, 2), batches.items("batch").filter { it.state == BatchItemState.QUEUED }.map { it.position })
    }

    @Test fun threeRefusalsPauseUntilExplicitResume() = runBlocking {
        create(10)
        engine.failure = DownloadError.SourceRefused
        val f = feeder(); f.tick()
        assertEquals(BatchState.PAUSED, batches.get("batch")!!.state)
        assertEquals(3, batches.items("batch").count { it.state == BatchItemState.FAILED })
        f.networkAvailable(); assertEquals(BatchState.PAUSED, batches.get("batch")!!.state)
        engine.failure = null; f.resume("batch"); f.tick()
        assertEquals(3, active())
        assertEquals(listOf(3, 4, 5), batches.items("batch").filter { it.state == BatchItemState.QUEUED }.map { it.position })
    }

    @Test fun transferFailuresAlsoTripTheBreakerAndInsufficientSpacePausesImmediately() = runBlocking {
        create(10)
        val f = feeder(); f.tick()
        val ids = jobs.all().map { it.jobId }
        ids.forEach { jobs.fail(it, DownloadError.SourceRefused) }
        f.tick()
        assertEquals(BatchState.PAUSED, batches.get("batch")!!.state)
        assertEquals(3, jobs.all().size)
        f.resume("batch"); f.tick()
        jobs.all().first { !it.stage.terminal }.let { jobs.fail(it.jobId, DownloadError.InsufficientSpace) }
        f.tick()
        assertEquals(BatchState.PAUSED, batches.get("batch")!!.state)
        assertEquals(DownloadError.InsufficientSpace, batches.get("batch")!!.error)
        assertTrue(jobs.all().filter { !it.stage.terminal }.all { it.stage == JobStage.PAUSED })
    }

    @Test fun stopCancelsLiveJobsSkipsWaitingAndKeepsCompletedFiles() = runBlocking {
        create()
        val f = feeder(); f.tick()
        val first = jobs.all().first().jobId
        val saved = File(app.cacheDir, "saved-fixture.mp4").apply { writeText("saved bytes") }
        complete(first, Uri.fromFile(saved))
        f.tick(); f.stop("batch"); f.tick()
        assertEquals(JobStage.COMPLETED, jobs.get(first)!!.stage)
        assertEquals("saved bytes", saved.readText())
        assertEquals(BatchState.STOPPED, batches.get("batch")!!.state)
        assertTrue(jobs.all().filter { it.jobId != first }.all { it.stage == JobStage.CANCELLED })
        assertFalse(batches.items("batch").any { it.state == BatchItemState.WAITING })
        assertTrue(batches.items("batch").all { batches.source(it) == null })
    }

    @Test fun retryUsesTheSameFailedJobWithinLookahead() = runBlocking {
        create(5)
        val f = feeder(); f.tick()
        val id = jobs.all().first().jobId
        jobs.fail(id, DownloadError.Network); f.tick()
        f.retry("batch")
        // Retrying respects lookahead, so free a slot before its existing job is admitted again.
        jobs.cancel(jobs.all().last().jobId)
        f.tick()
        assertEquals(JobStage.ENQUEUED, jobs.get(id)!!.stage)
        assertEquals(BatchItemState.QUEUED, batches.items("batch").first().state)
        assertEquals(4, jobs.all().size)
    }

    @Test fun expiryDuringInspectionNeverCreatesAFreshJobBeyondTheBatchDeadline() = runBlocking {
        create(3)
        engine.duringInspect = { now += 86_400_001 }
        feeder().tick()
        assertTrue(jobs.all().isEmpty())
        assertEquals(BatchState.EXPIRED, batches.get("batch")!!.state)
        assertTrue(batches.items("batch").all { batches.source(it) == null })
    }

    @Test fun transferNetworkFailuresAccumulateAcrossLookaheadRefills() = runBlocking {
        create(12)
        limit.value = 1
        val f = feeder(); f.tick()
        repeat(2) {
            jobs.all().filter { !it.stage.terminal }.forEach { jobs.fail(it.jobId, DownloadError.Network) }
            f.tick()
            assertEquals(BatchState.ACTIVE, batches.get("batch")!!.state)
        }
        jobs.all().first { !it.stage.terminal }.let { jobs.fail(it.jobId, DownloadError.Offline) }
        f.tick()
        assertEquals(BatchState.WAITING_NETWORK, batches.get("batch")!!.state)
        assertEquals(6, jobs.all().size)
        assertTrue(jobs.all().filter { !it.stage.terminal }.all { it.stage == JobStage.PAUSED })
    }

    @Test fun stopPersistedBeforeCancellationIsReplayedAfterRestart() = runBlocking {
        create(6)
        feeder().tick()
        batches.stop("batch") // Simulated death before invoking coordinator cancellation.
        jobs.close()
        jobs = JobStore(app); jobs.writableDatabase
        batches = BatchStore(jobs, cipher) { now }
        feeder().tick()
        assertTrue(jobs.all().all { it.stage == JobStage.CANCELLED })
        assertFalse(batches.items("batch").any { it.state == BatchItemState.WAITING })
    }

    @Test fun retryNeverReadmitsAllFailedJobsBeyondLookahead() = runBlocking {
        create(6)
        val f = feeder(); f.tick()
        jobs.all().forEach { jobs.fail(it.jobId, DownloadError.Network) }
        f.tick()
        jobs.all().filter { !it.stage.terminal }.forEach { jobs.fail(it.jobId, DownloadError.Network) }
        f.tick()
        assertEquals(BatchState.COMPLETED, batches.get("batch")!!.state)
        f.retry("batch"); f.tick()
        assertEquals(3, active())
        assertEquals(3, batches.items("batch").count { it.state == BatchItemState.WAITING })
        assertEquals(6, jobs.all().size)
    }

    @Test fun bestSmallAndAudioPreferTheirOwnReturnedPreset() = runBlocking {
        val cases = listOf(Preset.BEST to Format("best", "mp4", true, true),
            Preset.SMALL to Format("small", "mp4", true, true), Preset.AUDIO_M4A to Format("m4a-row", "m4a", false, true))
        val f = feeder()
        for ((index, pair) in cases.withIndex()) {
            val (preset, format) = pair
            val id = "preset-$index"
            batches.create(id, "Fixture", preset, batchItems(1))
            engine.formats = listOf(format)
            engine.presets = listOf(PresetChoice(preset, format.id))
            f.tick()
            val item = batches.items(id).single()
            assertEquals(format, jobs.get(item.jobId)!!.format)
            assertFalse(item.fallback)
            jobs.cancel(item.jobId); f.tick()
        }
    }

    @Test fun unavailableBetweenListingAndInspectionIsSkippedAndNotRetried() = runBlocking {
        create(1)
        engine.failure = DownloadError.Unavailable
        val f = feeder(); f.tick()
        assertEquals(BatchItemState.SKIPPED, batches.items("batch").single().state)
        assertEquals(DownloadError.Unavailable, batches.items("batch").single().error)
        f.retry("batch"); f.tick()
        assertTrue(jobs.all().isEmpty())
        assertNull(batches.source(batches.items("batch").single()))
    }

    @Test fun aCompletedItemResetsTheFailureStreak() = runBlocking {
        create(12)
        limit.value = 1
        val f = feeder(); f.tick()
        jobs.fail(jobs.all().first().jobId, DownloadError.Network)
        f.tick()
        complete(jobs.all().first { !it.stage.terminal }.jobId)
        f.tick()
        repeat(2) {
            jobs.all().filter { !it.stage.terminal }.forEach { jobs.fail(it.jobId, DownloadError.Network) }
            f.tick()
        }
        assertEquals(BatchState.ACTIVE, batches.get("batch")!!.state)
        assertEquals(5, batches.items("batch").count { it.state == BatchItemState.FAILED })
    }

    @Test fun thermalChangeDuringInspectionDoesNotFillTheOldLookaheadWindow() = runBlocking {
        create(6)
        limit.value = 3
        engine.duringInspect = { if (engine.calls == 2) heat.value = PowerManager.THERMAL_STATUS_SEVERE }
        feeder().tick()
        assertEquals(2, active())
        assertEquals(BatchItemState.WAITING, batches.items("batch")[2].state)
    }

    @Test fun pauseAndStopPersistAndCancelWithoutWaitingForInspection() = runBlocking {
        for (stop in listOf(false, true)) {
            val id = if (stop) "stop-resolve" else "pause-resolve"
            assertTrue(batches.create(id, "Fixture", Preset.BALANCED, batchItems(2)))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val item = batches.items(id).first()
            engine.duringInspect = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            engine.onCancel = { operation ->
                assertEquals("resolve-${item.jobId}", operation)
                cancelled.complete(Unit)
            }
            val f = feeder()
            val ticking = launch { f.tick() }
            try {
                withTimeout(2_000) { entered.await() }
                withTimeout(2_000) { if (stop) f.stop(id) else f.pause(id); cancelled.await() }
                yield() // A cancelled inspector that is slow to exit must not reclaim the controls gate.
                withTimeout(2_000) { f.networkAvailable() }
                assertEquals(if (stop) BatchState.STOPPED else BatchState.PAUSED, batches.get(id)!!.state)
                assertTrue(jobs.all().isEmpty())
                release.complete(Unit) // Even a late successful result cannot enqueue.
                withTimeout(2_000) { ticking.join() }
                assertTrue(jobs.all().isEmpty())
                assertTrue(batches.items(id).all { it.state == if (stop) BatchItemState.SKIPPED else BatchItemState.WAITING })
            } finally { release.complete(Unit); ticking.cancelAndJoin() }
        }
    }

    @Test fun pauseCancelsTheResolveCoroutineAndKeepsTheItemWaiting() = runBlocking {
        create(2)
        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        engine.duringInspect = {
            entered.complete(Unit)
            try { awaitCancellation() } finally { exited.complete(Unit) }
        }
        val f = feeder()
        val ticking = launch { f.tick() }
        try {
            withTimeout(2_000) { entered.await(); f.pause("batch"); exited.await(); ticking.join() }
            assertEquals(BatchState.PAUSED, batches.get("batch")!!.state)
            assertTrue(batches.items("batch").all { it.state == BatchItemState.WAITING })
            assertTrue(jobs.all().isEmpty())
        } finally { ticking.cancelAndJoin() }
    }

    @Test fun connectivityControlDoesNotWaitBehindAnotherBatchesInspection() = runBlocking {
        create(2)
        batches.create("network", "Fixture", Preset.BALANCED, batchItems(2))
        batches.state("network", BatchState.WAITING_NETWORK)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        engine.duringInspect = { entered.complete(Unit); release.await() }
        val f = feeder()
        val ticking = launch { f.tick() }
        try {
            withTimeout(2_000) { entered.await(); f.networkAvailable() }
            assertEquals(BatchState.ACTIVE, batches.get("network")!!.state)
        } finally { ticking.cancelAndJoin() }
    }

    @Test fun generalInspectionFailuresPauseAtFiveAndResumeRetriesThoseItems() = runBlocking {
        for ((index, error) in listOf(DownloadError.EngineUnavailable, DownloadError.Failed, DownloadError.ExecutionRestricted).withIndex()) {
            val id = "breaker-$index"
            batches.create(id, "Fixture", Preset.BALANCED, batchItems(10))
            engine.failure = error
            if (error == DownloadError.Failed) engine.duringInspect = { throw IllegalStateException() }
            val f = feeder(); f.tick()
            assertEquals(BatchState.PAUSED, batches.get(id)!!.state)
            assertEquals(5, batches.items(id).count { it.state == BatchItemState.FAILED })
            assertEquals(5, batches.items(id).count { it.state == BatchItemState.WAITING })
            engine.failure = null
            engine.duringInspect = {}
            f.resume(id); f.tick()
            assertEquals(listOf(0, 1, 2), batches.items(id).filter { it.state == BatchItemState.QUEUED }.map { it.position })
            assertEquals(2, batches.items(id).count { it.state == BatchItemState.WAITING && it.retryable })
            f.stop(id)
        }
    }

    @Test fun generalEnqueueFailuresAlsoPauseAtFive() = runBlocking {
        create(10)
        actions = object : BatchJobActions by actions {
            override suspend fun enqueue(command: DownloadCommand, preset: Preset) = false
        }
        feeder().tick()
        assertTrue(jobs.all().isEmpty())
        assertEquals(BatchState.PAUSED, batches.get("batch")!!.state)
        assertEquals(5, batches.items("batch").count { it.state == BatchItemState.FAILED })
        assertEquals(5, batches.items("batch").count { it.state == BatchItemState.WAITING })
    }

    @Test fun expiredUnreconciledJobIsAdoptedAfterStartupScrub() = runBlocking {
        create(1)
        val item = batches.items("batch").single()
        val source = LinkRevision(LinkInput.parse(batches.source(item))!!, 0)
        val inspection = (engine.inspect(source, "fixture") as InspectResult.Success).inspection
        val command = (DownloadCommand.confirm(item.jobId, inspection, source, "18", "Fixture") as ConfirmResult.Confirmed).command
        jobs.enqueue(command, Preset.BALANCED)
        now += 86_400_001
        batches.scrubExpired() // Startup runs this before the feeder's first reconcile.
        feeder().tick()
        assertEquals(BatchItemState.QUEUED, batches.items("batch").single().state)
        assertEquals(JobStage.ENQUEUED, jobs.get(item.jobId)!!.stage)
        assertEquals(BatchState.ACTIVE, batches.get("batch")!!.state)
    }

    @Test fun removingCompletedJobBeforeReconcileKeepsTheItemDone() = runBlocking {
        create(1)
        feeder().tick()
        val item = batches.items("batch").single()
        complete(item.jobId)
        assertTrue(jobs.remove(item.jobId))
        feeder().tick()
        assertNull(jobs.get(item.jobId))
        assertEquals(BatchItemState.DONE, batches.items("batch").single().state)
        assertEquals(BatchState.COMPLETED, batches.get("batch")!!.state)
    }

    @Test fun completionRememberedByRemovalOrPruningResetsTheFailureStreak() = runBlocking {
        for (remove in listOf(false, true)) {
            val id = if (remove) "remove-completion" else "prune-completion"
            batches.create(id, "Fixture", Preset.BALANCED, batchItems(9))
            val f = feeder(); f.tick()
            batches.items(id).takeLast(4).forEach { batches.finish(it, BatchItemState.FAILED, DownloadError.Network, true) }
            val first = batches.items(id).first().jobId
            complete(first)
            if (remove) assertTrue(jobs.remove(first)) else jobs.pruneTerminal()
            jobs.fail(batches.items(id)[1].jobId, DownloadError.Network)
            f.tick()
            assertEquals(BatchState.ACTIVE, batches.get(id)!!.state)
            assertEquals(5, batches.items(id).count { it.state == BatchItemState.FAILED })
            f.stop(id)
        }
    }

    @Test fun pruningCompletedJobBeforeReconcileKeepsTheItemDone() = runBlocking {
        create(1)
        feeder().tick()
        val item = batches.items("batch").single()
        complete(item.jobId)
        jobs.writableDatabase.execSQL("UPDATE jobs SET updated_at=0 WHERE job_id=?", arrayOf(item.jobId))
        val source = LinkRevision(LinkInput.parse("https://youtube.com/watch?v=abcdefghijk")!!, 0)
        val format = Format("18", "mp4", true, true)
        repeat(100) { index ->
            val command = (DownloadCommand.confirm("prune-$index", Inspection(source, "fixture", listOf(format)), source, "18", "Fixture") as ConfirmResult.Confirmed).command
            jobs.enqueue(command); jobs.cancel(command.jobId)
        }
        jobs.pruneTerminal()
        feeder().tick()
        assertNull(jobs.get(item.jobId))
        assertEquals(BatchItemState.DONE, batches.items("batch").single().state)
    }

    @Test fun individuallyRetriedFailureBecomesDoneWhenItsJobCompletes() = runBlocking {
        create(1)
        val f = feeder(); f.tick()
        val item = batches.items("batch").single()
        jobs.fail(item.jobId, DownloadError.Network); f.tick()
        assertEquals(BatchItemState.FAILED, batches.items("batch").single().state)
        assertTrue(jobs.retry(item.jobId))
        complete(item.jobId); f.tick()
        assertEquals(BatchItemState.DONE, batches.items("batch").single().state)
        assertEquals(BatchState.COMPLETED, batches.get("batch")!!.state)
    }

    private fun links(vararg urls: String) = urls.map { LinkInput.parse(it)!! }

    @Test fun linkBatchFeedsAnyHostAYouTubeListAsItsFirstVideoAndSkipsAPhotoPostWithItsReason() = runBlocking {
        assertTrue(batches.createLinks("links", "3 links", links("https://www.tiktok.com/@a/video/1",
            "https://www.youtube.com/watch?v=abcdefghijk&list=PL0123456789", "https://www.instagram.com/p/photos/"), Preset.BALANCED))
        engine.failFor = { if (it.link.host == Host.INSTAGRAM) DownloadError.NoVideo else null }
        limit.value = 3; feeder().tick()
        assertEquals(listOf(BatchItemState.QUEUED, BatchItemState.QUEUED, BatchItemState.SKIPPED), batches.items("links").map { it.state })
        assertEquals(DownloadError.PhotoPost, batches.items("links").last().error)
        assertEquals(2, jobs.all().size)
    }

    @Test fun aFailedLinkInALinkBatchRetriesTheSameItem() = runBlocking {
        assertTrue(batches.createLinks("links", "2 links", links("https://www.tiktok.com/@a/video/1", "https://x.com/a/status/2"), Preset.BALANCED))
        engine.failFor = { if (it.link.host == Host.X) DownloadError.Network else null }
        val f = feeder(); f.tick()
        val failed = batches.items("links").last()
        assertEquals(BatchItemState.FAILED, failed.state); assertTrue(failed.retryable)
        engine.failFor = { null }
        f.retry("links"); f.tick()
        assertEquals(BatchItemState.QUEUED, batches.items("links").last().state)
        assertEquals(failed.jobId, batches.items("links").last().jobId)
        assertEquals(2, jobs.all().size)
    }

    private fun complete(id: String, uri: Uri = Uri.parse("content://media/external/downloads/$id")) {
        val pending = PendingPublication(uri, "fixture.mp4")
        jobs.startTransfer(id); jobs.markStaged(id); jobs.recordPending(id, pending); jobs.markPublishing(id)
        assertTrue(jobs.complete(id, PublishedFile(pending.uri, pending.displayName)))
    }
    private class Engine : EngineAdapter {
        override val isAvailable = true
        var formats = listOf(Format("18", "mp4", true, true, height = 720))
        var presets = listOf(PresetChoice(Preset.BALANCED, "18"))
        var failure: DownloadError? = null
        var failFor: (LinkRevision) -> DownloadError? = { null }
        var failAfter: Int? = null
        var calls = 0
        var duringInspect: suspend () -> Unit = {}
        var onCancel: (String) -> Unit = {}
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            duringInspect()
            calls++
            (failure ?: failFor(source))?.let { return InspectResult.Failure(it) }
            return InspectResult.Success(Inspection(source, "fixture", formats,
                if (failAfter != null && calls > failAfter!!) emptyList() else presets))
        }
        override suspend fun download(command: DownloadCommand, directory: File, progress: (EngineEvent) -> Unit): TransferResult = error("No transfers in feeder tests")
        override fun cancel(operationId: String) = onCancel(operationId)
    }
}
