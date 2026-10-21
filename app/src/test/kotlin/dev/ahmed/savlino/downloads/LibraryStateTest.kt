package dev.ahmed.savlino.downloads

import android.app.*
import android.content.*
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.core.app.ActivityOptionsCompat
import java.util.concurrent.CountDownLatch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LibraryStateTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val work = LibraryTestWork(compose)
    private val context get() = RuntimeEnvironment.getApplication()
    private class Registry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry get() = this
        var request: Int? = null
        var launches = 0
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) { request = requestCode; launches++ }
    }
    private class Provider(private val work: LibraryTestWork) : SavedMediaRepositoryTest.Provider() {
        var denied: Exception? = null
        val deniedById = mutableMapOf<String, Exception>()
        val attempts = mutableListOf<String>()
        var requests = 0
        var deleteAttempts = 0
        var queryGate: CountDownLatch? = null
        var requestGate: CountDownLatch? = null
        var requestFailure: Exception? = null
        override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): android.database.Cursor {
            queryGate?.let { work.park(it) }
            return super.query(uri, columns, selection, args, sort)
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            deleteAttempts++
            attempts += uri.lastPathSegment!!
            deniedById[uri.lastPathSegment]?.let { throw it }
            denied?.let { throw it }
            return super.delete(uri, selection, args)
        }
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            requests++
            requestFailure?.let { throw it }
            requestGate?.let { work.park(it) }
            return super.call(method, arg, extras)
        }
    }
    private fun drain() = work.drain()
    private fun await(check: () -> Boolean) = work.await(check)
    private fun fixture(check: (Provider, Registry) -> Unit) {
        val provider = Provider(work).apply { attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" }) }
        ShadowContentResolver.registerProviderInternal("media", provider)
        try { check(provider, Registry()) } finally { provider.queryGate?.countDown(); provider.requestGate?.countDown(); work.close(); provider.db.close() }
    }
    @Test fun showFilesResolvesEachItemsFolderAndKeepsUnknownItemsUnavailable() {
        val manager = org.robolectric.Shadows.shadowOf(context.packageManager)
        val files = ComponentName("com.android.documentsui", "com.android.documentsui.FilesActivity")
        manager.addActivityIfNotPresent(files).apply { exported = true; enabled = true }
        manager.addIntentFilterForActivity(files, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("content")
            addDataType(android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
        })
        val media = SavedMedia(Uri.parse("content://media/external/downloads/1"),
            "clip.mp4", "Clip", "video/mp4", null, 0, null, null, null)
        val saf = media.copy(uri = Uri.parse("content://provider.documents/tree/saved-folder/document/clip"))
        val unknown = media.copy(uri = Uri.parse("content://unknown.documents/document/clip"))
        val observe = { kotlinx.coroutines.flow.flowOf(Result.success(listOf(media, saf, unknown))) }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var state: LibraryState? = null
        try {
            controller.get().setContent { state = rememberLibraryState(remember { SnackbarHostState() }, observe, work) }
            await { state?.media?.size == 3 }
            assertTrue(state!!.canShowFiles(media))
            assertTrue(state!!.canShowFiles(saf))
            assertFalse(state!!.canShowFiles(unknown))
            compose.runOnIdle { state!!.showFiles!!.invoke(media) }
            val first = org.robolectric.Shadows.shadowOf(controller.get()).nextStartedActivity
            assertEquals("primary:Download/Nezzel", android.provider.DocumentsContract.getDocumentId(first.data!!))
            compose.runOnIdle { state!!.showFiles!!.invoke(saf) }
            val second = org.robolectric.Shadows.shadowOf(controller.get()).nextStartedActivity
            assertEquals("content://provider.documents/tree/saved-folder/document/saved-folder", second.data.toString())
        } finally { controller.pause().stop().destroy(); work.close() }
    }
    @Test @Config(sdk = [35]) fun ownFileDeletesDirectlyWithoutSystemConsentRequest() = fixture { provider, registry ->
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var state: LibraryState? = null
        try {
            controller.get().setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) { state = rememberLibraryState(remember { SnackbarHostState() }, ioDispatcher = work) } }
            drain()
            val item = SavedMediaRepository(context.contentResolver, context.packageName).query().single()
            compose.runOnIdle { state!!.delete(item) }
            await { provider.db.rawQuery("SELECT _id FROM media", null).use { it.count } == 0 }
            assertEquals(0, provider.db.rawQuery("SELECT _id FROM media", null).use { it.count })
            assertNull(registry.request)
            assertEquals(0, provider.requests)
        } finally { controller.pause().stop().destroy() }
    }
    @Test @Config(sdk = [35]) fun securityFallbackDeletesAfterSystemConsentAndRefreshesLibrary() = consentResult(true)
    @Test @Config(sdk = [35]) fun declinedSystemConsentKeepsProviderRowAndLibrary() = consentResult(false)
    private fun consentResult(approved: Boolean) = fixture { provider, registry ->
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var state: LibraryState? = null
        try {
            controller.get().setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) { state = rememberLibraryState(remember { SnackbarHostState() }, ioDispatcher = work) } }
            await { state?.media?.size == 1 }
            val item = state!!.media.single()
            provider.denied = SecurityException("system consent needed")
            compose.runOnIdle { state!!.delete(item) }
            await { registry.request != null }
            assertEquals(1, provider.db.rawQuery("SELECT _id FROM media", null).use { it.count })
            assertEquals(1, provider.requests)
            if (approved) {
                provider.denied = null
                // The OS performs deletion before delivering the successful createDeleteRequest result.
                provider.delete(item.uri, null, null)
            }
            val attemptsBeforeResult = provider.deleteAttempts
            registry.dispatchResult(registry.request!!, if (approved) Activity.RESULT_OK else Activity.RESULT_CANCELED, Intent())
            if (approved) await { state?.media?.isEmpty() == true } else drain()
            assertEquals(if (approved) 0 else 1, provider.db.rawQuery("SELECT _id FROM media", null).use { it.count })
            assertEquals(attemptsBeforeResult, provider.deleteAttempts)
            assertEquals(if (approved) 0 else 1, state!!.media.size)
        } finally { controller.pause().stop().destroy() }
    }
    @Test @Config(sdk = [33]) fun batchModernDeleteRequestsAllUrisOnceAndCompletesAfterConsent() = fixture { provider, registry ->
        provider.insertRow(2, "audio/mpeg", 200)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var state: LibraryState? = null
        var completed = false
        try {
            controller.get().setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) { state = rememberLibraryState(remember { SnackbarHostState() }, ioDispatcher = work) } }
            await { state?.media?.size == 2 }
            val items = state!!.media
            compose.runOnIdle { state!!.deleteSelected(items) { completed = true } }
            await { registry.request != null }
            assertEquals(1, provider.requests)
            assertEquals(2, state!!.media.size)
            assertFalse(completed)
            assertEquals(0, provider.deleteAttempts)
            items.forEach { provider.delete(it.uri, null, null) }
            registry.dispatchResult(registry.request!!, Activity.RESULT_OK, Intent())
            await { completed && state?.media?.isEmpty() == true }
            assertEquals(2, provider.deleteAttempts)
        } finally { controller.pause().stop().destroy() }
    }
    @Test @Config(sdk = [33, 35]) fun oneSelectedVideoReachesConsentInsteadOfActionFailedAndDeletesOnlyAfterApproval() = fixture { provider, registry ->
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var state: LibraryState? = null
        val snackbar = SnackbarHostState()
        var cleared: Boolean? = null
        try {
            controller.get().setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) { state = rememberLibraryState(snackbar, ioDispatcher = work) } }
            await { state?.media?.size == 1 }
            val item = state!!.media.single()
            compose.runOnIdle { state!!.deleteSelected(listOf(item)) { cleared = it } }
            await { registry.request != null || snackbar.currentSnackbarData != null }
            assertNull("Selection must reach system consent, not saved_action_failed", snackbar.currentSnackbarData)
            assertNotNull(registry.request)
            assertNull(cleared)
            assertEquals(0, provider.deleteAttempts)
            assertEquals(1, provider.db.rawQuery("SELECT _id FROM media", null).use { it.count })
            // Model the OS deleting the exact requested row before returning RESULT_OK.
            val clip = provider.deleteRequests.single().getParcelable<ClipData>("clip_data")!!
            assertEquals(1, clip.itemCount)
            assertEquals("content://media/external/video/media/1", clip.getItemAt(0).uri.toString())
            provider.delete(clip.getItemAt(0).uri, null, null)
            registry.dispatchResult(registry.request!!, Activity.RESULT_OK, Intent())
            await { cleared == true && state?.media?.isEmpty() == true }
            assertEquals(0, provider.db.rawQuery("SELECT _id FROM media", null).use { it.count })
            assertEquals(1, provider.deleteAttempts)
        } finally { controller.pause().stop().destroy() }
    }
    @Test @Config(sdk = [33]) fun batchModernCancellationCompletesWithoutClearingSelectionOrDeletingRows() = fixture { provider, registry ->
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var state: LibraryState? = null
        val snackbar = SnackbarHostState()
        var cleared: Boolean? = null
        try {
            controller.get().setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) { state = rememberLibraryState(snackbar, ioDispatcher = work) } }
            await { state?.media?.size == 1 }
            compose.runOnIdle { state!!.deleteSelected(state!!.media) { cleared = it } }
            await { registry.request != null }
            registry.dispatchResult(registry.request!!, Activity.RESULT_CANCELED, Intent())
            await { cleared != null }
            assertEquals(false, cleared)
            assertEquals(1, state!!.media.size)
            assertEquals(0, provider.deleteAttempts)
            assertNull(snackbar.currentSnackbarData)
        } finally { controller.pause().stop().destroy() }
    }
    @Test @Config(sdk = [33]) fun batchModernRequestFailureShowsActionFailedWithoutDeletingRows() = fixture { provider, registry ->
        provider.requestFailure = IllegalStateException("request unavailable")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var state: LibraryState? = null
        val snackbar = SnackbarHostState()
        var completed = false
        try {
            controller.get().setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) { state = rememberLibraryState(snackbar, ioDispatcher = work) } }
            await { state?.media?.size == 1 }
            compose.runOnIdle { state!!.deleteSelected(state!!.media) { completed = true } }
            await { completed && snackbar.currentSnackbarData != null }
            assertEquals(context.getString(dev.ahmed.savlino.R.string.saved_action_failed), snackbar.currentSnackbarData!!.visuals.message)
            assertEquals(1, state!!.media.size)
            assertEquals(0, provider.deleteAttempts)
            assertNull(registry.request)
        } finally { controller.pause().stop().destroy() }
    }
    @Test @Config(sdk = [33]) fun batchModernPartialDeletionKeepsRemainingRowsAndShowsOneTruthfulMessage() = fixture { provider, registry ->
        provider.insertRow(2, "audio/mpeg", 200)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var state: LibraryState? = null
        val snackbar = SnackbarHostState()
        var completed = 0
        try {
            controller.get().setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) { state = rememberLibraryState(snackbar, ioDispatcher = work) } }
            await { state?.media?.size == 2 }
            compose.runOnIdle { state!!.deleteSelected(state!!.media) { completed++ } }
            await { registry.request != null }
            provider.delete(android.net.Uri.parse("content://media/external/downloads/2"), null, null)
            registry.dispatchResult(registry.request!!, Activity.RESULT_OK, Intent())
            await { completed == 1 && state?.media?.size == 1 }
            assertEquals("1", state!!.media.single().uri.lastPathSegment)
            assertEquals(context.getString(dev.ahmed.savlino.R.string.saved_delete_partial), snackbar.currentSnackbarData?.visuals?.message)
            compose.runOnIdle { snackbar.currentSnackbarData!!.dismiss() }; drain()
            assertNull(snackbar.currentSnackbarData)
            assertEquals(1, completed)
        } finally { controller.pause().stop().destroy() }
    }
    @Test @Config(sdk = [33]) fun batchModernResumesRequestCreationAfterRecreation() = fixture { provider, registry ->
        provider.requestGate = CountDownLatch(1)
        var controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var state: LibraryState? = null
        fun content() { controller.get().setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) { state = rememberLibraryState(remember { SnackbarHostState() }, ioDispatcher = work) } } }
        try {
            content(); await { state?.media?.size == 1 }
            compose.runOnIdle { state!!.deleteSelected(state!!.media) {} }
            await { provider.requests == 1 }
            assertNull(registry.request)
            val saved = Bundle()
            controller.pause().saveInstanceState(saved).stop().destroy()
            controller = Robolectric.buildActivity(ComponentActivity::class.java).create(saved).start().resume().visible()
            state = null; content()
            provider.requestGate!!.countDown()
            await { registry.request != null && provider.requests >= 2 }
            registry.dispatchResult(registry.request!!, Activity.RESULT_CANCELED, Intent())
            drain()
            registry.request = null
            compose.runOnIdle { state!!.deleteSelected(state!!.media) {} }
            await { registry.request != null }
        } finally { provider.requestGate?.countDown(); controller.pause().stop().destroy() }
    }
    @Test @Config(sdk = [33]) fun batchModernResumesConsumedResultVerificationAfterRecreation() = fixture { provider, registry ->
        var controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var state: LibraryState? = null
        fun content() { controller.get().setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) { state = rememberLibraryState(remember { SnackbarHostState() }, ioDispatcher = work) } } }
        try {
            content(); await { state?.media?.size == 1 }
            compose.runOnIdle { state!!.deleteSelected(state!!.media) {} }
            await { registry.request != null }
            provider.queryGate = CountDownLatch(1)
            provider.delete(state!!.media.single().uri, null, null)
            registry.dispatchResult(registry.request!!, Activity.RESULT_OK, Intent())
            drain()
            val saved = Bundle()
            controller.pause().saveInstanceState(saved).stop().destroy()
            controller = Robolectric.buildActivity(ComponentActivity::class.java).create(saved).start().resume().visible()
            state = null; content()
            provider.queryGate!!.countDown()
            await { state?.load is LibraryLoadState.Ready && state?.media?.isEmpty() == true }
            drain()
            assertEquals("The consumed result must not launch another consent prompt", 1, provider.requests)
            provider.insertRow(2, "image/jpeg", 200)
            val item = SavedMediaRepository(context.contentResolver, context.packageName).query().single()
            registry.request = null
            compose.runOnIdle { state!!.deleteSelected(listOf(item)) {} }
            await { registry.request != null }
        } finally { provider.queryGate?.countDown(); controller.pause().stop().destroy() }
    }
    @Test fun batchLegacyDeletesOwnedRowsAndContinuesAfterDeniedConsentWithOneMessage() = fixture { provider, registry ->
        provider.insertRow(2, "audio/mpeg", 200)
        provider.insertRow(3, "image/jpeg", 300)
        val action = PendingIntent.getActivity(context, 25, Intent("batch-consent"), PendingIntent.FLAG_IMMUTABLE)
        provider.deniedById["2"] = RecoverableSecurityException(SecurityException("consent"), "Consent",
            RemoteAction(Icon.createWithResource(context, android.R.drawable.ic_delete), "Delete", "Delete", action))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var state: LibraryState? = null
        val snackbar = SnackbarHostState()
        var cleared: Boolean? = null
        try {
            controller.get().setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) { state = rememberLibraryState(snackbar, ioDispatcher = work) } }
            await { state?.media?.size == 3 }
            compose.runOnIdle { state!!.deleteSelected(state!!.media) { cleared = it } }
            await { registry.request != null }
            assertEquals(listOf("3", "2"), provider.attempts)
            registry.dispatchResult(registry.request!!, Activity.RESULT_CANCELED, Intent())
            await { cleared != null && state?.media?.size == 1 }
            assertEquals(false, cleared)
            assertEquals(listOf("3", "2", "1"), provider.attempts)
            assertEquals(listOf("2"), state!!.media.map { it.uri.lastPathSegment })
            assertEquals(context.getString(dev.ahmed.savlino.R.string.saved_delete_partial), snackbar.currentSnackbarData?.visuals?.message)
            compose.runOnIdle { snackbar.currentSnackbarData!!.dismiss() }; drain()
            assertNull(snackbar.currentSnackbarData)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun batchLegacyRetriesEachApprovedUriAndMovesPastUnrecoverableFailure() = fixture { provider, registry ->
        provider.insertRow(2, "audio/mpeg", 200)
        provider.insertRow(3, "image/jpeg", 300)
        val action = PendingIntent.getActivity(context, 26, Intent("batch-consent"), PendingIntent.FLAG_IMMUTABLE)
        fun consent() = RecoverableSecurityException(SecurityException("consent"), "Consent",
            RemoteAction(Icon.createWithResource(context, android.R.drawable.ic_delete), "Delete", "Delete", action))
        provider.deniedById["3"] = consent(); provider.deniedById["2"] = consent()
        provider.deniedById["1"] = IllegalStateException("unavailable")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var state: LibraryState? = null
        var completed = false
        try {
            controller.get().setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) { state = rememberLibraryState(remember { SnackbarHostState() }, ioDispatcher = work) } }
            await { state?.media?.size == 3 }
            compose.runOnIdle { state!!.deleteSelected(state!!.media) { completed = true } }
            await { registry.request != null }
            provider.deniedById.remove("3")
            registry.dispatchResult(registry.request!!, Activity.RESULT_OK, Intent())
            // An IO delete attempt precedes the main-thread launch; wait for the dialog before replying.
            await { registry.launches == 2 }
            assertEquals(listOf("3", "3", "2"), provider.attempts)
            provider.deniedById.remove("2")
            registry.dispatchResult(registry.request!!, Activity.RESULT_OK, Intent())
            await { completed && state?.media?.size == 1 }
            assertEquals(listOf("3", "3", "2", "2", "1"), provider.attempts)
            assertEquals(listOf("1"), state!!.media.map { it.uri.lastPathSegment })
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun consentResultAfterRecreationRetriesUriBeforeLibraryLoads() = fixture { provider, registry ->
        val action = PendingIntent.getActivity(context, 22, Intent("delete-consent"), PendingIntent.FLAG_IMMUTABLE)
        provider.denied = RecoverableSecurityException(SecurityException("consent"), "Consent", RemoteAction(Icon.createWithResource(context, android.R.drawable.ic_delete), "Delete", "Delete", action))
        var controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var state: LibraryState? = null
        fun content() { controller.get().setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) { state = rememberLibraryState(remember { SnackbarHostState() }, ioDispatcher = work) } } }
        try {
            content(); drain()
            val item = SavedMediaRepository(context.contentResolver, context.packageName).query().single()
            compose.runOnIdle { state!!.delete(item) }; await { registry.request != null }
            assertNotNull(registry.request)
            assertEquals(1, provider.db.rawQuery("SELECT _id FROM media", null).use { it.count })
            val saved = Bundle()
            controller.pause().saveInstanceState(saved).stop().destroy()
            provider.denied = null
            provider.queryGate = CountDownLatch(1)
            controller = Robolectric.buildActivity(ComponentActivity::class.java).create(saved).start().resume().visible()
            state = null; content(); drain()
            assertTrue(state!!.media.isEmpty())
            registry.dispatchResult(registry.request!!, Activity.RESULT_OK, Intent())
            await { provider.db.rawQuery("SELECT _id FROM media", null).use { it.count } == 0 }
            assertEquals(0, provider.db.rawQuery("SELECT _id FROM media", null).use { it.count })
        } finally { provider.queryGate?.countDown(); controller.pause().stop().destroy() }
    }
}
