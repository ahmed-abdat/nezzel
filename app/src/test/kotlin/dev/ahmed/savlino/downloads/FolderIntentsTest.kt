package dev.ahmed.savlino.downloads

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.DocumentsContract
import dev.ahmed.savlino.storage.SaveDestination
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33, 35])
class FolderIntentsTest {
    private val pm get() = RuntimeEnvironment.getApplication().packageManager
    private val item = SavedMedia(Uri.parse("content://media/external/downloads/1"),
        "Family.mp4", "Family", "video/mp4", null, 0, null, null, null)
    private val folder = "content://com.android.externalstorage.documents/document/primary%3ADownload%2FNezzel"

    private fun handler(pkg: String, action: String = Intent.ACTION_VIEW, default: Boolean = true,
        name: String = "$pkg.FilesActivity"): ComponentName {
        val component = ComponentName(pkg, name)
        val manager = shadowOf(pm)
        manager.addActivityIfNotPresent(component).apply { exported = true; enabled = true }
        manager.addIntentFilterForActivity(component, IntentFilter(action).apply {
            if (default) addCategory(Intent.CATEGORY_DEFAULT)
            if (action == Intent.ACTION_VIEW) {
                addDataScheme("content")
                addDataType(DocumentsContract.Document.MIME_TYPE_DIR)
            }
        })
        return component
    }

    @Test fun defaultSaveTargetsGoogleDocumentsUiAtTheSavedFolder() {
        val documents = handler("com.google.android.documentsui")
        handler("example.other.files")
        val intent = FolderIntents.forItem(item, SaveDestination.Default, pm)!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(folder, intent.data.toString())
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, intent.type)
        assertEquals("com.google.android.documentsui", intent.`package`)
        assertEquals(documents, intent.component)
        assertEquals(documents, intent.resolveActivity(pm))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun onlyAChosenFolderCarriesAReadGrant() {
        handler("example.other.files")
        // Review F3: the default folder's URI is not ours to grant; the chosen tree is, and only for reading.
        val default = FolderIntents.forItem(item, SaveDestination.Default, pm)!!
        assertEquals(0, default.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Movies")
        val chosen = FolderIntents.forFolder(DocumentsContract.buildDocumentUriUsingTree(tree, "primary:Movies"), pm)!!
        assertEquals(Intent.ACTION_VIEW, chosen.action)
        assertTrue(chosen.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, chosen.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test fun aospDocumentsUiIsAlsoSupported() {
        val documents = handler("com.android.documentsui")
        assertEquals(documents, FolderIntents.forItem(item, SaveDestination.Default, pm)!!.component)
    }

    @Test fun mediaStoreRelativePathWinsOverTheCurrentSafSelection() {
        handler("com.android.documentsui")
        val intent = FolderIntents.forItem(item.copy(relativePath = "Download/Trips/"),
            SaveDestination.Tree(Uri.parse("content://other.documents/tree/selected")), pm)!!
        assertEquals("primary:Download/Trips", DocumentsContract.getDocumentId(intent.data!!))
    }

    @Test fun safFileUsesItsOwnTreeAfterTheDestinationChanges() {
        handler("com.android.documentsui")
        val saved = item.copy(uri = Uri.parse("content://provider.documents/tree/old-folder/document/opaque-file-id"))
        val intent = FolderIntents.forItem(saved,
            SaveDestination.Tree(Uri.parse("content://provider.documents/tree/new-folder")), pm)!!
        assertEquals("content://provider.documents/tree/old-folder/document/old-folder", intent.data.toString())
    }

    @Test fun externalStorageTreeUsesTheDerivableParentInsideTheTree() {
        handler("com.android.documentsui")
        val saved = item.copy(uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2FTrips%2Fclip.mp4"))
        val intent = FolderIntents.forItem(saved, SaveDestination.Default, pm)!!
        assertEquals("primary:Movies/Trips", DocumentsContract.getDocumentId(intent.data!!))
        assertEquals("primary:Movies", DocumentsContract.getTreeDocumentId(intent.data!!))
    }

    @Test fun opaqueDocumentCanUseTheMatchingCurrentTree() {
        handler("com.android.documentsui")
        val saved = item.copy(uri = Uri.parse("content://provider.documents/document/file-id"))
        val intent = FolderIntents.forItem(saved,
            SaveDestination.Tree(Uri.parse("content://provider.documents/tree/chosen")), pm)!!
        assertEquals("content://provider.documents/tree/chosen/document/chosen", intent.data.toString())
    }

    @Test fun singleViewHandlerIsPinnedWithoutOpeningAChooser() {
        val files = handler("com.sec.android.app.myfiles")
        val intent = FolderIntents.forItem(item, SaveDestination.Default, pm)!!
        assertNull(intent.`package`)
        assertEquals(files, intent.component)
        assertEquals(files, intent.resolveActivity(pm))
    }

    @Test fun multipleViewHandlersFallBackToTheFolderPicker() {
        handler("example.first.files")
        handler("example.second.files")
        val picker = handler("com.google.android.documentsui", Intent.ACTION_OPEN_DOCUMENT_TREE)
        val intent = FolderIntents.forItem(item, SaveDestination.Default, pm)!!
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        assertEquals(picker, intent.component)
        assertEquals(folder, intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI).toString())
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
        assertNull(intent.data)
    }

    @Test fun noViewHandlerUsesTheFolderPickerWithoutRequestingAGrant() {
        handler("com.android.documentsui", Intent.ACTION_OPEN_DOCUMENT_TREE)
        val intent = FolderIntents.forItem(item, SaveDestination.Default, pm)!!
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        assertEquals(folder, intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI).toString())
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
    }

    @Test fun resolverActivityIsNeverReturnedAsAFolderHandler() {
        handler("android", name = "com.android.internal.app.ResolverActivity")
        val picker = handler("com.android.documentsui", Intent.ACTION_OPEN_DOCUMENT_TREE)
        assertEquals(picker, FolderIntents.forItem(item, SaveDestination.Default, pm)!!.component)
    }

    @Test fun chooserOnlyPickerLeavesTheActionUnavailable() {
        handler("android", Intent.ACTION_OPEN_DOCUMENT_TREE, name = "com.android.internal.app.ChooserActivity")
        assertNull(FolderIntents.forItem(item, SaveDestination.Default, pm))
    }

    @Test fun nonDefaultViewActivityIsNotOffered() {
        handler("com.android.documentsui", default = false)
        assertNull(FolderIntents.forItem(item, SaveDestination.Default, pm))
    }

    @Test fun noHandlersLeavesTheActionUnavailable() {
        assertNull(FolderIntents.forItem(item, SaveDestination.Default, pm))
    }

    @Test fun unrelatedDocumentDoesNotOpenTheCurrentDestination() {
        handler("com.android.documentsui")
        assertNull(FolderIntents.forItem(item.copy(uri = Uri.parse("content://old.documents/document/file")),
            SaveDestination.Tree(Uri.parse("content://new.documents/tree/folder")), pm))
    }
}
