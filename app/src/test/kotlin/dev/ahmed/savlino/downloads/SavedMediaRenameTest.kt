package dev.ahmed.savlino.downloads

import android.content.*
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
// SDK coverage: SavedMedia.kt rename/ownership paths have no API branch; query-column coverage lives in SavedMediaRepositoryTest.
@Config(sdk = [29])
class SavedMediaRenameTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun fixture(check: (SavedMediaRepository, RenameProvider) -> Unit) {
        val provider = RenameProvider().apply { attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "media" }) }
        ShadowContentResolver.registerProviderInternal("media", provider)
        try { check(SavedMediaRepository(app.contentResolver, app.packageName), provider) } finally { provider.db.close() }
    }
    @Test fun renameUpdatesOnlyDisplayNameOnSameRowAndKeepsExtensionAndTitle() = fixture { repo, provider ->
        val before = repo.query().single()
        assertTrue(repo.canRename(before.uri))
        assertEquals("Summer memories.mp4", repo.rename(before.uri, "  Summer memories  "))
        val after = repo.query().single()
        assertEquals(before.uri, after.uri)
        assertEquals(before.title, after.title)
        assertEquals("Summer memories", after.displayName)
        assertEquals(setOf(MediaStore.MediaColumns.DISPLAY_NAME), provider.writtenKeys)
        assertEquals(before.size, after.size)
        assertEquals(1, provider.db.rawQuery("SELECT _id FROM media", null).use { it.count })
    }
    @Test fun clashReturnsFinalNameChosenByProviderAndKeepsBothRows() = fixture { repo, provider ->
        provider.insertRow(2, "video/mp4", 200)
        val uri = repo.query().first { it.uri.lastPathSegment == "1" }.uri
        assertEquals("Saved 2 (1).mp4", repo.rename(uri, "Saved 2"))
        assertEquals(listOf("Saved 2.mp4", "Saved 2 (1).mp4"), repo.query().map { it.filename })
    }
    @Test fun freshProviderNameDeterminesExtensionRatherThanStaleUiName() = fixture { repo, provider ->
        val uri = repo.query().single().uri
        provider.db.execSQL("UPDATE media SET _display_name = 'Actual.MP4'")
        assertEquals("New.MP4", repo.rename(uri, "New"))
    }
    @Test fun foreignMissingAndDocumentRowsCannotBeRenamed() = fixture { repo, provider ->
        val uri = repo.query().single().uri
        for (owner in listOf("other.app", null)) {
            provider.db.execSQL("UPDATE media SET owner_package_name = ?", arrayOf(owner))
            assertFalse(repo.canRename(uri))
            assertNull(repo.rename(uri, "Changed"))
            assertEquals("Saved 1.mp4", provider.name(1))
        }
        for (bad in listOf("content://media/external/downloads/999", "content://docs/tree/root/document/1",
            "content://media/external/downloads/1?x=1", "file:///Download/Nezzel/a.mp4")) {
            assertFalse(repo.canRename(Uri.parse(bad)))
        }
        assertEquals(0, provider.updates)
    }
    @Test fun ownershipIsRecheckedAfterSheetOpens() = fixture { repo, provider ->
        val uri = repo.query().single().uri
        assertTrue(repo.canRename(uri))
        provider.db.execSQL("UPDATE media SET owner_package_name = 'other.app'")
        assertNull(repo.rename(uri, "Changed"))
        assertEquals("Saved 1.mp4", provider.name(1))
        assertEquals(0, provider.updates)
    }
    @Test fun securityFailureLeavesRowUntouchedAndHidesActionForRestOfRepositoryLifetime() = fixture { repo, provider ->
        val uri = repo.query().single().uri
        assertTrue(repo.canRename(uri))
        provider.denyUpdate = true
        assertNull(repo.rename(uri, "Changed"))
        provider.denyUpdate = false
        assertFalse(repo.canRename(uri))
        assertNull(repo.rename(uri, "Again"))
        assertEquals("Saved 1.mp4", provider.name(1))
    }
    @Test fun ownershipQuerySecurityFailureHidesAction() = fixture { repo, provider ->
        val uri = repo.query().single().uri
        provider.denyQuery = true
        assertFalse(repo.canRename(uri))
        assertNull(repo.rename(uri, "Changed"))
        assertEquals(0, provider.updates)
    }
    @Test fun invalidNamesNeverWriteAndBoundaryLengthIsAccepted() = fixture { repo, provider ->
        val uri = repo.query().single().uri
        for (name in listOf("", "   ", "a/b", "a\\b", ".hidden", "  .hidden  ", "x".repeat(201)) +
            ((0..31) + (127..159)).map { "a${it.toChar()}b" }) {
            assertFalse(name, validSavedName(name))
            assertThrows(IllegalArgumentException::class.java) { repo.rename(uri, name) }
            assertEquals("Saved 1.mp4", provider.name(1))
        }
        assertEquals(0, provider.updates)
        assertEquals("${"x".repeat(200)}.mp4", repo.rename(uri, "x".repeat(200)))
    }
    @Test fun successfulUpdateWithoutRequeryRowReturnsRequestedNameAndPreservedExtension() = fixture { repo, provider ->
        val uri = repo.query().single().uri
        provider.hideUpdatedRow = true
        assertEquals("Holiday.mp4", repo.rename(uri, "  Holiday  "))
        assertEquals("Holiday.mp4", provider.name(1))
        assertEquals(1, provider.updates)
    }
    @Test fun zeroRowUpdateDoesNotClaimSuccess() = fixture { repo, provider ->
        val uri = repo.query().single().uri
        provider.skipUpdate = true
        assertNull(repo.rename(uri, "Changed"))
        assertEquals("Saved 1.mp4", provider.name(1))
    }

    class RenameProvider : SavedMediaRepositoryTest.Provider() {
        /** Holds background queries (never the main thread's) until released, so a test decides when an off-main result lands. */
        @Volatile var queryGate: java.util.concurrent.CountDownLatch? = null
        var denyUpdate = false
        var denyQuery = false
        var skipUpdate = false
        var hideUpdatedRow = false
        var updates = 0
        var writtenKeys = emptySet<String>()
        fun name(id: Int): String = db.rawQuery("SELECT _display_name FROM media WHERE _id = ?", arrayOf(id.toString()))
            .use { it.moveToFirst(); it.getString(0) }
        override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            if (denyQuery) throw SecurityException()
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) queryGate?.await(5, java.util.concurrent.TimeUnit.SECONDS)
            val id = uri.lastPathSegment?.toLongOrNull()
            val where = listOfNotNull(selection?.let { "($it)" }, id?.let { "_id = $it" },
                "0".takeIf { hideUpdatedRow && updates > 0 && id != null }).joinToString(" AND ").ifEmpty { null }
            return db.query("media", columns, where, args, null, null, sort)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            updates++
            if (denyUpdate) throw SecurityException()
            if (skipUpdate) return 0
            writtenKeys = values!!.keySet()
            val desired = values.getAsString("_display_name")
            val exists = db.rawQuery("SELECT _id FROM media WHERE _display_name = ? AND _id != ?", arrayOf(desired, uri.lastPathSegment))
                .use { it.moveToFirst() }
            val finalName = if (exists) desired.substringBeforeLast('.') + " (1)." + desired.substringAfterLast('.') else desired
            return db.update("media", ContentValues(values).apply { put("_display_name", finalName) },
                "_id = ? AND ($selection)", arrayOf(uri.lastPathSegment, *args.orEmpty())).also {
                appContext().contentResolver.notifyChange(uri, null)
            }
        }
        private fun appContext() = RuntimeEnvironment.getApplication()
    }
}
