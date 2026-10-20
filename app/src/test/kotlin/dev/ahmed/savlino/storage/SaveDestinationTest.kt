package dev.ahmed.savlino.storage

import android.content.*
import android.database.MatrixCursor
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import dev.ahmed.savlino.downloads.SavedMediaRepository
import dev.ahmed.savlino.downloads.MediaActions
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.jobs.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], manifest = Config.NONE)
class SaveDestinationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun customFolderImageUsesStemAndCachesActualBounds() {
        val provider = provider()
        val settings = SaveDestinations(context)
        val id = provider.createDocument("root", "image/png", "A photo.v2.png")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        val bitmap = android.graphics.Bitmap.createBitmap(800, 420, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            provider.files.getValue(id).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        settings.record(uri, "A photo.v2.png", "image/png", true)
        var opens = 0
        provider.onOpen = { opens++ }
        repeat(2) {
            val item = settings.savedFiles().single()
            assertEquals("A photo.v2", item.title)
            assertEquals("A photo.v2.png", item.filename)
            assertEquals(800, item.width)
            assertEquals(420, item.height)
        }
        assertEquals("Repeated listing must reuse bounds", 1, opens)
        provider.files.getValue(id).writeBytes(byteArrayOf(1, 2, 3))
        val changed = settings.savedFiles().single()
        assertNull(changed.width)
        assertNull(changed.height)
        assertEquals("Changed file must invalidate cached bounds", 2, opens)
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun customFolderGifShowsActualDimensions() {
        val provider = provider()
        val settings = SaveDestinations(context)
        val id = provider.createDocument("root", "image/gif", "Animation.gif")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        provider.files.getValue(id).writeBytes(java.util.Base64.getDecoder().decode(
            "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"))
        settings.record(uri, "Animation.gif", "image/gif", true)
        val item = settings.savedFiles().single()
        assertEquals("Animation", item.title)
        assertEquals("GIF", item.format)
        assertEquals(1, item.width)
        assertEquals(1, item.height)
    }
    @Test fun unreadableMetadataKeepsCustomFolderRowWithUnknownDimensions() {
        val provider = provider()
        val settings = SaveDestinations(context)
        val id = provider.createDocument("root", "video/mp4", "My clip.mp4")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        settings.record(uri, "My clip.mp4", "video/mp4", true)
        provider.failOpen = true
        val item = settings.savedFiles().single()
        assertEquals("My clip", item.title)
        assertNull(item.width)
        assertNull(item.height)
    }
    @Test fun revokedGrantDropsOrphanReceiptWithoutProviderAccess() {
        val provider = provider()
        val settings = SaveDestinations(context)
        settings.choose(tree)
        val publisher = DestinationPublisher(context, space)
        val pending = publisher.insertPending(JobDirectory(context.filesDir).create("orphan"), "partial.mp4", "video/mp4")
        context.contentResolver.releasePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        provider.denied = true
        assertEquals(MissingPublication, publisher.abandon(pending.uri))
        assertNull(settings.receipt(pending.uri))
        assertTrue(settings.pendingJobFiles().isEmpty())
        assertTrue(provider.files.containsKey("1"))
    }

    @Test fun orphanSweepPreservesNewPendingReceiptAtSameUri() = sweepWithReusedUri(false, false)
    @Test fun orphanSweepPreservesNewReceiptWhenOldFingerprintChanged() = sweepWithReusedUri(false, true)
    @Test fun orphanSweepPreservesJustPublishedFileAtSameUri() = sweepWithReusedUri(true, false)
    private fun sweepWithReusedUri(publish: Boolean, changed: Boolean) {
        val provider = provider()
        val settings = SaveDestinations(context)
        settings.choose(tree)
        val publisher = DestinationPublisher(context, space)
        val pending = publisher.insertPending(JobDirectory(context.filesDir).create("orphan"), "old.mp4", "video/mp4")
        val file = provider.files.getValue("1")
        if (changed) file.writeBytes(byteArrayOf(9, 8))
        var freshNonce: String? = null
        provider.afterSnapshot = { id ->
            if (id == "1") {
                provider.afterSnapshot = null
                // Path-based IDs can resolve to a new Retry's document during an old query.
                file.writeBytes(byteArrayOf(4, 5, 6))
                settings.record(pending.uri, "retry.mp4", "video/mp4", false)
                settings.recordPendingState(pending.uri)
                freshNonce = settings.receipt(pending.uri)!!.optString("nonce")
                if (publish) publisher.commit(PendingPublication(pending.uri, "retry.mp4"))
            }
        }
        JobStore(context).use { store ->
            JobRecovery(store, publisher, File(context.filesDir, "downloads")).recover()
        }
        assertNotNull("Sweep must keep the new job's receipt", settings.receipt(pending.uri))
        assertEquals(freshNonce, settings.receipt(pending.uri)!!.optString("nonce"))
        assertEquals(publish, settings.receipt(pending.uri)!!.getBoolean("published"))
        assertArrayEquals(byteArrayOf(4, 5, 6), file.readBytes())
        assertNull(provider.deletedBytes)
    }

    @Test fun hungOrphanQueryTimesOutAndCannotDeleteAfterStartup() {
        val provider = provider()
        val settings = SaveDestinations(context)
        settings.choose(tree)
        val publisher = DestinationPublisher(context, space)
        val pending = publisher.insertPending(JobDirectory(context.filesDir).create("hung-orphan"), "old.mp4", "video/mp4")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val proxy = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?) =
                provider.query(uri, columns, selection, args, sort)
            override fun query(uri: Uri, columns: Array<out String>?, args: Bundle?, signal: CancellationSignal?): android.database.Cursor? {
                signal?.setOnCancelListener { cancelled.countDown() }
                return provider.query(uri, columns, args, signal)
            }
            override fun call(method: String, arg: String?, extras: Bundle?) = provider.call(method, arg, extras)
            override fun getType(uri: Uri) = provider.getType(uri)
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, where: String?, args: Array<out String>?) = 0
            override fun delete(uri: Uri, where: String?, args: Array<out String>?) = 0
        }.apply { attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "test.documents" }) }
        ShadowContentResolver.registerProviderInternal("test.documents", proxy)
        provider.afterSnapshot = { id ->
            if (id == "1") {
                provider.afterSnapshot = null
                entered.countDown()
                try { check(release.await(15, java.util.concurrent.TimeUnit.SECONDS)) }
                finally { returned.countDown() }
            }
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val cleanupFinished = java.util.concurrent.CountDownLatch(1)
        val cleanupExecutor = java.util.concurrent.Executor { task ->
            pool.execute { try { task.run() } finally { cleanupFinished.countDown() } }
        }
        JobStore(context).use { store ->
            val startup = pool.submit { JobRecovery(store, publisher, File(context.filesDir, "downloads")).recover(cleanupExecutor) }
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                startup.get(4, java.util.concurrent.TimeUnit.SECONDS)
                assertTrue("Timeout must cancel the provider query", cancelled.await(1, java.util.concurrent.TimeUnit.SECONDS))
                assertNotNull(settings.receipt(pending.uri))
                // Workers may now reuse the URI. The late old query must not touch it.
                settings.record(pending.uri, "retry.mp4", "video/mp4", false)
                provider.files.getValue("1").writeBytes(byteArrayOf(7, 8, 9))
                settings.recordPendingState(pending.uri)
                publisher.commit(PendingPublication(pending.uri, "retry.mp4"))
                release.countDown()
                assertTrue(returned.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(cleanupFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
                pool.shutdown()
                assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(settings.receipt(pending.uri)!!.getBoolean("published"))
                assertArrayEquals(byteArrayOf(7, 8, 9), provider.files.getValue("1").readBytes())
                assertNull(provider.deletedBytes)
            } finally {
                release.countDown()
                startup.get(5, java.util.concurrent.TimeUnit.SECONDS)
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
    }

    @Test fun closedSweepCannotDeletePendingFile() = closeSweepDuringQuery(false)
    @Test fun closedSweepCannotForgetChangedReceipt() = closeSweepDuringQuery(true)
    private fun closeSweepDuringQuery(changed: Boolean) {
        val provider = provider()
        val settings = SaveDestinations(context)
        settings.choose(tree)
        val publisher = DestinationPublisher(context, space)
        val pending = publisher.insertPending(JobDirectory(context.filesDir).create("closed-sweep"), "clip.mp4", "video/mp4")
        val file = provider.files.getValue("1")
        if (changed) file.writeBytes(byteArrayOf(8, 9))
        var sweepOpen = true
        provider.afterSnapshot = { sweepOpen = false }
        publisher.abandon(pending.uri, CancellationSignal()) { sweepOpen }
        assertNotNull(settings.receipt(pending.uri))
        assertTrue(file.exists())
        assertNull(provider.deletedBytes)
    }

    @Test fun recordingSameUriCreatesFreshPendingReceipt() {
        provider()
        val settings = SaveDestinations(context)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "1")
        settings.record(uri, "old.mp4", "video/mp4", false)
        val old = settings.receipt(uri)!!
        settings.record(uri, "retry.mp4", "video/mp4", false)
        val fresh = settings.receipt(uri)!!
        assertEquals("retry.mp4", fresh.getString("name"))
        assertTrue(fresh.getString("nonce").isNotBlank())
        assertNotEquals(old.optString("nonce"), fresh.getString("nonce"))
    }

    @Test fun commitWithDriftingLastModifiedSucceeds() = commitWithMetadataDrift(false)
    @Test fun commitAfterPendingMetadataReadFailureSucceeds() = commitWithMetadataDrift(true)
    private fun commitWithMetadataDrift(readFailure: Boolean) {
        val provider = provider()
        val settings = SaveDestinations(context)
        settings.choose(tree)
        val job = JobDirectory(context.filesDir).create("drift")
        val source = job.file("clip", "mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val publisher = DestinationPublisher(context, space)
        val pending = publisher.insertPending(job, "clip.mp4", "video/mp4")
        if (readFailure) provider.onOpen = { provider.denied = true }
        publisher.copy(pending, source)
        provider.denied = false
        val file = provider.files.getValue("1")
        assertTrue(file.setLastModified(file.lastModified() + 60_000))
        assertTrue(publisher.commit(pending) is PublishedFile)
        assertTrue(settings.receipt(pending.uri)!!.getBoolean("published"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), file.readBytes())
    }

    @Test fun replacementWithDifferentSizeIsNeverDeleted() = replacedPendingDocument(false)
    @Test fun replacementWithSameSizeAndDifferentModifiedTimeIsNeverDeleted() = replacedPendingDocument(true)
    private fun replacedPendingDocument(sameSize: Boolean) {
        val provider = provider()
        val settings = SaveDestinations(context)
        settings.choose(tree)
        val job = JobDirectory(context.filesDir).create("replaced")
        val source = job.file("clip", "mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val publisher = DestinationPublisher(context, space)
        val pending = publisher.insertPending(job, "clip.mp4", "video/mp4")
        publisher.copy(pending, source)
        val file = provider.files.getValue("1")
        val modified = file.lastModified()
        val replacement = if (sameSize) byteArrayOf(9, 8, 7, 6) else byteArrayOf(9, 8)
        // A path-based provider can resolve the same document ID to a new user file.
        assertTrue(file.delete())
        file.writeBytes(replacement)
        assertTrue(file.setLastModified(modified + 60_000))
        assertEquals(MissingPublication, publisher.abandon(pending.uri))
        assertArrayEquals(replacement, file.readBytes())
        assertNull(settings.receipt(pending.uri))
        assertNull(provider.deletedBytes)
    }

    @Test fun largerLibraryDoesNotReintroduceInaccessibleRowsAfterCacheEviction() {
        val provider = provider()
        val settings = SaveDestinations(context)
        repeat(160) { index ->
            val id = provider.createDocument("root", "audio/mp4", "clip-$index.m4a")
            settings.record(DocumentsContract.buildDocumentUriUsingTree(tree, id), "clip-$index.m4a", "audio/mp4", true)
        }
        provider.deleteDocument(DocumentsContract.getDocumentId(settings.publishedUris().first()))
        val validated = settings.savedFiles().map { it.uri }.toSet()
        assertEquals(159, validated.size)
        assertEquals(validated, settings.recordedFiles().map { it.uri }.toSet())
    }

    @Test fun deleteUsesDocumentProviderAndLeavesOtherFiles() {
        val provider = provider()
        provider.createDocument("root", "video/mp4", "clip.mp4")
        provider.createDocument("root", "video/mp4", "other.mp4")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "1")
        assertTrue(SavedMediaRepository(context.contentResolver, context.packageName).delete(uri))
        assertFalse(provider.files.containsKey("1"))
        assertTrue(provider.files.containsKey("2"))
    }
    private val space = object : SpaceGate {
        override fun beforeTransfer(stagingBytes: Long?, outputBytes: Long?, unknownSizeUpperBound: Long?) = Unit
        override fun beforePublication(outputBytes: Long) = Unit
    }
    @Test fun choosingPersistsAcrossInstancesAndReleasesOnlyThePreviousGrant() {
        provider()
        val prefs = SaveDestinations(context)
        prefs.choose(tree)
        assertEquals(tree, (SaveDestinations(context).destination as SaveDestination.Tree).uri)
        assertEquals("Chosen folder", prefs.displayName)
        assertTrue(prefs.available())
        prefs.choose(tree) // Re-picking the same tree must not revoke its grant.
        assertEquals(1, context.contentResolver.persistedUriPermissions.size)
        val other = Uri.parse("content://test.documents/tree/other")
        // A second root exists in this provider fixture.
        prefs.choose(other)
        assertEquals(listOf(other), context.contentResolver.persistedUriPermissions.map { it.uri })
        prefs.choose(null)
        assertTrue(prefs.destination is SaveDestination.Default)
        assertTrue(context.contentResolver.persistedUriPermissions.isEmpty())
    }
    @Test fun journalUsesPublicationTimeDestinationAndSurvivesPreferenceChange() {
        val provider = provider()
        JobStore(context).use { store ->
            val (job, source) = staged(store, "video") // Default at enqueue.
            SaveDestinations(context).choose(tree)
            val publisher = DestinationPublisher(context, space)
            val saved = JobRecovery(store, publisher, File(context.filesDir, "downloads")).publish(job, source, "video/mp4") as PublishedFile
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), provider.files[DocumentsContract.getDocumentId(saved.uri)]!!.readBytes())
            assertEquals(JobStage.COMPLETED, store.get("video")!!.stage)
            assertEquals(tree, SaveDestinations.treeOf(store.get("video")!!.pendingUri!!))
            assertEquals(saved.uri, SaveDestinations(context).savedFiles().single().uri)
            SaveDestinations(context).choose(null)
            assertEquals(saved.uri, store.get("video")!!.pendingUri)
        }
    }
    @Test fun interruptedCopyDeletesOnlyItsDocumentAndBecomesRetryable() {
        val provider = provider()
        SaveDestinations(context).choose(tree)
        JobStore(context).use { store ->
            val (job, source) = staged(store, "interrupted")
            val publisher = DestinationPublisher(context, space)
            val pending = publisher.insertPending(job, "clip.mp4", "video/mp4")
            assertTrue(store.recordPending("interrupted", pending))
            publisher.copy(pending, source) // Process stops before the copy-complete journal boundary.
            provider.createDocument("root", "video/mp4", "unrelated.mp4")
            JobRecovery(store, DestinationPublisher(context, space), File(context.filesDir, "downloads")).recover()
            assertEquals(JobStage.FAILED, store.get("interrupted")!!.stage)
            assertTrue(store.get("interrupted")!!.retryable)
            assertFalse(provider.files.containsKey("1"))
            assertTrue(provider.files.containsKey("2"))
        }
    }
    @Test fun completedCopyBoundaryRecoversWithDriftingLastModifiedWithoutCreatingAnotherDocument() {
        val provider = provider()
        SaveDestinations(context).choose(tree)
        JobStore(context).use { store ->
            val (job, source) = staged(store, "copied")
            val publisher = DestinationPublisher(context, space)
            val pending = publisher.insertPending(job, "clip.mp4", "video/mp4")
            store.recordPending("copied", pending)
            publisher.copy(pending, source)
            store.markPublishing("copied")
            val file = provider.files.getValue("1")
            assertTrue(file.setLastModified(file.lastModified() + 60_000))
            JobRecovery(store, DestinationPublisher(context, space), File(context.filesDir, "downloads")).recover()
            assertEquals(JobStage.COMPLETED, store.get("copied")!!.stage)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), provider.files.values.single().readBytes())
        }
    }
    @Test fun openFailureDeletesPartialAndJournalsTruthfulFolderError() {
        val provider = provider()
        SaveDestinations(context).choose(tree)
        provider.failOpen = true
        JobStore(context).use { store ->
            val (job, source) = staged(store, "broken")
            try { JobRecovery(store, DestinationPublisher(context, space), File(context.filesDir, "downloads"))
                .publish(job, source, "video/mp4"); fail() } catch (failure: StorageException) {
                assertEquals(StorageError.FolderUnavailable, failure.error)
            }
            assertTrue(provider.files.isEmpty())
            assertEquals(JobStage.FAILED, store.get("broken")!!.stage)
            assertEquals(DownloadError.FolderUnavailable, store.get("broken")!!.error)
            assertTrue(store.get("broken")!!.retryable)
        }
    }
    @Test fun lostGrantFailsWithoutFallbackAndProviderLossIsUnavailable() {
        val provider = provider()
        val settings = SaveDestinations(context)
        settings.choose(tree)
        provider.denied = true
        assertFalse(settings.available())
        provider.denied = false
        context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertFalse(settings.available())
        JobStore(context).use { store ->
            val (job, source) = staged(store, "revoked")
            try { JobRecovery(store, DestinationPublisher(context, space), File(context.filesDir, "downloads"))
                .publish(job, source, "video/mp4"); fail() } catch (failure: StorageException) {
                assertEquals(StorageError.FolderUnavailable, failure.error)
            }
            assertEquals(JobStage.FAILED, store.get("revoked")!!.stage)
            assertEquals(DownloadError.FolderUnavailable, store.get("revoked")!!.error)
            assertTrue(store.get("revoked")!!.retryable)
            assertTrue(provider.files.isEmpty())
        }
    }
    @Test fun revokedMidCopyRecoveryFailsAndRetainsCleanupOwnership() {
        provider()
        val settings = SaveDestinations(context)
        settings.choose(tree)
        JobStore(context).use { store ->
            val (job, _) = staged(store, "midcopy")
            val publisher = DestinationPublisher(context, space)
            val pending = publisher.insertPending(job, "clip.mp4", "video/mp4")
            store.recordPending("midcopy", pending)
            context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            JobRecovery(store, publisher, File(context.filesDir, "downloads")).recover()
            assertEquals(JobStage.FAILED, store.get("midcopy")!!.stage)
            assertEquals(DownloadError.FolderUnavailable, store.get("midcopy")!!.error)
            assertTrue(store.get("midcopy")!!.retryable)
            assertEquals(pending.uri, store.get("midcopy")!!.pendingUri)
            assertNotNull(settings.receipt(pending.uri))
            settings.choose(tree)
            JobRecovery(store, publisher, File(context.filesDir, "downloads")).reconcile("midcopy")
            assertNull(settings.receipt(pending.uri))
            assertNull(store.get("midcopy")!!.pendingUri)
        }
    }
    @Test fun retryWhileDetachedRetainsIndependentPartialOwnership() = discardedPartial("retry")
    @Test fun removeWhileDetachedRetainsIndependentPartialOwnership() = discardedPartial("remove")
    @Test fun removeFailedWhileDetachedRetainsIndependentPartialOwnership() = discardedPartial("removeFailed")
    @Test fun pruneWhileDetachedRetainsIndependentPartialOwnership() = discardedPartial("prune")

    private fun discardedPartial(action: String) {
        val provider = provider()
        val settings = SaveDestinations(context)
        settings.choose(tree)
        var partial: Uri? = null
        var published: Uri? = null
        var live: Uri? = null
        JobStore(context).use { store ->
            val publisher = DestinationPublisher(context, space)
            val recovery = JobRecovery(store, publisher, File(context.filesDir, "downloads"))
            val (savedJob, savedSource) = staged(store, "published")
            published = (recovery.publish(savedJob, savedSource, "video/mp4") as PublishedFile).uri
            val (job, partialSource) = staged(store, "discarded")
            val pending = publisher.insertPending(job, "partial.mp4", "video/mp4")
            partial = pending.uri
            store.recordPending("discarded", pending)
            publisher.copy(pending, partialSource)
            provider.createDocument("root", "video/mp4", "unrelated.mp4")
            provider.denied = true // Detached/unavailable provider; the persisted grant still exists.
            recovery.recover()
            assertEquals(DownloadError.FolderUnavailable, store.get("discarded")!!.error)
            when (action) {
                "retry" -> assertTrue(store.retry("discarded"))
                "remove" -> assertTrue(store.remove("discarded"))
                "removeFailed" -> assertTrue(store.removeFailed("discarded"))
                "prune" -> {
                    store.writableDatabase.execSQL("UPDATE jobs SET updated_at = 0 WHERE job_id = 'discarded'")
                    repeat(101) { index ->
                        staged(store, "terminal-$index")
                        store.fail("terminal-$index", DownloadError.SaveFailed)
                    }
                    store.pruneTerminal()
                    assertNull(store.get("discarded"))
                }
            }
            assertNotNull(settings.receipt(partial!!))
            // A second recovery while access is still lost must retain the independent receipt.
            recovery.recover()
            assertNotNull(settings.receipt(partial!!))
            provider.denied = false
            // The next attempt can own another document. An orphan sweep must exclude live journal URIs.
            val (nextJob, _) = staged(store, "next-attempt")
            val next = publisher.insertPending(nextJob, "next.mp4", "video/mp4")
            live = next.uri
            store.recordPending("next-attempt", next)
            publisher.copy(next, nextJob.file("clip", "mp4"))
            store.markPublishing("next-attempt")
        }
        // Reopen both journals: cleanup ownership must survive process/repository recreation.
        JobStore(context).use { store ->
            JobRecovery(store, DestinationPublisher(context, space), File(context.filesDir, "downloads")).recover()
            assertFalse(provider.files.containsKey(DocumentsContract.getDocumentId(partial!!)))
            assertNull(SaveDestinations(context).receipt(partial!!))
            assertTrue(provider.files.containsKey(DocumentsContract.getDocumentId(published!!)))
            assertTrue(SaveDestinations(context).receipt(published!!)!!.getBoolean("published"))
            assertTrue(provider.files.containsKey("3")) // Unrelated document.
            assertTrue(provider.files.containsKey(DocumentsContract.getDocumentId(live!!)))
            assertEquals(JobStage.COMPLETED, store.get("next-attempt")!!.stage)
        }
    }

    @Test fun orphanSweepDoesNotRevisitAJournalOwnedUnresolvedPublication() {
        val provider = provider()
        SaveDestinations(context).choose(tree)
        JobStore(context).use { store ->
            val (job, _) = staged(store, "owned")
            val publisher = DestinationPublisher(context, space)
            val pending = publisher.insertPending(job, "owned.mp4", "video/mp4")
            store.recordPending("owned", pending)
            store.markPublishing("owned")
            var abandonments = 0
            val uncertain = object : Publisher by publisher {
                override fun commit(pending: PendingPublication): PublicationResult = CommitUncertain(pending)
                override fun abandon(uri: Uri): RecoveryResult {
                    abandonments++
                    return if (abandonments == 1) pending else publisher.abandon(uri)
                }
            }
            JobRecovery(store, uncertain, File(context.filesDir, "downloads")).recover()
            assertEquals(1, abandonments)
            assertEquals(JobStage.PUBLISHING, store.get("owned")!!.stage)
            assertTrue(provider.files.containsKey(DocumentsContract.getDocumentId(pending.uri)))
        }
    }

    @Test fun oneShotPublicationAndActionsUseActualDocumentWithReadOnlyGrants() {
        val provider = provider()
        val settings = SaveDestinations(context)
        settings.choose(tree)
        val job = JobDirectory(context.filesDir).create("image")
        val source = job.file("photo", "jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val publisher = DestinationPublisher(context, space)
        val saved = publisher.publish(job, source, "photo", "jpg", "image/jpeg") as PublishedFile
        val item = settings.savedFiles().single()
        assertEquals(saved.uri, item.uri)
        assertEquals("image/jpeg", item.mimeType)
        assertArrayEquals(byteArrayOf(4, 5, 6), provider.files.values.single().readBytes())
        assertTrue(settings.pendingImages().isEmpty())
        val open = MediaActions.open(item)
        assertEquals(Intent.ACTION_VIEW, open.action)
        assertEquals(saved.uri, open.data)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, open.flags)
        @Suppress("DEPRECATION") val send = MediaActions.share(item).getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(saved.uri, send.clipData!!.getItemAt(0).uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
        assertTrue(SavedMediaRepository(context.contentResolver, context.packageName, settings).delete(saved.uri))
        assertTrue(provider.files.isEmpty())
        assertTrue(settings.savedFiles().isEmpty())
    }
    @Test fun failedReplacementKeepsPreviousSelectionAndReleasesNewGrant() {
        val provider = provider()
        val settings = SaveDestinations(context)
        settings.choose(tree)
        provider.denied = true
        try { settings.choose(Uri.parse("content://test.documents/tree/other")); fail() } catch (_: Exception) { }
        assertEquals(tree, (settings.destination as SaveDestination.Tree).uri)
        assertEquals(listOf(tree), context.contentResolver.persistedUriPermissions.map { it.uri })
    }
    @Test fun sizeMismatchAfterSomeBytesDeletesThePartialDocument() {
        val provider = provider()
        SaveDestinations(context).choose(tree)
        JobStore(context).use { store ->
            val (job, source) = staged(store, "shortened")
            provider.onOpen = { source.writeBytes(byteArrayOf(1, 2)) }
            try { JobRecovery(store, DestinationPublisher(context, space), File(context.filesDir, "downloads"))
                .publish(job, source, "video/mp4"); fail() } catch (failure: StorageException) {
                assertEquals(StorageError.CopySizeMismatch, failure.error)
            }
            assertArrayEquals(byteArrayOf(1, 2), provider.deletedBytes)
            assertTrue(provider.files.isEmpty())
            assertEquals(JobStage.FAILED, store.get("shortened")!!.stage)
            assertTrue(store.get("shortened")!!.retryable)
        }
    }
    @Test fun changingFolderKeepsEarlierSaveAccessibleUntilDeleted() = keepsEarlierSave(false)
    @Test fun resettingFolderKeepsEarlierSaveAccessibleUntilDeleted() = keepsEarlierSave(true)
    private fun keepsEarlierSave(reset: Boolean) {
        provider()
        val settings = SaveDestinations(context)
        settings.choose(tree)
        val publisher = DestinationPublisher(context, space)
        val job = JobDirectory(context.filesDir).create("earlier")
        val source = job.file("clip", "mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val saved = publisher.publish(job, source, "clip", "mp4", "video/mp4") as PublishedFile
        val other = Uri.parse("content://test.documents/tree/other")
        settings.choose(if (reset) null else other)
        assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == tree })
        val repository = SavedMediaRepository(context.contentResolver, context.packageName, settings)
        val item = repository.query().single()
        assertEquals(saved.uri, item.uri)
        assertTrue(publisher.recover(saved.uri) is PublishedFile)
        context.contentResolver.openInputStream(MediaActions.open(item).data!!).use {
            assertArrayEquals(byteArrayOf(1, 2, 3), it!!.readBytes())
        }
        assertTrue(repository.delete(saved.uri))
        assertTrue(settings.savedFiles().isEmpty())
        assertEquals(if (reset) emptyList<Uri>() else listOf(other),
            context.contentResolver.persistedUriPermissions.map { it.uri })
    }
    @Test fun oldGrantLivesUntilLastReceiptAndCurrentGrantSurvivesForget() {
        provider()
        val settings = SaveDestinations(context)
        settings.choose(tree)
        val publisher = DestinationPublisher(context, space)
        val job = JobDirectory(context.filesDir).create("receipts")
        val first = publisher.insertPending(job, "one.mp4", "video/mp4")
        val second = publisher.insertPending(job, "two.mp4", "video/mp4")
        settings.choose(null)
        publisher.abandon(first.uri)
        assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == tree })
        publisher.abandon(second.uri)
        assertTrue(context.contentResolver.persistedUriPermissions.isEmpty())
        settings.choose(tree)
        publisher.abandon(publisher.insertPending(job, "current.mp4", "video/mp4").uri)
        assertTrue(settings.available())
    }
    @Test fun missingDocumentDuringCancelKeepsCancelledAndClearsFailedCleanup() {
        val provider = provider()
        val settings = SaveDestinations(context)
        settings.choose(tree)
        JobStore(context).use { store ->
            val (job, source) = staged(store, "cancelled")
            val signal = CancellationSignal()
            provider.onOpen = { signal.cancel(); provider.deleteDocument("1") }
            val publisher = DestinationPublisher(context, space)
            val recovery = JobRecovery(store, publisher, File(context.filesDir, "downloads"))
            try { recovery.publish(job, source, "video/mp4", signal); fail() } catch (_: StorageException) { }
            assertEquals(JobStage.CANCELLED, store.get("cancelled")!!.stage)
            assertEquals(DownloadError.Cancelled, store.get("cancelled")!!.error)
            recovery.recover()
            assertEquals(JobStage.CANCELLED, store.get("cancelled")!!.stage)

            val (failedJob, _) = staged(store, "failed-cleanup")
            val pending = publisher.insertPending(failedJob, "clip.mp4", "video/mp4")
            store.recordPending("failed-cleanup", pending)
            store.folderUnavailable("failed-cleanup")
            provider.deleteDocument(DocumentsContract.getDocumentId(pending.uri))
            recovery.reconcile("failed-cleanup")
            assertNull(store.get("failed-cleanup")!!.pendingUri)
            assertNull(settings.receipt(pending.uri))
        }
    }
    @Test fun cancelledCopyLeavesPendingDocumentForCallerCleanup() {
        val provider = provider()
        SaveDestinations(context).choose(tree)
        val publisher = DestinationPublisher(context, space)
        val job = JobDirectory(context.filesDir).create("caller-cleanup")
        val source = job.file("clip", "mp4").apply { writeBytes(byteArrayOf(1)) }
        val pending = publisher.insertPending(job, "clip.mp4", "video/mp4")
        try { publisher.copy(pending, source, CancellationSignal().apply { cancel() }); fail() }
        catch (failure: StorageException) { assertEquals(StorageError.Cancelled, failure.error) }
        assertTrue(publisher.recover(pending.uri) is PendingPublication)
        assertTrue(provider.files.containsKey("1"))
        assertEquals(MissingPublication, publisher.abandon(pending.uri))
        assertTrue(provider.files.isEmpty())
    }
    private fun staged(store: JobStore, id: String): Pair<JobDirectory.Job, File> {
        val source = LinkRevision(LinkInput.parse("https://example.com/video")!!, 1)
        val format = Format("mp4", "mp4", true, true, size = Size.Exact(4))
        val command = (DownloadCommand.confirm(id, Inspection(source, "engine", listOf(format)), source, format.id, "clip") as ConfirmResult.Confirmed).command
        check(store.enqueue(command)); check(store.startTransfer(id))
        val job = JobDirectory(context.filesDir).create(id)
        val file = job.file("clip", "mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        check(store.markStaged(id))
        return job to file
    }
    private fun provider() = Provider().also {
        it.attachInfo(context, android.content.pm.ProviderInfo().apply {
            authority = "test.documents"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        })
        ShadowContentResolver.registerProviderInternal("test.documents", it)
    }
    class Provider : DocumentsProvider() {
        val files = linkedMapOf<String, File>()
        private var nextId = 0
        val names = mutableMapOf<String, String>()
        var denied = false
        var failOpen = false
        var onOpen: (() -> Unit)? = null
        var onCreateDocument: ((String) -> Unit)? = null
        var deletedBytes: ByteArray? = null
        @Volatile var afterSnapshot: ((String) -> Unit)? = null
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?) = MatrixCursor(arrayOf("root_id"))
        override fun queryChildDocuments(parentDocumentId: String?, projection: Array<out String>?, sortOrder: String?) = MatrixCursor(arrayOf("document_id"))
        override fun queryDocument(documentId: String, projection: Array<out String>?): android.database.Cursor {
            if (denied) throw SecurityException()
            if (documentId !in listOf("root", "other") && !files.containsKey(documentId)) throw java.io.FileNotFoundException()
            val columns = projection ?: arrayOf("document_id", "_display_name", "mime_type", "_size", "last_modified", "flags")
            return MatrixCursor(columns).apply {
                if (documentId in listOf("root", "other") || files.containsKey(documentId)) addRow(columns.map<String, Any> { when(it) {
                    "document_id" -> documentId
                    "_display_name" -> names[documentId] ?: "Chosen folder"
                    "mime_type" -> if (documentId in listOf("root", "other")) DocumentsContract.Document.MIME_TYPE_DIR else "video/mp4"
                    "_size" -> files[documentId]?.length() ?: 0L
                    "last_modified" -> files[documentId]?.lastModified() ?: 0L
                    "flags" -> DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                    else -> 0L
                } }.toTypedArray())
            }.also { afterSnapshot?.invoke(documentId) }
        }
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            if (denied) throw SecurityException()
            val id = (++nextId).toString()
            files[id] = File(context!!.cacheDir, "document-$id").apply { writeBytes(byteArrayOf()) }
            names[id] = displayName
            onCreateDocument?.invoke(id)
            return id
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            if (denied) throw SecurityException()
            if (failOpen) throw java.io.FileNotFoundException()
            onOpen?.invoke()
            return ParcelFileDescriptor.open(files[documentId] ?: throw java.io.FileNotFoundException(), ParcelFileDescriptor.parseMode(mode))
        }
        override fun deleteDocument(documentId: String) { if (denied) throw SecurityException(); files.remove(documentId)?.let { deletedBytes = it.readBytes(); it.delete() } }
        override fun isChildDocument(parentDocumentId: String, documentId: String) = true
    }
    companion object { val tree: Uri get() = Uri.parse("content://test.documents/tree/root") }
}
