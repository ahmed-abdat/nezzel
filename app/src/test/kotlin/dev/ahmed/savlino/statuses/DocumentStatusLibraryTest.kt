package dev.ahmed.savlino.statuses

import android.content.ContentProvider
import android.content.ContentValues
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
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
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
    private val saveCalls = mutableListOf<StatusSaveSource>()
    private var saveAnswer: (StatusSaveSource) -> StatusSaveResult = { StatusSaveResult.Saved(Uri.parse("content://media/1")) }

    @Before fun setUp() {
        root = File(app.cacheDir, "volume").apply { deleteRecursively(); mkdirs() }
        provider = Provider()
        provider.attachInfo(app, ProviderInfo().apply { authority = auth; exported = true })
        ShadowContentResolver.registerProviderInternal(auth, provider)
        install("com.whatsapp", "WhatsApp"); install("com.whatsapp.w4b", "WhatsApp Business")
    }

    @After fun tearDown() {
        app.contentResolver.persistedUriPermissions.toList().forEach {
            app.contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun library() = DocumentStatusLibrary(app, { saveCalls += it; saveAnswer(it) })
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

    @Test fun movedFolderIsAccessLostAndItsGrantReleased() = runBlocking {
        seedPhone()
        val library = library()
        assertEquals(StatusGrantResult.Granted, library.onPicked(tree("Android/media/com.whatsapp")))
        File(root, "Android/media/com.whatsapp").deleteRecursively()
        assertTrue(library.load() is StatusAccess.AccessLost)
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
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
        saveAnswer = { if (it.uri == items[1].uri) StatusSaveResult.Gone else StatusSaveResult.Saved(Uri.parse("content://media/1")) }
        val unknown = items[2].copy(sizeBytes = null)
        val results = library.save(listOf(items[0], items[1], unknown))
        assertEquals(listOf(StatusSaveResult.Saved(Uri.parse("content://media/1")), StatusSaveResult.Gone,
            StatusSaveResult.Saved(Uri.parse("content://media/1"))), results)
        assertEquals(StatusSaveSource(items[0].uri, "video/mp4", 10L, 9_000L), saveCalls.first())

        val reloaded = ready(library().load()).items
        assertEquals(listOf(true, false, false, false), reloaded.map { it.saved })
        // Same name, new content: a different status, not saved.
        File(root, "$wa/accounts/1001/Media/.Statuses/b.mp4").setLastModified(9_100)
        assertFalse(ready(library().load()).items.first().saved)
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

    /** ExternalStorageProvider stand-in over [root]: tree-scoped child queries, read-only; writes are counted and fail. */
    private inner class Provider : ContentProvider() {
        val queried = mutableListOf<String>()
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
