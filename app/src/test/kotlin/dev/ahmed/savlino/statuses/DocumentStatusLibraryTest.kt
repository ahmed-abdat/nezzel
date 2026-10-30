package dev.ahmed.savlino.statuses

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DocumentStatusLibraryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val auth = StatusFolders.AUTHORITY
    private val wa = "Android/media/com.whatsapp/WhatsApp"
    private val biz = "Android/media/com.whatsapp.w4b/WhatsApp Business"
    private lateinit var root: File
    private lateinit var provider: Provider
    private lateinit var media: SavedMediaProvider
    private val savedCollection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private fun savedUri(id: Long = 1) = ContentUris.withAppendedId(savedCollection, id)
    private val marksPrefs get() = app.getSharedPreferences("nezzel-status-marks", Context.MODE_PRIVATE)
    private val saveCalls = mutableListOf<StatusSaveSource>()
    private var saveAnswer: (StatusSaveSource) -> StatusSaveResult = { StatusSaveResult.Saved(savedUri()) }

    @Before fun setUp() {
        root = File(app.cacheDir, "volume").apply { deleteRecursively(); mkdirs() }
        provider = Provider()
        provider.attachInfo(app, ProviderInfo().apply { authority = auth; exported = true })
        ShadowContentResolver.registerProviderInternal(auth, provider)
        media = SavedMediaProvider()
        media.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
        ShadowContentResolver.registerProviderInternal("media", media)
        install("com.whatsapp", "WhatsApp"); install("com.whatsapp.w4b", "WhatsApp Business")
    }

    @After fun tearDown() {
        app.contentResolver.persistedUriPermissions.toList().forEach {
            app.contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun library() = DocumentStatusLibrary(app, { source, _ -> saveCalls += source; saveAnswer(source) })
    private fun install(name: String, label: String) = shadowOf(app.packageManager).installPackage(PackageInfo().apply {
        packageName = name
        applicationInfo = ApplicationInfo().apply { packageName = name; nonLocalizedLabel = label; enabled = true }
    })
    private fun tree(path: String) = DocumentsContract.buildTreeDocumentUri(auth, "primary:$path")
    private fun file(path: String, modified: Long, bytes: Int = 10) = File(root, path).apply {
        parentFile!!.mkdirs(); writeBytes(ByteArray(bytes) { 1 }); setLastModified(modified)
    }
    private fun dir(path: String) = File(root, path).apply { mkdirs() }
    private fun ready(access: StatusAccess) = access as StatusAccess.Ready
    private fun names(access: StatusAccess) = ready(access).items.map { DocumentsContract.getDocumentId(it.uri).substringAfterLast('/') }

    /** Two WhatsApp accounts plus Business, mixed media, a .nomedia, an audio file and a decoy media folder. */
    private fun seedPhone() {
        file("$wa/accounts/1001/Media/.Statuses/a.jpg", 5_000)
        file("$wa/accounts/1001/Media/.Statuses/b.mp4", 9_000)
        file("$wa/accounts/1001/Media/.Statuses/.nomedia", 9_500)
        file("$wa/accounts/1001/Media/.Statuses/voice.opus", 9_600)
        file("$wa/accounts/1002/Media/.Statuses/c.jpg", 7_000)
        file("$biz/Media/.Statuses/d.webp", 8_000)
        file("$wa/Media/WhatsApp Images/private.jpg", 9_900)
        file("Android/media/org.telegram.messenger/x.jpg", 9_900)
    }

    @Test fun grantIntentOpensAtMediaRootAndAsksReadOnly() {
        val intent = library().grantIntent()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        assertEquals(DocumentsContract.buildDocumentUri(auth, "primary:Android/media"),
            intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test fun mediaRootGrantFindsBothAppsAndTwoAccountsWithoutWalkingTheTree() = runBlocking {
        seedPhone()
        val library = library()
        assertEquals(StatusGrantResult.Granted, library.onPicked(tree("Android/media")))
        val held = app.contentResolver.persistedUriPermissions.single()
        assertEquals(tree("Android/media"), held.uri)
        assertTrue(held.isReadPermission); assertFalse(held.isWritePermission)

        provider.queried.clear()
        val access = ready(library.load())
        assertEquals(listOf("com.whatsapp", "com.whatsapp.w4b"), access.apps.map { it.packageName })
        assertEquals(listOf("WhatsApp", "WhatsApp Business"), access.apps.map { it.label })
        assertEquals(listOf(StatusSource("com.whatsapp", 1), StatusSource("com.whatsapp", 2), StatusSource("com.whatsapp.w4b", 1)), access.sources)
        assertEquals(listOf("b.mp4", "d.webp", "c.jpg", "a.jpg"), names(access))
        val video = access.items.first()
        assertTrue(video.isVideo); assertEquals("video/mp4", video.mimeType); assertEquals(10L, video.sizeBytes)
        assertEquals(9_000L, video.lastModifiedMillis); assertEquals(StatusSource("com.whatsapp", 1), video.source)
        assertEquals(tree("Android/media").pathSegments[1], video.uri.pathSegments[1]) // readable through the grant
        assertEquals(StatusSource("com.whatsapp", 2), access.items[2].source)
        // Only the known segments were queried: no decoy folders, no other apps.
        assertTrue(provider.queried.none { "WhatsApp Images" in it || "telegram" in it })
        assertTrue(provider.queried.size <= 16)
    }

    @Test fun narrowerGrantsWorkAndCoexistWithoutDuplicates() = runBlocking {
        seedPhone()
        val library = library()
        assertEquals(StatusGrantResult.Granted, library.onPicked(tree("$wa/accounts/1002/Media/.Statuses")))
        assertEquals(listOf("c.jpg"), names(library.load()))
        assertEquals(StatusGrantResult.Granted, library.onPicked(tree("Android/media/com.whatsapp.w4b")))
        assertEquals(StatusGrantResult.Granted, library.onPicked(tree("$wa/accounts")))
        assertEquals(listOf("b.mp4", "d.webp", "c.jpg", "a.jpg"), names(library.load()))
        assertEquals(StatusGrantResult.Granted, library.onPicked(tree("Android/media")))
        val all = ready(library.load())
        assertEquals(4, all.items.size)
        assertEquals(4, app.contentResolver.persistedUriPermissions.size)
        assertTrue(app.contentResolver.persistedUriPermissions.none { it.isWritePermission })
    }

    @Test fun appFolderWithoutStatusesYetIsGrantedAndEmpty() = runBlocking {
        dir("$wa/Media/WhatsApp Images")
        val library = library()
        assertEquals(StatusGrantResult.Granted, library.onPicked(tree("Android/media")))
        val access = ready(library.load())
        assertTrue(access.items.isEmpty()); assertTrue(access.sources.isEmpty())
    }

    @Config(sdk = [29])
    @Test fun legacyLayoutOnApi29FromTheVolumeRoot() = runBlocking {
        file("WhatsApp/Media/.Statuses/old.jpg", 3_000)
        file("WhatsApp Business/Media/.Statuses/older.mp4", 2_000)
        val library = library()
        assertEquals(DocumentsContract.buildDocumentUri(auth, "primary:"),
            @Suppress("DEPRECATION") library.grantIntent().getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI))
        assertEquals(StatusGrantResult.Granted, library.onPicked(tree("")))
        assertEquals(listOf("old.jpg", "older.mp4"), names(library.load()))
    }

    @Test fun wrongFolderKeepsNoGrant() = runBlocking {
        seedPhone()
        dir("Download")
        val library = library()
        assertEquals(StatusGrantResult.WrongFolder, library.onPicked(tree("Download")))
        assertEquals(StatusGrantResult.WrongFolder, library.onPicked(tree("Android/media/org.telegram.messenger")))
        assertEquals(StatusGrantResult.WrongFolder, library.onPicked(tree("$wa/Media/WhatsApp Images")))
        assertEquals(StatusGrantResult.WrongFolder, library.onPicked(DocumentsContract.buildTreeDocumentUri("other.documents", "primary:Android/media")))
        assertEquals(StatusGrantResult.Cancelled, library.onPicked(null))
        root.deleteRecursively(); dir("Android/media/org.telegram.messenger")
        // Right shape, but nothing of WhatsApp below it: the grant taken for the check is released again.
        assertEquals(StatusGrantResult.WrongFolder, library.onPicked(tree("Android/media")))
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
        assertTrue(library.load() is StatusAccess.NeedsGrant)
    }

    @Test fun revokedGrantIsAccessLostUntilPickedAgain() = runBlocking {
        seedPhone()
        val library = library()
        assertEquals(StatusGrantResult.Granted, library.onPicked(tree("Android/media")))
        app.contentResolver.releasePersistableUriPermission(tree("Android/media"), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(library.load() is StatusAccess.AccessLost)
        assertTrue(library().load() is StatusAccess.AccessLost)
        assertEquals(StatusGrantResult.Granted, library.onPicked(tree("Android/media")))
        assertEquals(4, ready(library.load()).items.size)
    }

    @Test fun invalidStoredGrantIsReleasedNotLost() = runBlocking {
        seedPhone(); dir("Download")
        StatusGrants(app).add(tree("Download"))
        val library = library()
        assertTrue(library.load() is StatusAccess.NeedsGrant)
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
        assertTrue(StatusGrants(app).active().isEmpty())
        assertFalse(StatusGrants(app).isLost)
        // Next to a working grant, the invalid one is dropped and the working one still lists.
        StatusGrants(app).add(tree("Download"))
        assertEquals(StatusGrantResult.Granted, library.onPicked(tree("Android/media")))
        assertEquals(4, ready(library.load()).items.size)
        assertEquals(listOf(tree("Android/media")), app.contentResolver.persistedUriPermissions.map { it.uri })
    }

    @Test fun failingRootQueryKeepsTheGrantAndTheNextLoadWorks() = runBlocking {
        seedPhone()
        val library = library()
        assertEquals(StatusGrantResult.Granted, library.onPicked(tree("Android/media/com.whatsapp")))
        provider.failOnce += "Android/media/com.whatsapp"
        assertTrue(library.load() is StatusAccess.AccessLost)
        assertEquals(listOf(tree("Android/media/com.whatsapp")), app.contentResolver.persistedUriPermissions.map { it.uri })
        assertEquals(3, ready(library.load()).items.size)
    }

    @Test fun wrongPickAfterAccessLostStaysLost() = runBlocking {
        seedPhone(); dir("Download")
        val library = library()
        library.onPicked(tree("Android/media"))
        app.contentResolver.releasePersistableUriPermission(tree("Android/media"), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(library.load() is StatusAccess.AccessLost)
        assertEquals(StatusGrantResult.WrongFolder, library.onPicked(tree("Download")))
        assertEquals(StatusGrantResult.WrongFolder, library.onPicked(tree("$wa/Media/WhatsApp Images")))
        // Right shape, so the grant is taken for the check, but nothing of the app is there.
        File(root, "Android/media/com.whatsapp.w4b").deleteRecursively(); dir("Android/media/com.whatsapp.w4b")
        assertEquals(StatusGrantResult.WrongFolder, library.onPicked(tree("Android/media/com.whatsapp.w4b")))
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
        assertTrue(library.load() is StatusAccess.AccessLost)
        assertEquals(StatusGrantResult.Granted, library.onPicked(tree("Android/media")))
        library.forget()
        assertTrue(library.load() is StatusAccess.NeedsGrant)
    }

    @Test fun partialLoadMergesSeenInsteadOfForgettingIt() = runBlocking {
        seedPhone()
        val library = library()
        library.onPicked(tree("Android/media"))
        library.load()
        // Business folder fails this time: its status must not count as new on the next clean load.
        provider.failOnce += "$biz/Media/.Statuses"
        assertEquals(3, ready(library.load()).items.size)
        val clean = ready(library.load()).items
        assertEquals(4, clean.size)
        assertTrue(clean.none { it.isNew })
    }

    @Test fun installStatesAndUninstalledAppIsHidden() = runBlocking {
        seedPhone()
        val library = library()
        assertEquals(listOf("com.whatsapp", "com.whatsapp.w4b"), (library.load() as StatusAccess.NeedsGrant).apps.map { it.packageName })
        library.onPicked(tree("Android/media"))
        shadowOf(app.packageManager).removePackage("com.whatsapp.w4b")
        val access = ready(library.load())
        assertEquals(listOf("com.whatsapp"), access.apps.map { it.packageName })
        assertTrue(access.items.none { it.source.packageName == "com.whatsapp.w4b" })
        shadowOf(app.packageManager).removePackage("com.whatsapp")
        assertEquals(StatusAccess.NotInstalled, library.load())
    }

    @Test fun savedMarksSurviveReloadAndUnknownFieldsAreNeverMarked() = runBlocking {
        seedPhone()
        val library = library()
        library.onPicked(tree("Android/media"))
        val items = ready(library.load()).items
        saveAnswer = { if (it.uri == items[1].uri) StatusSaveResult.Gone else StatusSaveResult.Saved(savedUri()) }
        val unknown = items[2].copy(sizeBytes = null)
        val results = library.save(listOf(items[0], items[1], unknown))
        assertEquals(listOf(StatusSaveResult.Saved(savedUri()), StatusSaveResult.Gone,
            StatusSaveResult.Saved(savedUri())), results)
        assertEquals(StatusSaveSource(items[0].uri, "video/mp4", 10L, 9_000L), saveCalls.first())

        val reloaded = ready(library().load()).items
        assertEquals(listOf(true, false, false, false), reloaded.map { it.saved })
        // Same name, new content: a different status, not saved.
        File(root, "$wa/accounts/1001/Media/.Statuses/b.mp4").setLastModified(9_100)
        assertFalse(ready(library().load()).items.first().saved)
    }

    @Test fun cancellingASaveStopsTheBlockedCopyThroughItsSignalAndMarksNothing() = runBlocking {
        seedPhone()
        val started = CompletableDeferred<Unit>()
        var signalled = false
        // Like a provider query stuck on I/O: only the CancellationSignal gets it out.
        val library = DocumentStatusLibrary(app, { _, signal -> withContext(Dispatchers.IO) {
            val done = java.util.concurrent.CountDownLatch(1)
            signal.setOnCancelListener { done.countDown() }
            started.complete(Unit)
            check(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            signalled = true
            StatusSaveResult.Cancelled
        } })
        library.onPicked(tree("Android/media"))
        val item = ready(library.load()).items.first()
        var result: StatusSaveResult? = null
        val job = launch(Dispatchers.Default) { result = library.saveOne(item) }
        withTimeout(5_000) { started.await() }
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertTrue(signalled)
        assertNull("a cancelled caller gets the cancellation, not a result", result)
        assertFalse(ready(library.load()).items.first().saved)
    }

    @Test fun aCopyCommittedJustAsTheCancelCameStaysSaved() = runBlocking {
        seedPhone()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val library = DocumentStatusLibrary(app, { _, _ -> started.complete(Unit); release.await(); StatusSaveResult.Saved(savedUri()) })
        library.onPicked(tree("Android/media"))
        val item = ready(library.load()).items.first()
        var result: StatusSaveResult? = null
        val job = launch(Dispatchers.Default) { result = library.saveOne(item) }
        withTimeout(5_000) { started.await() }
        job.cancel()
        release.complete(Unit)
        withTimeout(5_000) { job.join() }
        assertEquals(StatusSaveResult.Saved(savedUri()), result)
        assertTrue(ready(library.load()).items.first().saved)
    }

    @Test fun deletedSavedFileClearsBadgeAndPrunesMarkOnNextLoad() = runBlocking {
        seedPhone()
        val library = library()
        library.onPicked(tree("Android/media"))
        val item = ready(library.load()).items.first()
        library.save(listOf(item))
        assertTrue(ready(library.load()).items.first().saved)

        assertEquals(1, app.contentResolver.delete(savedUri(), null, null))
        assertFalse(ready(library.load()).items.first().saved)
        assertEquals("", marksPrefs.getString("saved", null))
        // Even if the id is reused, the removed mark must not return after rebuilding the library.
        media.ids += 1L
        assertFalse(ready(library().load()).items.first().saved)
    }

    @Test fun savedFilesAreCheckedInOneBatchAndSurviveLibraryRecreation() = runBlocking {
        seedPhone()
        val library = library()
        library.onPicked(tree("Android/media"))
        val items = ready(library.load()).items
        var nextId = 0L
        saveAnswer = { StatusSaveResult.Saved(savedUri(++nextId)) }
        media.ids += 2L
        library.save(items.take(2))
        media.queries = 0

        assertEquals(listOf(true, true, false, false), ready(library().load()).items.map { it.saved })
        assertEquals(1, media.queries)
        app.contentResolver.delete(savedUri(2), null, null)
        media.queries = 0
        assertEquals(listOf(true, false, false, false), ready(library.load()).items.map { it.saved })
        assertEquals(1, media.queries)
    }

    @Test fun legacyMarkWithoutSavedUriStillCountsAsSaved() = runBlocking {
        seedPhone()
        val library = library()
        library.onPicked(tree("Android/media"))
        // Old on-disk format: hash of the source document id, size (10) and modified time (9000).
        val legacyKey = "0a8ecb2caf4eec01be9c0ea0ec9796b7"
        marksPrefs.edit().putString("saved", legacyKey).commit()

        assertEquals(listOf(true, false, false, false), ready(library.load()).items.map { it.saved })
        assertEquals(legacyKey, marksPrefs.getString("saved", null))
        assertEquals(0, media.queries)
    }

    @Test fun securityExceptionKeepsSavedMarkUntilExistenceCanBeChecked() = runBlocking {
        seedPhone()
        val library = library()
        library.onPicked(tree("Android/media"))
        library.save(ready(library.load()).items.take(1))
        val stored = marksPrefs.getString("saved", null)
        media.ids.clear()
        media.denyQuery = true

        assertTrue(ready(library.load()).items.first().saved)
        assertEquals(stored, marksPrefs.getString("saved", null))
        media.denyQuery = false
        assertFalse(ready(library.load()).items.first().saved)
    }

    @Test fun nullCursorKeepsSavedMarkUntilExistenceCanBeChecked() = runBlocking {
        seedPhone()
        val library = library()
        library.onPicked(tree("Android/media"))
        library.save(ready(library.load()).items.take(1))
        val stored = marksPrefs.getString("saved", null)
        media.ids.clear()
        media.nullCursor = true

        assertTrue(ready(library.load()).items.first().saved)
        assertEquals(stored, marksPrefs.getString("saved", null))
        media.nullCursor = false
        assertFalse(ready(library.load()).items.first().saved)
    }

    @Test fun newMeansNotSeenOnThePreviousLoad() = runBlocking {
        seedPhone()
        val library = library()
        library.onPicked(tree("Android/media"))
        assertTrue(ready(library.load()).items.none { it.isNew })
        file("$wa/accounts/1002/Media/.Statuses/fresh.mp4", 20_000)
        val second = ready(library.load()).items
        assertEquals(listOf(true, false, false, false, false), second.map { it.isNew })
        assertTrue(ready(library.load()).items.none { it.isNew })
    }

    @Test fun forgetReleasesTheGrantAndSourceIsNeverWritten() = runBlocking {
        seedPhone()
        val before = root.walkTopDown().filter { it.isFile }.associate { it.path to (it.readBytes().toList() to it.lastModified()) }
        val library = library()
        library.onPicked(tree("Android/media"))
        library.save(ready(library.load()).items)
        library.forget()
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
        assertTrue(library.load() is StatusAccess.NeedsGrant)
        assertEquals(before, root.walkTopDown().filter { it.isFile }.associate { it.path to (it.readBytes().toList() to it.lastModified()) })
        assertEquals(0, provider.writes)
    }

    private inner class SavedMediaProvider : ContentProvider() {
        val ids = mutableSetOf(1L)
        var queries = 0
        var denyQuery = false
        var nullCursor = false
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
            assertEquals(savedCollection, uri) // No per-item queries.
            queries++
            if (denyQuery) throw SecurityException()
            if (nullCursor) return null
            assertArrayEquals(arrayOf(MediaStore.MediaColumns._ID), projection)
            return MatrixCursor(requireNotNull(projection)).apply {
                ids.forEach { addRow(arrayOf<Any>(it)) }
            }
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) =
            if (ids.remove(ContentUris.parseId(uri))) 1 else 0
        override fun getType(uri: Uri) = "image/jpeg"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("unexpected insert")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("unexpected update")
    }

    /** ExternalStorageProvider stand-in over [root]: tree-scoped child queries, read-only; writes are counted and fail. */
    private inner class Provider : ContentProvider() {
        val queried = mutableListOf<String>()
        val failOnce = mutableSetOf<String>()
        var writes = 0
        override fun onCreate() = true
        private fun fileOf(documentId: String): File {
            val path = documentId.substringAfter("primary:")
            return if (path.isEmpty()) root else File(root, path)
        }
        private fun mime(file: File) = when {
            file.isDirectory -> Document.MIME_TYPE_DIR
            file.name.endsWith(".jpg") -> "image/jpeg"
            file.name.endsWith(".webp") -> "image/webp"
            file.name.endsWith(".mp4") -> "video/mp4"
            file.name.endsWith(".opus") -> "audio/ogg"
            else -> "application/octet-stream"
        }
        private fun idOf(file: File) = "primary:" + file.relativeTo(root).path
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val treeId = DocumentsContract.getTreeDocumentId(uri)
            val documentId = DocumentsContract.getDocumentId(uri)
            val treePath = treeId.substringAfter("primary:")
            val docPath = documentId.substringAfter("primary:")
            require(treePath.isEmpty() || docPath == treePath || docPath.startsWith("$treePath/")) { "outside tree" }
            assertEquals("children", uri.lastPathSegment)
            queried += docPath
            if (failOnce.remove(docPath)) throw IllegalStateException("busy")
            val dir = fileOf(documentId)
            if (!dir.isDirectory) throw IllegalArgumentException(FileNotFoundException())
            val columns = requireNotNull(projection)
            return MatrixCursor(columns).apply {
                dir.listFiles().orEmpty().sortedBy { it.name }.forEach { child ->
                    addRow(columns.map<String, Any?> { when (it) {
                        Document.COLUMN_DOCUMENT_ID -> idOf(child)
                        Document.COLUMN_DISPLAY_NAME -> child.name
                        Document.COLUMN_MIME_TYPE -> mime(child)
                        Document.COLUMN_SIZE -> if (child.isDirectory) null else child.length()
                        Document.COLUMN_LAST_MODIFIED -> child.lastModified()
                        else -> null
                    } }.toTypedArray())
                }
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (mode != "r") { writes++; fail("source opened for writing") }
            return ParcelFileDescriptor.open(fileOf(DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun getType(uri: Uri) = mime(fileOf(DocumentsContract.getDocumentId(uri)))
        override fun insert(uri: Uri, values: ContentValues?): Uri? { writes++; fail("insert"); return null }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int { writes++; fail("update"); return 0 }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int { writes++; fail("delete"); return 0 }
    }
}
