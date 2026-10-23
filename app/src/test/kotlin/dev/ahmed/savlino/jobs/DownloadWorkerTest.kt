package dev.ahmed.savlino.jobs

import android.content.*
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 35], manifest = Config.NONE)
class DownloadWorkerTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun deniedNotificationsStillPublishOnceScrubUrlAndReportRealStages() = runBlocking {
        shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val stages = mutableListOf<DownloadPhase>()
        fixture(FakeEngine(steps = listOf(
            FakeEngine.Step.Emit(EngineEvent.Progress(2, Size.Exact(4))),
            FakeEngine.Step.Emit(EngineEvent.Merging),
            FakeEngine.Step.Output("output.mp4", byteArrayOf(1, 2, 3, 4))
        ))).use { f ->
            val worker = f.worker("success")
            f.store.enqueue(command("success"))
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, worker.getForegroundInfo().foregroundServiceType)
            val result = f.coordinator.execute("success", {}, { stages += it.phase })
            assertEquals(JobStage.COMPLETED, result!!.stage)
            assertNull(result.sourceUrl)
            assertEquals("exact/source", f.engine.commands.single().format.sourceFormatId)
            assertEquals("engine-42", f.engine.commands.single().engineVersion)
            assertEquals(listOf(DownloadPhase.PREPARING, DownloadPhase.DOWNLOADING, DownloadPhase.JOINING,
                DownloadPhase.SAVING, DownloadPhase.COMPLETED), stages)
            assertFalse(File(app.filesDir, "downloads/success").exists())
            assertEquals(1, f.provider.rows.size)
            val row = f.provider.rows.values.single()
            assertEquals(0, row.getAsInteger(MediaStore.Downloads.IS_PENDING))
            assertEquals("Download/Nezzel/", row.getAsString(MediaStore.Downloads.RELATIVE_PATH))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), f.provider.file.readBytes())
        }
    }

    @Test fun activeRowsStayNewestFirstWhenAnOlderJobUpdates() = runBlocking {
        fixture(FakeEngine(steps = listOf(FakeEngine.Step.Fail(DownloadError.Network)))).use { f ->
            f.store.enqueue(command("older")); f.store.enqueue(command("newer"))
            f.store.writableDatabase.execSQL("UPDATE jobs SET created_at = ? WHERE job_id = ?",
                arrayOf<Any>(System.currentTimeMillis() - 1000, "older"))
            f.coordinator.initialize()
            f.coordinator.execute("older", {}, {})
            f.coordinator.execute("older", {}, {}) // Exhaust the single silent resume before checking failed-row ordering.
            assertEquals(listOf("newer", "older"), dev.ahmed.savlino.downloads.visibleJobs(f.coordinator.progress.value).map { it.jobId })
        }
    }

    @Test fun completionEventIsNewOnlyAndUpdatingAnotherJobKeepsFailedRows() = runBlocking {
        fixture(FakeEngine(steps = listOf(FakeEngine.Step.Output("output.mp4", byteArrayOf(1, 2, 3, 4))))).use { f ->
            f.store.enqueue(command("failed-first")); f.store.fail("failed-first", DownloadError.Network)
            f.coordinator.initialize()
            val received = kotlinx.coroutines.channels.Channel<DownloadProgress>(1)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { f.coordinator.completed.collect { received.send(it) } }
            try {
                f.store.enqueue(command("finished-next"))
                f.coordinator.execute("finished-next", {}, {})
                val finished = withTimeout(5000) { received.receive() }
                assertEquals("finished-next", finished.jobId)
                assertEquals(DownloadPhase.COMPLETED, finished.phase)
                assertNotNull(finished.publishedUri)
                assertTrue(f.coordinator.progress.value.any { it.jobId == "failed-first" && it.phase == DownloadPhase.FAILED })
                assertTrue(f.coordinator.completed.replayCache.isEmpty())
                assertFalse(f.coordinator.progress.value.any { it.jobId == "finished-next" })
            } finally { collector.cancelAndJoin() }
        }
    }

    @Test fun actualWorkerCompletesWithNotificationsDeniedAndReportsStages() = runBlocking {
        shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        fixture(FakeEngine(steps = listOf(
            FakeEngine.Step.Emit(EngineEvent.Progress(2, Size.Exact(4))),
            FakeEngine.Step.Emit(EngineEvent.Merging),
            FakeEngine.Step.Output("output.mp4", byteArrayOf(1, 2, 3, 4))
        ))).use { f ->
            f.store.enqueue(command("denied"))
            assertEquals(ListenableWorker.Result.success(), f.worker("denied").doWork())
            assertEquals(JobStage.COMPLETED, f.store.get("denied")!!.stage)
            assertNull(f.store.get("denied")!!.sourceUrl)
            assertEquals(listOf("PREPARING", "DOWNLOADING", "JOINING", "SAVING", "COMPLETED"), f.phases)
            assertEquals(1, f.provider.rows.size)
        }
    }

    @Test fun workerWaitsForStartupRecoveryBeforeStartingTransfer() = runBlocking {
        fixture(FakeEngine(steps = listOf(FakeEngine.Step.Output("output.mp4", byteArrayOf(1, 2, 3, 4))))).use { f ->
            f.store.enqueue(command("old")); f.store.startTransfer("old")
            File(app.filesDir, "downloads/old").apply { mkdirs(); resolve("partial").writeText("bytes") }
            f.store.enqueue(command("new"))
            f.provider.onOpen = {
                assertEquals(JobStage.ENQUEUED, f.store.get("old")!!.stage)
                assertNull(f.store.get("old")!!.error)
                assertFalse(f.store.get("old")!!.hasPartial)
                assertFalse(File(app.filesDir, "downloads/old").exists())
            }
            assertEquals(ListenableWorker.Result.success(), f.worker("new").doWork())
            assertEquals(JobStage.COMPLETED, f.store.get("new")!!.stage)
        }
    }

    @Test fun unknownSizeWithAmpleSpaceTransfersWithoutInventingTotal() = runBlocking {
        fixture(FakeEngine(steps = listOf(FakeEngine.Step.Output("output.mp4", byteArrayOf(1, 2, 3, 4)))),
            freeBytes = 1_140_850_688L).use { f ->
            f.store.enqueue(command("unknown", Size.Unknown))
            assertEquals(ListenableWorker.Result.success(), f.worker("unknown").doWork())
            assertEquals(JobStage.COMPLETED, f.store.get("unknown")!!.stage)
            assertEquals(Size.Unknown, f.engine.commands.single().format.size)
        }
    }

    @Test fun unknownReserveRejectsOneByteBelow1GiBPlusMargin() = runBlocking {
        fixture(FakeEngine(), freeBytes = 1_140_850_687L).use { f ->
            f.store.enqueue(command("unknown-reserve", Size.Unknown))
            assertEquals(ListenableWorker.Result.failure(), f.worker("unknown-reserve").doWork())
            assertEquals(DownloadError.InsufficientSpace, f.store.get("unknown-reserve")!!.error)
            assertEquals(0, f.attempts.get())
            assertFalse(File(app.filesDir, "downloads/unknown-reserve").exists())
        }
    }

    @Test fun estimatedBudgetIncludes25PercentAndMargin() = runBlocking {
        // 100 MiB estimate: aggregate 125 MiB reserve + 64 MiB margin = 189 MiB.
        fixture(FakeEngine(steps = listOf(FakeEngine.Step.Output("output.mp4", byteArrayOf(1, 2, 3, 4)))),
            freeBytes = 198_180_864L).use { f ->
            f.store.enqueue(command("estimate-fits", Size.Estimated(104_857_600L)))
            assertEquals(ListenableWorker.Result.success(), f.worker("estimate-fits").doWork())
            assertEquals(Size.Estimated(104_857_600L), f.engine.commands.single().format.size)
        }
    }

    @Test fun estimatedBudgetRejectsBelow25PercentAndMargin() = runBlocking {
        fixture(FakeEngine(), freeBytes = 198_180_863L).use { f ->
            f.store.enqueue(command("estimate-full", Size.Estimated(104_857_600L)))
            assertEquals(ListenableWorker.Result.failure(), f.worker("estimate-full").doWork())
            assertEquals(DownloadError.InsufficientSpace, f.store.get("estimate-full")!!.error)
            assertTrue(f.engine.commands.isEmpty())
        }
    }

    @Test fun unknownStagingEnospcFailsWithSpaceErrorAndCleansPartial() = runBlocking {
        fixture(FakeEngine(), downloadFailure = java.io.IOException("write failed: ENOSPC (No space left on device)")).use { f ->
            f.store.enqueue(command("unknown-full", Size.Unknown))
            f.worker("unknown-full").doWork()
            assertEquals(1, f.attempts.get())
            assertEquals(DownloadError.InsufficientSpace, f.store.get("unknown-full")!!.error)
            assertNotNull(f.store.get("unknown-full")!!.sourceUrl)
            assertFalse(File(app.filesDir, "downloads/unknown-full").exists())
            assertTrue(f.provider.rows.isEmpty())
        }
    }

    @Test fun expiredQueueInLiveProcessFailsWithoutEngineAndPostsFailure() = runBlocking {
        fixture(FakeEngine(steps = listOf(FakeEngine.Step.Output("output.mp4", byteArrayOf(1, 2, 3, 4))))).use { f ->
            shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            f.coordinator.initialize()
            f.store.enqueue(command("expired-live"))
            // Age the queued row after startup so startup recovery cannot enforce this expiry.
            f.store.writableDatabase.execSQL("UPDATE jobs SET created_at = ? WHERE job_id = ?",
                arrayOf<Any>(System.currentTimeMillis() - 86_400_001L, "expired-live"))
            val staging = File(app.filesDir, "downloads/expired-live").apply {
                mkdirs(); resolve("partial.mp4").writeBytes(byteArrayOf(1))
            }
            assertEquals(ListenableWorker.Result.failure(), f.worker("expired-live").doWork())
            val row = f.store.get("expired-live")!!
            assertEquals(JobStage.FAILED, row.stage)
            assertEquals(DownloadError.FormatExpired, row.error)
            assertNull(row.sourceUrl)
            assertFalse(row.retryable)
            assertFalse(staging.exists())
            assertEquals(0, f.attempts.get())
            assertTrue(f.provider.rows.isEmpty())
            assertEquals(DownloadError.FormatExpired, f.coordinator.progress.value.single().error)
            val notification = resultNotification()
            assertEquals(app.getString(dev.ahmed.savlino.R.string.download_expired_error),
                notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        }
    }

    @Test fun cancellationWinsExpiredQueueInLiveProcessBeforeAnyTransfer() = runBlocking {
        fixture(FakeEngine()).use { f ->
            f.coordinator.initialize()
            f.store.enqueue(command("expired-cancel"))
            f.store.writableDatabase.execSQL("UPDATE jobs SET created_at = ? WHERE job_id = ?",
                arrayOf<Any>(System.currentTimeMillis() - 86_400_001L, "expired-cancel"))
            f.coordinator.requestCancellation("expired-cancel")
            assertEquals(ListenableWorker.Result.success(), f.worker("expired-cancel").doWork())
            val row = f.store.get("expired-cancel")!!
            assertEquals(JobStage.CANCELLED, row.stage)
            assertEquals(DownloadError.Cancelled, row.error)
            assertNull(row.sourceUrl)
            assertEquals(0, f.attempts.get())
            assertTrue(f.provider.rows.isEmpty())
        }
    }

    @Test fun publicationEnospcKeepsSpaceErrorAbandonsPendingAndCleansStaging() = runBlocking {
        fixture(FakeEngine(steps = listOf(FakeEngine.Step.Output("output.mp4", byteArrayOf(1, 2, 3, 4))))).use { f ->
            shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            f.store.enqueue(command("publish-full"))
            f.provider.onOpen = {
                assertEquals(JobStage.PENDING_INSERTED, f.store.get("publish-full")!!.stage)
                assertEquals(1, f.provider.rows.values.single().getAsInteger(MediaStore.Downloads.IS_PENDING))
                throw java.io.IOException("write failed", android.system.ErrnoException("write", android.system.OsConstants.ENOSPC))
            }
            assertEquals(ListenableWorker.Result.failure(), f.worker("publish-full").doWork())
            val row = f.store.get("publish-full")!!
            assertEquals(JobStage.FAILED, row.stage)
            assertEquals(DownloadError.InsufficientSpace, row.error)
            assertNotNull(row.sourceUrl)
            assertNull(row.pendingUri)
            assertTrue(row.retryable)
            assertEquals(1, f.attempts.get())
            assertFalse(File(app.filesDir, "downloads/publish-full").exists())
            assertTrue(f.provider.rows.isEmpty())
            assertFalse(f.provider.file.exists())
            val notification = resultNotification()
            assertEquals(app.getString(dev.ahmed.savlino.R.string.download_space_error),
                notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        }
    }

    @Test fun arabicNotificationsUseLatinDigitsAndUnknownNeverGetsPercent() {
        val config = android.content.res.Configuration(app.resources.configuration)
        config.setLocale(java.util.Locale.forLanguageTag("ar-u-nu-latn"))
        val context = app.createConfigurationContext(config)
        val measured = DownloadProgress("digits", "clip.mp4", DownloadPhase.DOWNLOADING, 2, Size.Exact(4))
        assertEquals(50, measured.percent)
        val text = DownloadNotifications.text(context, measured)
        assertTrue(text.contains("50%"))
        assertFalse(text.any { it in '\u0660'..'\u0669' || it in '\u06f0'..'\u06f9' })
        assertNull(measured.copy(total = Size.Unknown).percent)
        assertNull(measured.copy(total = Size.Estimated(4)).percent)
    }

    @Test fun workerFailureKeepsTypedErrorAndRemovesStaging() = runBlocking {
        fixture(FakeEngine(steps = listOf(FakeEngine.Step.Fail(DownloadError.Network)))).use { f ->
            f.store.enqueue(command("failure"))
            assertEquals(ListenableWorker.Result.retry(), f.worker("failure").doWork())
            assertEquals(JobStage.ENQUEUED, f.store.get("failure")!!.stage)
            assertEquals(ListenableWorker.Result.failure(), f.worker("failure").doWork())
            assertEquals(JobStage.FAILED, f.store.get("failure")!!.stage)
            assertEquals(DownloadError.Network, f.store.get("failure")!!.error)
            assertFalse(File(app.filesDir, "downloads/failure").exists())
            assertTrue(f.provider.rows.isEmpty())
        }
    }

    @Test fun cancelWhileEngineWaitsStopsItAndCleansJournalAndStaging() = runBlocking {
        fixture(FakeEngine(steps = listOf(FakeEngine.Step.Emit(EngineEvent.Merging), FakeEngine.Step.AwaitCancellation))).use { f ->
            f.store.enqueue(command("cancel"))
            val running = async(Dispatchers.Default) { f.worker("cancel").doWork() }
            withTimeout(5000) { while (f.engine.commands.isEmpty()) yield() }
            f.coordinator.cancel("cancel")
            withTimeout(5000) { running.await() }
            assertEquals(JobStage.CANCELLED, f.store.get("cancel")!!.stage)
            assertNull(f.store.get("cancel")!!.sourceUrl)
            assertFalse(File(app.filesDir, "downloads/cancel").exists())
            assertTrue(f.provider.rows.isEmpty())
        }
    }

    @Test fun cancelDuringPublicationDeletesPendingRowAndStaging() = runBlocking {
        fixture(FakeEngine(steps = listOf(FakeEngine.Step.Output("output.mp4", byteArrayOf(1, 2, 3, 4))))).use { f ->
            f.store.enqueue(command("copy-cancel"))
            f.provider.onOpen = { f.coordinator.requestCancellation("copy-cancel") }
            f.worker("copy-cancel").doWork()
            assertEquals(JobStage.CANCELLED, f.store.get("copy-cancel")!!.stage)
            assertTrue(f.provider.rows.isEmpty())
            assertFalse(File(app.filesDir, "downloads/copy-cancel").exists())
        }
    }

    @Test
    @Config(manifest = "AndroidManifest.xml")
    fun notificationPendingIntentCancelsTheActualApplicationJob() = runBlocking {
        fixture(FakeEngine(steps = listOf(FakeEngine.Step.AwaitCancellation))).use { f ->
            f.store.enqueue(command("notification-cancel"))
            val running = async(Dispatchers.Default) { f.worker("notification-cancel").doWork() }
            withTimeout(5000) { while (f.engine.commands.isEmpty()) yield() }
            val receiver = DownloadCancelReceiver()
            app.registerReceiver(receiver, IntentFilter(DownloadNotifications.CANCEL), Context.RECEIVER_NOT_EXPORTED)
            try {
                val info = DownloadNotifications(app).foreground(f.coordinator.progress.value.single())
                info.notification.actions.single().actionIntent.send()
                shadowOf(android.os.Looper.getMainLooper()).idle()
                withTimeout(5000) { running.await() }
            } finally { app.unregisterReceiver(receiver) }
            assertEquals(JobStage.CANCELLED, f.store.get("notification-cancel")!!.stage)
            assertFalse(File(app.filesDir, "downloads/notification-cancel").exists())
        }
    }

    @Test fun systemStopDuringPublicationIsInterruptedAndCleansPendingRow() = runBlocking {
        fixture(FakeEngine(steps = listOf(FakeEngine.Step.Output("output.mp4", byteArrayOf(1, 2, 3, 4))))).use { f ->
            shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            f.store.enqueue(command("copy-stop"))
            val opened = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            f.provider.onOpen = { opened.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            val worker = f.worker("copy-stop")
            val running = launch(Dispatchers.Default) { worker.doWork() }
            try {
                assertTrue(withContext(Dispatchers.IO) { opened.await(5, java.util.concurrent.TimeUnit.SECONDS) })
                worker.stop(WorkInfo.STOP_REASON_UNKNOWN)
                running.cancel()
                withTimeout(5000) { while (!f.coordinator.stopRequested("copy-stop")) yield() }
            } finally { release.countDown() }
            running.join()
            assertEquals(JobStage.FAILED, f.store.get("copy-stop")!!.stage)
            assertEquals(DownloadError.Interrupted, f.store.get("copy-stop")!!.error)
            val notification = resultNotification()
            assertEquals(app.getString(dev.ahmed.savlino.R.string.download_interrupted_error),
                notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
            assertTrue(f.provider.rows.isEmpty())
            assertFalse(File(app.filesDir, "downloads/copy-stop").exists())
        }
    }

    @Test fun coroutineStopCancelsEngineBeforeRemovingStaging() = runBlocking {
        fixture(FakeEngine(steps = listOf(FakeEngine.Step.AwaitCancellation))).use { f ->
            f.store.enqueue(command("wm-stop"))
            val running = launch(Dispatchers.Default) { f.worker("wm-stop").doWork() }
            withTimeout(5000) { while (f.engine.commands.isEmpty()) yield() }
            running.cancelAndJoin()
            assertEquals(JobStage.FAILED, f.store.get("wm-stop")!!.stage)
            assertEquals(DownloadError.Interrupted, f.store.get("wm-stop")!!.error)
            assertFalse(File(app.filesDir, "downloads/wm-stop").exists())
        }
    }

    @Test fun zeroByteConnectivityStopReturnsWorkerRetry() = runBlocking {
        fixture(FakeEngine(steps = listOf(FakeEngine.Step.Fail(DownloadError.Cancelled)))).use { f ->
            f.store.enqueue(command("network-retry"))
            val worker = f.worker("network-retry")
            worker.stop(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)
            assertEquals(ListenableWorker.Result.retry(), worker.doWork())
            assertEquals(JobStage.ENQUEUED, f.store.get("network-retry")!!.stage)
            assertTrue(f.coordinator.progress.value.single().waiting)
            assertEquals(DownloadPhase.PREPARING, f.coordinator.progress.value.single().phase)
            assertNotNull(f.store.get("network-retry")!!.sourceUrl)
            assertEquals(1, f.store.all().size)
            assertFalse(File(app.filesDir, "downloads/network-retry").exists())
        }
    }

    @Test @Config(sdk = [29, 33, 35])
    fun connectivityStopBeforeBytesReturnsSameRowToQueueAndReruns() = runBlocking {
        fixture(FakeEngine(steps = listOf(FakeEngine.Step.AwaitCancellation))).use { f ->
            shadowOf(app.getSystemService(android.net.ConnectivityManager::class.java)).setActiveNetworkInfo(null)
            f.store.enqueue(command("network-zero"))
            val worker = f.worker("network-zero")
            val running = launch(Dispatchers.Default) { worker.doWork() }
            withTimeout(5000) { while (f.engine.commands.isEmpty()) yield() }
            worker.stop(if (android.os.Build.VERSION.SDK_INT >= 31) WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY
                else WorkInfo.STOP_REASON_UNKNOWN)
            running.cancelAndJoin()
            val row = f.store.get("network-zero")!!
            assertEquals(JobStage.ENQUEUED, row.stage)
            assertNotNull(row.sourceUrl)
            assertEquals(1, f.store.all().size)
            assertFalse(File(app.filesDir, "downloads/network-zero").exists())
            val work = WorkManager.getInstance(app).getWorkInfosForUniqueWork("network-zero").get()
            assertEquals(1, work.size)
            assertEquals(NetworkType.CONNECTED, work.single().constraints.requiredNetworkType)
            fixture(FakeEngine(steps = listOf(FakeEngine.Step.Output("output.mp4", byteArrayOf(1, 2, 3, 4))))).use { rerun ->
                assertEquals(ListenableWorker.Result.success(), rerun.worker("network-zero").doWork())
                assertEquals(JobStage.COMPLETED, rerun.store.get("network-zero")!!.stage)
                assertEquals(1, rerun.store.all().size)
                assertEquals(1, rerun.provider.rows.size)
            }
        }
    }

    @Test @Config(sdk = [29, 33, 35])
    fun connectivityStopAfterBytesRequeuesAndKeepsPartial() = runBlocking {
        fixture(FakeEngine(), script = { _, dir, emit ->
            dir.resolve("output.mp4.part").writeText("bytes")
            emit(EngineEvent.Progress(2, Size.Exact(4)))
            awaitCancellation()
        }).use { f ->
            shadowOf(app.getSystemService(android.net.ConnectivityManager::class.java)).setActiveNetworkInfo(null)
            f.store.enqueue(command("network-partial"))
            val worker = f.worker("network-partial")
            val running = launch(Dispatchers.Default) { worker.doWork() }
            withTimeout(5000) { while (f.coordinator.progress.value.none { it.bytes == 2L }) yield() }
            worker.stop(if (android.os.Build.VERSION.SDK_INT >= 31) WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY
                else WorkInfo.STOP_REASON_UNKNOWN)
            running.cancelAndJoin()
            val row = f.store.get("network-partial")!!
            assertEquals(JobStage.ENQUEUED, row.stage)
            assertNull(row.error)
            assertFalse(row.retryable)
            assertTrue(row.hasPartial)
            assertNotNull(row.sourceUrl)
            assertEquals("bytes", File(app.filesDir, "downloads/network-partial/output.mp4.part").readText())
        }
    }

    @Test fun stoppedWorkerWithDiskBytesReturnsRetryAndRetainsItsStaging() = runBlocking {
        fixture(FakeEngine(), script = { _, dir, _ ->
            dir.resolve("output.mp4.part").writeText("keep")
            TransferResult.Failure(DownloadError.Cancelled)
        }).use { f ->
            f.store.enqueue(command("stopped-partial"))
            val worker = f.worker("stopped-partial")
            worker.stop(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)
            assertEquals(ListenableWorker.Result.retry(), worker.doWork())
            val row = f.store.get("stopped-partial")!!
            assertEquals(JobStage.ENQUEUED, row.stage); assertTrue(row.hasPartial)
            assertNull(row.error); assertNotNull(row.sourceUrl)
            assertEquals("keep", File(app.filesDir, "downloads/stopped-partial/output.mp4.part").readText())
        }
    }

    @Test fun transientFailureWithNewDiskBytesSucceedsIntoFreshWorkWithoutTerminalEvent() = runBlocking {
        fixture(FakeEngine(), script = { _, dir, emit ->
            dir.resolve("output.mp4.part").writeText("keep")
            emit(EngineEvent.Progress(4, Size.Exact(100)))
            TransferResult.Failure(DownloadError.Network)
        }).use { f ->
            f.store.enqueue(command("transport"))
            assertEquals(ListenableWorker.Result.success(), f.worker("transport").doWork())
            // Startup scheduled this job; the continuation is chained behind that pending work.
            val work = WorkManager.getInstance(app).getWorkInfosForUniqueWork("transport").get()
            assertEquals(2, work.size)
            val next = work.single { it.state == WorkInfo.State.BLOCKED }
            assertEquals(NetworkType.CONNECTED, next.constraints.requiredNetworkType)
            val row = f.store.get("transport")!!
            assertEquals(JobStage.ENQUEUED, row.stage); assertTrue(row.hasPartial)
            assertNotNull(row.sourceUrl); assertEquals(1L, row.restartEpoch)
            assertEquals("keep", File(app.filesDir, "downloads/transport/output.mp4.part").readText())
            assertFalse(f.phases.contains("FAILED")); assertTrue(f.provider.rows.isEmpty())
        }
    }

    @Test fun networkFailureWithoutResumePermissionStillFailsAndDeletesPartial() = runBlocking {
        fixture(FakeEngine(), script = { _, dir, _ ->
            dir.resolve("output.mp4.part").writeText("discard")
            TransferResult.Failure(DownloadError.Network, resumeAllowed = false)
        }).use { f ->
            f.store.enqueue(command("http-failure"))
            assertEquals(ListenableWorker.Result.failure(), f.worker("http-failure").doWork())
            assertEquals(JobStage.FAILED, f.store.get("http-failure")!!.stage)
            assertFalse(f.store.get("http-failure")!!.hasPartial)
            assertFalse(File(app.filesDir, "downloads/http-failure").exists())
        }
    }

    @Test fun pauseStopsTransferKeepsBytesResumeSchedulesAndCancelPausedScrubs() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        fixture(FakeEngine(), script = { _, dir, emit ->
            dir.resolve("output.mp4.part").writeText("keep")
            emit(EngineEvent.Progress(4, Size.Exact(100)))
            entered.complete(Unit)
            stopped.await()
            TransferResult.Failure(DownloadError.Cancelled)
        }, cancelHook = { stopped.complete(Unit) }).use { f ->
            f.coordinator.enqueue(command("pause"))
            val running = async(Dispatchers.Default) { f.worker("pause").doWork() }
            try {
                withTimeout(5000) { entered.await() }
                withTimeout(5000) { f.coordinator.progress.first { it.singleOrNull()?.bytes == 4L } }
                assertTrue(f.coordinator.pause("pause"))
                withTimeout(5000) { running.await() }
                val row = f.store.get("pause")!!
                assertEquals(JobStage.PAUSED, row.stage); assertTrue(row.hasPartial)
                assertNotNull(row.sourceUrl); assertTrue(stopped.isCompleted)
                assertEquals("keep", File(app.filesDir, "downloads/pause/output.mp4.part").readText())
                f.coordinator.progress.value.single().let {
                    assertTrue(it.paused); assertEquals(4L, it.bytes); assertEquals(Size.Exact(100), it.total); assertEquals(4, it.percent)
                }
                assertFalse(f.phases.any { it in listOf("FAILED", "CANCELLED") })
                assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork("pause").get().all { it.state.isFinished })
                val previous = WorkManager.getInstance(app).getWorkInfosForUniqueWork("pause").get().map { it.id }.toSet()
                assertTrue(f.coordinator.resume("pause"))
                assertEquals(JobStage.ENQUEUED, f.store.get("pause")!!.stage)
                assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork("pause").get().any { it.id !in previous })
                assertTrue(f.coordinator.pause("pause"))
                f.coordinator.cancel("pause")
                assertEquals(JobStage.CANCELLED, f.store.get("pause")!!.stage)
                assertNull(f.store.get("pause")!!.sourceUrl)
                assertFalse(File(app.filesDir, "downloads/pause").exists())
            } finally { stopped.complete(Unit); running.cancelAndJoin() }
        }
    }

    @Test fun cancelAfterPausedReportStillScrubsAndDeletesAfterWriterExit() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val exit = CompletableDeferred<Unit>()
        val pausedReport = CompletableDeferred<Unit>()
        val releaseReport = CompletableDeferred<Unit>()
        val endings = mutableListOf<DownloadProgress>()
        fixture(FakeEngine(), script = { _, dir, _ ->
            dir.resolve("output.mp4.part").writeText("keep")
            entered.complete(Unit); exit.await()
            TransferResult.Failure(DownloadError.Cancelled)
        }, cancelHook = { exit.complete(Unit) }).use { f ->
            f.coordinator.initialize(); f.store.enqueue(command("late-paused-cancel"))
            val running = async(Dispatchers.Default) {
                f.coordinator.execute("late-paused-cancel", { WorkInfo.STOP_REASON_NOT_STOPPED }, {}, {}) {
                    endings += it
                    if (it.paused) { pausedReport.complete(Unit); releaseReport.await() }
                }
            }
            try {
                withTimeout(5000) { entered.await() }
                assertTrue(f.coordinator.pause("late-paused-cancel"))
                withTimeout(5000) { pausedReport.await() }
                f.coordinator.cancel("late-paused-cancel")
                releaseReport.complete(Unit)
                val row = withTimeout(5000) { running.await() }!!
                assertEquals(JobStage.CANCELLED, row.stage); assertNull(row.sourceUrl)
                assertFalse(File(app.filesDir, "downloads/late-paused-cancel").exists())
                assertEquals(1, endings.count { it.phase == DownloadPhase.CANCELLED })
                assertFalse(endings.any { it.phase == DownloadPhase.FAILED })
            } finally { exit.complete(Unit); releaseReport.complete(Unit); running.cancelAndJoin() }
        }
    }

    @Test fun recipeMismatchWipesPartialBeforeTransferAndKeepsExactSelection() = runBlocking {
        fixture(FakeEngine(), script = { command, dir, emit ->
            assertTrue(dir.list()!!.isEmpty())
            assertEquals("exact/source", command.format.sourceFormatId)
            emit(EngineEvent.Progress(1, Size.Exact(100)))
            TransferResult.Failure(DownloadError.Failed)
        }).use { f ->
            f.coordinator.initialize()
            f.store.enqueue(command("recipe")); f.store.startTransfer("recipe", "old-recipe")
            File(app.filesDir, "downloads/recipe").apply { mkdirs(); resolve("output.mp4.part").writeText("old") }
            f.store.pause("recipe", true); f.store.resume("recipe")
            val first = mutableListOf<DownloadProgress>()
            f.coordinator.execute("recipe", { first += it }, {})
            assertEquals(DownloadPhase.PREPARING, first.single().phase)
            assertNull(first.single().visiblePercent)
            assertEquals(2L, f.store.get("recipe")!!.restartEpoch)
            assertNotEquals("old-recipe", f.store.get("recipe")!!.resumeRecipe)
            assertFalse(File(app.filesDir, "downloads/recipe").exists())
        }
    }

    @Test fun matchingRecipeAdoptsPartialAndDropsOnlyStaleControlAndFinalOutput() = runBlocking {
        fixture(FakeEngine(), script = { _, dir, _ ->
            assertEquals(setOf("output.mp4.part"), dir.list()!!.toSet())
            assertEquals("keep", dir.resolve("output.mp4.part").readText())
            TransferResult.Failure(DownloadError.Failed)
        }).use { f ->
            f.coordinator.initialize(); f.store.enqueue(command("matching-recipe"))
            val recipe = "${dev.ahmed.savlino.BuildConfig.VERSION_CODE}:engine-42:${YtdlpCommand.ARGV_VERSION}"
            f.store.startTransfer("matching-recipe", recipe)
            File(app.filesDir, "downloads/matching-recipe").apply {
                mkdirs(); resolve("output.mp4.part").writeText("keep")
                resolve("result-path.txt").writeText("old manifest"); resolve("output.mp4").writeText("old final")
            }
            f.store.pause("matching-recipe", true); f.store.resume("matching-recipe")
            f.coordinator.execute("matching-recipe", {}, {})
            assertEquals(1, f.attempts.get())
            assertEquals(2L, f.store.get("matching-recipe")!!.restartEpoch)
        }
    }

    @Test fun resumedEpochStartsIndeterminateDropsLateSamplesAndRebasesBytes() = runBlocking {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val snapshots = mutableListOf<DownloadProgress>()
        val starts = mutableListOf<DownloadProgress>()
        var late: ((EngineEvent) -> Unit)? = null
        lateinit var coordinator: DownloadCoordinator
        fixture(FakeEngine(), clock = { 5000L }, nextTick = { awaitCancellation() }, script = { _, dir, emit ->
            if (attempts.incrementAndGet() == 1) {
                late = emit
                dir.resolve("output.mp4.part").writeText("bytes")
                emit(EngineEvent.Progress(90, Size.Exact(100)))
                TransferResult.Failure(DownloadError.Network)
            } else {
                late!!(EngineEvent.Progress(99, Size.Exact(100)))
                assertEquals(DownloadPhase.PREPARING, coordinator.progress.value.single().phase)
                emit(EngineEvent.Progress(1, Size.Exact(100)))
                snapshots += coordinator.progress.value.single()
                emit(EngineEvent.Restarting(1))
                snapshots += coordinator.progress.value.single()
                emit(EngineEvent.Progress(99, Size.Exact(100), restartEpoch = 0))
                snapshots += coordinator.progress.value.single()
                emit(EngineEvent.Progress(2, Size.Exact(100), restartEpoch = 1))
                snapshots += coordinator.progress.value.single()
                TransferResult.Failure(DownloadError.Failed)
            }
        }).use { f ->
            coordinator = f.coordinator
            coordinator.initialize(); f.store.enqueue(command("epoch"))
            coordinator.execute("epoch", { starts += it }, {})
            assertEquals(JobStage.ENQUEUED, f.store.get("epoch")!!.stage)
            coordinator.execute("epoch", { starts += it }, {})
            assertEquals(listOf(1L, 2L), starts.map { it.restartEpoch })
            assertTrue(starts.all { it.visiblePercent == null && it.bytes == null })
            assertEquals(1, snapshots[0].visiblePercent)
            assertEquals(DownloadPhase.PREPARING, snapshots[1].phase)
            assertNull(snapshots[1].visiblePercent); assertEquals(snapshots[1], snapshots[2])
            assertEquals(2, snapshots[3].visiblePercent)
            assertEquals(3L, f.store.get("epoch")!!.restartEpoch)
        }
    }

    @Test fun failedWithPartialAlwaysDeletesItAndExplicitRetryStartsEmpty() = runBlocking {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        fixture(FakeEngine(), script = { _, dir, _ ->
            assertTrue(dir.list()!!.isEmpty())
            if (attempts.incrementAndGet() == 1) dir.resolve("output.mp4.part").writeText("discard")
            TransferResult.Failure(DownloadError.MergeFailed)
        }).use { f ->
            f.coordinator.initialize(); f.store.enqueue(command("failed-partial"))
            f.coordinator.execute("failed-partial", {}, {})
            assertEquals(JobStage.FAILED, f.store.get("failed-partial")!!.stage)
            assertFalse(f.store.get("failed-partial")!!.hasPartial)
            assertFalse(File(app.filesDir, "downloads/failed-partial").exists())
            assertTrue(f.coordinator.retry("failed-partial"))
            f.coordinator.execute("failed-partial", {}, {})
            assertEquals(2, attempts.get())
        }
    }

    @Test fun cancellationRacingWorkerStartStopsSignalBeforeAnyTransfer() = runBlocking {
        val cancelling = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        fixture(FakeEngine(steps = listOf(FakeEngine.Step.AwaitCancellation)), cancelHook = {
            if (first.compareAndSet(true, false)) {
                cancelling.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }).use { f ->
            f.coordinator.initialize()
            f.store.enqueue(command("start-race"))
            val cancel = async(Dispatchers.Default) { f.coordinator.cancel("start-race") }
            try {
                assertTrue(withContext(Dispatchers.IO) { cancelling.await(5, java.util.concurrent.TimeUnit.SECONDS) })
                val worker = async(Dispatchers.Default) {
                    f.coordinator.execute("start-race", {}, {})
                }
                assertEquals(JobStage.CANCELLED, withTimeout(5000) { worker.await() }!!.stage)
                release.countDown()
                withTimeout(5000) { cancel.await() }
            } finally { release.countDown() }
            assertEquals(JobStage.CANCELLED, f.store.get("start-race")!!.stage)
            assertNull(f.store.get("start-race")!!.sourceUrl)
            assertTrue(f.engine.commands.isEmpty())
            assertFalse(File(app.filesDir, "downloads/start-race").exists())
        }
    }

    @Test fun cancellationAfterCommitStillPostsSavedWithOpenAndShare() = runBlocking {
        fixture(FakeEngine(steps = listOf(FakeEngine.Step.Output("output.mp4", byteArrayOf(1, 2, 3, 4))))).use { f ->
            shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            f.store.enqueue(command("late-stop"))
            val committed = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            f.provider.onCommit = { committed.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            val worker = f.worker("late-stop")
            val running = launch(Dispatchers.Default) { worker.doWork() }
            try {
                assertTrue(withContext(Dispatchers.IO) { committed.await(5, java.util.concurrent.TimeUnit.SECONDS) })
                worker.stop(WorkInfo.STOP_REASON_UNKNOWN)
                running.cancel()
                withTimeout(5000) { while (!f.coordinator.stopRequested("late-stop")) yield() }
            } finally { release.countDown() }
            running.join()
            assertSaved(f, "late-stop")
        }
    }

    @Test fun coordinatorCancelAfterCompletedKeepsSavedFileAndActions() = runBlocking {
        fixture(FakeEngine(steps = listOf(FakeEngine.Step.Output("output.mp4", byteArrayOf(1, 2, 3, 4))))).use { f ->
            shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            f.store.enqueue(command("late-cancel"))
            f.worker("late-cancel").doWork()
            f.coordinator.cancel("late-cancel")
            assertSaved(f, "late-cancel")
        }
    }

    @Test @Config(manifest = "AndroidManifest.xml")
    fun notificationCancelAfterCompletedKeepsSavedFileAndActions() = runBlocking {
        fixture(FakeEngine(steps = listOf(FakeEngine.Step.Output("output.mp4", byteArrayOf(1, 2, 3, 4))))).use { f ->
            shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            f.store.enqueue(command("late-receiver"))
            val cancelIntent = DownloadNotifications(app).foreground(DownloadProgress("late-receiver", "clip.mp4",
                DownloadPhase.PREPARING)).notification.actions.single().actionIntent
            f.worker("late-receiver").doWork()
            val receiver = DownloadCancelReceiver()
            app.registerReceiver(receiver, IntentFilter(DownloadNotifications.CANCEL), Context.RECEIVER_NOT_EXPORTED)
            try {
                cancelIntent.send()
                shadowOf(android.os.Looper.getMainLooper()).idle()
                withTimeout(5000) {
                    while ((app as NezzelApplication).scope.coroutineContext[Job]!!.children.any()) yield()
                }
            } finally { app.unregisterReceiver(receiver) }
            assertSaved(f, "late-receiver")
        }
    }

    @Test @Config(sdk = [33])
    fun burstBoundsUiAndReporterWhilePreservingMeasuredFileAndOrderedTerminal() = runBlocking {
        val firstReport = CompletableDeferred<Unit>()
        val releaseReporter = CompletableDeferred<Unit>()
        val burstDone = CompletableDeferred<Unit>()
        val releaseEngine = CompletableDeferred<Unit>()
        val callbacks = java.util.concurrent.atomic.AtomicInteger()
        val ui = java.util.Collections.synchronizedList(mutableListOf<DownloadProgress>())
        val reports = java.util.Collections.synchronizedList(mutableListOf<DownloadProgress>())
        fixture(FakeEngine(), clock = { 0L }, nextTick = { awaitCancellation() }, script = { _, dir, emit ->
            emit(EngineEvent.Progress(1, Size.Exact(10_000))); callbacks.incrementAndGet()
            firstReport.await()
            for (n in 2L..10_000L) { emit(EngineEvent.Progress(n, Size.Exact(10_000))); callbacks.incrementAndGet() }
            burstDone.complete(Unit); releaseEngine.await()
            emit(EngineEvent.Merging)
            val output = dir.resolve("output.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            TransferResult.Success(StagedOutput.validate(dir, listOf(output)))
        }).use { f ->
            f.coordinator.initialize(); f.store.enqueue(command("burst"))
            val collect = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                f.coordinator.progress.collect { values -> values.firstOrNull { it.jobId == "burst" }?.let { ui += it } }
            }
            val running = async(Dispatchers.Default) { f.coordinator.execute("burst", {}, { event ->
                reports += event
                if (event.phase == DownloadPhase.DOWNLOADING && firstReport.complete(Unit)) releaseReporter.await()
            }) }
            var beforeRelease = 0
            try {
                withTimeout(5000) { burstDone.await() }
                beforeRelease = ui.count { it.phase == DownloadPhase.DOWNLOADING }
            } finally { releaseReporter.complete(Unit); releaseEngine.complete(Unit) }
            try {
                assertEquals(JobStage.COMPLETED, withTimeout(5000) { running.await() }!!.stage)
                println("MEASURE coordinator callbacks=${callbacks.get()} uiBeforeRelease=$beforeRelease reports=${reports.size}")
                assertTrue("UI publishes=$beforeRelease for10000 callbacks", beforeRelease <= 1)
                assertTrue("reports=${reports.size}", reports.size <= 7)
                assertEquals(10_000L, reports.last { it.phase == DownloadPhase.DOWNLOADING }.bytes)
                assertEquals(listOf(DownloadPhase.PREPARING, DownloadPhase.DOWNLOADING, DownloadPhase.JOINING,
                    DownloadPhase.SAVING, DownloadPhase.COMPLETED), reports.map { it.phase }.distinct())
                assertEquals(1, reports.count { it.terminal })
                assertTrue(reports.last().terminal)
                assertEquals(4L, reports.last().bytes)
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), f.provider.file.readBytes())
                assertEquals(1, f.provider.rows.size)
                assertNull(f.store.get("burst")!!.sourceUrl)
                assertTrue(reports.filter { it.phase != DownloadPhase.DOWNLOADING }.all {
                    it.bytesPerSecond == null && it.remainingTransferSeconds == null && it.stalled == null
                })
            } finally { running.cancelAndJoin(); collect.cancelAndJoin() }
        }
    }

    @Test @Config(sdk = [33])
    fun workerPersistenceIsBoundedForTenThousandCallbacksAndFlushesFinalTransferBytes() = runBlocking {
        fixture(FakeEngine(), clock = { 0L }, nextTick = { awaitCancellation() }, script = { _, dir, emit ->
            repeat(10_000) { emit(EngineEvent.Progress(it + 1L, Size.Exact(10_000))) }
            emit(EngineEvent.Merging)
            val output = dir.resolve("output.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            TransferResult.Success(StagedOutput.validate(dir, listOf(output)))
        }).use { f ->
            f.store.enqueue(command("persist-burst"))
            assertEquals(ListenableWorker.Result.success(), f.worker("persist-burst").doWork())
            println("MEASURE worker callbacks=10000 persistenceWrites=${f.phases.size}")
            assertTrue("persistence writes=${f.phases.size}", f.phases.size <= 7)
            val transfer = f.progressData.filter { it.getString("phase") == "DOWNLOADING" }
            assertEquals(10_000L, transfer.last().getLong("bytes", -1))
            assertEquals(1, f.phases.count { it == "COMPLETED" })
            assertEquals("COMPLETED", f.phases.last())
            assertEquals(1, f.provider.rows.size)
        }
    }

    @Test @Config(sdk = [33])
    fun silentStallClearsVisibleMetricsAndTickerStopsAtTerminal() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong()
        val ticks = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.RENDEZVOUS)
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture(FakeEngine(), clock = { now.get() }, nextTick = { ticks.receive() }, script = { _, _, emit ->
            emit(EngineEvent.Progress(0, Size.Exact(20_000)))
            now.set(2000); emit(EngineEvent.Progress(2000, Size.Exact(20_000)))
            ready.complete(Unit); release.await()
            TransferResult.Failure(DownloadError.Network, resumeAllowed = false)
        }).use { f ->
            f.coordinator.initialize(); f.store.enqueue(command("silent-stall"))
            val running = async(Dispatchers.Default) { f.coordinator.execute("silent-stall", {}, {}) }
            try {
                withTimeout(5000) { ready.await() }
                val observedRate = f.coordinator.progress.value.single().bytesPerSecond
                assertNotNull("Coordinator must publish measured rate after2s before silent stall", observedRate)
                assertEquals(1000.0, observedRate!!, 0.001)
                now.set(7000); withTimeout(5000) { ticks.send(Unit) }
                withTimeout(5000) { while (f.coordinator.progress.value.single().stalled != true) yield() }
                val stalled = f.coordinator.progress.value.single()
                assertNull(stalled.bytesPerSecond); assertNull(stalled.remainingTransferSeconds)
                assertEquals(2000L, stalled.bytes)
            } finally { release.complete(Unit); withTimeout(5000) { running.await() } }
            val terminal = f.coordinator.progress.value.single()
            assertTrue(terminal.terminal)
            assertNull(terminal.stalled); assertNull(terminal.bytesPerSecond); assertNull(terminal.remainingTransferSeconds)
            assertTrue(ticks.trySend(Unit).isFailure)
        }
    }

    @Test @Config(sdk = [33])
    fun sameJobIdExecuteRetryStartsNewObservationWindow() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong()
        val attempted = java.util.concurrent.atomic.AtomicInteger()
        val warmups = mutableListOf<DownloadProgress>()
        val measured = mutableListOf<DownloadProgress>()
        lateinit var coordinator: DownloadCoordinator
        fixture(FakeEngine(), clock = { now.get() }, nextTick = { awaitCancellation() }, script = { _, _, emit ->
            val attempt = attempted.incrementAndGet()
            now.set((attempt - 1) * 10_000L)
            emit(EngineEvent.Progress(0, Size.Unknown))
            warmups += coordinator.progress.value.single()
            now.addAndGet(2000); emit(EngineEvent.Progress(2000, Size.Exact(10_000)))
            measured += coordinator.progress.value.single()
            TransferResult.Failure(DownloadError.Network, resumeAllowed = false)
        }).use { f ->
            coordinator = f.coordinator
            coordinator.initialize(); f.store.enqueue(command("same-attempt"))
            coordinator.execute("same-attempt", {}, {})
            assertTrue(coordinator.retry("same-attempt"))
            coordinator.execute("same-attempt", {}, {})
            assertEquals(2, attempted.get())
            assertTrue(warmups.all { it.bytesPerSecond == null && it.remainingTransferSeconds == null && it.stalled == false })
            assertEquals(listOf(1000.0, 1000.0), measured.map { it.bytesPerSecond })
            assertEquals(listOf(8L, 8L), measured.map { it.remainingTransferSeconds })
            assertEquals(1, f.store.all().size)
        }
    }

    @Test @Config(sdk = [33])
    fun reportedHlsFragmentsReachLiveRowsAndWorkerReportsWithoutInventingTime() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong()
        val snapshots = mutableListOf<DownloadProgress>()
        val reports = mutableListOf<DownloadProgress>()
        val reportedFragments = CompletableDeferred<Unit>()
        lateinit var coordinator: DownloadCoordinator
        fixture(FakeEngine(), clock = { now.get() }, nextTick = { awaitCancellation() }, script = { _, _, emit ->
            now.set(2000)
            emit(EngineEvent.Progress(160_900_000, Size.Estimated(156_700_000), 25, 100))
            snapshots += coordinator.progress.value.single()
            withTimeout(5000) { reportedFragments.await() }
            now.set(4000)
            emit(EngineEvent.Progress(161_000_000, Size.Unknown))
            snapshots += coordinator.progress.value.single()
            TransferResult.Failure(DownloadError.Network, resumeAllowed = false)
        }).use { f ->
            coordinator = f.coordinator
            coordinator.initialize(); f.store.enqueue(command("hls-fragments"))
            coordinator.execute("hls-fragments", {}, {
                reports += it
                if (it.fragmentIndex == 25L && it.fragmentCount == 100L) reportedFragments.complete(Unit)
            })
            val measured = snapshots.first()
            assertEquals(25L, measured.fragmentIndex); assertEquals(100L, measured.fragmentCount)
            assertEquals(25, measured.visiblePercent); assertNull(measured.exactTransferTotal)
            assertNull(measured.remainingTransferSeconds)
            assertNull(snapshots.last().visiblePercent); assertNull(snapshots.last().remainingTransferSeconds)
            assertTrue(reports.any { it.fragmentIndex == 25L && it.fragmentCount == 100L && it.visiblePercent == 25 })
            assertNull(coordinator.progress.value.single().fragmentIndex)
        }
    }

    @Test @Config(sdk = [33])
    fun invalidNegativeCounterIsAbsentAndOverTotalNeverLooksComplete() = runBlocking {
        val snapshots = mutableListOf<DownloadProgress>()
        val now = java.util.concurrent.atomic.AtomicLong()
        lateinit var coordinator: DownloadCoordinator
        fixture(FakeEngine(), clock = { now.get() }, nextTick = { awaitCancellation() }, script = { _, _, emit ->
            for (bytes in listOf(0L, 2L, -1L, 120L)) {
                now.addAndGet(2000); emit(EngineEvent.Progress(bytes, Size.Exact(100)))
                snapshots += coordinator.progress.value.single()
            }
            TransferResult.Failure(DownloadError.Network, resumeAllowed = false)
        }).use { f ->
            coordinator = f.coordinator
            coordinator.initialize(); f.store.enqueue(command("invalid-count"))
            coordinator.execute("invalid-count", {}, {})
            assertNull(snapshots[2].bytes); assertNull(snapshots[2].percent)
            assertNull(snapshots[2].bytesPerSecond); assertNull(snapshots[2].remainingTransferSeconds)
            assertEquals(120L, snapshots[3].bytes); assertNull(snapshots[3].percent)
            assertEquals(JobStage.FAILED, f.store.get("invalid-count")!!.stage)
        }
    }

    @Test @Config(sdk = [33])
    fun cancellationDuringBlockedBurstReporterReachesEngineAndDeliversOneTerminal() = runBlocking {
        val reportEntered = CompletableDeferred<Unit>()
        val blockReporter = CompletableDeferred<Unit>()
        val burstDone = CompletableDeferred<Unit>()
        val terminals = mutableListOf<DownloadProgress>()
        fixture(FakeEngine(), clock = { 0L }, nextTick = { awaitCancellation() }, script = { _, _, emit ->
            emit(EngineEvent.Progress(1)); reportEntered.await()
            repeat(9999) { emit(EngineEvent.Progress(it + 2L)) }
            burstDone.complete(Unit); awaitCancellation()
        }).use { f ->
            f.coordinator.initialize(); f.store.enqueue(command("cancel-burst"))
            val running = launch(Dispatchers.Default) {
                f.coordinator.execute("cancel-burst", { WorkInfo.STOP_REASON_UNKNOWN }, {}, { event ->
                    if (event.phase == DownloadPhase.DOWNLOADING) { reportEntered.complete(Unit); blockReporter.await() }
                }, { terminals += it })
            }
            try {
                withTimeout(5000) { burstDone.await() }
                f.coordinator.requestCancellation("cancel-burst"); running.cancel()
                withTimeout(5000) { f.engineStopped.await(); running.join() }
                assertFalse(blockReporter.isCompleted)
                assertEquals(listOf(DownloadPhase.CANCELLED), terminals.map { it.phase })
                assertEquals(JobStage.CANCELLED, f.store.get("cancel-burst")!!.stage)
                assertNull(f.store.get("cancel-burst")!!.sourceUrl)
                assertFalse(File(app.filesDir, "downloads/cancel-burst").exists())
                assertTrue(f.provider.rows.isEmpty())
            } finally { blockReporter.complete(Unit); running.cancelAndJoin() }
        }
    }

    @Test @Config(sdk = [33])
    fun failureAndUserCancellationFlushClearedMetricsBeforeSingleTerminal() = runBlocking {
        for (cancel in listOf(false, true)) {
            val now = java.util.concurrent.atomic.AtomicLong()
            val measuredReport = CompletableDeferred<Unit>()
            val reports = mutableListOf<DownloadProgress>()
            lateinit var coordinator: DownloadCoordinator
            val id = if (cancel) "clear-cancel" else "clear-failure"
            fixture(FakeEngine(), clock = { now.get() }, nextTick = { awaitCancellation() }, script = { _, _, emit ->
                emit(EngineEvent.Progress(0, Size.Exact(10_000)))
                now.set(2000); emit(EngineEvent.Progress(2000, Size.Exact(10_000)))
                measuredReport.await()
                if (cancel) coordinator.requestCancellation(id)
                TransferResult.Failure(if (cancel) DownloadError.Cancelled else DownloadError.Network, resumeAllowed = false)
            }).use { f ->
                coordinator = f.coordinator
                coordinator.initialize(); f.store.enqueue(command(id))
                withTimeout(5000) { coordinator.execute(id, {}, { event ->
                    reports += event
                    if (event.bytesPerSecond != null) measuredReport.complete(Unit)
                }) }
                assertTrue("Setup must deliver a measured sample before ending transfer", measuredReport.isCompleted)
                val lastTransfer = reports.last { it.phase == DownloadPhase.DOWNLOADING }
                assertNull("Failure/cancel pre-terminal flush must clear measured rate", lastTransfer.bytesPerSecond)
                assertNull(lastTransfer.remainingTransferSeconds); assertNull(lastTransfer.stalled)
                assertEquals(2000L, lastTransfer.bytes)
                assertEquals(1, reports.count { it.terminal })
                assertEquals(if (cancel) DownloadPhase.CANCELLED else DownloadPhase.FAILED, reports.last().phase)
                assertEquals(if (cancel) JobStage.CANCELLED else JobStage.FAILED, f.store.get(id)!!.stage)
                assertTrue(f.provider.rows.isEmpty())
                assertFalse(File(app.filesDir, "downloads/$id").exists())
            }
        }
    }

    @Test @Config(sdk = [33])
    fun lateEngineProgressCannotReopenTransferAfterJoining() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong()
        val reports = mutableListOf<DownloadProgress>()
        var afterLateProgress: DownloadProgress? = null
        lateinit var coordinator: DownloadCoordinator
        fixture(FakeEngine(), clock = { now.get() }, nextTick = { awaitCancellation() }, script = { _, dir, emit ->
            emit(EngineEvent.Progress(0, Size.Exact(10_000)))
            now.set(2000); emit(EngineEvent.Progress(2000, Size.Exact(10_000)))
            emit(EngineEvent.Merging)
            now.set(4000); emit(EngineEvent.Progress(4000, Size.Exact(10_000)))
            now.set(6000); emit(EngineEvent.Progress(6000, Size.Exact(10_000)))
            afterLateProgress = coordinator.progress.value.single()
            val output = dir.resolve("output.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            TransferResult.Success(StagedOutput.validate(dir, listOf(output)))
        }).use { f ->
            coordinator = f.coordinator
            coordinator.initialize(); f.store.enqueue(command("late-progress"))
            coordinator.execute("late-progress", {}, { reports += it })
            assertNotNull(afterLateProgress)
            assertEquals("Postprocessing phase must not regress on a late byte callback", DownloadPhase.JOINING, afterLateProgress!!.phase)
            assertNull(afterLateProgress!!.bytesPerSecond); assertNull(afterLateProgress!!.remainingTransferSeconds)
            val joined = reports.indexOfFirst { it.phase == DownloadPhase.JOINING }
            assertTrue(joined >= 0)
            assertFalse(reports.drop(joined + 1).any { it.phase == DownloadPhase.DOWNLOADING })
            assertEquals(1, reports.count { it.terminal })
            assertEquals(DownloadPhase.COMPLETED, reports.last().phase)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), f.provider.file.readBytes())
        }
    }

    private fun resultNotification(): android.app.Notification {
        // Manual doWork() does not run WorkManager's foreground-service cleanup. Immediate phase
        // delivery can leave its active ID in the shadow beside the separate result ID (:finished).
        val notifications = shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).allNotifications
        val results = notifications.filter { it.channelId == DownloadNotifications.COMPLETED_CHANNEL }
        assertEquals("Exactly one result notification must be delivered", 1, results.size)
        val others = notifications.filter { it.channelId != DownloadNotifications.COMPLETED_CHANNEL }
        assertTrue("Only one expected active notification may remain before foreground cleanup", others.size <= 1)
        others.forEach { active ->
            assertEquals(DownloadNotifications.CHANNEL, active.channelId)
            assertTrue(active.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(listOf(app.getString(dev.ahmed.savlino.R.string.cancel)), active.actions.map { it.title.toString() })
        }
        return results.single()
    }

    @Test fun progressedRequeueChainsFreshWorkAfterThisAttemptAndNoProgressKeepsBackoff() = runBlocking {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        fixture(FakeEngine(), script = { _, dir, _ ->
            if (attempts.incrementAndGet() == 1) dir.resolve("output.mp4.part").writeText("keep")
            TransferResult.Failure(DownloadError.Network, cause = DownloadFailureCause.STALL)
        }).use { f ->
            assertTrue(f.coordinator.enqueue(command("chain")))
            val wm = WorkManager.getInstance(app)
            val pending = wm.getWorkInfosForUniqueWork("chain").get().single()
            assertEquals(WorkInfo.State.ENQUEUED, pending.state)
            assertEquals(ListenableWorker.Result.success(), f.worker("chain").doWork())
            val chain = wm.getWorkInfosForUniqueWork("chain").get()
            assertEquals(2, chain.size)
            val next = chain.single { it.id != pending.id }
            // APPEND_OR_REPLACE: the continuation waits for the current (still unfinished) work.
            assertEquals(WorkInfo.State.BLOCKED, next.state)
            assertEquals(NetworkType.CONNECTED, next.constraints.requiredNetworkType)
            WorkManagerTestInitHelper.getTestDriver(app)!!.setAllConstraintsMet(pending.id)
            withTimeout(5000) { while (wm.getWorkInfoById(next.id).get()!!.state != WorkInfo.State.ENQUEUED) yield() }
            assertEquals(WorkInfo.State.SUCCEEDED, wm.getWorkInfoById(pending.id).get()!!.state)
            assertEquals(0, wm.getWorkInfoById(next.id).get()!!.runAttemptCount)
            // Same bytes again: no continuation; Result.retry() keeps the exponential backoff growing.
            assertEquals(ListenableWorker.Result.retry(), f.worker("chain").doWork())
            assertEquals(2, wm.getWorkInfosForUniqueWork("chain").get().size)
            val row = f.store.get("chain")!!
            assertEquals(JobStage.ENQUEUED, row.stage); assertTrue(row.hasPartial)
            assertEquals("keep", File(app.filesDir, "downloads/chain/output.mp4.part").readText())
            assertEquals(2, attempts.get())
        }
    }

    @Test fun connectivityStopBeforeStartTransferKeepsAdoptedPartial() = runBlocking {
        val probing = CompletableDeferred<Unit>()
        fixture(FakeEngine(), version = { probing.complete(Unit); awaitCancellation() }).use { f ->
            shadowOf(app.getSystemService(android.net.ConnectivityManager::class.java)).setActiveNetworkInfo(null)
            f.coordinator.initialize(); f.store.enqueue(command("early-stop"))
            f.store.startTransfer("early-stop", "${dev.ahmed.savlino.BuildConfig.VERSION_CODE}:engine-42:${YtdlpCommand.ARGV_VERSION}")
            File(app.filesDir, "downloads/early-stop").apply { mkdirs(); resolve("output.mp4.part").writeText("keep") }
            f.store.pause("early-stop", true); f.store.resume("early-stop")
            val worker = f.worker("early-stop")
            val running = launch(Dispatchers.Default) { worker.doWork() }
            withTimeout(5000) { probing.await() }
            worker.stop(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)
            running.cancelAndJoin()
            val row = f.store.get("early-stop")!!
            assertEquals(JobStage.ENQUEUED, row.stage); assertTrue(row.hasPartial)
            assertNull(row.error); assertNotNull(row.sourceUrl); assertEquals(1L, row.restartEpoch)
            assertEquals("keep", File(app.filesDir, "downloads/early-stop/output.mp4.part").readText())
            assertEquals(0, f.attempts.get())
        }
    }

    @Test fun pauseIsRefusedOncePostProcessingOrPublicationStarted() = runBlocking {
        val joining = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture(FakeEngine(), script = { _, dir, emit ->
            dir.resolve("output.f137.mp4").writeText("video")
            emit(EngineEvent.Progress(4, Size.Exact(4)))
            emit(EngineEvent.Merging)
            joining.complete(Unit); release.await()
            dir.resolve("output.f137.mp4").delete()
            val output = dir.resolve("output.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            TransferResult.Success(StagedOutput.validate(dir, listOf(output)))
        }).use { f ->
            shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            f.store.enqueue(command("joining"))
            val opened = java.util.concurrent.CountDownLatch(1)
            val copy = java.util.concurrent.CountDownLatch(1)
            f.provider.onOpen = { opened.countDown(); check(copy.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            val running = async(Dispatchers.Default) { f.worker("joining").doWork() }
            try {
                withTimeout(5000) { joining.await() }
                assertFalse(f.coordinator.pause("joining"))
                assertEquals(JobStage.TRANSFERRING, f.store.get("joining")!!.stage)
                release.complete(Unit)
                assertTrue(withContext(Dispatchers.IO) { opened.await(5, java.util.concurrent.TimeUnit.SECONDS) })
                assertFalse(f.coordinator.pause("joining"))
            } finally { release.complete(Unit); copy.countDown() }
            assertEquals(ListenableWorker.Result.success(), withTimeout(5000) { running.await() })
            assertSaved(f, "joining")
            assertFalse(f.phases.contains("PAUSED"))
        }
    }

    @Test fun expiryFromAnotherStoreWhileWriterLiveKeepsStagingUntilTheWriterExits() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture(FakeEngine(), script = { _, dir, emit ->
            dir.resolve("output.mp4.part").writeText("bytes")
            emit(EngineEvent.Progress(5, Size.Exact(100)))
            entered.complete(Unit); release.await()
            TransferResult.Failure(DownloadError.Network)
        }).use { f ->
            f.coordinator.initialize(); f.store.enqueue(command("expire-live"))
            val running = async(Dispatchers.Default) { f.coordinator.execute("expire-live", {}, {}) }
            try {
                withTimeout(5000) { entered.await() }
                JobStore(app, null).use { other ->
                    other.writableDatabase.execSQL("UPDATE jobs SET created_at=? WHERE job_id=?", arrayOf<Any?>(
                        System.currentTimeMillis() - JobStore.CONFIRMATION_LIFETIME_MS - 1000, "expire-live"))
                    assertEquals(JobStage.FAILED, other.get("expire-live")!!.stage)
                }
                assertEquals("bytes", File(app.filesDir, "downloads/expire-live/output.mp4.part").readText())
            } finally { release.complete(Unit) }
            withTimeout(5000) { running.await() }
            val row = f.store.get("expire-live")!!
            assertEquals(JobStage.FAILED, row.stage); assertEquals(DownloadError.FormatExpired, row.error)
            assertNull(row.sourceUrl); assertFalse(row.hasPartial)
            assertFalse(File(app.filesDir, "downloads/expire-live").exists())
            assertFalse("expire-live" in JobStore.liveWriters)
        }
    }

    @Test fun quickPauseResumeAroundAWorkerStartLeavesOneQueuedResumableRow() = runBlocking {
        val stopped = CompletableDeferred<Unit>()
        val endings = java.util.concurrent.CopyOnWriteArrayList<DownloadProgress>()
        fixture(FakeEngine(), script = { _, _, emit ->
            emit(EngineEvent.Progress(4, Size.Exact(100)))
            stopped.await()
            TransferResult.Failure(DownloadError.Cancelled)
        }, cancelHook = { stopped.complete(Unit) }).use { f ->
            f.coordinator.initialize(); f.store.enqueue(command("flip"))
            f.store.startTransfer("flip", "${dev.ahmed.savlino.BuildConfig.VERSION_CODE}:engine-42:${YtdlpCommand.ARGV_VERSION}")
            File(app.filesDir, "downloads/flip").apply { mkdirs(); resolve("output.mp4.part").writeText("keep") }
            f.store.pause("flip", true); f.store.resume("flip")
            val worker: Deferred<JobRecord?>
            synchronized(f.store) {
                worker = async(Dispatchers.Default) {
                    f.coordinator.execute("flip", { WorkInfo.STOP_REASON_NOT_STOPPED }, {}, {}) { endings += it }
                }
                // The attempt registers while its row read still waits: a pause from here on reaches it.
                val deadline = System.nanoTime() + 5_000_000_000
                while (!f.coordinator.isRunning("flip")) { check(System.nanoTime() < deadline); Thread.yield() }
            }
            try {
                assertTrue(f.coordinator.pause("flip"))
                assertTrue(f.coordinator.resume("flip"))
                withTimeout(5000) { worker.await() }
            } finally { stopped.complete(Unit); worker.cancelAndJoin() }
            val row = f.store.get("flip")!!
            assertEquals(JobStage.ENQUEUED, row.stage); assertTrue(row.hasPartial)
            assertNull(row.error); assertNotNull(row.sourceUrl)
            assertEquals("keep", File(app.filesDir, "downloads/flip/output.mp4.part").readText())
            assertFalse(endings.any { it.phase in setOf(DownloadPhase.FAILED, DownloadPhase.CANCELLED) })
            assertFalse(f.coordinator.isRunning("flip"))
            assertEquals(WorkInfo.State.ENQUEUED, WorkManager.getInstance(app).getWorkInfosForUniqueWork("flip").get().single().state)
        }
    }

    @Test fun progressReadsTheRowAtMostOncePerSecondAndStaleStaysDropped() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong()
        val seen = mutableListOf<Long?>()
        lateinit var f: Fixture
        fixture(FakeEngine(), clock = { now.get() }, nextTick = { awaitCancellation() }, script = { _, dir, emit ->
            dir.resolve("output.mp4.part").writeText("bytes")
            emit(EngineEvent.Progress(1, Size.Exact(100)))
            // Raw write the callback has not read yet: the next check comes a second after the last one.
            f.store.writableDatabase.execSQL("UPDATE jobs SET restart_epoch = restart_epoch + 5 WHERE job_id = 'throttle'")
            for ((at, bytes) in listOf(500L to 2L, 1000L to 3L, 1600L to 4L)) {
                now.set(at); emit(EngineEvent.Progress(bytes, Size.Exact(100)))
                seen += f.coordinator.progress.value.single().bytes
            }
            TransferResult.Failure(DownloadError.Failed)
        }).use { fx ->
            f = fx
            fx.coordinator.initialize(); fx.store.enqueue(command("throttle"))
            fx.coordinator.execute("throttle", {}, {})
            assertEquals(listOf<Long?>(2, 2, 2), seen)
        }
    }

    @Test fun newerEngineDiscardsCachedInfoJsonBeforeTheReconfirmedTransfer() = runBlocking {
        val info = File(app.noBackupFilesDir, "ytdlp-info/upgraded.json")
        var infoAtTransfer: Boolean? = null
        var engineVersion: String? = null
        fixture(FakeEngine(), version = { "engine-43" }, script = { command, _, _ ->
            infoAtTransfer = info.exists(); engineVersion = command.engineVersion
            TransferResult.Failure(DownloadError.Failed)
        }).use { f ->
            f.coordinator.initialize(); f.store.enqueue(command("upgraded"))
            info.apply { parentFile!!.mkdirs(); writeText("{}") }
            f.coordinator.execute("upgraded", {}, {})
            assertEquals(false, infoAtTransfer); assertEquals("engine-43", engineVersion)
            assertEquals(1, f.attempts.get())
        }
    }

    @Test fun transportResumesOnceWithoutGrowthThenFailsAndDeletesPartial() = runBlocking {
        for (error in listOf(DownloadError.Network, DownloadError.Offline)) {
            fixture(FakeEngine(), script = { selected, dir, emit ->
                assertEquals("exact/source", selected.format.sourceFormatId)
                val part = dir.resolve("output.mp4.part")
                if (!part.exists()) part.writeText("keep")
                assertEquals("keep", part.readText())
                emit(EngineEvent.Progress(4, Size.Exact(100)))
                TransferResult.Failure(error, cause = DownloadFailureCause.TRANSPORT)
            }).use { f ->
                val id = "once-${error.name}"
                f.store.enqueue(command(id))
                assertEquals(ListenableWorker.Result.success(), f.worker(id).doWork())
                assertEquals(JobStage.ENQUEUED, f.store.get(id)!!.stage)
                assertTrue(f.store.get(id)!!.hasPartial)
                assertFalse(f.phases.contains("FAILED"))
                assertEquals("keep", File(app.filesDir, "downloads/$id/output.mp4.part").readText())
                assertEquals(ListenableWorker.Result.failure(), f.worker(id).doWork())
                val row = f.store.get(id)!!
                assertEquals(JobStage.FAILED, row.stage); assertTrue(row.retryable); assertFalse(row.hasPartial)
                assertFalse(File(app.filesDir, "downloads/$id").exists())
                assertEquals(1, f.phases.count { it == "FAILED" })
                assertEquals(2, f.attempts.get())
            }
        }
    }

    @Test fun mebibyteGrowthBeforePauseResetsTheTransportEpisode() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
        var attempt = 0
        fixture(FakeEngine(), cancelHook = { released.complete(Unit) }, script = { _, dir, emit ->
            attempt++
            val part = dir.resolve("output.mp4.part")
            if (!part.exists()) part.writeText("keep") else if (attempt == 2) part.appendBytes(ByteArray(1024 * 1024))
            emit(EngineEvent.Progress(part.length(), Size.Exact(100)))
            if (attempt == 2) {
                entered.complete(Unit); released.await()
                TransferResult.Failure(DownloadError.Cancelled)
            } else TransferResult.Failure(DownloadError.Network)
        }).use { f ->
            val id = "growth-pause"
            f.store.enqueue(command(id, Size.Exact(16L * 1024 * 1024)))
            assertEquals(ListenableWorker.Result.success(), f.worker(id).doWork())
            assertEquals(1, f.store.get(id)!!.transportResumes)
            val running = async(Dispatchers.Default) { f.worker(id).doWork() }
            try {
                withTimeout(5000) { entered.await() }
                assertTrue(f.coordinator.pause(id))
            } finally { released.complete(Unit) }
            assertEquals(ListenableWorker.Result.success(), withTimeout(5000) { running.await() })
            assertEquals(JobStage.PAUSED, f.store.get(id)!!.stage)
            assertEquals(0, f.store.get(id)!!.transportResumes)
            assertEquals(4L + 1024 * 1024, File(app.filesDir, "downloads/$id/output.mp4.part").length())
            assertTrue(f.coordinator.resume(id))
            assertEquals(ListenableWorker.Result.retry(), f.worker(id).doWork())
            assertEquals(JobStage.ENQUEUED, f.store.get(id)!!.stage)
            assertFalse(f.phases.contains("FAILED"))
            assertEquals(ListenableWorker.Result.failure(), f.worker(id).doWork())
            assertEquals(JobStage.FAILED, f.store.get(id)!!.stage)
            assertFalse(File(app.filesDir, "downloads/$id").exists())
        }
    }

    @Test fun subMebibyteJobResetsTransportBudgetOnlyWhenBytesReachCompletion() = runBlocking {
        var attempt = 0
        fixture(FakeEngine(), script = { _, dir, emit ->
            attempt++
            val bytes = if (attempt == 1) 2 else 4
            dir.resolve("output.mp4.part").writeBytes(ByteArray(bytes))
            emit(EngineEvent.Progress(bytes.toLong(), Size.Exact(4)))
            TransferResult.Failure(DownloadError.Network)
        }).use { f ->
            val id = "small-complete"
            f.store.enqueue(command(id))
            assertEquals(ListenableWorker.Result.success(), f.worker(id).doWork())
            assertEquals(0L, f.store.get(id)!!.resumeResetBytes)
            assertEquals(1, f.store.get(id)!!.transportResumes)
            assertEquals(ListenableWorker.Result.success(), f.worker(id).doWork())
            assertEquals(4L, f.store.get(id)!!.resumeResetBytes)
            assertEquals(JobStage.ENQUEUED, f.store.get(id)!!.stage)
            assertEquals(4L, File(app.filesDir, "downloads/$id/output.mp4.part").length())
            assertFalse(f.phases.contains("FAILED"))
            assertEquals(ListenableWorker.Result.failure(), f.worker(id).doWork())
            assertEquals(JobStage.FAILED, f.store.get(id)!!.stage)
            assertFalse(File(app.filesDir, "downloads/$id").exists())
        }
    }

    @Test fun typedHttpFailureNeverAutoResumesEvenWhenItsDisplayErrorIsNetwork() = runBlocking {
        for (error in listOf(DownloadError.Failed, DownloadError.Network)) {
            fixture(FakeEngine(), script = { _, dir, _ ->
                dir.resolve("output.mp4.part").writeText("discard")
                TransferResult.Failure(error, cause = DownloadFailureCause.HTTP)
            }).use { f ->
                val id = "http-${error.name}"
                f.store.enqueue(command(id))
                assertEquals(ListenableWorker.Result.failure(), f.worker(id).doWork())
                assertEquals(JobStage.FAILED, f.store.get(id)!!.stage)
                assertTrue(f.store.get(id)!!.retryable); assertFalse(f.store.get(id)!!.hasPartial)
                assertFalse(File(app.filesDir, "downloads/$id").exists())
                assertEquals(1, WorkManager.getInstance(app).getWorkInfosForUniqueWork(id).get().size)
                assertEquals(1, f.phases.count { it == "FAILED" })
            }
        }
    }

    @Test fun stallResumesThreeTimesThenFailsAndGrowthResetsBothBudgets() = runBlocking {
        var grow = false
        var cause = DownloadFailureCause.STALL
        fixture(FakeEngine(), script = { _, dir, emit ->
            val part = dir.resolve("output.mp4.part")
            if (!part.exists()) part.writeText("keep") else if (grow) part.appendBytes(ByteArray(1024 * 1024))
            emit(EngineEvent.Progress(part.length(), Size.Exact(100)))
            TransferResult.Failure(DownloadError.Network, cause = cause)
        }).use { f ->
            f.store.enqueue(command("stall-budget", Size.Exact(16L * 1024 * 1024)))
            assertEquals(ListenableWorker.Result.success(), f.worker("stall-budget").doWork())
            repeat(2) { assertEquals(ListenableWorker.Result.retry(), f.worker("stall-budget").doWork()) }
            assertFalse(f.phases.contains("FAILED"))
            // Progress after three stalls starts a fresh episode; consume transport, then grow again.
            cause = DownloadFailureCause.TRANSPORT; grow = true
            assertEquals(ListenableWorker.Result.success(), f.worker("stall-budget").doWork())
            cause = DownloadFailureCause.STALL
            assertEquals(ListenableWorker.Result.success(), f.worker("stall-budget").doWork())
            grow = false
            repeat(2) { assertEquals(ListenableWorker.Result.retry(), f.worker("stall-budget").doWork()) }
            assertEquals(JobStage.ENQUEUED, f.store.get("stall-budget")!!.stage)
            assertTrue(f.store.get("stall-budget")!!.hasPartial)
            assertEquals(4L + 2 * 1024 * 1024, File(app.filesDir, "downloads/stall-budget/output.mp4.part").length())
            assertEquals(ListenableWorker.Result.failure(), f.worker("stall-budget").doWork())
            assertEquals(JobStage.FAILED, f.store.get("stall-budget")!!.stage)
            assertFalse(File(app.filesDir, "downloads/stall-budget").exists())
            assertEquals(1, f.phases.count { it == "FAILED" })
        }
    }

    @Test fun pauseAndCancelWinWhileAutomaticResumeIsBeingReported() = runBlocking {
        for (cancel in listOf(false, true)) {
            val reporting = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            fixture(FakeEngine(), script = { _, dir, _ ->
                dir.resolve("output.mp4.part").writeText("keep")
                TransferResult.Failure(DownloadError.Network)
            }).use { f ->
                val id = if (cancel) "resume-cancel" else "resume-pause"
                f.coordinator.initialize(); f.store.enqueue(command(id))
                val events = java.util.concurrent.CopyOnWriteArrayList<DownloadProgress>()
                val running = async(Dispatchers.Default) {
                    f.coordinator.execute(id, { WorkInfo.STOP_REASON_NOT_STOPPED }, {}, {}) {
                        events += it
                        if (it.waiting) { reporting.complete(Unit); release.await() }
                    }
                }
                try {
                    withTimeout(5000) { reporting.await() }
                    if (cancel) f.coordinator.cancel(id) else assertTrue(f.coordinator.pause(id))
                } finally { release.complete(Unit) }
                withTimeout(5000) { running.await() }
                val row = f.store.get(id)!!
                assertEquals(if (cancel) JobStage.CANCELLED else JobStage.PAUSED, row.stage)
                assertEquals(!cancel, row.hasPartial)
                assertEquals(!cancel, File(app.filesDir, "downloads/$id/output.mp4.part").exists())
                if (cancel) assertNull(row.sourceUrl) else assertNotNull(row.sourceUrl)
                assertFalse(events.any { it.phase == DownloadPhase.FAILED })
                assertFalse(f.coordinator.continueAfterProgress(id))
                assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork(id).get().all {
                    it.state == WorkInfo.State.CANCELLED
                })
            }
        }
    }

    private fun assertSaved(f: Fixture, id: String) {
        assertEquals(JobStage.COMPLETED, f.store.get(id)!!.stage)
        assertNull(f.store.get(id)!!.sourceUrl)
        assertEquals(1, f.provider.rows.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), f.provider.file.readBytes())
        val notification = resultNotification()
        assertTrue(notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
            .startsWith(app.getString(dev.ahmed.savlino.R.string.download_saved)))
        assertEquals(listOf(app.getString(dev.ahmed.savlino.R.string.open_file), app.getString(dev.ahmed.savlino.R.string.share_file)),
            notification.actions.map { it.title.toString() })
    }

    private fun fixture(engine: FakeEngine, freeBytes: Long = Long.MAX_VALUE,
        downloadFailure: Exception? = null, cancelHook: () -> Unit = {},
        clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }, nextTick: suspend () -> Unit = { delay(250) },
        version: suspend () -> String? = { null },
        script: (suspend (DownloadCommand, File, (EngineEvent) -> Unit) -> TransferResult)? = null): Fixture {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder()
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, params: WorkerParameters) =
                    object : Worker(context, params) { override fun doWork() = Result.success() }
            }).build())
        val provider = TestDownloadsProvider(File(app.cacheDir, "provider.mp4"), app.packageName)
        provider.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val space = SpaceBudget(app.filesDir, app.cacheDir, FreeSpace { freeBytes })
        val publisher = MediaStorePublisher(app.contentResolver, space, app.packageName) { }
        val store = JobStore(app)
        val engineStopped = CompletableDeferred<Unit>()
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val observedEngine = object : EngineAdapter by engine {
            override fun cancel(operationId: String) { cancelHook(); engine.cancel(operationId); engineStopped.complete(Unit) }
            override suspend fun installedVersion() = version()
            override suspend fun download(command: DownloadCommand, directory: File, progress: (EngineEvent) -> Unit): TransferResult {
                attempts.incrementAndGet()
                if (downloadFailure != null) {
                    directory.resolve("partial.mp4").writeBytes(byteArrayOf(1))
                    throw downloadFailure
                }
                return script?.invoke(command, directory, progress) ?: engine.download(command, directory, progress)
            }
        }
        val coordinator = DownloadCoordinator(app, observedEngine, store, publisher, space = space, monotonicMillis = clock, nextProgressTick = nextTick)
        (app as IsolatedTestApplication).coordinatorOverride = coordinator
        return Fixture(engine, provider, store, coordinator, engineStopped, attempts)
    }
    private inner class Fixture(val engine: FakeEngine, val provider: TestDownloadsProvider, val store: JobStore,
        val coordinator: DownloadCoordinator, val engineStopped: CompletableDeferred<Unit>, val attempts: java.util.concurrent.atomic.AtomicInteger) : AutoCloseable {
        val phases = mutableListOf<String>()
        val progressData = mutableListOf<Data>()
        fun worker(id: String) = TestListenableWorkerBuilder<DownloadWorker>(app)
            .setInputData(workDataOf(DownloadWorker.JOB_ID to id))
            .setWorkerFactory(DownloadWorker.Factory(coordinator))
            .setProgressUpdater { _, _, data ->
                phases += data.getString("phase")!!
                progressData += data
                com.google.common.util.concurrent.SettableFuture.create<Void>().apply { set(null) }
            }.build()
        override fun close() = store.close()
    }
}

