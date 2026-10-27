package dev.ahmed.savlino.downloads

import android.app.PendingIntent
import android.content.*
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
class SavedMediaRepositoryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun fixture(check: (SavedMediaRepository, Provider) -> Unit) {
        val provider = Provider().apply { attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" }) }
        ShadowContentResolver.registerProviderInternal("media", provider)
        try { check(SavedMediaRepository(context.contentResolver, context.packageName), provider) } finally { provider.db.close() }
    }
    @Test fun historicalUnambiguousAudioExtensionsRouteAsAudioWithoutChangingProviderMime() = fixture { repo, _ ->
        val row = repo.query().single()
        for (extension in listOf("opus", "ogg", "m4a", "mp3", "weba", "flac")) {
            val legacy = row.copy(filename = "Song.$extension", mimeType = "video/mp4")
            assertTrue(extension, legacy.audio)
            assertEquals(listOf(legacy), filterMedia(listOf(legacy), MediaFilter.AUDIO))
            assertEquals("video/mp4", legacy.mimeType) // no MediaStore rewrite
        }
        assertEquals(SavedMediaKind.VIDEO, row.copy(filename = "Clip.mkv", mimeType = "application/octet-stream").kind)
        assertFalse(row.copy(filename = "Ambiguous.webm", mimeType = "video/webm").audio)
    }
    @Test fun queryExcludesPendingOtherFoldersAndNonMediaAndOrdersNewestFirst() = fixture { repo, provider ->
        provider.insertRow(2, "audio/mpeg", 200)
        provider.insertRow(3, "video/mp4", 300, pending = 1)
        provider.insertRow(4, "video/mp4", 400, path = "Download/Other/")
        provider.insertRow(5, "application/pdf", 500)
        val list = repo.query()
        assertEquals(listOf("content://media/external/downloads/2", "content://media/external/downloads/1"), list.map { it.uri.toString() })
        assertEquals(listOf("MP3", "MP4"), list.map { it.format })
        assertEquals(720, list.last().height)
        assertEquals("Download/Nezzel/", list.last().relativePath)
        assertEquals("external_primary", list.last().volumeName)
        assertNull(list.first().size)
        assertEquals(listOf("Saved 2"), filterMedia(list, MediaFilter.AUDIO).map { it.title })
        assertTrue(filterMedia(list, MediaFilter.VIDEO, "2").isEmpty())
    }
    @Test fun searchFindsArabicVariantsInTitleAndFilename() = fixture { repo, provider ->
        provider.db.execSQL("UPDATE media SET _display_name = ?, title = ? WHERE _id = 1",
            arrayOf("محمـد ٢٠٢٤.mp4", "أَحْمَد في المدرسة"))
        val items = repo.query()
        for (query in listOf("احمد", "مدرسه", "محمد", "2024", "محمد احمد", "2024 مدرسه")) {
            assertEquals(query, items, filterMedia(items, MediaFilter.ALL, query))
        }
        assertTrue(filterMedia(items, MediaFilter.ALL, "احمد 2025").isEmpty())
        assertTrue(filterMedia(items, MediaFilter.ALL, "مفقود").isEmpty())
    }
    @Test fun searchCombinesFilterChipWithLatinCaseAndAccents() = fixture { repo, provider ->
        provider.insertRow(2, "audio/mpeg", 200)
        provider.db.execSQL("UPDATE media SET _display_name = CASE WHEN mime_type LIKE 'audio/%' THEN ? ELSE ? END, title = ?",
            arrayOf("été.mp3", "été.mp4", "Summer"))
        val items = repo.query()
        assertEquals(items, filterMedia(items, MediaFilter.ALL, "SUMMER ETE"))
        assertEquals(listOf("MP3"), filterMedia(items, MediaFilter.AUDIO, "ETE").map { it.format })
        assertEquals(listOf("MP4"), filterMedia(items, MediaFilter.VIDEO, "summer").map { it.format })
        assertTrue(filterMedia(items, MediaFilter.IMAGE, "ete").isEmpty())
        assertTrue(filterMedia(items, MediaFilter.AUDIO, "winter").isEmpty())
    }
    @Test fun cachedSearchKeyDoesNotChangeEqualityAndCopyUsesNewText() = fixture { repo, _ ->
        val item = repo.query().single().copy(title = "أحمد", filename = "مدرسة.mp4")
        val copy = item.copy()
        val hash = item.hashCode()
        assertEquals("احمد مدرسه.mp4", item.searchKey)
        assertEquals(copy, item)
        assertEquals(hash, item.hashCode())
        val renamed = item.copy(title = "محمد", filename = "جديد.mp4")
        assertEquals(listOf(renamed), filterMedia(listOf(renamed), MediaFilter.ALL, "محمد جديد"))
        assertTrue(filterMedia(listOf(renamed), MediaFilter.ALL, "احمد").isEmpty())
    }
    @Test fun missingOrNumericProviderTitleUsesTheConfirmedDisplayName() = fixture { repo, provider ->
        for (title in listOf(null, "", "1058356515425744", " 1058356515425744 ", "١٠٥٨٣٥٦٥١٥٤٢٥٧٤٤")) {
            provider.db.execSQL("UPDATE media SET _display_name = ?, title = ? WHERE _id = 1",
                arrayOf("Inspected post title.mp4", title))
            val item = repo.query().single()
            assertEquals("Inspected post title.mp4", item.filename)
            assertEquals("Inspected post title", item.title)
        }
    }
    @Test fun scannerReplacingPublishedTitleWithNumericIdStillShowsConfirmedFilenameStem() = fixture { repo, provider ->
        provider.db.execSQL("UPDATE media SET _display_name = ?, title = ? WHERE _id = 1",
            arrayOf("Confirmed Facebook title.mp4", "Confirmed Facebook title"))
        assertEquals("Confirmed Facebook title", repo.query().single().title)
        provider.db.execSQL("UPDATE media SET title = ? WHERE _id = 1", arrayOf("1058356515425744"))
        val rescanned = repo.query().single()
        assertEquals("Confirmed Facebook title", rescanned.title)
        assertEquals(listOf(rescanned), filterMedia(listOf(rescanned), MediaFilter.VIDEO, "Confirmed Facebook"))
    }
    @Test fun mkvWebmAndMovVideosAreListedAsVideoWithTheirFormat() = fixture { repo, provider ->
        provider.insertRow(2, "video/x-matroska", 200)
        provider.insertRow(3, "video/webm", 300)
        provider.insertRow(4, "video/quicktime", 400)
        provider.insertRow(5, "video/quicktime", 500, owner = "other.app")
        provider.insertRow(6, "video/quicktime", 600, pending = 1)
        val list = repo.query()
        assertEquals(listOf("MOV", "WebM", "MKV", "MP4"), list.map { it.format })
        assertEquals(4, filterMedia(list, MediaFilter.VIDEO).size)
    }
    @Test fun queryExcludesOtherOwnerAndAcceptsCanonicalDirectoryWithoutSlash() = fixture { repo, provider ->
        provider.insertRow(2, "video/mp4", 200, owner = "other.app")
        provider.insertRow(3, "audio/mpeg", 300, path = "Download/Nezzel")
        assertEquals(listOf(3L, 1L), repo.query().map { ContentUris.parseId(it.uri) })
    }
    @Test fun directDeleteRemovesOnlySelectedProviderRow() = fixture { repo, provider ->
        provider.insertRow(2, "audio/mpeg", 200)
        assertTrue(repo.delete(repo.query().last().uri))
        assertEquals(listOf(2L), repo.query().map { ContentUris.parseId(it.uri) })
    }
    @Test fun restoredDeleteRejectsMalformedUriWithoutDeletingAnyRow() = fixture { repo, provider ->
        listOf("content://other/external/downloads/1", "content://media/external/downloads/1?x=1",
            "content://media/external/downloads/-1", "content://media/external/images/1").forEach { raw ->
            try { repo.delete(Uri.parse(raw)); fail("Accepted malformed media URI") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(1, provider.db.rawQuery("SELECT _id FROM media", null).use { it.count })
    }
    @Test fun observerRefreshesAfterExternalDeletionAndUnregisters() = fixture { repo, provider -> runBlocking {
        val results = Channel<List<SavedMedia>>(Channel.UNLIMITED)
        val observer = launch { repo.observe().collect { results.send(it.getOrThrow()) } }
        try {
            assertEquals(1, withTimeout(5000) { results.receive() }.size)
            provider.delete(Uri.parse("content://media/external/downloads/1"), null, null)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(withTimeout(5000) { results.receive() }.isEmpty())
        } finally { observer.cancelAndJoin() }
    } }
    @Test fun unchangedRescanOfALargeLibraryEmitsNothingAndOneNewRowEmitsOnce() = fixture { repo, provider -> runBlocking {
        provider.db.beginTransaction()
        try { (2..2000).forEach { provider.insertRow(it, "video/mp4", 100L + it) }; provider.db.setTransactionSuccessful() }
        finally { provider.db.endTransaction() }
        val results = Channel<List<SavedMedia>>(Channel.UNLIMITED)
        val observer = launch { repo.observe().collect { results.send(it.getOrThrow()) } }
        fun rescan() {
            context.contentResolver.notifyChange(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, null)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        try {
            val first = withTimeout(5000) { results.receive() }
            assertEquals(2000, first.size)
            rescan()
            rescan()
            provider.insertRow(2001, "video/mp4", 5000)
            rescan()
            // Identical rescans emit nothing, so the next snapshot is the one with the new row.
            val next = withTimeout(5000) { results.receive() }
            assertEquals(2001, next.size)
            assertEquals(next.first().uri.toString(), "content://media/external/downloads/2001")
            assertEquals(first, next.drop(1))
            rescan()
            delay(300)
            assertTrue("An unchanged rescan must not emit", results.tryReceive().isFailure)
        } finally { observer.cancelAndJoin() }
    } }

    @Test fun initialMediaStoreAndRecordedRowsArriveBeforeSlowFolderValidation() = folderFixture { repo, folder, _ -> runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        folder.onQuery = { signal ->
            entered.countDown()
            signal?.setOnCancelListener { release.countDown() }
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
        }
        val results = Channel<List<SavedMedia>>(Channel.UNLIMITED)
        val observer = launch { repo.observe().collect { results.send(it.getOrThrow()) } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            val initial = withTimeout(1000) { results.receive() }
            assertTrue(initial.any { it.uri.authority == "media" })
            assertEquals(32, initial.count { it.uri.authority == "slow.documents" })
            assertEquals("Recorded 1", initial.first { it.filename == "Recorded 1.m4a" }.title)
            assertEquals("Initial result must not wait for all receipts", 1, folder.queries.get())
        } finally { release.countDown(); observer.cancelAndJoin() }
    } }

    @Test fun refreshEmitsNewMediaStoreRowsWhileOldFolderQueryIgnoresCancellation() = folderFixture { repo, folder, media -> runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.CountDownLatch(1)
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        folder.onQuery = {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                try { check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
                finally { returned.countDown() }
            }
        }
        val results = Channel<List<SavedMedia>>(Channel.UNLIMITED)
        val observer = launch { repo.observe().collect { results.send(it.getOrThrow()) } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            withTimeout(1000) { results.receive() }
            media.insertRow(2, "video/mp4", 200)
            context.contentResolver.notifyChange(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, null)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val refreshed = withTimeout(1500) { results.receive() }
            assertTrue(refreshed.any { it.uri.toString() == "content://media/external/downloads/2" })
            assertEquals("Old query must still be blocked", 1L, returned.count)
        } finally {
            release.countDown()
            observer.cancelAndJoin()
            assertTrue(withContext(Dispatchers.IO) { returned.await(5, java.util.concurrent.TimeUnit.SECONDS) })
        }
    } }

    @Test fun repeatedRefreshesDeduplicateInFlightFolderQueries() = folderFixture { repo, folder, media -> runBlocking {
        val release = java.util.concurrent.CountDownLatch(1)
        val entered = Channel<Uri>(Channel.UNLIMITED)
        val returned = Channel<Unit>(Channel.UNLIMITED)
        val started = java.util.concurrent.atomic.AtomicInteger()
        // Keep every query pending: a refresh must skip all of them and reach a distinct document.
        // Signalling entry must never be mistaken for completion of that query.
        folder.onQueryUri = { uri ->
            started.incrementAndGet()
            entered.trySend(uri)
            try { release.await() }
            finally { returned.trySend(Unit) }
        }
        val observer = launch { repo.observe().collect { it.getOrThrow() } }
        try {
            val seen = mutableSetOf(withTimeout(5000) { entered.receive() })
            repeat(3) { index ->
                media.insertRow(index + 2, "video/mp4", (index + 200).toLong())
                context.contentResolver.notifyChange(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, null)
                shadowOf(android.os.Looper.getMainLooper()).idle()
                val uri = withTimeout(5000) { entered.receive() }
                assertTrue("Refresh must not repeat an in-flight folder query: $uri", seen.add(uri))
            }
            assertEquals(4, started.get())
        } finally {
            observer.cancelAndJoin()
            release.countDown()
            withTimeout(5000) { repeat(started.get()) { returned.receive() } }
        }
    } }

    @Test fun completedAbandonedFolderQueryCanBeObservedAgain() = folderFixture { repo, folder, _ -> runBlocking {
        val entered = Channel<Uri>(Channel.UNLIMITED)
        val release = java.util.concurrent.CountDownLatch(1)
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        folder.onQueryUri = { uri ->
            if (first.compareAndSet(true, false)) {
                entered.trySend(uri)
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        var observer = launch { repo.observe().collect { it.getOrThrow() } }
        try {
            val blocked = withTimeout(5000) { entered.receive() }
            observer.cancelAndJoin()
            release.countDown()
            val results = Channel<List<SavedMedia>>(Channel.UNLIMITED)
            observer = launch { repo.observe().collect { results.send(it.getOrThrow()) } }
            // The provider returning is too early: onFinished runs afterwards. Refresh until the observable
            // result proves repository cleanup completed and this exact URI was queried again.
            withTimeout(5000) {
                var found = false
                while (!found) {
                    val rows = kotlinx.coroutines.withTimeoutOrNull(100) { results.receive() }
                    found = rows?.any { it.uri == blocked && it.size == 123L } == true
                    if (!found) {
                        context.contentResolver.notifyChange(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, null)
                        shadowOf(android.os.Looper.getMainLooper()).idle()
                    }
                }
            }
        } finally {
            release.countDown()
            observer.cancelAndJoin()
        }
    } }

    @Test fun cancellingObservationCancelsTheBlockedProviderQuery() = folderFixture { repo, folder, _ -> runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        folder.onQuery = { signal ->
            signal?.setOnCancelListener { cancelled.countDown(); release.countDown() }
            entered.countDown()
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            signal?.throwIfCanceled()
        }
        val observer = launch { repo.observe().collect {} }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            observer.cancel()
            assertTrue("Cancellation must reach the provider before it returns",
                withContext(Dispatchers.IO) { cancelled.await(2, java.util.concurrent.TimeUnit.SECONDS) })
        } finally { release.countDown(); observer.cancelAndJoin() }
    } }

    @Test fun folderValidationEmitsIncrementallyAndExternalDeletionDisappearsOnRefresh() = folderFixture { repo, folder, _ -> runBlocking {
        val results = Channel<List<SavedMedia>>(Channel.UNLIMITED)
        var observer = launch { repo.observe().collect { results.send(it.getOrThrow()) } }
        suspend fun until(check: (List<SavedMedia>) -> Boolean): List<SavedMedia> = withTimeout(5000) {
            var rows = results.receive()
            while (!check(rows)) rows = results.receive()
            rows
        }
        try {
            val first = until { rows -> rows.any { it.uri.authority == "slow.documents" && it.size == 123L } }
            assertTrue("One receipt should update before the remaining receipts", first.any { it.uri.authority == "slow.documents" && it.size == null })
            until { rows -> rows.filter { it.uri.authority == "slow.documents" }.all { it.size == 123L } }
            folder.missing = "1"
            // Recollect models pause/resume; local changes must also revalidate receipts.
            observer.cancelAndJoin()
            while (results.tryReceive().isSuccess) { }
            observer = launch { repo.observe().collect { results.send(it.getOrThrow()) } }
            val refreshed = until { rows -> rows.none { it.uri == folder.uri("1") } }
            assertEquals(31, refreshed.count { it.uri.authority == "slow.documents" })
            assertTrue(refreshed.any { it.uri.authority == "media" })
            folder.missing = "2"
            context.contentResolver.notifyChange(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, null)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val changed = until { rows -> rows.none { it.uri == folder.uri("2") } }
            assertTrue(changed.none { it.uri == folder.uri("1") })
        } finally { observer.cancelAndJoin() }
    } }

    private fun folderFixture(check: (SavedMediaRepository, FolderProvider, Provider) -> Unit) = fixture { _, media ->
        val folder = FolderProvider().apply {
            attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "slow.documents" })
        }
        ShadowContentResolver.registerProviderInternal("slow.documents", folder)
        val destinations = dev.ahmed.savlino.storage.SaveDestinations(context)
        repeat(32) { destinations.record(folder.uri((it + 1).toString()), "Recorded ${it + 1}.m4a", "audio/mp4", true) }
        check(SavedMediaRepository(context.contentResolver, context.packageName, destinations), folder, media)
    }

    class FolderProvider : ContentProvider() {
        val queries = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var missing: String? = null
        @Volatile var onQuery: ((android.os.CancellationSignal?) -> Unit)? = null
        @Volatile var onQueryUri: ((Uri) -> Unit)? = null
        fun uri(id: String) = Uri.parse("content://slow.documents/tree/root/document/$id")
        override fun onCreate() = true
        override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?) =
            query(uri, columns, selection, args, sort, null)
        override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?,
            signal: android.os.CancellationSignal?): Cursor {
            queries.incrementAndGet()
            onQueryUri?.invoke(uri)
            onQuery?.invoke(signal)
            return android.database.MatrixCursor(columns ?: arrayOf("_display_name", "_size", "last_modified")).apply {
                if (uri.lastPathSegment != missing) addRow(columnNames.map<String, Any> { when (it) {
                    "_display_name" -> "Recorded ${uri.lastPathSegment}.m4a"
                    "_size" -> 123L
                    else -> 0L
                } }.toTypedArray())
            }
        }
        override fun getType(uri: Uri) = "audio/mp4"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    }

    @Test @Config(sdk = [35]) fun modernDeleteCreatesSystemRequestWithoutDeletingBeforeConsent() = fixture { repo, _ ->
        val item = repo.query().single()
        assertNotNull(repo.deleteRequest(item))
        assertEquals(1, repo.query().size)
    }
    @Test fun openAndShareGrantOnlyReadAccessToActualMediaUri() = fixture { repo, _ ->
        val item = repo.query().single()
        val open = MediaActions.open(item)
        assertEquals(item.uri, open.data)
        assertEquals("video/mp4", open.type)
        assertTrue(open.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, open.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        @Suppress("DEPRECATION") val send = MediaActions.share(item).getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        @Suppress("DEPRECATION") val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals(item.uri, stream)
        assertEquals(item.uri, send.clipData!!.getItemAt(0).uri)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    @Test fun imageQueryUsesRealProviderScopeAllowlistAndNewestIdTiebreak() = fixture { repo, provider ->
        provider.insertRow(2, "image/jpeg", 200)
        provider.insertRow(3, "image/png", 300, path = "Download/Nezzel")
        provider.insertRow(4, "image/webp", 300)
        provider.insertRow(5, "image/jpeg", 500, owner = "other.app")
        provider.insertRow(6, "image/png", 600, path = "Pictures/Nezzel/")
        provider.insertRow(7, "image/webp", 700, pending = 1)
        provider.insertRow(8, "image/gif", 800)
        provider.insertRow(9, "image/svg+xml", 900)
        provider.insertRow(11, "image/avif", 1100)
        provider.insertRow(10, "image/jpeg", 1000, path = "Download/Nezzel/subfolder/")
        provider.insertRow(12, "video/mp4", 1200, path = "Download/Nezzel/Statuses/")
        provider.insertRow(13, "image/jpeg", 1300, path = "Download/Nezzel/Statuses")
        provider.insertRow(14, "image/jpeg", 1400, path = "Download/Nezzel/Statuses/", owner = "other.app")
        val saved = repo.query()
        assertEquals("Saved statuses are listed with the downloads", listOf(13L, 12L), saved.take(2).map { ContentUris.parseId(it.uri) })
        provider.db.execSQL("DELETE FROM media WHERE _id IN (12, 13)")
        val items = repo.query()
        assertEquals(listOf(11L, 8L, 4L, 3L, 2L, 1L), items.map { ContentUris.parseId(it.uri) })
        assertEquals(listOf("AVIF", "GIF", "WebP", "PNG", "JPEG", "MP4"), items.map { it.format })
        assertEquals(listOf(11L, 8L, 4L, 3L, 2L), filterMedia(items, MediaFilter.IMAGE).map { ContentUris.parseId(it.uri) })
    }
    @Test fun providerHeightRejectsLongOverflowInsteadOfWrappingToVideoQuality() = fixture { repo, provider ->
        listOf(2147483648L, 4294967297L, Long.MAX_VALUE, 0L, -1L).forEach { height ->
            provider.db.execSQL("UPDATE media SET height = ? WHERE _id = 1", arrayOf(height))
            assertNull("height=$height", repo.query().single().height)
        }
        provider.db.execSQL("UPDATE media SET height = 2147483647 WHERE _id = 1")
        assertEquals(Int.MAX_VALUE, repo.query().single().height)
    }
    @Test fun imageActionsKeepExactMimeUriAndReadOnlyGrantsFromQuery() = fixture { repo, provider ->
        listOf("image/jpeg", "image/png", "image/webp", "image/avif").forEachIndexed { i, mime -> provider.insertRow(i + 2, mime, 200L + i) }
        val images = repo.query().filter { it.mimeType.startsWith("image/") }
        assertEquals(4, images.size)
        images.forEach { item ->
            val open = MediaActions.open(item)
            assertEquals(Intent.ACTION_VIEW, open.action)
            assertEquals(item.uri, open.data)
            assertEquals(item.mimeType, open.type)
            assertEquals(item.uri, open.clipData!!.getItemAt(0).uri)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, open.flags)
            @Suppress("DEPRECATION") val send = MediaActions.share(item).getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            @Suppress("DEPRECATION") val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals(item.mimeType, send.type)
            assertEquals(item.uri, stream)
            assertEquals(item.uri, send.clipData!!.getItemAt(0).uri)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
        }
    }
    @Test fun imageDirectDeleteRemovesSelectedRowAndLeavesNeighbors() = fixture { repo, provider ->
        provider.insertRow(2, "image/jpeg", 200)
        provider.insertRow(3, "image/png", 300)
        val item = repo.query().first { it.mimeType == "image/jpeg" }
        assertTrue(repo.delete(item.uri))
        assertEquals(listOf(3L, 1L), repo.query().map { ContentUris.parseId(it.uri) })
    }
    @Test @Config(sdk = [35]) fun imageModernDeleteWaitsForConsentAndKeepsAllRows() = fixture { repo, provider ->
        provider.insertRow(2, "image/webp", 200)
        assertNotNull(repo.deleteRequest(repo.query().first { it.mimeType == "image/webp" }))
        assertEquals(listOf(2L, 1L), repo.query().map { ContentUris.parseId(it.uri) })
    }
    @Test fun multiShareIncludesEveryUriReadGrantAndCommonMime() = fixture { repo, provider ->
        provider.insertRow(2, "video/mp4", 200)
        provider.insertRow(3, "video/webm", 300)
        provider.insertRow(4, "audio/mpeg", 400)
        val items = repo.query().associateBy { ContentUris.parseId(it.uri) }
        listOf(listOf(1L, 2L) to "video/mp4", listOf(1L, 3L) to "video/*", listOf(1L, 4L) to "*/*").forEach { (ids, mime) ->
            val selected = ids.map { items.getValue(it) }
            @Suppress("DEPRECATION") val send = MediaActions.share(selected).getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
            assertEquals(mime, send.type)
            @Suppress("DEPRECATION") val streams = send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals(selected.map { it.uri }, streams)
            assertEquals(selected.map { it.uri }, (0 until send.clipData!!.itemCount).map { send.clipData!!.getItemAt(it).uri })
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
        }
        @Suppress("DEPRECATION") val single = MediaActions.share(listOf(items.getValue(1L))).getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, single.action)
        @Suppress("DEPRECATION") val stream = single.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals(items.getValue(1L).uri, stream)
    }
    @Test @Config(sdk = [33]) fun batchDeleteUsesOneSystemRequestContainingAllUrisBeforeAnyDeletion() = fixture { repo, provider ->
        provider.insertRow(2, "audio/mpeg", 200)
        provider.insertRow(3, "image/jpeg", 300)
        val items = repo.query()
        assertNotNull(repo.deleteRequest(items.map { it.uri }))
        assertEquals(1, provider.deleteRequests.size)
        // MediaStore packages the selected URIs as ClipData for the media provider.
        val clip = provider.deleteRequests.single().getParcelable<ClipData>("clip_data")!!
        assertEquals(listOf("content://media/external/images/media/3", "content://media/external/audio/media/2",
            "content://media/external/video/media/1"), (0 until clip.itemCount).map { clip.getItemAt(it).uri.toString() })
        assertEquals(3, repo.query().size)
    }
    @Test @Config(sdk = [33, 35]) fun deleteConsentPreservesVolumeAndIdAndDeduplicates() = fixture { repo, provider ->
        listOf("external", "external_primary", "0123-4567").forEach { volume ->
            val uri = Uri.parse("content://media/$volume/downloads/1")
            repo.deleteRequest(listOf(uri, uri))
            val clip = provider.deleteRequests.last().getParcelable<ClipData>("clip_data")!!
            assertEquals(1, clip.itemCount)
            assertEquals("content://media/$volume/video/media/1", clip.getItemAt(0).uri.toString())
        }
        assertEquals(1, repo.query().size)
    }
    @Test @Config(sdk = [35]) fun missingOrNonMediaRowCannotRequestDeletion() = fixture { repo, provider ->
        provider.insertRow(2, "application/pdf", 200)
        listOf(2, 999).forEach { id ->
            assertThrows(IllegalArgumentException::class.java) {
                repo.deleteRequest(listOf(Uri.parse("content://media/external/downloads/$id")))
            }
        }
        assertTrue(provider.deleteRequests.isEmpty())
        assertEquals(2, provider.db.rawQuery("SELECT _id FROM media", null).use { it.count })
    }
    open class Provider : ContentProvider() {
        val db = SQLiteDatabase.create(null).apply {
            execSQL("CREATE TABLE media (_id INTEGER PRIMARY KEY, _display_name TEXT, title TEXT, mime_type TEXT, _size INTEGER, date_added INTEGER, height INTEGER, width INTEGER, duration INTEGER, bitrate INTEGER, relative_path TEXT, is_pending INTEGER, owner_package_name TEXT, volume_name TEXT)")
        }
        init { insertRow(1, "video/mp4", 100) }
        fun insertRow(id: Int, mime: String, date: Long, pending: Int = 0, path: String = "Download/Nezzel/", owner: String = RuntimeEnvironment.getApplication().packageName, volume: String = "external_primary") {
            db.execSQL("INSERT INTO media VALUES (?, ?, ?, ?, NULL, ?, 720, NULL, 84000, NULL, ?, ?, ?, ?)", arrayOf<Any>(id, "Saved $id.mp4", "Saved $id", mime, date, path, pending, owner, volume))
        }
        override fun onCreate() = true
        override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
            db.query("media", columns, selection, args, null, null, sort)
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = db.delete("media", "_id = ?", arrayOf(uri.lastPathSegment)).also {
            RuntimeEnvironment.getApplication().contentResolver.notifyChange(uri, null)
        }
        val deleteRequests = mutableListOf<Bundle>()
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            // AOSP MediaProvider.createRequest rejects Downloads IDs, even for app-owned rows.
            // https://android.googlesource.com/platform/packages/providers/MediaProvider/+/33ff6a663eea1fcdd2b422b98722c1dee48a7f6a/src/com/android/providers/media/MediaProvider.java
            val clip = extras!!.getParcelable<ClipData>("clip_data")!!
            for (i in 0 until clip.itemCount) {
                val uri = clip.getItemAt(i).uri
                require(uri.authority == "media" && uri.pathSegments.size == 4 &&
                    uri.pathSegments[1] in setOf("images", "video", "audio") && uri.pathSegments[2] == "media" &&
                    uri.lastPathSegment?.toLongOrNull() != null) { "All requested items must be referenced by specific ID" }
            }
            deleteRequests += Bundle(extras ?: Bundle())
            return Bundle().apply {
                putParcelable("result", PendingIntent.getActivity(RuntimeEnvironment.getApplication(), 1,
                    Intent("test-delete"), PendingIntent.FLAG_IMMUTABLE))
            }
        }
        override fun getType(uri: Uri): String? = db.rawQuery("SELECT mime_type FROM media WHERE _id = ?",
            arrayOf(uri.lastPathSegment)).use { if (it.moveToFirst()) it.getString(0) else null }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }
}
