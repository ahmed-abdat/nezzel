package dev.ahmed.savlino.media

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import dev.ahmed.savlino.engine.mediaimage.ImageSaveResult
import dev.ahmed.savlino.storage.*
import java.io.File
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Local provider contract tests (no Android MediaProvider); device acceptance is a separate on-device check. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], manifest = Config.NONE)
class ImageSaverTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val collection: Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val fixture = ByteArray(4096) { (it * 31).toByte() }
    private lateinit var provider: Provider
    private lateinit var publisher: MediaStorePublisher
    private lateinit var staging: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val space = object : SpaceGate {
        override fun beforeTransfer(stagingBytes: Long?, outputBytes: Long?, unknownSizeUpperBound: Long?) = Unit
        override fun beforePublication(outputBytes: Long) = Unit
    }

    @Before fun setUp() {
        provider = Provider(File(app.cacheDir, "image-provider").apply { deleteRecursively(); mkdirs() })
        provider.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
        ShadowContentResolver.registerProviderInternal("media", provider)
        publisher = MediaStorePublisher(app.contentResolver, space, app.packageName)
        staging = File(app.noBackupFilesDir, "image-save").apply { deleteRecursively() }
    }
    @After fun tearDown() { scope.cancel() }

    private val selection = MediaSelection(LinkRevision(LinkInput.parse("https://example.com/p.jpg")!!, 1),
        MediaAdapterIdentity("public-image", 1, "android-image-1", 1),
        MediaRenditionRef(MediaItemRef("public-image", "item"), "rendition"), MediaKind.IMAGE, MediaInspectionValidity.processLocal())
    private val proof = MediaImageProof("a".repeat(64), fixture.size.toLong())
    private val image = MediaImageMetadata(MediaImageFormat.JPEG, Size.Exact(fixture.size.toLong()), 640, 480, proof)

    @Test fun treeImageSaveDoesNotPreflightDownloadsSpaceAndDefaultStillDoes() = runBlocking {
        val documents = SaveDestinationTest.Provider()
        documents.attachInfo(app, ProviderInfo().apply {
            authority = "test.documents"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        })
        ShadowContentResolver.registerProviderInternal("test.documents", documents)
        org.robolectric.shadows.ShadowStatFs.registerStats(staging.absolutePath, 1_000_000, 1_000_000, 1_000_000)
        org.robolectric.shadows.ShadowStatFs.registerStats(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath, 0, 0, 0)
        val settings = SaveDestinations(app)
        val saver = MediaServices(app, { error("Image save must not load video engine") }, scope).saver
        settings.choose(SaveDestinationTest.tree)
        assertTrue(saver.save(selection, image, "photo"))
        saver.awaitIdle()
        val treeResult = saver.outcome.value as ImageSaveState.Failed
        // The process-local reference is absent: reaching that check proves the transfer was admitted.
        assertEquals(ImageFailure.STALE, treeResult.image)
        assertNull(treeResult.storage)
        settings.choose(null)
        assertTrue(saver.save(selection, image, "photo"))
        saver.awaitIdle()
        assertEquals(StorageError.InsufficientSpace, (saver.outcome.value as ImageSaveState.Failed).storage)
    }

    /** Writes the fixture; [gate] holds the transfer open mid-body until released or cancelled. */
    private inner class FakeFetch(val result: () -> ImageSaveResult = { ImageSaveResult.Success(image) },
        val gate: CompletableDeferred<Unit>? = null) : ImageFetch {
        val calls = AtomicInteger()
        val cancels = AtomicInteger()
        val midTransfer = CompletableDeferred<Unit>()
        override suspend fun transfer(selection: MediaSelection, sink: OutputStream): ImageSaveResult {
            calls.incrementAndGet()
            sink.write(fixture, 0, fixture.size / 2)
            midTransfer.complete(Unit)
            gate?.await()
            if (cancels.get() > 0) return ImageSaveResult.Failure(ImageFailure.CANCELLED)
            sink.write(fixture, fixture.size / 2, fixture.size - fixture.size / 2)
            return result()
        }
        override fun cancel() { cancels.incrementAndGet(); gate?.complete(Unit) }
    }

    private fun saver(fetch: ImageFetch, publisher: Publisher = DestinationPublisher(app, this.space), space: SpaceGate = this.space) =
        ImageSaver(fetch, publisher, staging, space, scope) { ownPendingImages(app.contentResolver, app.packageName) }
    private fun ImageSaver.finish(): ImageSaveState = runBlocking { withTimeout(10_000) { awaitIdle() }; outcome.value }
    private fun stagingEntries(): List<File> = staging.walkTopDown().filter { it.isFile }.toList() +
        File(staging, "downloads").listFiles().orEmpty().toList()

    private fun treeProvider(): SaveDestinationTest.Provider = SaveDestinationTest.Provider().also {
        it.attachInfo(app, ProviderInfo().apply {
            authority = "test.documents"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        })
        ShadowContentResolver.registerProviderInternal("test.documents", it)
    }

    @Test fun imageResolvesTreeAfterRefetchAndNeverInsertsMediaStoreRow() {
        val treeProvider = treeProvider()
        val settings = SaveDestinations(app)
        val fetch = FakeFetch(result = { settings.choose(SaveDestinationTest.tree); ImageSaveResult.Success(image) })
        val saver = saver(fetch)
        assertTrue(saver.save(selection, image, "Photo"))
        val saved = saver.finish() as ImageSaveState.Saved
        assertEquals("test.documents", saved.uri.authority)
        assertArrayEquals(fixture, treeProvider.files.values.single().readBytes())
        assertTrue(provider.rows.isEmpty())
        assertEquals(saved.uri, settings.savedFiles().single().uri)
    }

    @Test fun gifPublishesOriginalBytesWithGifMimeAndExtensionToBothDestinations() {
        val gif = MediaImageMetadata(MediaImageFormat.valueOf("GIF"), Size.Exact(fixture.size.toLong()), 1, 1, proof)
        val documents = treeProvider()
        val settings = SaveDestinations(app)
        for (tree in listOf(null, SaveDestinationTest.tree)) {
            settings.choose(tree)
            val saver = saver(FakeFetch(result = { ImageSaveResult.Success(gif) }))
            assertTrue(saver.save(selection, gif, "Animation"))
            val saved = saver.finish() as ImageSaveState.Saved
            assertTrue(saved.displayName.endsWith(".gif"))
            assertArrayEquals(fixture, app.contentResolver.openInputStream(saved.uri)!!.use { it.readBytes() })
            if (tree == null) {
                assertEquals(collection.path, saved.uri.path!!.substringBeforeLast('/'))
                assertEquals("image/gif", provider.row(saved.uri).getAsString(MediaStore.Downloads.MIME_TYPE))
            } else {
                val item = settings.savedFiles().single()
                assertEquals("image/gif", item.mimeType)
                assertTrue(item.filename.endsWith(".gif"))
                assertArrayEquals(fixture, documents.files.values.single().readBytes())
            }
        }
    }

    @Test fun imageLostTreePermissionDoesNotFallbackToMediaStore() {
        val treeProvider = treeProvider()
        val settings = SaveDestinations(app)
        settings.choose(SaveDestinationTest.tree)
        treeProvider.denied = true
        val saver = saver(FakeFetch())
        assertTrue(saver.save(selection, image, "Photo"))
        val failed = saver.finish() as ImageSaveState.Failed
        assertEquals(StorageError.FolderUnavailable, failed.storage)
        assertTrue(provider.rows.isEmpty())
        assertTrue(treeProvider.files.isEmpty())
    }

    @Test fun imageSweepDeletesRecordedPartialTreeDocumentOnly() = runBlocking {
        val treeProvider = treeProvider()
        val settings = SaveDestinations(app)
        settings.choose(SaveDestinationTest.tree)
        val destinationPublisher = DestinationPublisher(app, space)
        staging.mkdirs()
        val job = JobDirectory(staging).create("img-interrupted")
        val pending = destinationPublisher.insertPending(job, "photo.jpg", "image/jpeg")
        val source = job.file("photo", "jpg").apply { writeBytes(byteArrayOf(7, 8)) }
        destinationPublisher.copy(pending, source) // Stop before commit, with the real cleanup fingerprint.
        val saver = ImageSaver(FakeFetch(), destinationPublisher, staging, space, scope) { settings.pendingImages() }
        saver.sweep()
        assertTrue(treeProvider.files.isEmpty())
        assertTrue(settings.pendingImages().isEmpty())
        assertFalse(job.directory.exists())
        assertNull(settings.receipt(pending.uri))
    }

    @Test fun successPublishesOneCommittedJpegWithTheFixtureBytesAndEmptiesStaging() {
        val fetch = FakeFetch()
        val saver = saver(fetch)
        assertTrue(saver.save(selection, image, "Nezzel_photo"))
        val saved = saver.finish() as ImageSaveState.Saved
        assertEquals(1, provider.rows.size)
        val row = provider.row(saved.uri)
        assertEquals(0, row.getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertEquals("image/jpeg", row.getAsString(MediaStore.Downloads.MIME_TYPE))
        assertEquals("Download/Nezzel/", row.getAsString(MediaStore.Downloads.RELATIVE_PATH))
        assertTrue(saved.displayName, saved.displayName.endsWith(".jpg"))
        assertArrayEquals(fixture, app.contentResolver.openInputStream(saved.uri)!!.use { it.readBytes() })
        assertEquals(1, fetch.calls.get())
        assertEquals(emptyList<File>(), stagingEntries())
    }

    @Test fun webpPreviewPublishesJpegOriginalWithJpgExtensionAndMime() {
        val saver = saver(FakeFetch())
        assertTrue(saver.save(selection, MediaImageMetadata(MediaImageFormat.WEBP), "Nezzel_photo"))
        val saved = saver.finish() as ImageSaveState.Saved
        assertTrue(saved.displayName, saved.displayName.endsWith(".jpg"))
        assertEquals("image/jpeg", provider.row(saved.uri).getAsString(MediaStore.Downloads.MIME_TYPE))
        assertArrayEquals(fixture, app.contentResolver.openInputStream(saved.uri)!!.use { it.readBytes() })
        assertEquals(emptyList<File>(), stagingEntries())
    }

    @Test fun cancelMidTransferCancelsTheFetchAndLeavesNoRowOrStaging() {
        val fetch = FakeFetch(gate = CompletableDeferred())
        val saver = saver(fetch)
        assertTrue(saver.save(selection, image, "Nezzel_photo"))
        runBlocking { withTimeout(10_000) { fetch.midTransfer.await() } }
        saver.cancel()
        val failed = saver.finish() as ImageSaveState.Failed
        assertEquals(ImageFailure.CANCELLED, failed.image)
        assertEquals(1, fetch.cancels.get())
        assertTrue(provider.rows.isEmpty())
        assertEquals(emptyList<File>(), stagingEntries())
    }

    @Test fun cancelAfterCommitKeepsThePublishedRow() {
        lateinit var saver: ImageSaver
        provider.afterCommit = { saver.cancel() }
        saver = saver(FakeFetch())
        assertTrue(saver.save(selection, image, "Nezzel_photo"))
        val saved = saver.finish() as ImageSaveState.Saved
        assertEquals(1, provider.rows.size)
        assertEquals(0, provider.row(saved.uri).getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertEquals(emptyList<File>(), stagingEntries())
    }

    @Test fun proofMismatchAndStaleLeaveNoRowAndEmptyStaging() {
        for (reason in listOf(ImageFailure.PROOF_MISMATCH, ImageFailure.STALE)) {
            val saver = saver(FakeFetch({ ImageSaveResult.Failure(reason) }))
            assertTrue(saver.save(selection, image, "Nezzel_photo"))
            assertEquals(reason, (saver.finish() as ImageSaveState.Failed).image)
            assertTrue(provider.rows.isEmpty())
            assertEquals(emptyList<File>(), stagingEntries())
        }
    }

    @Test fun lowSpaceNeverFetchesOrCreatesStaging() {
        val fetch = FakeFetch()
        var asked: Triple<Long?, Long?, Long?>? = null
        val full = object : SpaceGate {
            override fun beforeTransfer(stagingBytes: Long?, outputBytes: Long?, unknownSizeUpperBound: Long?) {
                asked = Triple(stagingBytes, outputBytes, unknownSizeUpperBound)
                throw StorageException(StorageError.InsufficientSpace)
            }
            override fun beforePublication(outputBytes: Long) = Unit
        }
        val saver = saver(fetch, space = full)
        assertTrue(saver.save(selection, image, "Nezzel_photo"))
        val failed = saver.finish() as ImageSaveState.Failed
        assertEquals(StorageError.InsufficientSpace, failed.storage)
        assertEquals(Triple(fixture.size.toLong(), fixture.size.toLong(), 20L * 1024 * 1024), asked)
        assertEquals(0, fetch.calls.get())
        assertTrue(File(staging, "downloads").listFiles().orEmpty().isEmpty())
        assertTrue(provider.rows.isEmpty())
    }

    @Test fun customFolderStillRejectsFullPrivateStagingBeforeRefetch() = runBlocking {
        val documents = treeProvider()
        val settings = SaveDestinations(app)
        settings.choose(SaveDestinationTest.tree)
        org.robolectric.shadows.ShadowStatFs.registerStats(staging.absolutePath, 0, 0, 0)
        val saver = MediaServices(app, { error("Image save must not load video engine") }, scope).saver
        assertTrue(saver.save(selection, image, "photo"))
        saver.awaitIdle()
        assertSpaceFailure(saver.outcome.value)
        assertTrue(documents.files.isEmpty())
        assertTrue(provider.rows.isEmpty())
        assertEquals(emptyList<File>(), stagingEntries())
    }

    @Test fun stagingWriteEnospcLeavesNoPartialAndShowsFreeSpaceAdvice() {
        val saver = saver(ImageFetch { _, sink ->
            sink.write(fixture, 0, 100)
            throw IOException("private diagnostics", android.system.ErrnoException("write", android.system.OsConstants.ENOSPC))
        })
        assertTrue(saver.save(selection, image, "photo"))
        assertSpaceFailure(saver.finish())
        assertTrue(provider.rows.isEmpty())
        assertEquals(emptyList<File>(), stagingEntries())
    }

    @Test fun publicationSyncEnospcAbandonsPartialAndShowsFreeSpaceAdvice() {
        val full = MediaStorePublisher(app.contentResolver, space, app.packageName) {
            throw IOException("private diagnostics", android.system.ErrnoException("fsync", android.system.OsConstants.ENOSPC))
        }
        val saver = saver(FakeFetch(), publisher = full)
        assertTrue(saver.save(selection, image, "photo"))
        assertSpaceFailure(saver.finish())
        assertTrue(provider.rows.isEmpty())
        assertEquals(emptyList<File>(), stagingEntries())
    }

    @Test fun stagingSyncEnospcCleansPartialAndShowsFreeSpaceAdvice() {
        val saver = ImageSaver(FakeFetch(), publisher, staging, space, scope, syncStaging = {
            throw IOException("private diagnostics", android.system.ErrnoException("fsync", android.system.OsConstants.ENOSPC))
        })
        assertTrue(saver.save(selection, image, "photo"))
        assertSpaceFailure(saver.finish())
        assertTrue(provider.rows.isEmpty())
        assertEquals(emptyList<File>(), stagingEntries())
    }

    @Test fun typedTransferSpaceFailureCleansPartialWithoutSyncOrPublication() {
        val saver = ImageSaver(FakeFetch({ ImageSaveResult.Failure(ImageFailure.INSUFFICIENT_SPACE) }),
            publisher, staging, space, scope, syncStaging = { fail("A failed transfer must not be synced") })
        assertTrue(saver.save(selection, image, "photo"))
        assertSpaceFailure(saver.finish())
        assertTrue(provider.rows.isEmpty())
        assertEquals(emptyList<File>(), stagingEntries())
    }

    @Test fun safOutputOpenEnospcAbandonsPartialAndShowsFreeSpaceAdvice() {
        val documents = treeProvider()
        val settings = SaveDestinations(app)
        settings.choose(SaveDestinationTest.tree)
        documents.onOpen = { throw java.io.FileNotFoundException("private diagnostics").apply {
            initCause(android.system.ErrnoException("open", android.system.OsConstants.ENOSPC))
        } }
        val saver = saver(FakeFetch())
        assertTrue(saver.save(selection, image, "photo"))
        assertSpaceFailure(saver.finish())
        assertTrue(documents.files.isEmpty())
        assertTrue(settings.pendingImages().isEmpty())
        assertTrue(provider.rows.isEmpty())
        assertEquals(emptyList<File>(), stagingEntries())
    }

    @Test fun safOutputWriteAndCloseEnospcRemovePartialAndShowFreeSpaceAdvice() {
        val documents = treeProvider()
        val settings = SaveDestinations(app)
        settings.choose(SaveDestinationTest.tree)
        for (failOnClose in listOf(false, true)) {
            documents.onCreateDocument = { id ->
                val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(SaveDestinationTest.tree, id)
                val partial = documents.files.getValue(id).outputStream()
                org.robolectric.Shadows.shadowOf(app.contentResolver).registerOutputStream(uri, object : OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        partial.write(bytes, offset, if (failOnClose) length else minOf(100, length))
                        if (!failOnClose) throw IOException("ENOSPC private diagnostics")
                    }
                    override fun close() {
                        partial.close()
                        if (failOnClose) throw IOException("ENOSPC private diagnostics")
                    }
                })
            }
            val saver = saver(FakeFetch())
            assertTrue(saver.save(selection, image, "photo"))
            assertSpaceFailure(saver.finish())
            assertTrue(documents.files.isEmpty())
            assertTrue(settings.pendingImages().isEmpty())
            assertArrayEquals(if (failOnClose) fixture else fixture.copyOf(100), documents.deletedBytes)
            assertTrue(provider.rows.isEmpty())
            assertEquals(emptyList<File>(), stagingEntries())
        }
    }

    private fun assertSpaceFailure(state: ImageSaveState) {
        val failed = state as ImageSaveState.Failed
        assertEquals(StorageError.InsufficientSpace, failed.storage)
        assertEquals(Triple(dev.ahmed.savlino.R.string.image_save_failed,
            dev.ahmed.savlino.R.string.download_space_error, false),
            dev.ahmed.savlino.ui.imageErrorCopy(dev.ahmed.savlino.ui.OptionsState.ImageError(
                failed.image ?: ImageFailure.STAGING, storage = failed.storage)))
    }

    @Test fun twoQuickTapsFetchOnceAndPublishOneRow() {
        val gate = CompletableDeferred<Unit>()
        val fetch = FakeFetch(gate = gate)
        val saver = saver(fetch)
        assertTrue(saver.save(selection, image, "Nezzel_photo"))
        assertFalse(saver.save(selection, image, "Nezzel_photo"))
        gate.complete(Unit)
        assertTrue(saver.finish() is ImageSaveState.Saved)
        assertEquals(1, fetch.calls.get())
        assertEquals(1, provider.rows.size)
    }

    @Test fun uncertainCommitAdoptsAPublishedRow() {
        val uncertain = object : Publisher by publisher {
            override fun publish(job: JobDirectory.Job, stagedFile: File, title: String, extension: String, mimeType: String,
                cancellation: CancellationSignal): PublicationResult {
                val published = publisher.publish(job, stagedFile, title, extension, mimeType, cancellation) as PublishedFile
                return CommitUncertain(PendingPublication(published.uri, published.displayName))
            }
        }
        val saver = saver(FakeFetch(), publisher = uncertain)
        assertTrue(saver.save(selection, image, "Nezzel_photo"))
        val saved = saver.finish() as ImageSaveState.Saved
        assertEquals(1, provider.rows.size)
        assertEquals(0, provider.row(saved.uri).getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertEquals(emptyList<File>(), stagingEntries())
    }

    @Test fun uncertainCommitAbandonsAStillPendingRow() {
        val uncertain = object : Publisher by publisher {
            override fun publish(job: JobDirectory.Job, stagedFile: File, title: String, extension: String, mimeType: String,
                cancellation: CancellationSignal): PublicationResult {
                val pending = publisher.insertPending(job, FilenameSanitizer.sanitize(title, extension), mimeType)
                publisher.copy(pending, job.requireOutput(stagedFile), cancellation)
                return CommitUncertain(pending)
            }
        }
        val saver = saver(FakeFetch(), publisher = uncertain)
        assertTrue(saver.save(selection, image, "Nezzel_photo"))
        assertTrue(saver.finish() is ImageSaveState.Failed)
        assertTrue(provider.rows.isEmpty())
        assertEquals(emptyList<File>(), stagingEntries())
    }

    @Test fun aFailedSweepIsRetriedBeforeTheNextSave() {
        val pending = provider.seed("photo.jpg", "image/jpeg", pending = 1)
        var failures = 1
        val flaky = ImageSaver(FakeFetch(), publisher, staging, space, scope) {
            if (failures-- > 0) throw IllegalStateException("transient provider failure")
            ownPendingImages(app.contentResolver, app.packageName)
        }
        runBlocking { flaky.sweep() }
        assertTrue("query failed: the row is still there", provider.rows.containsKey(ContentUris.parseId(pending)))
        assertTrue(flaky.save(selection, image, "Nezzel_photo"))
        assertTrue(flaky.finish() is ImageSaveState.Saved)
        assertFalse("the next Save retried the sweep", provider.rows.containsKey(ContentUris.parseId(pending)))
        assertEquals(1, provider.rows.size)
    }

    @Test fun delayedStartupRetryCannotUnlinkAnActiveImageSave() = runBlocking {
        val fetch = FakeFetch(gate = CompletableDeferred())
        var queries = 0
        val saver = ImageSaver(fetch, publisher, staging, space, scope) {
            if (++queries == 1) throw IllegalStateException("transient provider failure")
            ownPendingImages(app.contentResolver, app.packageName)
        }
        try {
            assertTrue(saver.save(selection, image, "photo"))
            withTimeout(10_000) { fetch.midTransfer.await() }
            assertEquals(SweepFailure.QUERY, saver.lastSweepFailure)
            saver.sweep() // Application's delayed startup pass arrives during the write.
            fetch.gate!!.complete(Unit)
            val saved = saver.finish() as ImageSaveState.Saved
            assertArrayEquals(fixture, app.contentResolver.openInputStream(saved.uri)!!.use { it.readBytes() })
            assertEquals(1, queries)
            assertEquals(emptyList<File>(), stagingEntries())
            saver.sweep() // The failed pass remains retryable once the save is over.
            assertEquals(2, queries)
        } finally { fetch.gate!!.complete(Unit) }
    }

    @Test fun failedCleanupRetriesBetweenBatchesRatherThanBetweenTheirImages() {
        var queries = 0
        val saver = ImageSaver(FakeFetch(), publisher, staging, space, scope) {
            if (++queries == 1) throw IllegalStateException("transient provider failure")
            ownPendingImages(app.contentResolver, app.packageName)
        }
        val second = selection.copy(renditionRef = selection.renditionRef.copy(renditionId = "second"))
        val requests = listOf(ImageSaveRequest(selection, image, "one"), ImageSaveRequest(second, image, "two"))
        assertTrue(saver.saveBatch(requests))
        assertEquals(2, (saver.finish() as ImageSaveState.BatchFinished).saved.size)
        assertEquals(1, queries)
        assertTrue(saver.saveBatch(requests))
        assertEquals(2, (saver.finish() as ImageSaveState.BatchFinished).saved.size)
        assertEquals(2, queries)
    }

    @Test fun aFailedAbandonIsRetriedAndNeverBlocksASave() {
        val pending = provider.seed("photo.jpg", "image/jpeg", pending = 1)
        var failures = 1
        val flakyPublisher = object : Publisher by publisher {
            override fun abandon(uri: Uri): RecoveryResult {
                if (failures-- > 0) throw StorageException(StorageError.SaveFailed)
                return publisher.abandon(uri)
            }
        }
        val saver = saver(FakeFetch(), publisher = flakyPublisher)
        runBlocking { saver.sweep() }
        assertTrue(provider.rows.containsKey(ContentUris.parseId(pending)))
        assertTrue(saver.save(selection, image, "Nezzel_photo"))
        assertTrue(saver.finish() is ImageSaveState.Saved)
        assertFalse(provider.rows.containsKey(ContentUris.parseId(pending)))
    }

    @Test fun aPermanentlyFailingSweepIsBoundedAndSavesStillSucceed() {
        var queries = 0
        val broken = ImageSaver(FakeFetch(), publisher, staging, space, scope) { queries++; throw IllegalStateException("broken") }
        runBlocking { broken.sweep() }
        repeat(4) {
            assertTrue(broken.save(selection, image, "Nezzel_photo"))
            assertTrue(broken.finish() is ImageSaveState.Saved)
        }
        assertEquals(3, queries)
        assertEquals(SweepFailure.QUERY, broken.lastSweepFailure)
        assertEquals(4, provider.rows.size)
    }

    @Test fun startupSweepRemovesOnlyImageStagingAndOwnPendingImageRows() {
        File(staging, "downloads/killed-save").apply { mkdirs() }.resolve("photo.jpg").writeBytes(fixture)
        val avStaging = File(app.filesDir, "downloads/av-job").apply { mkdirs() }.resolve("clip.mp4").apply { writeBytes(fixture) }
        val pendingImage = provider.seed("photo.jpg", "image/jpeg", pending = 1)
        val pendingPng = provider.seed("shot.png", "image/png", pending = 1)
        val pendingVideo = provider.seed("clip.mp4", "video/mp4", pending = 1)
        val publishedImage = provider.seed("kept.jpg", "image/jpeg", pending = 0)
        val foreignImage = provider.seed("foreign.jpg", "image/jpeg", pending = 1, owner = "other.package")
        runBlocking { saver(FakeFetch()).sweep() }
        assertEquals(setOf(pendingVideo, publishedImage, foreignImage).map(ContentUris::parseId).toSet(), provider.rows.keys)
        assertFalse(provider.rows.containsKey(ContentUris.parseId(pendingImage)))
        assertFalse(provider.rows.containsKey(ContentUris.parseId(pendingPng)))
        assertEquals(emptyList<File>(), stagingEntries())
        assertArrayEquals(fixture, avStaging.readBytes())
    }

    /** Robolectric has no MediaProvider. Rows + bytes persist through ContentResolver/PFD; pending rows need MATCH_INCLUDE. */
    private inner class Provider(private val directory: File) : ContentProvider() {
        val rows = linkedMapOf<Long, ContentValues>()
        private var nextId = 0L
        var afterCommit: () -> Unit = {}
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/jpeg"
        fun row(uri: Uri) = rows.getValue(ContentUris.parseId(uri))
        fun file(uri: Uri) = File(directory, ContentUris.parseId(uri).toString())
        fun seed(name: String, mime: String, pending: Int, owner: String = app.packageName): Uri {
            val id = ++nextId
            rows[id] = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Nezzel/"); put(MediaStore.Downloads.IS_PENDING, pending)
                put(MediaStore.Downloads.OWNER_PACKAGE_NAME, owner)
            }
            return ContentUris.withAppendedId(collection, id).also { file(it).writeBytes(fixture) }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = ++nextId
            rows[id] = ContentValues(requireNotNull(values)).apply {
                put(MediaStore.Downloads.OWNER_PACKAGE_NAME, app.packageName)
                val name = getAsString(MediaStore.Downloads.DISPLAY_NAME)
                if (rows.values.any { it.getAsString(MediaStore.Downloads.DISPLAY_NAME).equals(name, ignoreCase = true) })
                    put(MediaStore.Downloads.DISPLAY_NAME, name.substringBeforeLast('.') + " (provider)." + name.substringAfterLast('.'))
            }
            return ContentUris.withAppendedId(uri, id)
        }
        override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor =
            select(uri, projection, queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
                queryArgs?.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS),
                queryArgs?.getInt(MediaStore.QUERY_ARG_MATCH_PENDING) == MediaStore.MATCH_INCLUDE ||
                    uri.getQueryParameter("includePending") == "1")
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            select(uri, projection, selection, selectionArgs, uri.getQueryParameter("includePending") == "1")
        private fun select(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, includePending: Boolean): Cursor {
            val columns = requireNotNull(projection)
            val itemId = uri.lastPathSegment?.toLongOrNull()
            return MatrixCursor(columns).apply {
                rows.forEach { (id, values) ->
                    val owned = values.getAsString(MediaStore.Downloads.OWNER_PACKAGE_NAME) == app.packageName
                    val pending = values.getAsInteger(MediaStore.Downloads.IS_PENDING) == 1
                    val visible = when {
                        itemId != null -> itemId == id && owned
                        // Default collection scans hide pending rows; MATCH_INCLUDE shows our own pending rows.
                        pending && !includePending -> false
                        else -> owned && matches(values, selection, args)
                    }
                    if (visible) addRow(columns.map { if (it == MediaStore.Downloads._ID) id else values.get(it) }.toTypedArray())
                }
            }
        }
        /** Supports the publisher's name scan and the sweep's own-pending-image query, nothing broader. */
        private fun matches(values: ContentValues, selection: String?, args: Array<out String>?): Boolean {
            if (selection == null || args == null) return true
            if (selection.contains("mime_type LIKE")) {
                assertEquals("is_pending = ? AND owner_package_name = ? AND relative_path = ? AND mime_type LIKE ?", selection)
                return values.getAsInteger(MediaStore.Downloads.IS_PENDING).toString() == args[0] &&
                    values.getAsString(MediaStore.Downloads.OWNER_PACKAGE_NAME) == args[1] &&
                    values.getAsString(MediaStore.Downloads.RELATIVE_PATH) == args[2] &&
                    args[3] == "image/%" && values.getAsString(MediaStore.Downloads.MIME_TYPE).startsWith("image/")
            }
            return values.getAsString(MediaStore.Downloads.RELATIVE_PATH) in args &&
                values.getAsInteger(MediaStore.Downloads.IS_PENDING) == 0
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            assertEquals(listOf("1", app.packageName, "Download/Nezzel/"), selectionArgs?.toList())
            if (row(uri).getAsInteger(MediaStore.Downloads.IS_PENDING) != 1) return 0
            row(uri).putAll(requireNotNull(values))
            afterCommit()
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val row = rows[ContentUris.parseId(uri)] ?: return 0
            assertEquals("is_pending = ? AND owner_package_name = ? AND relative_path = ?", selection)
            assertEquals(listOf("1", app.packageName, "Download/Nezzel/"), selectionArgs?.toList())
            // Removing published output, a foreign row or a video row fails the test.
            assertEquals(app.packageName, row.getAsString(MediaStore.Downloads.OWNER_PACKAGE_NAME))
            if (row.getAsInteger(MediaStore.Downloads.IS_PENDING) != 1) return 0
            assertTrue(row.getAsString(MediaStore.Downloads.MIME_TYPE).startsWith("image/"))
            rows.remove(ContentUris.parseId(uri))
            file(uri).delete()
            return 1
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            row(uri)
            return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode))
        }
    }
}
