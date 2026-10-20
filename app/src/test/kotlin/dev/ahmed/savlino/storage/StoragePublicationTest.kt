package dev.ahmed.savlino.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.ContentUris
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files

/** Local provider contract tests, not evidence of Android MediaProvider/device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], manifest = Config.NONE)
class StoragePublicationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: DownloadsProvider
    private lateinit var jobs: JobDirectory
    private lateinit var budget: SpaceBudget
    private lateinit var publisher: MediaStorePublisher
    private val bytes = byteArrayOf(0, 1, 2, 42, 127, -1)

    @Before fun setUp() {
        provider = DownloadsProvider(File(app.cacheDir, "provider").apply { mkdirs() })
        provider.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
        ShadowContentResolver.registerProviderInternal("media", provider)
        jobs = JobDirectory(app.filesDir)
        budget = SpaceBudget(app.filesDir, app.cacheDir, FreeSpace { Long.MAX_VALUE }, marginBytes = 10)
        publisher = MediaStorePublisher(app.contentResolver, budget, app.packageName)
    }

    @Test fun publishCopiesBytesClearsPendingAndReturnsReadableUri() {
        val job = jobs.create("success")
        val staged = job.file("../مقطع/clip", "MP4").apply { writeBytes(bytes) }
        provider.onWrite = { row ->
            assertEquals(1, row.getAsInteger(MediaStore.Downloads.IS_PENDING))
            assertEquals("Download/Nezzel/", row.getAsString(MediaStore.Downloads.RELATIVE_PATH))
            assertEquals("video/mp4", row.getAsString(MediaStore.Downloads.MIME_TYPE))
        }
        val saved = publisher.publish(job, staged, "../مقطع/clip", "MP4", "video/mp4").published()
        assertEquals("مقطعclip.mp4", saved.displayName)
        assertEquals(saved.displayName, provider.row(saved.uri).getAsString(MediaStore.Downloads.DISPLAY_NAME))
        assertEquals(0, provider.row(saved.uri).getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertArrayEquals(bytes, app.contentResolver.openInputStream(saved.uri)!!.use { it.readBytes() })
        assertFalse(job.directory.exists())
        assertEquals(1, provider.rows.size)
    }

    @Test fun stagingExistsUntilCommitSucceeds() {
        val job = jobs.create("durable-order")
        val staged = job.file("clip", "mp4").apply { writeBytes(bytes) }
        provider.afterCommit = { assertArrayEquals(bytes, staged.readBytes()) }
        publisher.publish(job, staged, "clip", "mp4", "video/mp4")
        assertFalse(job.directory.exists())
    }

    @Test fun publicationTitleUsesConfirmedNameInsteadOfNumericFileMetadata() {
        val job = jobs.create("inspected-title")
        val staged = job.file("output", "mp4").apply { writeBytes(bytes) }
        provider.onWrite = { it.put(MediaStore.Downloads.TITLE, "1058356515425744") }
        val saved = publisher.publish(job, staged, "Inspected post title", "mp4", "video/mp4").published()
        assertEquals("Inspected post title.mp4", saved.displayName)
        assertEquals("Inspected post title", provider.row(saved.uri).getAsString(MediaStore.Downloads.TITLE))
    }

    @Test fun providerChosenNameIsReturned() {
        val job = jobs.create("provider-name")
        val staged = job.file("clip", "mp4").apply { writeBytes(bytes) }
        provider.afterInsert = { provider.row(it).put(MediaStore.Downloads.DISPLAY_NAME, "clip (provider).mp4") }
        assertEquals("clip (provider).mp4", publisher.publish(job, staged, "clip", "mp4", "video/mp4").published().displayName)
    }

    @Test fun linkSwappedBetweenLengthCheckAndOpenIsNeverRead() {
        val job = jobs.create("late-link")
        val staged = job.file("clip", "mp4").apply { writeBytes(bytes) }
        val outside = File(app.filesDir, "private.mp4").apply { writeBytes(ByteArray(6) { 99 }) }
        val pending = publisher.insertPending(job, "clip.mp4", "video/mp4")
        val swappingFile = object : File(staged.path) {
            private var swapped = false
            override fun length(): Long {
                val size = super.length()
                if (!swapped) {
                    swapped = true
                    delete()
                    Files.createSymbolicLink(toPath(), outside.toPath())
                }
                return size
            }
        }
        assertError(StorageError.SaveFailed) { publisher.copy(pending, swappingFile) }
        assertTrue(provider.rows.isEmpty())
        assertArrayEquals(ByteArray(6) { 99 }, outside.readBytes())
    }

    @Test fun cancelledBeforeInsertRemovesOnlyThisJobsStaging() {
        val job = jobs.create("cancel-before")
        val staged = job.file("clip", "mp4").apply { writeBytes(bytes) }
        val other = jobs.create("other").file("keep", "mp4").apply { writeBytes(bytes) }
        val signal = CancellationSignal().apply { cancel() }
        assertError(StorageError.Cancelled) { publisher.publish(job, staged, "clip", "mp4", "video/mp4", signal) }
        assertTrue(provider.rows.isEmpty())
        assertFalse(job.directory.exists())
        assertArrayEquals(bytes, other.readBytes())
    }

    @Test fun cancelOpeningCopyDeletesOurPendingRowAndStagingButPreservesForeignRow() {
        val foreign = provider.foreignPending()
        val job = jobs.create("cancel-copy")
        val staged = job.file("clip", "mp4").apply { writeBytes(ByteArray(100_000) { 42 }) }
        val signal = CancellationSignal()
        provider.onWrite = { signal.cancel() }
        provider.beforeDelete = { uri -> assertEquals(0L, provider.file(uri).length()) }
        assertError(StorageError.Cancelled) { publisher.publish(job, staged, "clip", "mp4", "video/mp4", signal) }
        assertFalse(job.directory.exists())
        assertEquals(setOf(ContentUris.parseId(foreign)), provider.rows.keys)
        assertArrayEquals(bytes, app.contentResolver.openInputStream(foreign)!!.use { it.readBytes() })
    }

    @Test fun outputOpenFailureDeletesOnlyInsertedRowAndStagedFiles() {
        val foreign = provider.foreignPending()
        val job = jobs.create("copy-fail")
        val staged = job.file("clip", "mp4").apply { writeBytes(bytes) }
        provider.failWrites = true
        assertError(StorageError.SaveFailed) { publisher.publish(job, staged, "clip", "mp4", "video/mp4") }
        assertFalse(job.directory.exists())
        assertEquals(setOf(ContentUris.parseId(foreign)), provider.rows.keys)
        assertArrayEquals(bytes, app.contentResolver.openInputStream(foreign)!!.use { it.readBytes() })
    }

    @Test fun syncFailureRemovesBytesRowAndStagingWithoutCommit() {
        val foreign = provider.foreignPending()
        val job = jobs.create("sync-fail")
        val staged = job.file("clip", "mp4").apply { writeBytes(bytes) }
        val failing = MediaStorePublisher(app.contentResolver, budget, app.packageName) { throw IOException("sync failed") }
        provider.beforeDelete = { assertArrayEquals(bytes, provider.file(it).readBytes()) }
        provider.afterCommit = { fail("must not commit unsynced bytes") }
        assertError(StorageError.SaveFailed) { failing.publish(job, staged, "clip", "mp4", "video/mp4") }
        assertFalse(job.directory.exists())
        assertEquals(setOf(ContentUris.parseId(foreign)), provider.rows.keys)
    }

    @Test fun cancellationAfterCopyBeforeCommitRemovesPendingOutput() {
        val signal = CancellationSignal()
        val job = jobs.create("cancel-commit")
        val staged = job.file("clip", "mp4").apply { writeBytes(bytes) }
        publisher = MediaStorePublisher(app.contentResolver, budget, app.packageName) { it.sync(); signal.cancel() }
        provider.beforeDelete = { uri -> assertArrayEquals(bytes, provider.file(uri).readBytes()) }
        assertError(StorageError.Cancelled) { publisher.publish(job, staged, "clip", "mp4", "video/mp4", signal) }
        assertTrue(provider.rows.isEmpty())
        assertFalse(job.directory.exists())
    }

    @Test fun cancellationAfterCommitKeepsPublishedFile() {
        val signal = CancellationSignal()
        val job = jobs.create("commit-wins")
        val staged = job.file("clip", "mp4").apply { writeBytes(bytes) }
        provider.afterCommit = { signal.cancel() }
        val saved = publisher.publish(job, staged, "clip", "mp4", "video/mp4", signal).published()
        assertEquals(0, provider.row(saved.uri).getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertArrayEquals(bytes, app.contentResolver.openInputStream(saved.uri)!!.use { it.readBytes() })
        assertFalse(job.directory.exists())
    }

    @Test fun ambiguousCommitFailureNeverDeletesAnAlreadyPublishedRow() {
        val job = jobs.create("ambiguous-commit")
        val staged = job.file("clip", "mp4").apply { writeBytes(bytes) }
        provider.afterCommit = { throw IllegalStateException("fixture lost commit acknowledgement") }
        val result = publisher.publish(job, staged, "clip", "mp4", "video/mp4") as CommitUncertain
        assertEquals(1, provider.rows.size)
        assertEquals(0, provider.row(result.pending.uri).getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertArrayEquals(bytes, staged.readBytes())
        val recovered = newPublisher().recover(result.pending.uri) as PublishedFile
        assertArrayEquals(bytes, app.contentResolver.openInputStream(recovered.uri)!!.use { it.readBytes() })
    }

    @Test fun ambiguousCommitCanStillBePendingAndAbandonedAfterRestart() {
        val job = jobs.create("uncertain-pending")
        val staged = job.file("clip", "mp4").apply { writeBytes(bytes) }
        provider.beforeCommit = { throw IOException("provider acknowledgement unavailable") }
        val result = publisher.publish(job, staged, "clip", "mp4", "video/mp4") as CommitUncertain
        val recovered = newPublisher().recover(Uri.parse(result.pending.uri.toString())) as PendingPublication
        assertArrayEquals(bytes, provider.file(recovered.uri).readBytes())
        assertArrayEquals(bytes, staged.readBytes())
        assertEquals(MissingPublication, newPublisher().abandon(recovered.uri))
        assertTrue(provider.rows.isEmpty())
    }

    @Test fun failedPendingClearKeepsStagingForRetryWithoutRedownload() {
        val job = jobs.create("clear-fail")
        val staged = job.file("clip", "mp4").apply { writeBytes(bytes) }
        provider.failCommit = true
        val failed = publisher.publish(job, staged, "clip", "mp4", "video/mp4") as CommitFailed
        assertEquals(StorageError.SaveFailed, failed.error)
        assertTrue(provider.rows.isEmpty())
        assertArrayEquals(bytes, staged.readBytes())
        provider.failCommit = false
        val saved = publisher.publish(job, staged, "clip", "mp4", "video/mp4").published()
        assertArrayEquals(bytes, provider.file(saved.uri).readBytes())
        assertFalse(job.directory.exists())
    }

    @Test fun fullVolumeFailsBeforeCreatingMediaRowAndCleansStaging() {
        val job = jobs.create("full")
        val staged = job.file("clip", "mp4").apply { writeBytes(bytes) }
        val full = SpaceBudget(app.filesDir, app.cacheDir, FreeSpace { 15 }, marginBytes = 10)
        assertError(StorageError.InsufficientSpace) {
            MediaStorePublisher(app.contentResolver, full, app.packageName).publish(job, staged, "clip", "mp4", "video/mp4")
        }
        assertTrue(provider.rows.isEmpty())
        assertFalse(job.directory.exists())
    }

    @Test fun duplicateNamesUseSuffixAndNeverOverwritePreviousBytes() {
        fun publish(id: String, payload: ByteArray): PublishedFile {
            val job = jobs.create(id)
            return publisher.publish(job, job.file("clip", "mp4").apply { writeBytes(payload) }, "clip", "mp4", "video/mp4").published()
        }
        val first = publish("first", bytes)
        val second = publish("second", byteArrayOf(99))
        assertEquals("clip.mp4", first.displayName)
        assertEquals("clip (1).mp4", second.displayName)
        assertNotEquals(first.uri, second.uri)
        assertArrayEquals(bytes, app.contentResolver.openInputStream(first.uri)!!.use { it.readBytes() })
        assertArrayEquals(byteArrayOf(99), app.contentResolver.openInputStream(second.uri)!!.use { it.readBytes() })
    }

    @Test fun vp9MkvPublishesWithMatroskaMimeAndSuffixedDuplicate() {
        val mime = dev.ahmed.savlino.jobs.mimeTypeFor("mkv")
        assertEquals("video/x-matroska", mime)
        assertEquals("video/webm", dev.ahmed.savlino.jobs.mimeTypeFor("webm"))
        assertEquals("video/mp4", dev.ahmed.savlino.jobs.mimeTypeFor("mp4"))
        fun publish(id: String): PublishedFile {
            val job = jobs.create(id)
            provider.onWrite = { row -> assertEquals("video/x-matroska", row.getAsString(MediaStore.Downloads.MIME_TYPE)) }
            return publisher.publish(job, job.file("Clip", "mkv").apply { writeBytes(bytes) }, "Clip", "mkv", mime).published()
        }
        assertEquals("Clip.mkv", publish("mkv-1").displayName)
        val second = publish("mkv-2")
        assertEquals("Clip (1).mkv", second.displayName)
        assertEquals("video/x-matroska", provider.row(second.uri).getAsString(MediaStore.Downloads.MIME_TYPE))
    }

    @Test fun directMovKeepsItsExtensionAndQuickTimeMimeWhenPublished() {
        val mime = dev.ahmed.savlino.jobs.mimeTypeFor("mov")
        assertEquals("video/quicktime", mime)
        val job = jobs.create("mov")
        val result = publisher.publish(job, job.file("Sample clip", "mov").apply { writeBytes(bytes) },
            "Sample clip", "mov", mime).published()
        assertEquals("Sample clip.mov", result.displayName)
        assertEquals("video/quicktime", provider.row(result.uri).getAsString(MediaStore.Downloads.MIME_TYPE))
    }

    @Test fun longUnicodeDuplicateRetainsSuffixWithinFilenameByteLimit() {
        val title = "م".repeat(150)
        val names = (1..2).map {
            val job = jobs.create("long-$it")
            publisher.publish(job, job.file(title, "mp4").apply { writeBytes(bytes) }, title, "mp4", "video/mp4").published().displayName
        }
        assertNotEquals(names[0], names[1])
        assertTrue(names[1].endsWith(" (1).mp4"))
        names.forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 240) }
    }

    @Test fun pathEscapeAndPreexistingDirectoriesAreRejectedWithoutDeletion() {
        for (id in listOf("../escape", "/tmp/escape", "a/b", "a\\b", ".", "..", "")) {
            assertError(StorageError.InvalidPath) { jobs.create(id) }
        }
        val job = jobs.create("existing")
        val keep = job.file("keep", "mp4").apply { writeBytes(bytes) }
        assertError(StorageError.InvalidPath) { jobs.create("existing") }
        assertArrayEquals(bytes, keep.readBytes())
        val foreign = File(app.filesDir, "outside.mp4").apply { writeBytes(bytes) }
        assertError(StorageError.InvalidPath) { publisher.publish(job, foreign, "clip", "mp4", "video/mp4") }
        assertArrayEquals(bytes, foreign.readBytes())
        assertTrue(provider.rows.isEmpty())
    }

    @Test fun symlinkOutputRejectedAndCleanupNeverFollowsLinks() {
        val outside = File(app.filesDir, "outside").apply { mkdirs() }
        val keep = File(outside, "keep.mp4").apply { writeBytes(bytes) }
        val job = jobs.create("symlinks")
        val link = File(job.directory, "escape.mp4")
        Files.createSymbolicLink(link.toPath(), keep.toPath())
        Files.createSymbolicLink(File(job.directory, "outside").toPath(), outside.toPath())
        assertError(StorageError.InvalidPath) { publisher.publish(job, link, "clip", "mp4", "video/mp4") }
        assertFalse(job.directory.exists())
        assertArrayEquals(bytes, keep.readBytes())
        assertTrue(provider.rows.isEmpty())
    }

    @Test fun completedJobHandleCannotDeleteAReplacementJobWithTheSameId() {
        val old = jobs.create("reused-id")
        old.file("old", "mp4").writeBytes(bytes)
        old.cleanup()
        val replacement = jobs.create("reused-id")
        val keep = replacement.file("keep", "mp4").apply { writeBytes(bytes) }
        old.cleanup()
        assertArrayEquals(bytes, keep.readBytes())
        assertError(StorageError.InvalidPath) { old.file("late", "mp4") }
    }

    @Test fun symlinkedJobOrRootCannotRedirectCreationOrCleanup() {
        val outside = File(app.filesDir, "outside-root").apply { mkdirs() }
        val keep = File(outside, "keep").apply { writeBytes(bytes) }
        val job = jobs.create("swapped")
        assertTrue(job.directory.delete())
        Files.createSymbolicLink(job.directory.toPath(), outside.toPath())
        assertError(StorageError.InvalidPath) { job.file("clip", "mp4") }
        assertError(StorageError.InvalidPath) { job.cleanup() }
        assertArrayEquals(bytes, keep.readBytes())
    }

    @Test fun transferBudgetIncludesStagingOutputAndMarginOnBothVolumes() {
        val low = SpaceBudget(app.filesDir, app.cacheDir, FreeSpace { 109 }, marginBytes = 10)
        assertError(StorageError.InsufficientSpace) { low.beforeTransfer(60, 40) }
        SpaceBudget(app.filesDir, app.cacheDir, FreeSpace { 110 }, marginBytes = 10).beforeTransfer(60, 40)
        val separateFull = SpaceBudget(app.filesDir, app.cacheDir, FreeSpace { if (it == app.cacheDir) 0 else 1000 }, 10)
        assertError(StorageError.InsufficientSpace) { separateFull.beforeTransfer(1, 1) }
    }

    @Test fun unknownSizeRequiresExplicitBoundAndOverflowCannotPassBudget() {
        assertError(StorageError.UnknownSizeRequiresBound) { budget.beforeTransfer(null, 40) }
        val low = SpaceBudget(app.filesDir, app.cacheDir, FreeSpace { 209 }, marginBytes = 10)
        assertError(StorageError.InsufficientSpace) { low.beforeTransfer(null, null, unknownSizeUpperBound = 100) }
        SpaceBudget(app.filesDir, app.cacheDir, FreeSpace { 210 }, 10).beforeTransfer(null, null, 100)
        assertError(StorageError.InsufficientSpace) { budget.beforeTransfer(Long.MAX_VALUE, 1) }
    }

    @Test fun syncPrecedesCommitAndCleanupFollowsCommit() {
        val job = jobs.create("ordered")
        val staged = job.file("clip", "mp4").apply { writeBytes(bytes) }
        val events = mutableListOf<String>()
        publisher = MediaStorePublisher(app.contentResolver, budget, app.packageName) { fd ->
            assertTrue(staged.exists()); fd.sync(); events += "sync"
        }
        provider.afterCommit = { assertTrue(staged.exists()); events += "commit" }
        publisher.publish(job, staged, "clip", "mp4", "video/mp4").published()
        assertEquals(listOf("sync", "commit"), events)
        assertFalse(staged.exists())
    }

    @Test fun byteMismatchFailsTypedAndDeletesOnlyOurPendingRow() {
        val foreign = provider.foreignPending()
        val job = jobs.create("short-copy")
        val staged = job.file("clip", "mp4").apply { writeBytes(bytes) }
        provider.onWrite = { staged.writeBytes(byteArrayOf(42)) }
        provider.afterCommit = { fail("short copy must not commit") }
        assertError(StorageError.CopySizeMismatch) { publisher.publish(job, staged, "clip", "mp4", "video/mp4") }
        assertEquals(setOf(ContentUris.parseId(foreign)), provider.rows.keys)
        assertArrayEquals(bytes, provider.file(foreign).readBytes())
    }

    @Test fun cleanupFailureAfterCommitIsNonfatalAndKeepsPublishedBytes() {
        val job = jobs.create("cleanup-fail")
        val staged = job.file("clip", "mp4").apply { writeBytes(bytes) }
        val outside = File(app.filesDir, "outside-cleanup").apply { mkdirs() }
        provider.afterCommit = {
            assertTrue(staged.delete()); assertTrue(job.directory.delete())
            Files.createSymbolicLink(job.directory.toPath(), outside.toPath())
        }
        val saved = publisher.publish(job, staged, "clip", "mp4", "video/mp4").published()
        assertTrue(saved.cleanupFailed)
        assertEquals(0, provider.row(saved.uri).getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertArrayEquals(bytes, provider.file(saved.uri).readBytes())
    }

    @Test fun persistedUriRecoversAfterInsertCopyAndCommitWithNewInstances() {
        val job = jobs.create("steps")
        val staged = job.file("clip", "mp4").apply { writeBytes(bytes) }
        val inserted = publisher.insertPending(job, "clip.mp4", "video/mp4")
        val persistedUri = Uri.parse(inserted.uri.toString())
        val pending = newPublisher().recover(persistedUri) as PendingPublication
        assertEquals(inserted, pending)
        newPublisher().copy(pending, job.requireOutput(staged))
        val afterCopy = newPublisher().recover(persistedUri) as PendingPublication
        assertArrayEquals(bytes, provider.file(persistedUri).readBytes())
        val published = newPublisher().commit(afterCopy).published()
        assertEquals(published, newPublisher().recover(persistedUri))
        assertEquals(published, newPublisher().abandon(persistedUri))
        assertArrayEquals(bytes, provider.file(persistedUri).readBytes())
        assertTrue(staged.exists()) // journal owner schedules staging cleanup after recording completion.
    }

    @Test fun persistedPendingUriCanBeAbandonedAndForeignOrArbitraryUrisStayUntouched() {
        val job = jobs.create("abandon")
        val pending = publisher.insertPending(job, "clip.mp4", "video/mp4")
        val staged = job.file("clip", "mp4").apply { writeBytes(bytes) }
        newPublisher().copy(pending, staged)
        val output = provider.file(pending.uri)
        assertArrayEquals(bytes, output.readBytes())
        assertEquals(MissingPublication, newPublisher().abandon(Uri.parse(pending.uri.toString())))
        assertFalse(output.exists())
        assertArrayEquals(bytes, staged.readBytes())
        assertEquals(MissingPublication, newPublisher().recover(pending.uri))
        val foreign = provider.foreignPending()
        assertEquals(MissingPublication, newPublisher().recover(foreign))
        assertEquals(MissingPublication, newPublisher().abandon(foreign))
        provider.readableForeignUris += foreign // Even a granted foreign URI is never our publication.
        for (uri in listOf(foreign, Uri.parse("content://other/downloads/1"), Uri.parse("content://media/external_primary/images/media/1"))) {
            assertError(StorageError.InvalidPublication) { newPublisher().recover(uri) }
            assertError(StorageError.InvalidPublication) { newPublisher().abandon(uri) }
        }
        assertEquals(setOf(ContentUris.parseId(foreign)), provider.rows.keys)
        assertArrayEquals(bytes, provider.file(foreign).readBytes())
    }

    @Test fun pendingAndForeignNamesAreHiddenAndProviderNameRemainsAuthoritative() {
        val job = jobs.create("hidden")
        val hidden = publisher.insertPending(job, "clip.mp4", "video/mp4")
        val foreign = provider.foreignPending()
        provider.row(foreign).put(MediaStore.Downloads.DISPLAY_NAME, "unseen.mp4")
        provider.row(foreign).put(MediaStore.Downloads.IS_PENDING, 0)
        val second = publisher.insertPending(jobs.create("hidden-two"), "clip.mp4", "video/mp4")
        assertEquals("clip (provider).mp4", second.displayName)
        assertEquals("clip.mp4", hidden.displayName)
        val third = publisher.insertPending(jobs.create("foreign-name"), "unseen.mp4", "video/mp4")
        assertEquals("unseen (provider).mp4", third.displayName)
    }

    @Test fun duplicateHintsCompareCaseInsensitively() {
        val job = jobs.create("uppercase")
        publisher.publish(job, job.file("CLIP", "mp4").apply { writeBytes(bytes) }, "CLIP", "mp4", "video/mp4").published()
        val hint = publisher.insertPending(jobs.create("lowercase"), "clip.mp4", "video/mp4")
        assertEquals("clip (1).mp4", hint.displayName)
    }

    private fun newPublisher() = MediaStorePublisher(app.contentResolver, budget, app.packageName)
    private fun PublicationResult.published() = this as PublishedFile

    private fun assertError(reason: StorageError, action: () -> Unit) {
        try { action(); fail("Expected $reason") } catch (error: StorageException) { assertEquals(reason, error.error) }
    }

    /** Robolectric has no MediaProvider. Persist actual rows + bytes through ContentResolver/PFD. */
    private inner class DownloadsProvider(private val directory: File) : ContentProvider() {
        val rows = linkedMapOf<Long, ContentValues>()
        val readableForeignUris = mutableSetOf<Uri>()
        private var nextId = 0L
        var failWrites = false
        var failCommit = false
        var onWrite: (ContentValues) -> Unit = {}
        var afterInsert: (Uri) -> Unit = {}
        var beforeDelete: (Uri) -> Unit = {}
        var beforeCommit: () -> Unit = {}
        var afterCommit: () -> Unit = {}
        override fun onCreate() = true
        override fun getType(uri: Uri) = "video/mp4"
        fun row(uri: Uri) = rows.getValue(ContentUris.parseId(uri))
        fun foreignPending(): Uri = insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "foreign.mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Nezzel/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }).also { row(it).put(MediaStore.Downloads.OWNER_PACKAGE_NAME, "other.package"); file(it).writeBytes(bytes) }
        fun file(uri: Uri) = File(directory, ContentUris.parseId(uri).toString())
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            assertEquals("downloads", uri.pathSegments.last())
            val id = ++nextId
            rows[id] = ContentValues(requireNotNull(values)).apply {
                put(MediaStore.Downloads.OWNER_PACKAGE_NAME, app.packageName)
                val name = getAsString(MediaStore.Downloads.DISPLAY_NAME)
                if (rows.values.any { it.getAsString(MediaStore.Downloads.DISPLAY_NAME).equals(name, ignoreCase = true) }) {
                    put(MediaStore.Downloads.DISPLAY_NAME, name.substringBeforeLast('.') + " (provider)." + name.substringAfterLast('.'))
                }
            }
            return ContentUris.withAppendedId(uri, id).also(afterInsert)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = requireNotNull(projection)
            return MatrixCursor(columns).apply {
                rows.forEach { (id, values) ->
                    val path = values.getAsString(MediaStore.Downloads.RELATIVE_PATH)
                    val itemId = uri.lastPathSegment?.toLongOrNull()
                    val owned = values.getAsString(MediaStore.Downloads.OWNER_PACKAGE_NAME) == app.packageName
                    // Item queries can see our own pending rows; collection scans exclude pending/foreign rows.
                    val visible = if (itemId != null) itemId == id && (owned || uri in readableForeignUris)
                        else owned && values.getAsInteger(MediaStore.Downloads.IS_PENDING) == 0
                    if (visible && (itemId != null || selectionArgs == null || path in selectionArgs)) {
                        addRow(columns.map { if (it == MediaStore.Downloads._ID) id else values.get(it) }.toTypedArray())
                    }
                }
            }
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            assertEquals("is_pending = ? AND owner_package_name = ? AND relative_path = ?", selection)
            assertEquals(listOf("1", app.packageName, "Download/Nezzel/"), selectionArgs?.toList())
            assertEquals(app.packageName, row(uri).getAsString(MediaStore.Downloads.OWNER_PACKAGE_NAME))
            assertEquals(1, row(uri).getAsInteger(MediaStore.Downloads.IS_PENDING))
            beforeCommit()
            if (failCommit) return 0
            row(uri).putAll(requireNotNull(values))
            afterCommit()
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val row = rows[ContentUris.parseId(uri)] ?: return 0
            assertEquals("is_pending = ? AND owner_package_name = ? AND relative_path = ?", selection)
            assertEquals(listOf("1", app.packageName, "Download/Nezzel/"), selectionArgs?.toList())
            assertEquals(app.packageName, row.getAsString(MediaStore.Downloads.OWNER_PACKAGE_NAME))
            if (row.getAsInteger(MediaStore.Downloads.IS_PENDING) != 1) return 0
            beforeDelete(uri)
            // Any attempt to remove published output or a foreign pending row fails the test.
            assertEquals(1, row.getAsInteger(MediaStore.Downloads.IS_PENDING))
            assertNotEquals("foreign.mp4", row.getAsString(MediaStore.Downloads.DISPLAY_NAME))
            rows.remove(ContentUris.parseId(uri))
            file(uri).delete()
            return 1
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            row(uri)
            if (mode.contains('w')) {
                onWrite(row(uri))
                if (failWrites) throw FileNotFoundException("fixture write failure")
            }
            return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode))
        }
    }
}
