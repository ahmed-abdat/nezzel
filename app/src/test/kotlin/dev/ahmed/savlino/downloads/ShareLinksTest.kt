package dev.ahmed.savlino.downloads

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.jobs.JobStage
import dev.ahmed.savlino.jobs.JobStore
import dev.ahmed.savlino.storage.AesGcmLinkCipher
import dev.ahmed.savlino.storage.LinkCipher
import dev.ahmed.savlino.storage.LinkKeySource
import dev.ahmed.savlino.storage.PendingPublication
import dev.ahmed.savlino.storage.PublishedFile
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
class ShareLinksTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "share-links-io") }
    private val dispatcher = io.asCoroutineDispatcher()
    private val keys = object : LinkKeySource {
        var key: SecretKey? = null
        override fun load(create: Boolean): SecretKey? =
            key ?: if (create) KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it } else null
        override fun delete() { key = null }
    }
    /** Records the thread of every open: a DB read always precedes it. */
    private val opens = mutableListOf<String>()
    private val cipher = object : LinkCipher {
        private val real = AesGcmLinkCipher(keys)
        override fun seal(link: String) = real.seal(link)
        override fun open(blob: ByteArray): LinkCipher.Opened { opens += Thread.currentThread().name.substringBefore(" @"); return real.open(blob) }
        override fun forget() = real.forget()
    }

    @After fun tearDown() { dispatcher.close(); app.deleteDatabase(JobStore.DATABASE_NAME) }

    private fun JobStore.completed(id: String, uri: Uri, link: String = "https://example.com/v/$id"): Uri {
        val format = Format("choice", "mp4", true, true, size = Size.Exact(10))
        val source = LinkRevision(LinkInput.parse(link)!!, 1)
        val command = (DownloadCommand.confirm(id, Inspection(source, "engine", listOf(format)), source, format.id, "clip")
            as ConfirmResult.Confirmed).command
        check(enqueue(command)); check(startTransfer(id)); check(markStaged(id))
        check(recordPending(id, PendingPublication(uri, "$id.mp4"))); check(markPublishing(id))
        check(complete(id, PublishedFile(uri, "$id.mp4")))
        return uri
    }

    private fun media(id: Long, volume: String?) = SavedMedia(Uri.parse("content://media/external/downloads/$id"),
        "$id.mp4", "$id", "video/mp4", null, 0, null, null, null, volumeName = volume)
    private fun document(uri: Uri) = SavedMedia(uri, "clip.mp4", "clip", "video/mp4", null, 0, null, null, null)
    private val tree = Uri.parse("content://docs.test/tree/root")
    private fun doc(name: String) = DocumentsContract.buildDocumentUriUsingTree(tree, "root/$name")

    @Test fun linkMatchesVolumeAndIdNeverIdAlone() { JobStore(app, null, cipher).use { store ->
        store.completed("primary", Uri.parse("content://media/external_primary/downloads/7"), "https://example.com/primary")
        store.completed("card", Uri.parse("content://media/0123-4567/downloads/7"), "https://example.com/card")
        store.completed("folder", doc("clip.mp4"), "https://example.com/folder")
        val links = ShareLinks(store, { null }, dispatcher) { }
        runBlocking {
            assertEquals("https://example.com/primary", links.link(media(7, "external_primary")))
            assertEquals("https://example.com/card", links.link(media(7, "0123-4567")))
            assertNull(links.link(media(7, null)))
            assertNull(links.link(media(8, "external_primary")))
            assertEquals("https://example.com/folder", links.link(document(doc("clip.mp4"))))
            assertNull(links.link(document(doc("other.mp4"))))
        }
        assertEquals(listOf("share-links-io"), opens.distinct())
    } }

    @Test fun sweepForgetsOnlyConfirmedAbsentCandidatesOnTheIoDispatcher() { JobStore(app, null, cipher).use { store ->
        val listed = store.completed("listed", Uri.parse("content://media/external_primary/downloads/1"))
        val gone = store.completed("gone", Uri.parse("content://media/external_primary/downloads/2"))
        val present = store.completed("present", Uri.parse("content://media/external_primary/downloads/3"))
        val unknown = store.completed("unknown", Uri.parse("content://media/external_primary/downloads/4"))
        val throwing = store.completed("throwing", Uri.parse("content://media/external_primary/downloads/5"))
        val listedAt = System.currentTimeMillis() + 1
        Thread.sleep(2)
        val newer = store.completed("newer", Uri.parse("content://media/external_primary/downloads/6"))
        val probed = mutableListOf<Uri>()
        val threads = mutableListOf<String>()
        val forgotten = mutableListOf<String>()
        val links = ShareLinks(store, { uri ->
            probed += uri; threads += Thread.currentThread().name.substringBefore(" @")
            when (uri) { gone -> true; present -> false; throwing -> throw SecurityException(); else -> null }
        }, dispatcher) { forgotten += it; threads += Thread.currentThread().name.substringBefore(" @") }
        runBlocking { links.sweep(listOf(media(1, "external_primary")), listedAt) }
        assertEquals(setOf(gone, present, unknown, throwing), probed.toSet())
        assertFalse(listed in probed); assertFalse(newer in probed)
        assertEquals(listOf("gone"), forgotten)
        assertEquals(setOf("listed", "present", "unknown", "throwing", "newer"), store.linkedFiles().map { it.jobId }.toSet())
        assertEquals(JobStage.COMPLETED, store.get("gone")!!.stage)
        assertEquals(listOf("share-links-io"), threads.distinct())
    } }

    /** MediaStore: 0 rows = gone; trashed rows still count (API 30+); a failing provider is unknown. */
    @Test fun mediaStoreProbeTellsGoneFromUnknown() {
        val provider = MediaProvider().apply { attachInfo(app, android.content.pm.ProviderInfo().apply { authority = MediaStore.AUTHORITY }) }.also { ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, it) }
        val probe = FileProbe(app.contentResolver)
        provider.rows += "1"; provider.trashed += "2"
        assertEquals(false, probe.gone(Uri.parse("content://media/external_primary/downloads/1")))
        assertEquals(if (android.os.Build.VERSION.SDK_INT >= 30) false else true,
            probe.gone(Uri.parse("content://media/external_primary/downloads/2")))
        assertEquals(true, probe.gone(Uri.parse("content://media/external_primary/downloads/3")))
        provider.nullCursor = true
        assertNull(probe.gone(Uri.parse("content://media/external_primary/downloads/3")))
        provider.nullCursor = false; provider.failure = IllegalArgumentException("volume not mounted")
        assertNull(probe.gone(Uri.parse("content://media/0123-4567/downloads/3")))
    }

    /** Folder documents: gone only while Nezzel can still read the folder; revoked or unreachable keeps the link. */
    @Test fun folderProbeNeedsTheGrantAndAReachableFolder() {
        val provider = DocsProvider().apply { attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "docs.test" }) }.also { ShadowContentResolver.registerProviderInternal("docs.test", it) }
        val probe = FileProbe(app.contentResolver)
        provider.docs += "root/clip.mp4"
        assertNull(probe.gone(doc("missing.mp4"))) // no persisted grant
        app.contentResolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(false, probe.gone(doc("clip.mp4")))
        assertEquals(true, probe.gone(doc("missing.mp4"))) // DocumentsProvider: null cursor
        provider.missing = FileNotFoundException()
        assertEquals(true, probe.gone(doc("missing.mp4")))
        provider.missing = IllegalStateException(FileNotFoundException())
        assertEquals(true, probe.gone(doc("missing.mp4")))
        provider.missing = SecurityException()
        assertNull(probe.gone(doc("missing.mp4")))
        provider.missing = null; provider.folderReachable = false // SD card out: the folder itself is gone
        assertNull(probe.gone(doc("missing.mp4")))
        provider.folderReachable = true
        app.contentResolver.releasePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNull(probe.gone(doc("missing.mp4")))
    }

    private class MediaProvider : ContentProvider() {
        val rows = mutableSetOf<String>(); val trashed = mutableSetOf<String>()
        var nullCursor = false; var failure: Exception? = null
        override fun onCreate() = true
        override fun query(uri: Uri, columns: Array<out String>?, args: Bundle?, signal: CancellationSignal?): Cursor? {
            failure?.let { throw it }
            if (nullCursor) return null
            val id = uri.lastPathSegment!!
            val withTrash = args?.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED) == MediaStore.MATCH_INCLUDE
            return MatrixCursor(arrayOf("_id")).apply { if (id in rows || (withTrash && id in trashed)) addRow(arrayOf(id)) }
        }
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = query(uri, p, null, null)
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    private class DocsProvider : ContentProvider() {
        val docs = mutableSetOf<String>()
        var missing: Exception? = null
        var folderReachable = true
        override fun onCreate() = true
        override fun query(uri: Uri, columns: Array<out String>?, args: Bundle?, signal: CancellationSignal?): Cursor? {
            val id = DocumentsContract.getDocumentId(uri)
            if (id == "root") return if (folderReachable) MatrixCursor(arrayOf("document_id")).apply { addRow(arrayOf(id)) } else null
            if (id in docs) return MatrixCursor(arrayOf("document_id")).apply { addRow(arrayOf(id)) }
            missing?.let { throw it }
            return null
        }
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = query(uri, p, null, null)
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }
}
