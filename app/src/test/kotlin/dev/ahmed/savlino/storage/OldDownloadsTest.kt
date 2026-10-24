package dev.ahmed.savlino.storage

import android.content.Context
import android.content.Intent
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import dev.ahmed.savlino.downloads.SavedMedia
import dev.ahmed.savlino.downloads.SavedMediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class OldDownloadsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val resolver get() = context.contentResolver
    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    @Test fun offerShowsOnlyForEligibleUngrantedUndismissed() {
        assertTrue(OldDownloads.offer(eligible = true, granted = false, dismissed = false))
        assertFalse(OldDownloads.offer(eligible = false, granted = false, dismissed = false))
        assertFalse(OldDownloads.offer(eligible = true, granted = true, dismissed = false))
        assertFalse(OldDownloads.offer(eligible = true, granted = false, dismissed = true))
    }

    @Test fun freshInstallIsEligibleAndADownloadBeforeOpeningDownloadsKeepsTheOffer() {
        OldDownloads.markFirstStart(context) { false }
        assertTrue(OldDownloads(context).eligible)
        // A job or settings written later in this install never change the first-start decision.
        OldDownloads.markFirstStart(context) { true }
        val old = OldDownloads(context)
        assertTrue(old.eligible)
        assertTrue(old.offerVisible())
    }

    @Test fun earlierRunMeansNoOffer() {
        OldDownloads.markFirstStart(context) { true }
        assertFalse(OldDownloads(context).offerVisible())
    }

    @Test fun earlierRunIsSettingsOrTheJobDatabase() {
        val root = java.nio.file.Files.createTempDirectory("data").toFile()
        val prefs = File(root, "shared_prefs").apply { mkdirs() }
        val db = File(root, "databases/nezzel-jobs.db")
        assertFalse(OldDownloads.earlierRun(prefs, db))
        File(prefs, "nezzel-old-downloads.xml").writeText("<map/>")
        File(prefs, "usage.xml").writeText("<map/>")
        assertFalse("Own prefs and counters are not an earlier run", OldDownloads.earlierRun(prefs, db))
        File(prefs, "nezzel.xml").writeText("<map/>")
        assertTrue(OldDownloads.earlierRun(prefs, db))
        File(prefs, "nezzel.xml").delete()
        db.parentFile!!.mkdirs(); db.writeBytes(byteArrayOf(1))
        assertTrue(OldDownloads.earlierRun(prefs, db))
    }

    @Test fun dismissedStaysDismissed() {
        OldDownloads.markFirstStart(context) { false }
        OldDownloads(context).dismiss()
        assertFalse(OldDownloads(context).offerVisible())
        OldDownloads.markFirstStart(context) { false }
        assertFalse(OldDownloads(context).offerVisible())
    }

    @Test fun offerGoesAfterGrantAndComesBackWhenTheSystemDropsIt() {
        provider()
        OldDownloads.markFirstStart(context) { false }
        val old = OldDownloads(context)
        assertEquals(OldDownloadsPick.GRANTED, old.onPicked(tree))
        assertFalse(old.offerVisible())
        resolver.releasePersistableUriPermission(tree, flags)
        assertNull(old.active())
        assertTrue(old.offerVisible())
        assertTrue(old.list().isEmpty())
    }

    @Test fun onlyDownloadNezzelIsAccepted() {
        provider()
        var found = 0
        val old = OldDownloads(context) { found++ }
        assertEquals(OldDownloadsPick.CANCELLED, old.onPicked(null))
        val downloadRoot = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "primary:Download")
        resolver.takePersistableUriPermission(downloadRoot, flags)
        assertEquals(OldDownloadsPick.WRONG_FOLDER, old.onPicked(downloadRoot))
        assertTrue("A wrong pick is given back", resolver.persistedUriPermissions.isEmpty())
        assertEquals(OldDownloadsPick.WRONG_FOLDER, old.onPicked(DocumentsContract.buildTreeDocumentUri(AUTHORITY, "primary:Download/Nezzel/Statuses")))
        assertEquals(OldDownloadsPick.WRONG_FOLDER, old.onPicked(Uri.parse("content://test.documents/tree/root")))
        assertNull(old.active())
        assertEquals(OldDownloadsPick.GRANTED, old.onPicked(tree))
        assertEquals(OldDownloadsPick.GRANTED, old.onPicked(tree))
        assertEquals(tree, old.active())
        assertEquals("Counted once, never per file", 1, found)
        assertTrue(OldDownloads.isNezzelFolder(DocumentsContract.buildTreeDocumentUri(AUTHORITY, "1A2B-3C4D:Download/Nezzel")))
    }

    @Test fun listingIsOneChildrenQueryPerFolderWithoutProbes() {
        val provider = provider()
        provider.add("Clip.mp4", "video/mp4")
        provider.add("Song.m4a", "audio/mp4")
        provider.add("notes.txt", "text/plain")
        provider.add(".hidden.mp4", "video/mp4")
        provider.add("Other", DocumentsContract.Document.MIME_TYPE_DIR)
        provider.add("Statuses", DocumentsContract.Document.MIME_TYPE_DIR)
        provider.add("Statuses/Status 1.jpg", "image/jpeg")
        val old = OldDownloads(context).apply { onPicked(tree) }
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, ROOT), null, android.os.Bundle.EMPTY, null)!!.close()
        provider.childQueries.clear()
        val rows = old.list().map { it.media }
        assertEquals(listOf("Clip.mp4", "Song.m4a", "Status 1.jpg"), rows.map { it.filename })
        assertEquals(listOf("Download/Nezzel/", "Download/Nezzel/", "Download/Nezzel/Statuses/"), rows.map { it.relativePath })
        assertTrue(rows.all { DocumentsContract.isTreeUri(it.uri) })
        assertEquals("Root + Statuses only", listOf("primary:Download/Nezzel", "primary:Download/Nezzel/Statuses"), provider.childQueries)
        assertEquals("No per-file probe while listing", 0, provider.opens)
    }

    @Test fun goneFolderEndsTheGrantQuietly() {
        val provider = provider()
        val old = OldDownloads(context).apply { onPicked(tree) }
        provider.gone = true
        assertTrue(old.list().isEmpty())
        assertNull(old.active())
        assertTrue(resolver.persistedUriPermissions.isEmpty())
    }

    @Test fun dedupeHidesWhatTheLibraryAlreadyShows() {
        val provider = provider()
        provider.add("Same.mp4", "video/mp4", size = 10)
        provider.add("Bigger.mp4", "video/mp4", size = 20)
        provider.add("Receipt.mp4", "video/mp4", size = 30)
        val old = OldDownloads(context).apply { onPicked(tree) }
        fun media(id: Int, name: String, size: Long) = SavedMedia(Uri.parse("content://media/external_primary/downloads/$id"), name,
            name, "video/mp4", size, 1, null, null, null, relativePath = "Download/Nezzel/", volumeName = "external_primary")
        val receipt = SavedMedia(DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri(AUTHORITY, "primary:Download/Nezzel"), "primary:Download/Nezzel/Receipt.mp4"),
            "Receipt.mp4", "Receipt", "video/mp4", 30, 1, null, null, null)
        val kept = OldDownloads.dedupe(old.list(), listOf(media(1, "Same.mp4", 10), media(2, "Bigger.mp4", 99), receipt))
        assertEquals(listOf("Bigger.mp4"), kept.map { it.media.filename })
    }

    @Test fun repositoryListsFoundFilesAndDeletesThroughTheTreeKeepingTheGrant() {
        val provider = provider()
        provider.add("Clip.mp4", "video/mp4")
        provider.add("Keep.mp4", "video/mp4")
        val old = OldDownloads(context).apply { onPicked(tree) }
        val repository = SavedMediaRepository(resolver, context.packageName, SaveDestinations(context), old)
        val clip = repository.query().single { it.filename == "Clip.mp4" }
        assertTrue(repository.delete(clip.uri))
        assertEquals(listOf("Keep.mp4"), repository.query().map { it.filename })
        assertEquals("Deleting a found file keeps the folder grant", listOf(tree), resolver.persistedUriPermissions.map { it.uri })
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun observeShowsRowsFirstThenFillsDimensions() {
        val provider = provider()
        val id = provider.add("Photo.png", "image/png")
        val bitmap = android.graphics.Bitmap.createBitmap(640, 480, android.graphics.Bitmap.Config.ARGB_8888)
        provider.files.getValue(id).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val repository = SavedMediaRepository(resolver, context.packageName, SaveDestinations(context), OldDownloads(context).apply { onPicked(tree) })
        // Collect off the main thread and keep the main looper turning; a hang fails within the deadline.
        val job = kotlinx.coroutines.GlobalScope.async(Dispatchers.IO) {
            kotlinx.coroutines.withTimeout(10_000) { repository.observe(Dispatchers.IO).take(3).toList() }
        }
        val deadline = System.currentTimeMillis() + 15_000
        while (!job.isCompleted && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        val emissions = runBlocking { kotlinx.coroutines.withTimeout(1_000) { job.await() } }.map { it.getOrThrow() }
        assertTrue(emissions[0].isEmpty())
        assertNull("Rows come before any probe", emissions[1].single().width)
        assertEquals(640, emissions[2].single().width)
        assertEquals(480, emissions[2].single().height)
        assertEquals(1, provider.childQueries.size)
    }

    @Test fun forgettingTheSaveFolderWhenItIsDownloadNezzelKeepsTheOldDownloadsGrant() {
        provider()
        val destinations = SaveDestinations(context)
        destinations.choose(tree)
        val old = OldDownloads(context)
        assertEquals(OldDownloadsPick.GRANTED, old.onPicked(tree))
        destinations.choose(null)
        assertEquals(listOf(tree), resolver.persistedUriPermissions.map { it.uri })
        assertEquals(tree, old.active())
    }

    @Test fun forgettingOldDownloadsKeepsTheSaveFolderGrant() {
        provider()
        val destinations = SaveDestinations(context)
        destinations.choose(tree)
        val old = OldDownloads(context).apply { onPicked(tree) }
        old.forget()
        assertNull(old.active())
        assertEquals(listOf(tree), resolver.persistedUriPermissions.map { it.uri })
        assertTrue(destinations.available())
        destinations.choose(null)
        assertTrue(resolver.persistedUriPermissions.isEmpty())
    }

    private fun provider() = Provider().also {
        it.attachInfo(context, android.content.pm.ProviderInfo().apply {
            authority = AUTHORITY; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, it)
    }

    /** ExternalStorageProvider's shape: "primary:<path>" ids under Download/Nezzel. */
    class Provider : DocumentsProvider() {
        private class Doc(val name: String, val mime: String, val size: Long?)
        private val docs = linkedMapOf<String, Doc>()
        val files = mutableMapOf<String, File>()
        val childQueries = mutableListOf<String>()
        var opens = 0
        var gone = false
        fun add(path: String, mime: String, size: Long? = null): String {
            val id = "$ROOT/$path"
            docs[id] = Doc(path.substringAfterLast('/'), mime, size)
            if (mime != DocumentsContract.Document.MIME_TYPE_DIR) files[id] = File.createTempFile("doc", null).apply { if (size != null) writeBytes(ByteArray(size.toInt())) }
            return id
        }
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?) = MatrixCursor(arrayOf("root_id"))
        private fun row(cursor: MatrixCursor, id: String) {
            val doc = docs[id]
            val dir = id == ROOT || doc?.mime == DocumentsContract.Document.MIME_TYPE_DIR
            cursor.addRow(cursor.columnNames.map<String, Any?> { when (it) {
                "document_id" -> id
                "_display_name" -> doc?.name ?: "Nezzel"
                "mime_type" -> if (dir) DocumentsContract.Document.MIME_TYPE_DIR else doc!!.mime
                "_size" -> doc?.size ?: files[id]?.length()
                "last_modified" -> files[id]?.lastModified() ?: 1_000L
                "flags" -> DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                else -> null
            } }.toTypedArray())
        }
        override fun queryDocument(documentId: String, projection: Array<out String>?): android.database.Cursor {
            if (gone || (documentId != ROOT && documentId !in docs)) throw java.io.FileNotFoundException()
            return MatrixCursor(projection ?: arrayOf("document_id", "_display_name", "mime_type", "_size", "last_modified", "flags")).also { row(it, documentId) }
        }
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): android.database.Cursor {
            if (gone) throw java.io.FileNotFoundException()
            childQueries += parentDocumentId
            val cursor = MatrixCursor(projection ?: arrayOf("document_id", "_display_name", "mime_type", "_size", "last_modified"))
            docs.keys.filter { it.substringBeforeLast('/') == parentDocumentId }.forEach { row(cursor, it) }
            return cursor
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            opens++
            return ParcelFileDescriptor.open(files[documentId] ?: throw java.io.FileNotFoundException(), ParcelFileDescriptor.parseMode(mode))
        }
        override fun deleteDocument(documentId: String) { docs.remove(documentId); files.remove(documentId)?.delete() }
        override fun isChildDocument(parentDocumentId: String, documentId: String) = documentId.startsWith("$parentDocumentId/")
    }

    companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
        const val ROOT = "primary:Download/Nezzel"
        val tree: Uri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT)
    }
}
