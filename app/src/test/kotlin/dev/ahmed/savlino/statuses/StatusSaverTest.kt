package dev.ahmed.savlino.statuses

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import dev.ahmed.savlino.media.ownPendingImages
import dev.ahmed.savlino.storage.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaMetadataRetriever

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusSaverTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val sourceUri = Uri.parse("content://status.documents/document/status")
    private lateinit var provider: Provider
    private lateinit var source: File
    private lateinit var staging: File
    private lateinit var bytes: ByteArray
    private lateinit var saver: StatusSaver
    private val space = object : SpaceGate {
        override fun beforeTransfer(stagingBytes: Long?, outputBytes: Long?, unknownSizeUpperBound: Long?) = Unit
        override fun beforePublication(outputBytes: Long) = Unit
    }

    @Before fun setUp() {
        // Warm stores outside provider callbacks: no reentrant Application/store initialization.
        SaveDestinations(app).savedFiles()
        bytes = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        staging = File(app.noBackupFilesDir, "status-save").apply { deleteRecursively(); mkdirs() }
        source = File(app.cacheDir, "source-status").apply { writeBytes(bytes) }
        provider = Provider(File(app.cacheDir, "status-media").apply { deleteRecursively(); mkdirs() })
        provider.attachInfo(app, ProviderInfo().apply { authority = "media;status.documents"; exported = true })
        ShadowContentResolver.registerProviderInternal("media", provider)
        ShadowContentResolver.registerProviderInternal("status.documents", provider)
        saver = StatusSaver(app, stagingRoot = staging)
        ShadowStatFs.registerStats(staging.absolutePath, 1_000_000, 1_000_000, 1_000_000)
        ShadowStatFs.registerStats(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
            1_000_000, 1_000_000, 1_000_000)
    }

    private fun request() = StatusSaveSource(sourceUri, "image/png", bytes.size.toLong(), 1_700_000_000_000L)

    @Test fun savesOriginalBytesToStatusesWithSourceTimestampAndKeepsSource() = runBlocking {
        val saved = saver.save(request()) as StatusSaveResult.Saved
        val row = provider.rows.getValue(ContentUris.parseId(saved.uri))
        assertEquals("Download/Nezzel/Statuses/", row.getAsString(MediaStore.Downloads.RELATIVE_PATH))
        assertEquals("image/png", row.getAsString(MediaStore.Downloads.MIME_TYPE))
        assertEquals(0, row.getAsInteger(MediaStore.Downloads.IS_PENDING))
        val formatter = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
        assertEquals("Status-${formatter.format(java.util.Date(1_700_000_000_000L))}.png", row.getAsString(MediaStore.Downloads.DISPLAY_NAME))
        assertArrayEquals(bytes, provider.file(saved.uri).readBytes())
        assertArrayEquals(bytes, source.readBytes())
        assertEquals(bytes.size.toLong(), source.length())
        assertTrue(File(staging, "downloads").listFiles().orEmpty().isEmpty())
    }

    @Test fun providerJpgAliasAcceptsDecodableJpegAndPublishesStandardMime() = runBlocking {
        bytes = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, output)
            output.toByteArray()
        }
        source.writeBytes(bytes)
        provider.sourceMime = "image/jpg"
        val saved = saver.save(request().copy(mimeType = "image/jpg")) as StatusSaveResult.Saved
        val row = provider.rows.getValue(ContentUris.parseId(saved.uri))
        assertEquals("image/jpeg", row.getAsString(MediaStore.Downloads.MIME_TYPE))
        assertTrue(row.getAsString(MediaStore.Downloads.DISPLAY_NAME).endsWith(".jpg"))
        assertArrayEquals(bytes, provider.file(saved.uri).readBytes())
        assertArrayEquals(bytes, source.readBytes())
        assertEquals(bytes.size.toLong(), source.length())
    }

    @Test fun repeatedTimestampGetsUniqueName() = runBlocking {
        val first = saver.save(request()) as StatusSaveResult.Saved
        val second = saver.save(request()) as StatusSaveResult.Saved
        val names = listOf(first, second).map { provider.rows.getValue(ContentUris.parseId(it.uri)).getAsString(MediaStore.Downloads.DISPLAY_NAME) }
        assertNotEquals(names[0], names[1])
        assertTrue(names[1].endsWith(" (1).png"))
    }

    @Test fun missingSourceReturnsGoneWithoutPartialFile() = runBlocking {
        source.delete()
        assertEquals(StatusSaveResult.Gone, saver.save(request()))
        assertTrue(provider.rows.isEmpty())
        assertTrue(File(staging, "downloads").listFiles().orEmpty().isEmpty())
    }

    @Test fun changedLengthReturnsGoneWithoutPublishing() = runBlocking {
        source.appendBytes(byteArrayOf(1))
        assertEquals(StatusSaveResult.Gone, saver.save(request()))
        assertTrue(provider.rows.isEmpty())
        assertTrue(File(staging, "downloads").listFiles().orEmpty().isEmpty())
    }

    @Test fun malformedImageFailsWithoutPublishingOrChangingSource() = runBlocking {
        bytes = byteArrayOf(1, 2, 3)
        source.writeBytes(bytes)
        assertEquals(StatusSaveResult.Failed, saver.save(request()))
        assertTrue(provider.rows.isEmpty())
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun noSpacePreventsSourceCopyAndPublication() = runBlocking {
        ShadowStatFs.registerStats(staging.absolutePath, 1, 0, 0)
        assertEquals(StatusSaveResult.NoSpace, saver.save(request()))
        assertTrue(provider.rows.isEmpty())
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun defaultPublisherStillUsesOriginalFolder() {
        val job = JobDirectory(staging).create("default")
        val staged = job.file("output", "png").apply { writeBytes(bytes) }
        val saved = MediaStorePublisher(app.contentResolver, space, app.packageName)
            .publish(job, staged, "photo", "png", "image/png") as PublishedFile
        assertEquals("Download/Nezzel/", provider.rows.getValue(ContentUris.parseId(saved.uri)).getAsString(MediaStore.Downloads.RELATIVE_PATH))
    }

    @Test fun eachPublisherRejectsTheOtherFolderAndCannotDeleteItsPendingRow() {
        val normal = MediaStorePublisher(app.contentResolver, space, app.packageName)
        val statuses = MediaStorePublisher(app.contentResolver, space, app.packageName, relativePath = "Download/Nezzel/Statuses/")
        val job = JobDirectory(staging).create("path-guards")
        val normalPending = normal.insertPending(job, "normal.png", "image/png")
        val statusPending = statuses.insertPending(job, "status.png", "image/png")
        for ((publisher, other) in listOf(normal to statusPending, statuses to normalPending)) {
            try {
                publisher.abandon(other.uri)
                fail("Wrong-folder publication must be rejected")
            } catch (error: StorageException) {
                assertEquals(StorageError.InvalidPublication, error.error)
            }
        }
        assertEquals(2, provider.rows.size)
        assertTrue(provider.rows.values.all { it.getAsInteger(MediaStore.Downloads.IS_PENDING) == 1 })
    }

    @Test fun killedPublicationLeavesPendingStatusForSweepAndForeignOrCommittedRowsStay() {
        val publisher = MediaStorePublisher(app.contentResolver, space, app.packageName, relativePath = "Download/Nezzel/Statuses/")
        val job = JobDirectory(staging).create("status-123")
        val pending = publisher.insertPending(job, "Status-crash.png", "image/png")
        // Simulate death during publication: only a prefix reached the pending provider file.
        provider.file(pending.uri).writeBytes(bytes.copyOf(8))
        val foreign = provider.seed("foreign.png", "Download/Nezzel/Statuses/", 1, "foreign.package", "image/png")
        val committed = provider.seed("kept.png", "Download/Nezzel/Statuses/", 0, app.packageName, "image/png")
        val video = provider.seed("pending.mp4", "Download/Nezzel/", 1, app.packageName, "video/mp4")
        val pendingVideo = provider.seed("status.mp4", "Download/Nezzel/Statuses/", 1, app.packageName, "video/mp4")
        val unrelated = provider.seed("other.png", "Download/Other/", 1, app.packageName, "image/png")
        assertTrue(ownPendingImages(app.contentResolver, app.packageName).contains(pending.uri))
        runBlocking { saver.sweep() }
        assertEquals(setOf(foreign, committed, video, unrelated).map(ContentUris::parseId).toSet(), provider.rows.keys)
        assertFalse(provider.rows.containsKey(ContentUris.parseId(pendingVideo)))
        assertFalse(job.directory.exists())
    }

    @Test fun statusSweepOnlyDeletesOwnStatusRowsAndLeavesDefaultImagesAndForeignRows() = runBlocking {
        val ownStatus = provider.seed("status.png", "Download/Nezzel/Statuses/", 1, app.packageName, "image/png")
        val ownDefault = provider.seed("default.png", "Download/Nezzel/", 1, app.packageName, "image/png")
        val foreignStatus = provider.seed("foreign-status.png", "Download/Nezzel/Statuses/", 1, "foreign.package", "image/png")
        val foreignDefault = provider.seed("foreign-default.png", "Download/Nezzel/", 1, "foreign.package", "image/png")
        for (uri in listOf(ownStatus, ownDefault, foreignStatus, foreignDefault)) provider.file(uri).writeBytes(bytes)
        saver.sweep()
        assertFalse(provider.rows.containsKey(ContentUris.parseId(ownStatus)))
        assertEquals(setOf(ownDefault, foreignStatus, foreignDefault).map(ContentUris::parseId).toSet(), provider.rows.keys)
        for (uri in listOf(ownDefault, foreignStatus, foreignDefault)) assertArrayEquals(bytes, provider.file(uri).readBytes())
    }

    @Test fun videosNeedPositiveDurationAndKeepProviderMimeAndBytes() = runBlocking {
        provider.sourceMime = "video/mp4"
        val request = request().copy(mimeType = "video/mp4")
        provider.onSourceOpen = {
            val stage = File(staging, "downloads").listFiles()!!.single().resolve("output.mp4")
            ShadowMediaMetadataRetriever.addMetadata(stage.canonicalPath, MediaMetadataRetriever.METADATA_KEY_DURATION, "1200")
        }
        val saved = saver.save(request) as StatusSaveResult.Saved
        assertEquals("video/mp4", provider.rows.getValue(ContentUris.parseId(saved.uri)).getAsString(MediaStore.Downloads.MIME_TYPE))
        assertArrayEquals(bytes, provider.file(saved.uri).readBytes())
        assertArrayEquals(bytes, source.readBytes())
        provider.onSourceOpen = {
            val stage = File(staging, "downloads").listFiles()!!.single().resolve("output.mp4")
            ShadowMediaMetadataRetriever.addMetadata(stage.canonicalPath, MediaMetadataRetriever.METADATA_KEY_DURATION, "0")
        }
        assertEquals(StatusSaveResult.Failed, saver.save(request))
        assertEquals(1, provider.rows.size)
    }

    @Test fun missingMtimeUsesClockAndUnknownSizeStillPublishesVerifiedBytes() = runBlocking {
        provider.sourceModified = 0
        provider.unknownSize = true
        saver = StatusSaver(app, stagingRoot = staging, now = { 1_700_000_000_000L })
        val saved = saver.save(request().copy(sizeBytes = null, lastModifiedMillis = null)) as StatusSaveResult.Saved
        val formatter = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
        assertEquals("Status-${formatter.format(java.util.Date(1_700_000_000_000L))}.png",
            provider.rows.getValue(ContentUris.parseId(saved.uri)).getAsString(MediaStore.Downloads.DISPLAY_NAME))
        assertArrayEquals(bytes, provider.file(saved.uri).readBytes())
    }

    @Test fun sourceChangedAfterOpeningIsGoneAndStageIsDeleted() = runBlocking {
        provider.onSourceOpen = { provider.sourceModified++ }
        assertEquals(StatusSaveResult.Gone, saver.save(request()))
        assertTrue(provider.rows.isEmpty())
        assertTrue(File(staging, "downloads").listFiles().orEmpty().isEmpty())
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun cancelledCopyDeletesStageAndDoesNotPublish() = runBlocking {
        val cancellation = CancellationSignal()
        provider.onSourceOpen = { cancellation.cancel() }
        assertEquals(StatusSaveResult.Cancelled, saver.save(request(), cancellation))
        assertTrue(provider.rows.isEmpty())
        assertTrue(File(staging, "downloads").listFiles().orEmpty().isEmpty())
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun mimeMismatchAndNonMediaMimeNeverPublish() = runBlocking {
        assertEquals(StatusSaveResult.Gone, saver.save(request().copy(mimeType = "image/jpeg")))
        provider.sourceMime = "application/octet-stream"
        assertEquals(StatusSaveResult.Failed, saver.save(request().copy(mimeType = "application/octet-stream")))
        assertTrue(provider.rows.isEmpty())
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun startupSweepNeverFollowsStatusJobSymlinkOrTouchesDownloadStaging() = runBlocking {
        val downloads = File(staging, "downloads").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(downloads, "status-456").toPath(), source.toPath())
        val normalJob = File(app.filesDir, "downloads/regular-job").apply { mkdirs() }
        val normalOutput = normalJob.resolve("output.mp4").apply { writeBytes(bytes) }
        saver.sweep()
        assertFalse(java.nio.file.Files.exists(File(downloads, "status-456").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertArrayEquals(bytes, normalOutput.readBytes())
        assertArrayEquals(bytes, source.readBytes())
    }

    private inner class Provider(private val root: File) : ContentProvider() {
        val rows = linkedMapOf<Long, ContentValues>()
        private var next = 0L
        var sourceMime = "image/png"
        var sourceModified = 1_700_000_000_000L
        var unknownSize = false
        var onSourceOpen: () -> Unit = {}
        override fun onCreate() = true
        override fun getType(uri: Uri) = if (uri.authority == "status.documents") sourceMime else row(uri).getAsString(MediaStore.Downloads.MIME_TYPE)
        fun file(uri: Uri) = File(root, ContentUris.parseId(uri).toString())
        private fun row(uri: Uri) = rows.getValue(ContentUris.parseId(uri))
        fun seed(name: String, path: String, pending: Int, owner: String, mime: String): Uri = insert(collection, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.RELATIVE_PATH, path)
            put(MediaStore.Downloads.IS_PENDING, pending); put(MediaStore.Downloads.OWNER_PACKAGE_NAME, owner)
            put(MediaStore.Downloads.MIME_TYPE, mime)
        })
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = ++next
            rows[id] = ContentValues(requireNotNull(values)).apply {
                if (!containsKey(MediaStore.Downloads.OWNER_PACKAGE_NAME)) put(MediaStore.Downloads.OWNER_PACKAGE_NAME, app.packageName)
            }
            return ContentUris.withAppendedId(collection, id)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            queryRows(uri, requireNotNull(projection), selection, selectionArgs)
        override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor =
            queryRows(uri, requireNotNull(projection), queryArgs?.getString("android:query-arg-sql-selection"),
                queryArgs?.getStringArray("android:query-arg-sql-selection-args"))
        private fun queryRows(uri: Uri, columns: Array<out String>, selection: String?, args: Array<out String>?): Cursor = MatrixCursor(columns).apply {
            if (uri.authority == "status.documents") {
                if (source.exists()) addRow(columns.map<String, Any?> { when (it) {
                    Document.COLUMN_MIME_TYPE -> sourceMime
                    Document.COLUMN_SIZE -> if (unknownSize) null else source.length()
                    Document.COLUMN_LAST_MODIFIED -> sourceModified
                    else -> null
                } }.toTypedArray())
            } else {
                rows.forEach { (id, value) ->
                    val selected = if (uri.lastPathSegment?.toLongOrNull() != null) id == ContentUris.parseId(uri)
                        else if (selection?.contains("is_pending") == true) {
                            assertEquals("is_pending = ? AND owner_package_name = ? AND " +
                                "((relative_path = ? AND mime_type LIKE ?) OR (relative_path = ? AND (mime_type LIKE ? OR mime_type LIKE ?)))", selection)
                            assertEquals(listOf("1", app.packageName, "Download/Nezzel/", "image/%", "Download/Nezzel/Statuses/", "image/%", "video/%"), args?.toList())
                            val path = value.getAsString(MediaStore.Downloads.RELATIVE_PATH)
                            val mime = value.getAsString(MediaStore.Downloads.MIME_TYPE)
                            value.getAsInteger(MediaStore.Downloads.IS_PENDING) == 1 &&
                                value.getAsString(MediaStore.Downloads.OWNER_PACKAGE_NAME) == app.packageName &&
                                ((path == "Download/Nezzel/" && mime.startsWith("image/")) ||
                                    (path == "Download/Nezzel/Statuses/" && (mime.startsWith("image/") || mime.startsWith("video/"))))
                        } else value.getAsString(MediaStore.Downloads.RELATIVE_PATH) in args.orEmpty()
                    if (selected) addRow(columns.map { if (it == MediaStore.Downloads._ID) id else value.get(it) }.toTypedArray())
                }
            }
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            val value = row(uri)
            assertEquals(listOf("1", app.packageName, value.getAsString(MediaStore.Downloads.RELATIVE_PATH)), selectionArgs?.toList())
            value.putAll(requireNotNull(values)); return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            assertEquals("media", uri.authority) // Source writes/deletes are forbidden.
            val value = row(uri)
            assertEquals(listOf("1", app.packageName, value.getAsString(MediaStore.Downloads.RELATIVE_PATH)), selectionArgs?.toList())
            if (value.getAsInteger(MediaStore.Downloads.IS_PENDING) != 1) return 0
            rows.remove(ContentUris.parseId(uri)); file(uri).delete(); return 1
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (uri.authority == "status.documents") {
                assertEquals("r", mode)
                onSourceOpen()
                if (!source.exists()) throw FileNotFoundException()
                return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode))
        }
    }
}
