package dev.ahmed.savlino.jobs

import android.net.Uri
import android.os.CancellationSignal
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.storage.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real SQLite + disk, with simulated transfer/provider process-death boundaries. No network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33, 35], manifest = Config.NONE)
class JobRecoveryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var store: JobStore
    private lateinit var directories: JobDirectory
    private lateinit var root: File
    private lateinit var publisher: DiskPublisher
    private val bytes = byteArrayOf(1, 2, 42, -1)

    @Before fun setUp() {
        store = JobStore(app)
        directories = JobDirectory(app.filesDir)
        root = File(app.filesDir, "downloads")
        publisher = DiskPublisher(File(app.cacheDir, "provider").apply { mkdirs() })
    }
    @After fun close() { store.close() }

    @Test fun enqueueSurvivesReopenWithoutStartingOrDuplicatingTransfer() {
        val command = command("queued")
        assertTrue(store.enqueue(command, Preset.BEST))
        reopen()
        recoverTwice()
        val row = store.get("queued")!!
        assertEquals(JobStage.ENQUEUED, row.stage)
        assertFalse(store.enqueue(command))
        assertEquals(1, store.all().size)
        assertEquals(0, publisher.inserts)
        val restored = row.command()!!
        assertEquals(command.source.link.rawUrl, restored.source.link.rawUrl)
        assertEquals(7, restored.source.revision)
        assertEquals(command.format, restored.format)
        assertEquals("engine-42", restored.engineVersion)
        assertEquals("clip.mp4", restored.filename)
        assertEquals(Preset.BEST, row.preset)
        assertTrue(row.createdAt > 0)
        assertTrue(row.updatedAt >= row.createdAt)
    }

    @Test fun enqueuedOlderThan24HoursExpiresAndScrubsUrl() {
        store.enqueue(command("expired")); store.enqueue(command("fresh"))
        store.writableDatabase.execSQL("UPDATE jobs SET created_at = ? WHERE job_id = ?",
            arrayOf<Any>(System.currentTimeMillis() - 24L * 60 * 60 * 1000 - 1000, "expired"))
        recoverTwice()
        assertEquals(JobStage.FAILED, store.get("expired")!!.stage)
        assertEquals(DownloadError.FormatExpired, store.get("expired")!!.error)
        assertNull(store.get("expired")!!.sourceUrl)
        assertEquals(JobStage.ENQUEUED, store.get("fresh")!!.stage)
    }

    @Test fun interruptedVerifiedPublicationCommitsButUserCancelledDiscards() {
        for ((id, reason) in listOf("interrupted-copy" to DownloadError.Interrupted,
            "cancelled-copy" to DownloadError.Cancelled)) {
            val (job, staged) = staged(id)
            publisher.boundary = "before-commit"
            die { JobRecovery(store, publisher, root).publish(job, staged, "video/mp4") }
            store.requestPublicationFailure(id, reason)
        }
        recoverTwice()
        val completed = store.get("interrupted-copy")!!
        assertEquals(JobStage.COMPLETED, completed.stage)
        assertNull(completed.sourceUrl)
        assertArrayEquals(bytes, publisher.files[completed.pendingUri]!!.readBytes())
        assertEquals(JobStage.CANCELLED, store.get("cancelled-copy")!!.stage)
        assertEquals(1, publisher.files.size)
        assertEquals(1, publisher.commits)
        assertFalse(File(root, "interrupted-copy").exists())
        assertFalse(File(root, "cancelled-copy").exists())
    }

    @Test fun conversionAndOpaqueSourceIdsSurviveReopen() {
        val format = Format("mp3-choice", "mp3", false, true, audioCodec = "mp3", bitrateKbps = 320,
            conversion = AudioConversion.MP3_320, sourceFormatId = "audio/exact?x=1")
        store.enqueue(command("convert", format), Preset.AUDIO_MP3)
        reopen()
        val row = store.get("convert")!!
        assertEquals(format, row.command()!!.format)
        assertEquals(Preset.AUDIO_MP3, row.preset)
    }

    @Test fun transferringRequeuesAndKeepsOnlyValidPartialFiles() {
        val job = directories.create("transfer")
        store.enqueue(command("transfer"))
        assertTrue(store.startTransfer("transfer"))
        // Fake transfer has produced partial bytes when the process dies.
        File(job.directory, "output.mp4.part").writeBytes(bytes.take(2).toByteArray())
        val unrelated = File(app.filesDir, "outside").apply { writeText("keep") }
        val queued = directories.create("queued")
        store.enqueue(command("queued"))
        reopen()
        recoverTwice()
        val row = store.get("transfer")!!
        assertEquals(JobStage.ENQUEUED, row.stage)
        assertNull(row.error)
        assertFalse(row.retryable)
        assertTrue(row.hasPartial)
        assertNotNull(row.sourceUrl)
        assertNotNull(row.command())
        assertArrayEquals(bytes.take(2).toByteArray(), File(job.directory, "output.mp4.part").readBytes())
        assertTrue(queued.directory.exists())
        assertEquals("keep", unrelated.readText())
        assertEquals(0, publisher.inserts)
    }

    @Test fun killedTransfersRejectForeignContentsAndSymlinkButPausedAndQueuedPartialsSurvive() {
        for (id in listOf("foreign", "symlink", "queued-partial", "paused")) store.enqueue(command(id))
        store.startTransfer("foreign"); store.startTransfer("symlink"); store.startTransfer("paused")
        val bad = directories.create("foreign").directory
        bad.resolve("output.mp4.part").writeText("partial"); bad.resolve("unknown").writeText("foreign")
        val outside = File(app.filesDir, "outside").apply { mkdirs(); resolve("precious").writeText("keep") }
        Files.createSymbolicLink(File(root, "symlink").toPath(), outside.toPath())
        directories.create("queued-partial").directory.resolve("output.mp4.part").writeText("queued")
        store.requeue("queued-partial", true)
        directories.create("paused").directory.resolve("output.mp4.part").writeText("paused")
        store.pause("paused", true)
        reopen(); recoverTwice()
        for (id in listOf("foreign", "symlink")) {
            assertEquals(JobStage.ENQUEUED, store.get(id)!!.stage)
            assertFalse(store.get(id)!!.hasPartial); assertFalse(File(root, id).exists())
        }
        assertEquals("keep", outside.resolve("precious").readText())
        assertEquals(JobStage.ENQUEUED, store.get("queued-partial")!!.stage)
        assertTrue(store.get("queued-partial")!!.hasPartial)
        assertEquals("queued", File(root, "queued-partial/output.mp4.part").readText())
        assertEquals(JobStage.PAUSED, store.get("paused")!!.stage)
        assertTrue(store.get("paused")!!.hasPartial)
        assertEquals("paused", File(root, "paused/output.mp4.part").readText())
        assertEquals(0, publisher.inserts)
    }

    @Test fun stagedFileWithoutUriFailsAndRemovesStaging() {
        val (_, staged) = staged("staged")
        reopen()
        recoverTwice()
        assertFailed("staged")
        assertFalse(staged.exists())
        assertEquals(0, publisher.inserts)
    }

    // S3: a death between staged and published keeps the finished file; the job runs again and only saves.
    @Test fun stagedFinishedSourceIsRequeuedWithItsFile() {
        val (_, output) = finished("staged-finished")
        reopen()
        recoverTwice()
        assertRequeued("staged-finished", output)
        assertEquals(0, publisher.inserts)
    }
    @Test fun abandonedPendingInsertWithAFinishedSourceIsRequeued() {
        val (job, output) = finished("pending-finished")
        publisher.boundary = "inserted"
        die { JobRecovery(store, publisher, root).publish(job, output, "video/mp4") }
        val pendingUri = store.get("pending-finished")!!.pendingUri!!
        reopen()
        recoverTwice()
        assertRequeued("pending-finished", output)
        assertFalse(publisher.files.containsKey(pendingUri))
        assertNull(store.get("pending-finished")!!.pendingUri)
    }
    @Test fun stagedWithNoneOrTwoFinishedFilesStillFails() {
        val (_, none) = finished("staged-none"); none.delete()
        val (_, two) = finished("staged-two"); File(two.parentFile, "output.mkv").writeBytes(bytes)
        reopen()
        recoverTwice()
        assertFailed("staged-none"); assertFailed("staged-two")
        assertFalse(two.parentFile.exists())
    }
    @Test fun cancelledPendingInsertIsNeverRequeued() {
        val (job, output) = finished("pending-cancelled")
        publisher.boundary = "inserted"
        die { JobRecovery(store, publisher, root).publish(job, output, "video/mp4") }
        store.requestPublicationFailure("pending-cancelled", DownloadError.Cancelled)
        reopen()
        recoverTwice()
        assertEquals(JobStage.CANCELLED, store.get("pending-cancelled")!!.stage)
        assertFalse(output.exists())
    }

    @Test fun deathInsideInsertBeforeUriWasJournalledNeverTouchesUnrecordedRow() {
        val (job, staged) = staged("unrecorded")
        publisher.boundary = "before-journal"
        die { JobRecovery(store, publisher, root).publish(job, staged, "video/mp4") }
        assertNull(store.get("unrecorded")!!.pendingUri)
        assertEquals(1, publisher.files.size)
        reopen()
        recoverTwice()
        assertFailed("unrecorded")
        assertEquals(1, publisher.files.size)
        assertTrue(publisher.touched.isEmpty())
        assertFalse(staged.exists())
        assertEquals(0, publisher.commits)
    }

    @Test fun deathAfterPendingInsertAbandonsOnlyRecordedPendingUri() {
        val (job, staged) = staged("inserted")
        publisher.boundary = "inserted"
        die { JobRecovery(store, publisher, root).publish(job, staged, "video/mp4") }
        val pendingUri = store.get("inserted")!!.pendingUri!!
        assertEquals(JobStage.PENDING_INSERTED, store.get("inserted")!!.stage)
        val unrelated = publisher.foreign()
        reopen()
        recoverTwice()
        assertFailed("inserted")
        assertFalse(publisher.files.containsKey(pendingUri))
        assertFalse(staged.exists())
        assertEquals("foreign", publisher.files[unrelated]!!.readText())
        assertEquals(setOf(pendingUri), publisher.touched.toSet())
        assertEquals(1, publisher.inserts)
    }

    @Test fun deathDuringCopyDeletesPartialPublicOutputAndStaging() {
        val (job, staged) = staged("copy")
        publisher.boundary = "copy"
        die { JobRecovery(store, publisher, root).publish(job, staged, "video/mp4") }
        val uri = store.get("copy")!!.pendingUri!!
        assertArrayEquals(byteArrayOf(1), publisher.files[uri]!!.readBytes())
        reopen()
        recoverTwice()
        assertFailed("copy")
        assertFalse(publisher.files.containsKey(uri))
        assertFalse(staged.exists())
        assertEquals(1, publisher.inserts)
    }

    @Test fun deathAfterCopyBeforeCommitPublishesSyncedOutput() {
        val (job, staged) = staged("copied")
        publisher.boundary = "before-commit"
        die { JobRecovery(store, publisher, root).publish(job, staged, "video/mp4") }
        val uri = store.get("copied")!!.pendingUri!!
        assertEquals(JobStage.PUBLISHING, store.get("copied")!!.stage)
        assertArrayEquals(bytes, publisher.files[uri]!!.readBytes())
        reopen()
        recoverTwice()
        assertEquals(JobStage.COMPLETED, store.get("copied")!!.stage)
        assertNull(store.get("copied")!!.sourceUrl)
        assertArrayEquals(bytes, publisher.files[uri]!!.readBytes())
        assertEquals(1, publisher.files.size)
        assertFalse(publisher.hasPending())
        assertFalse(staged.exists())
    }

    @Test fun deathAfterCommitAdoptsSameUriAndNeverPublishesAgain() {
        val (job, staged) = staged("committed")
        publisher.boundary = "committed"
        die { JobRecovery(store, publisher, root).publish(job, staged, "video/mp4") }
        val uri = store.get("committed")!!.pendingUri!!
        assertTrue(staged.exists())
        reopen()
        recoverTwice()
        val row = store.get("committed")!!
        assertEquals(JobStage.COMPLETED, row.stage)
        assertEquals(uri, row.pendingUri)
        assertNull(row.sourceUrl)
        assertEquals("provider-name.mp4", row.displayName)
        assertArrayEquals(bytes, publisher.files[uri]!!.readBytes())
        assertFalse(job.directory.exists())
        assertEquals(1, publisher.inserts)
        assertEquals(1, publisher.commits)
    }

    @Test fun successfulPublishJournalsBeforeCopyAndCompletionBeforeCleanup() {
        val (job, staged) = staged("success")
        val result = JobRecovery(store, publisher, root).publish(job, staged, "video/mp4") as PublishedFile
        assertEquals(JobStage.COMPLETED, store.get("success")!!.stage)
        assertNull(store.get("success")!!.sourceUrl)
        assertFalse(job.directory.exists())
        assertArrayEquals(bytes, publisher.files[result.uri]!!.readBytes())
        reopen()
        recoverTwice()
        assertEquals(1, publisher.inserts)
    }

    @Test fun startupRemovesLeftoverTerminalStagingWithoutTouchingPublishedFile() {
        val (job, staged) = staged("cleanup")
        val pending = publisher.insertPending(job, "clip.mp4", "video/mp4")
        check(store.recordPending("cleanup", pending))
        publisher.copy(pending, staged)
        check(store.markPublishing("cleanup"))
        val result = publisher.commit(pending) as PublishedFile
        check(store.complete("cleanup", result))
        assertFalse(staged.exists())
        // Model leftover bytes from an earlier terminal cleanup failure.
        job.directory.mkdirs()
        staged.writeBytes(bytes)
        assertNull(store.get("cleanup")!!.sourceUrl)
        reopen()
        recoverTwice()
        assertFalse(staged.exists())
        assertArrayEquals(bytes, publisher.files[result.uri]!!.readBytes())
        assertEquals(1, publisher.inserts)
        assertEquals(1, publisher.commits)
    }

    @Test fun ambiguousCommitReconcilesCommittedOutputInProcess() {
        val (job, staged) = staged("uncertain")
        publisher.boundary = "uncertain"
        assertTrue(JobRecovery(store, publisher, root).publish(job, staged, "video/mp4") is CommitUncertain)
        assertFalse(publisher.hasPending())
        assertEquals(JobStage.COMPLETED, store.get("uncertain")!!.stage)
        assertFalse(staged.exists())
        assertEquals(1, publisher.inserts)
    }

    @Test fun failedCommitReconcilesAndRemovesStagingInProcess() {
        val (job, staged) = staged("failed-commit")
        publisher.boundary = "failed"
        assertTrue(JobRecovery(store, publisher, root).publish(job, staged, "video/mp4") is CommitFailed)
        assertFailed("failed-commit")
        assertNull(store.get("failed-commit")!!.pendingUri)
        assertFalse(publisher.hasPending())
        assertTrue(publisher.files.isEmpty())
        assertFalse(staged.exists())
        assertEquals(1, publisher.inserts)
    }

    @Test fun commitRacingAbandonIsAdoptedInsteadOfMarkedFailed() {
        val (job, staged) = staged("racing")
        publisher.boundary = "before-commit"
        die { JobRecovery(store, publisher, root).publish(job, staged, "video/mp4") }
        publisher.boundary = "race"
        reopen()
        recoverTwice()
        val row = store.get("racing")!!
        assertEquals(JobStage.COMPLETED, row.stage)
        assertNotNull(row.pendingUri)
        assertTrue(publisher.files.containsKey(row.pendingUri))
        assertArrayEquals(bytes, publisher.files[row.pendingUri]!!.readBytes())
        assertNull(row.sourceUrl)
        assertFalse(staged.exists())
        assertEquals(1, publisher.inserts)
    }

    @Test fun failedAbandonKeepsUriUntilDeletionIsAcknowledged() {
        val (job, staged) = staged("delete-fails")
        publisher.boundary = "copy"
        die { JobRecovery(store, publisher, root).publish(job, staged, "video/mp4") }
        publisher.boundary = "delete-fails"
        reopen()
        recoverTwice()
        assertEquals(JobStage.PENDING_INSERTED, store.get("delete-fails")!!.stage)
        assertNotNull(store.get("delete-fails")!!.sourceUrl)
        assertTrue(staged.exists())
        publisher.boundary = ""
        recoverTwice()
        assertFailed("delete-fails")
        assertTrue(publisher.files.isEmpty())
    }

    @Test fun pendingPublicationCannotBeCancelledFailedOrReplacedWithAnotherUri() {
        val (job, staged) = staged("absorbing")
        publisher.boundary = "copy"
        die { JobRecovery(store, publisher, root).publish(job, staged, "video/mp4") }
        assertFalse(store.cancel("absorbing"))
        assertFalse(store.fail("absorbing", DownloadError.Network))
        assertFalse(store.complete("absorbing", PublishedFile(Uri.parse("content://other/unrecorded/1"), "other.mp4")))
        assertNotNull(store.get("absorbing")!!.sourceUrl)
        recoverTwice()
        assertFailed("absorbing")
    }

    @Test fun terminalFailureScrubsUrlAndCannotRestart() {
        store.enqueue(command("failure"))
        assertTrue(store.fail("failure", DownloadError.Network, retryable = false))
        reopen()
        val row = store.get("failure")!!
        assertNull(row.sourceUrl)
        assertNull(row.command())
        assertEquals(DownloadError.Network, row.error)
        assertFalse(row.retryable)
        assertFalse(store.startTransfer("failure"))
        assertFalse(store.enqueue(command("failure")))
        recoverTwice()
    }

    @Test fun exactAndUnknownSizesRoundTripWithoutInventedBytes() {
        store.enqueue(command("exact", Format("exact-id", "m4a", false, true, size = Size.Exact(5678))))
        store.enqueue(command("unknown", Format("unknown-id", "m4a", false, true)))
        reopen()
        assertEquals(Size.Exact(5678), store.get("exact")!!.format.size)
        assertEquals(Size.Unknown, store.get("unknown")!!.format.size)
    }

    @Test fun schemaUsesDurableRollbackJournalAndSecureDelete() {
        val db = store.writableDatabase
        assertEquals(JobStore.DATABASE_VERSION, db.version)
        for ((pragma, expected) in listOf("journal_mode" to "delete", "synchronous" to "2", "secure_delete" to "1")) {
            db.rawQuery("PRAGMA $pragma", null).use { check(it.moveToFirst()); assertEquals(expected, it.getString(0)) }
        }
    }

    @Test fun providerFailureLeavesJournalForLaterRecoveryWithoutNewOutput() {
        val (job, staged) = staged("unavailable")
        publisher.boundary = "copy"
        die { JobRecovery(store, publisher, root).publish(job, staged, "video/mp4") }
        publisher.unavailable = true
        reopen()
        JobRecovery(store, publisher, root).recover()
        assertEquals(JobStage.PENDING_INSERTED, store.get("unavailable")!!.stage)
        assertNotNull(store.get("unavailable")!!.pendingUri)
        assertTrue(staged.exists())
        publisher.unavailable = false
        recoverTwice()
        assertFailed("unavailable")
        assertEquals(1, publisher.inserts)
    }

    @Test fun recoverySkipsInvalidIdsAndSymlinksAndNeverFollowsNestedLinks() {
        val outside = File(app.filesDir, "outside").apply { mkdirs() }
        val precious = File(outside, "precious").apply { writeText("keep") }
        root.mkdirs()
        val orphan = File(root, "valid-orphan").apply { mkdirs() }
        Files.createSymbolicLink(File(orphan, "nested").toPath(), outside.toPath())
        val invalid = File(root, ".unknown").apply { mkdirs() }
        Files.createSymbolicLink(File(root, "valid-link").toPath(), outside.toPath())
        val plain = File(root, "valid-file").apply { writeText("keep file") }
        recoverTwice()
        assertFalse(orphan.exists())
        assertEquals("keep", precious.readText())
        assertTrue(invalid.exists())
        assertTrue(Files.isSymbolicLink(File(root, "valid-link").toPath()))
        assertEquals("keep file", plain.readText())
    }

    @Test fun symlinkRootCannotDeleteOutsideFiles() {
        val outside = File(app.filesDir, "outside").apply { mkdirs() }
        val valid = File(outside, "valid-id").apply { mkdirs() }
        Files.createSymbolicLink(root.toPath(), outside.toPath())
        recoverTwice()
        assertTrue(valid.exists())
    }

    @Test fun terminalRowsAreScrubbedAbsorbingAndOnlyNewestHundredRemain() {
        for (index in 0..101) {
            val id = "cancel-$index"
            store.enqueue(command(id))
            val job = directories.create(id)
            job.file("partial", "mp4").writeBytes(bytes)
            assertTrue(store.cancel(id))
            assertNull(store.get(id)!!.sourceUrl)
            assertFalse(store.startTransfer(id))
            assertFalse(store.enqueue(command(id)))
        }
        store.enqueue(command("active"))
        reopen()
        recoverTwice()
        assertEquals(101, store.all().size)
        assertNull(store.get("cancel-0"))
        assertNull(store.get("cancel-1"))
        assertNotNull(store.get("cancel-101"))
        assertEquals(JobStage.ENQUEUED, store.get("active")!!.stage)
        assertTrue(root.listFiles()!!.isEmpty())
        assertTrue(store.all().filter { it.stage.terminal }.all { it.sourceUrl == null })
    }

    @Test fun invalidJobIdsCannotEnterJournalOrEscapeRoot() {
        for (id in listOf("_bad", "-bad", "a".repeat(81))) {
            try { store.enqueue(command(id)); fail("invalid job ID accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertTrue(store.all().isEmpty())
    }


    @Test fun storeReadReturnsWhileCopyIsBlocked() {
        val (job, staged) = staged("blocked-copy")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        publisher.copyStart = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        try {
            val publication = pool.submit<PublicationResult> {
                JobRecovery(store, publisher, root).publish(job, staged, "video/mp4")
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val read = pool.submit<JobRecord?> { store.get("blocked-copy") }
            assertEquals(JobStage.PENDING_INSERTED, read.get(1, TimeUnit.SECONDS)!!.stage)
            release.countDown()
            assertTrue(publication.get(5, TimeUnit.SECONDS) is PublishedFile)
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun cancellationDuringCopyReconcilesOnlyItsRowInProcess() {
        val (job, staged) = staged("cancel-copy")
        val other = directories.create("active-transfer")
        store.enqueue(command("active-transfer"))
        store.startTransfer("active-transfer")
        val partial = other.file("partial", "mp4").apply { writeBytes(bytes) }
        val cancellation = CancellationSignal()
        publisher.copyStart = { cancellation.cancel(); cancellation.throwIfCanceled() }
        expectFailure { JobRecovery(store, publisher, root).publish(job, staged, "video/mp4", cancellation) }
        assertCancelledAndClean("cancel-copy")
        assertEquals(JobStage.TRANSFERRING, store.get("active-transfer")!!.stage)
        assertArrayEquals(bytes, partial.readBytes())
    }

    @Test fun cancellationAfterCopyReconcilesInProcess() {
        val (job, staged) = staged("cancel-after-copy")
        val cancellation = CancellationSignal()
        publisher.copyEnd = { cancellation.cancel() }
        expectFailure { JobRecovery(store, publisher, root).publish(job, staged, "video/mp4", cancellation) }
        assertCancelledAndClean("cancel-after-copy")
        assertEquals(0, publisher.commits)
    }

    @Test fun copyFailureReconcilesInProcess() {
        val (job, staged) = staged("copy-error")
        publisher.copyEnd = { throw java.io.IOException("copy failed") }
        expectFailure { JobRecovery(store, publisher, root).publish(job, staged, "video/mp4") }
        assertFailed("copy-error")
        assertFalse(staged.exists())
        assertFalse(publisher.hasPending())
        assertTrue(publisher.files.isEmpty())
    }

    @Test fun copyNoSpaceFailuresKeepTypedErrorAndAbandonOnlyPendingOutput() {
        val failures = listOf(
            java.io.IOException("write failed: ENOSPC"),
            java.io.IOException("disk write failed", android.system.ErrnoException("write", android.system.OsConstants.ENOSPC)),
            StorageException(StorageError.InsufficientSpace),
        )
        for ((index, failure) in failures.withIndex()) {
            val id = "copy-full-$index"
            val (job, staged) = staged(id)
            publisher.copyEnd = { throw failure }
            expectFailure { JobRecovery(store, publisher, root).publish(job, staged, "video/mp4") }
            val row = store.get(id)!!
            assertEquals(JobStage.FAILED, row.stage)
            assertEquals(DownloadError.InsufficientSpace, row.error)
            assertNotNull(row.sourceUrl)
            assertNull(row.pendingUri)
            assertTrue(row.retryable)
            assertFalse(job.directory.exists())
            assertFalse(publisher.hasPending())
            assertTrue(publisher.files.isEmpty())
        }
    }

    @Test fun noSpaceReasonSurvivesDeferredAbandonAndJournalReopen() {
        val (job, staged) = staged("deferred-full")
        publisher.copyEnd = {
            publisher.unavailable = true
            throw java.io.IOException("No space left on device")
        }
        expectFailure { JobRecovery(store, publisher, root).publish(job, staged, "video/mp4") }
        assertEquals(JobStage.PENDING_INSERTED, store.get("deferred-full")!!.stage)
        assertEquals(DownloadError.InsufficientSpace, store.get("deferred-full")!!.error)
        assertNotNull(store.get("deferred-full")!!.pendingUri)
        assertTrue(staged.exists())
        reopen()
        publisher.unavailable = false
        recoverTwice()
        val row = store.get("deferred-full")!!
        assertEquals(JobStage.FAILED, row.stage)
        assertEquals(DownloadError.InsufficientSpace, row.error)
        assertNotNull(row.sourceUrl)
        assertNull(row.pendingUri)
        assertTrue(row.retryable)
        assertFalse(job.directory.exists())
        assertFalse(publisher.hasPending())
        assertTrue(publisher.files.isEmpty())
    }

    @Test fun acknowledgedCommitNoSpaceKeepsSpaceErrorThroughAbandon() {
        val (job, staged) = staged("commit-full")
        val failingPublisher = object : Publisher by publisher {
            override fun commit(pending: PendingPublication): PublicationResult =
                CommitFailed(pending, StorageError.InsufficientSpace)
        }
        val result = JobRecovery(store, failingPublisher, root).publish(job, staged, "video/mp4")
        assertTrue(result is CommitFailed)
        val row = store.get("commit-full")!!
        assertEquals(JobStage.FAILED, row.stage)
        assertEquals(DownloadError.InsufficientSpace, row.error)
        assertNull(row.pendingUri)
        assertTrue(row.retryable)
        assertNotNull(row.sourceUrl)
        assertFalse(job.directory.exists())
        assertTrue(publisher.files.isEmpty())
    }

    @Test fun stagedBoundaryNoSpaceFailsBeforePendingInsertAndCleansOnlyItsJob() {
        val (job, staged) = staged("staged-full")
        val (_, keep) = staged("staged-keep")
        val noSpacePublisher = object : Publisher by publisher {
            override fun insertPending(job: JobDirectory.Job, name: String, mimeType: String): PendingPublication {
                assertEquals(JobStage.STAGED, store.get("staged-full")!!.stage)
                throw StorageException(StorageError.InsufficientSpace)
            }
        }
        expectFailure { JobRecovery(store, noSpacePublisher, root).publish(job, staged, "video/mp4") }
        val row = store.get("staged-full")!!
        assertEquals(JobStage.FAILED, row.stage)
        assertEquals(DownloadError.InsufficientSpace, row.error)
        assertNull(row.pendingUri)
        assertFalse(job.directory.exists())
        assertArrayEquals(bytes, keep.readBytes())
    }

    @Test fun cancelWinsNoSpaceAtStagedAndPendingBoundaries() {
        for (boundary in listOf("staged", "pending")) {
            val id = "cancel-full-$boundary"
            val (job, staged) = staged(id)
            val signal = CancellationSignal()
            val failure = {
                signal.cancel()
                throw java.io.IOException("write failed: ENOSPC")
            }
            val failingPublisher = object : Publisher by publisher {
                override fun insertPending(job: JobDirectory.Job, name: String, mimeType: String): PendingPublication {
                    if (boundary == "staged") failure()
                    return publisher.insertPending(job, name, mimeType)
                }
                override fun copy(pending: PendingPublication, source: File, cancellation: CancellationSignal) = failure()
            }
            expectFailure { JobRecovery(store, failingPublisher, root).publish(job, staged, "video/mp4", signal) }
            assertCancelledAndClean(id)
        }
    }

    @Test fun deferredCancellationCannotBeOverwrittenByLaterNoSpaceReconciliation() {
        val (job, staged) = staged("cancel-then-full")
        publisher.copyEnd = { publisher.unavailable = true; throw java.io.IOException("copy failed") }
        val recovery = JobRecovery(store, publisher, root)
        expectFailure { recovery.publish(job, staged, "video/mp4") }
        recovery.reconcile("cancel-then-full", cancellationRequested = true)
        recovery.reconcile("cancel-then-full", publicationError = DownloadError.InsufficientSpace)
        reopen()
        publisher.unavailable = false
        recoverTwice()
        assertCancelledAndClean("cancel-then-full")
    }

    @Test fun noSpaceThenCancelWhileAbandonDeferredEndsCancelled() {
        val (job, staged) = staged("full-then-cancel")
        publisher.copyEnd = { publisher.unavailable = true; throw java.io.IOException("ENOSPC") }
        val recovery = JobRecovery(store, publisher, root)
        expectFailure { recovery.publish(job, staged, "video/mp4") }
        assertEquals(DownloadError.InsufficientSpace, store.get("full-then-cancel")!!.error)
        recovery.reconcile("full-then-cancel", cancellationRequested = true)
        reopen()
        publisher.unavailable = false
        recoverTwice()
        assertCancelledAndClean("full-then-cancel")
    }

    @Test fun recordPendingFailureAbandonsNewUriInProcess() {
        val (job, staged) = staged("journal-error")
        publisher.insertEnd = { store.cancel("journal-error") }
        expectFailure { JobRecovery(store, publisher, root).publish(job, staged, "video/mp4") }
        assertCancelledAndClean("journal-error")
    }

    @Test fun directTerminalTransitionsRemoveOnlyTheirStaging() {
        for (id in listOf("cancel-direct", "fail-direct")) staged(id)
        val (_, keep) = staged("keep")
        assertTrue(store.cancel("cancel-direct"))
        assertTrue(store.fail("fail-direct", DownloadError.Network))
        assertFalse(File(root, "cancel-direct").exists())
        assertFalse(File(root, "fail-direct").exists())
        assertArrayEquals(bytes, keep.readBytes())
    }

    @Test fun newerJournalDowngradesToEmptyUsableCurrentVersion() {
        store.enqueue(command("old-cache"))
        store.writableDatabase.version = JobStore.DATABASE_VERSION + 1
        reopen()
        assertEquals(JobStore.DATABASE_VERSION, store.writableDatabase.version)
        assertTrue(store.all().isEmpty())
        assertTrue(store.enqueue(command("new-cache")))
    }

    private fun assertCancelledAndClean(id: String) {
        assertEquals(JobStage.CANCELLED, store.get(id)!!.stage)
        assertEquals(DownloadError.Cancelled, store.get(id)!!.error)
        assertNull(store.get(id)!!.sourceUrl)
        assertNull(store.get(id)!!.pendingUri)
        assertFalse(File(root, id).exists())
        assertFalse(publisher.hasPending())
        assertTrue(publisher.files.isEmpty())
    }

    private fun expectFailure(action: () -> Unit) {
        try { action(); fail("expected publication failure") } catch (_: Exception) { }
    }

    /** STAGED with the engine's finished file (output.<ext>, the name a rerun adopts) and a leftover partial. */
    private fun finished(id: String): Pair<JobDirectory.Job, File> {
        store.enqueue(command(id))
        check(store.startTransfer(id, "recipe"))
        val job = directories.create(id)
        val file = File(job.directory, "output.mp4").apply { writeBytes(bytes) }
        File(job.directory, "output.f1.mp4.part").writeBytes(bytes)
        check(store.markStaged(id))
        return job to file
    }
    private fun assertRequeued(id: String, output: File) {
        val row = store.get(id)!!
        assertEquals(JobStage.ENQUEUED, row.stage); assertTrue(row.hasPartial); assertNull(row.error)
        assertEquals("recipe", row.resumeRecipe)
        assertArrayEquals(bytes, output.readBytes())
    }
    private fun staged(id: String): Pair<JobDirectory.Job, File> {
        store.enqueue(command(id))
        check(store.startTransfer(id))
        val job = directories.create(id)
        val file = job.file("clip", "mp4").apply { writeBytes(bytes) }
        check(store.markStaged(id))
        return job to file
    }
    private fun command(id: String, format: Format = Format("opaque-choice", "mp4", true, false,
        videoCodec = "h264", audioCodec = "aac", width = 1280, height = 720, bitrateKbps = 256,
        size = Size.Estimated(1234), audioFormatId = "audio/exact", requiresMerge = true,
        sourceFormatId = "video/exact?signature=abc")): DownloadCommand {
        val source = LinkRevision(LinkInput.parse("https://example.com/watch?token=private&x=%2f")!!, 7)
        return (DownloadCommand.confirm(id, Inspection(source, "engine-42", listOf(format)), source,
            format.id, "clip") as ConfirmResult.Confirmed).command
    }
    private fun reopen() { store.close(); store = JobStore(app) }
    private fun recoverTwice() {
        val recovery = JobRecovery(store, publisher, root)
        recovery.recover()
        val rows = snapshot()
        val paths = stagingSnapshot()
        val outputs = publisher.files.mapValues { it.value.readBytes().toList() }
        recovery.recover()
        assertEquals(rows, snapshot())
        assertEquals(paths, stagingSnapshot())
        assertEquals(outputs, publisher.files.mapValues { it.value.readBytes().toList() })
    }
    private fun snapshot() = store.all().map {
        listOf(it.jobId, it.stage, it.sourceUrl, it.error, it.retryable, it.pendingUri, it.displayName,
            it.createdAt, it.updatedAt, it.engineVersion, it.format, it.filename, it.preset)
    }
    private fun stagingSnapshot(): List<String> = if (!Files.exists(root.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        emptyList() else Files.walk(root.toPath()).use { paths -> paths.map { root.toPath().relativize(it).toString() }.sorted().toList() }

    private fun assertFailed(id: String) {
        val row = store.get(id)!!
        assertEquals(JobStage.FAILED, row.stage)
        assertEquals(DownloadError.SaveFailed, row.error)
        assertTrue(row.retryable)
        assertNotNull(row.sourceUrl)
        assertNull(row.command())
    }
    private fun die(action: () -> Unit) {
        try { action(); fail("expected simulated process death") } catch (_: ProcessDeath) { }
    }
    private class ProcessDeath : Error()

    /** Provider rows and their bytes outlive each new JobStore/JobRecovery instance. */
    private inner class DiskPublisher(private val root: File) : Publisher {
        val files = linkedMapOf<Uri, File>()
        private val pending = mutableSetOf<Uri>()
        val touched = mutableListOf<Uri>()
        var inserts = 0
        var commits = 0
        var boundary = ""
        var unavailable = false
        var copyStart: () -> Unit = {}
        var copyEnd: () -> Unit = {}
        var insertEnd: () -> Unit = {}
        fun hasPending() = pending.isNotEmpty()
        override fun insertPending(job: JobDirectory.Job, name: String, mimeType: String): PendingPublication {
            inserts++
            val uri = Uri.parse("content://media/external_primary/downloads/$inserts")
            files[uri] = File(root, "$inserts").apply { writeBytes(byteArrayOf()) }
            pending += uri
            if (boundary == "before-journal") throw ProcessDeath()
            insertEnd()
            return PendingPublication(uri, "provider-name.mp4")
        }
        override fun copy(pending: PendingPublication, source: File, cancellation: CancellationSignal) {
            val row = store.all().single { it.pendingUri == pending.uri }
            assertEquals(JobStage.PENDING_INSERTED, row.stage)
            assertEquals("provider-name.mp4", row.displayName)
            if (boundary == "inserted") throw ProcessDeath()
            copyStart()
            files[pending.uri]!!.writeBytes(byteArrayOf(1))
            if (boundary == "copy") throw ProcessDeath()
            files[pending.uri]!!.writeBytes(source.readBytes())
            copyEnd()
        }
        override fun commit(pending: PendingPublication): PublicationResult {
            assertEquals(JobStage.PUBLISHING, store.all().single { it.pendingUri == pending.uri }.stage)
            if (boundary == "before-commit") { boundary = ""; throw ProcessDeath() }
            if (boundary == "failed" || boundary == "race") return CommitFailed(pending)
            commits++
            this.pending -= pending.uri
            if (boundary == "committed") throw ProcessDeath()
            if (boundary == "uncertain") return CommitUncertain(pending)
            return PublishedFile(pending.uri, pending.displayName)
        }
        override fun recover(uri: Uri): RecoveryResult {
            touched += uri
            if (unavailable) throw StorageException(StorageError.SaveFailed)
            return when {
                uri !in files -> MissingPublication
                uri in pending -> PendingPublication(uri, "provider-name.mp4")
                else -> PublishedFile(uri, "provider-name.mp4")
            }
        }
        override fun abandon(uri: Uri): RecoveryResult {
            if (boundary == "race") pending -= uri
            if (boundary != "delete-fails" && uri in pending) { pending -= uri; files.remove(uri)!!.delete() }
            return recover(uri)
        }
        fun foreign(): Uri = Uri.parse("content://other/unrelated/42").also {
            files[it] = File(root, "foreign").apply { writeText("foreign") }
        }
        override fun publish(job: JobDirectory.Job, stagedFile: File, title: String, extension: String,
            mimeType: String, cancellation: CancellationSignal): PublicationResult = error("journal must use step API")
    }
}