/** Provider boundary only: actual SQLite, filesystem, pending publication/copy/commit run above it. */
internal class TestDownloadsProvider(val file: File, private val owner: String) : ContentProvider() {
    val rows = linkedMapOf<Long, ContentValues>()
    var onOpen: () -> Unit = {}
    var onCommit: () -> Unit = {}
    private var nextId = 0L
    override fun onCreate() = true
    override fun getType(uri: Uri) = "video/mp4"
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val id = ++nextId
        rows[id] = ContentValues(values!!).apply { put(MediaStore.Downloads.OWNER_PACKAGE_NAME, owner) }
        return ContentUris.withAppendedId(uri, id)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): MatrixCursor {
        val columns = projection ?: arrayOf(MediaStore.Downloads._ID)
        val id = uri.lastPathSegment?.toLongOrNull()
        return MatrixCursor(columns).apply {
            rows.filterKeys { id == null || it == id }.forEach { (key, row) ->
                addRow(columns.map { if (it == MediaStore.Downloads._ID) key else row[it] }.toTypedArray())
            }
        }
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        val row = rows[ContentUris.parseId(uri)] ?: return 0
        values?.let(row::putAll)
        if (values?.getAsInteger(MediaStore.Downloads.IS_PENDING) == 0) onCommit()
        return 1
    }
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int =
        if (rows.remove(ContentUris.parseId(uri)) != null) { file.delete(); 1 } else 0
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        onOpen()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
    }
}
