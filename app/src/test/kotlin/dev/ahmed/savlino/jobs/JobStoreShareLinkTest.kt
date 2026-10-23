package dev.ahmed.savlino.jobs

import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.storage.AesGcmLinkCipher
import dev.ahmed.savlino.storage.LinkCipher
import dev.ahmed.savlino.storage.LinkKeySource
import dev.ahmed.savlino.storage.PendingPublication
import dev.ahmed.savlino.storage.PublishedFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Software stand-in for the AndroidKeyStore alias: same cipher code, a key that can vanish or fail. */
class MemoryLinkKeys : LinkKeySource {
    var key: SecretKey? = null
    var failing = false
    var loads = 0
    override fun load(create: Boolean): SecretKey? {
        loads++
        if (failing) throw java.security.KeyStoreException("busy")
        return key ?: if (create) KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it } else null
    }
    override fun delete() { key = null }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], manifest = Config.NONE)
class JobStoreShareLinkTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val link = "https://example.com/watch?v=private-token&x=%2f"
    private val keys = MemoryLinkKeys()
    private val cipher = AesGcmLinkCipher(keys)

    @After fun tearDown() { app.deleteDatabase(JobStore.DATABASE_NAME) }

    private fun command(id: String): DownloadCommand {
        val format = Format("choice", "mp4", true, true, size = Size.Exact(10))
        val source = LinkRevision(LinkInput.parse(link)!!, 1)
        return (DownloadCommand.confirm(id, Inspection(source, "engine", listOf(format)), source, format.id, "clip")
            as ConfirmResult.Confirmed).command
    }

    private fun uri(id: String) = Uri.parse("content://media/external_primary/downloads/${id.hashCode() and 0xffff}")

    private fun JobStore.completed(id: String): Boolean {
        check(enqueue(command(id))); check(startTransfer(id)); check(markStaged(id))
        check(recordPending(id, PendingPublication(uri(id), "$id.mp4"))); check(markPublishing(id))
        return complete(id, PublishedFile(uri(id), "$id.mp4"))
    }

    private fun JobStore.blob(id: String): ByteArray? = readableDatabase.rawQuery(
        "SELECT share_link FROM jobs WHERE job_id = ?", arrayOf(id)).use { if (it.moveToFirst() && !it.isNull(0)) it.getBlob(0) else null }

    @Test fun completeStoresCiphertextNotTheLinkAndScrubsTheSource() { JobStore(app, null, cipher).use { store ->
        assertTrue(store.completed("done"))
        val blob = store.blob("done")!!
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("private-token"))
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("example.com"))
        assertNull(store.get("done")!!.sourceUrl)
        assertEquals(link, store.shareLink("done"))
        assertEquals(listOf("done"), store.linkedFiles().map { it.jobId })
        assertEquals(uri("done"), store.linkedFiles().single().published)
    } }

    @Test fun noCipherOrSealFailureStoresNothingButStillCompletes() {
        JobStore(app, null, null).use { store ->
            assertTrue(store.completed("plain"))
            assertNull(store.blob("plain")); assertNull(store.get("plain")!!.sourceUrl)
        }
        keys.failing = true
        JobStore(app, null, cipher).use { store ->
            assertTrue(store.completed("failed-seal"))
            assertNull(store.blob("failed-seal")); assertNull(store.get("failed-seal")!!.sourceUrl)
            assertTrue(store.linkedFiles().isEmpty())
        }
    }

    @Test fun onlyCompletedRowsMayHoldALink() { JobStore(app, null, cipher).use { store ->
        store.enqueue(command("live"))
        assertThrows(SQLiteConstraintException::class.java) {
            store.writableDatabase.execSQL("UPDATE jobs SET share_link = x'00' WHERE job_id = 'live'")
        }
        assertTrue(store.completed("done"))
        assertThrows(SQLiteConstraintException::class.java) {
            store.writableDatabase.execSQL("UPDATE jobs SET stage = 'FAILED' WHERE job_id = 'done'")
        }
    } }

    @Test fun removeAndPruneDeleteTheLinkWithTheRow() { JobStore(app, null, cipher).use { store ->
        assertTrue(store.completed("removed"))
        assertTrue(store.remove("removed"))
        assertNull(store.blob("removed"))
        for (i in 0..100) assertTrue(store.completed("bulk-$i"))
        store.writableDatabase.execSQL("UPDATE jobs SET updated_at = 1 WHERE job_id = 'bulk-0'")
        store.pruneTerminal()
        assertNull(store.get("bulk-0")); assertNull(store.blob("bulk-0"))
        assertEquals(100, store.linkedFiles().size)
    } }

    @Test fun clearForgetsEveryLinkAndTheKeyAndLaterLinksStillWork() { JobStore(app, null, cipher).use { store ->
        assertTrue(store.completed("a")); assertTrue(store.completed("b"))
        val oldBlob = store.blob("a")!!
        store.clearShareLinks()
        assertNull(store.blob("a")); assertNull(store.blob("b"))
        assertEquals(LinkCipher.Opened.Gone, cipher.open(oldBlob)) // a DB copy taken before the clear can't be opened
        assertTrue(store.completed("after")) // same process, no restart
        assertEquals(link, store.shareLink("after"))
    } }

    @Test fun busyKeystoreHidesButKeepsALostKeyDrops() { JobStore(app, null, cipher).use { store ->
        assertTrue(store.completed("kept")); assertTrue(store.completed("lost"))
        val fresh = AesGcmLinkCipher(keys) // a new process: nothing cached
        JobStore(app, null, fresh).use { reopened ->
            keys.failing = true
            assertNull(reopened.shareLink("kept"))
            assertNotNull(reopened.blob("kept"))
            keys.failing = false
            assertEquals(link, reopened.shareLink("kept"))
        }
        keys.delete()
        JobStore(app, null, AesGcmLinkCipher(keys)).use { reopened ->
            assertNull(reopened.shareLink("lost"))
            assertNull(reopened.blob("lost"))
        }
    } }

    @Test fun tamperedBlobIsDropped() { JobStore(app, null, cipher).use { store ->
        assertTrue(store.completed("bad"))
        store.writableDatabase.execSQL("UPDATE jobs SET share_link = x'${"00".repeat(40)}' WHERE job_id = 'bad'")
        assertNull(store.shareLink("bad"))
        assertNull(store.blob("bad"))
    } }

    @Test fun durableJournalAndSecureDeleteStillHoldOnTheCurrentSchema() { JobStore(app, null, cipher).use { store ->
        val db = store.writableDatabase
        assertEquals(JobStore.DATABASE_VERSION, db.version)
        for ((pragma, expected) in listOf("journal_mode" to "delete", "secure_delete" to "1"))
            db.rawQuery("PRAGMA $pragma", null).use { assertTrue(it.moveToFirst()); assertEquals(expected, it.getString(0).lowercase()) }
    } }
    /** C9: a file confirmed gone takes its link and artwork; the row stays, and a row with only artwork still counts. */
    @Test fun forgetFileDropsLinkAndArtworkButKeepsTheRow() { JobStore(app, null, cipher).use { store ->
        assertTrue(store.completed("gone")); assertTrue(store.completed("art-only"))
        assertTrue(store.artwork.save("gone", byteArrayOf(1, 2, 3)))
        store.dropShareLink("art-only"); assertTrue(store.artwork.save("art-only", byteArrayOf(1)))
        assertEquals(setOf("gone", "art-only"), store.linkedFiles().map { it.jobId }.toSet())
        store.forgetFile("gone")
        assertNull(store.blob("gone")); assertFalse(store.artwork.has("gone"))
        assertEquals(JobStage.COMPLETED, store.get("gone")!!.stage)
        store.forgetFile("art-only")
        assertTrue(store.linkedFiles().isEmpty())
    } }
}
