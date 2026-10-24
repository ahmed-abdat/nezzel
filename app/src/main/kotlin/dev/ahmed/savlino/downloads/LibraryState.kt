package dev.ahmed.savlino.downloads

import dev.ahmed.savlino.storage.SaveDestinations
import dev.ahmed.savlino.storage.SaveDestination

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.ahmed.savlino.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

sealed interface LibraryLoadState {
    val lastSuccessfulRows: List<SavedMedia>?
    data class Loading(override val lastSuccessfulRows: List<SavedMedia>? = null) : LibraryLoadState
    data class Ready(val rows: List<SavedMedia>) : LibraryLoadState {
        override val lastSuccessfulRows get() = rows
    }
    data class Error(override val lastSuccessfulRows: List<SavedMedia>? = null) : LibraryLoadState
}

class LibraryState(
    val load: LibraryLoadState,
    val open: (SavedMedia) -> Unit, val share: (SavedMedia) -> Unit, val openWith: (SavedMedia) -> Unit,
    val showFiles: ((SavedMedia) -> Unit)?, val delete: (SavedMedia) -> Unit,
    val onRetry: () -> Unit,
    val shareSelected: (List<SavedMedia>) -> Unit,
    val deleteSelected: (List<SavedMedia>, (cleared: Boolean) -> Unit) -> Unit,
    val canShowFiles: (SavedMedia) -> Boolean = { showFiles != null },
) {
    val media get() = load.lastSuccessfulRows.orEmpty()
}

/** Copy link for a saved file: [find] returns null when no link is kept (no job row, cleared, or the key is gone). */
class LinkCopy(val find: suspend (SavedMedia) -> String?, val copy: (String) -> Unit)
val LocalLinkCopy = staticCompositionLocalOf<LinkCopy?> { null }

/** The link counts as handled before it reaches the clipboard, so Nezzel never offers it back; a failed write still copies. */
suspend fun copyHandled(link: String, markHandled: suspend (String) -> Boolean, setClip: (String) -> Unit) {
    markHandled(link)
    setClip(link)
}

@Composable
fun rememberLibraryState(
    snackbar: SnackbarHostState,
    observe: (() -> Flow<Result<List<SavedMedia>>>)? = null,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Runs on each listed snapshot: every delete path ends in a reload, and so do deletes outside Nezzel. */
    sweep: (suspend (rows: List<SavedMedia>, listedAt: Long) -> Unit)? = null,
    /** Null keeps the cold start plain Loading; tests pass their own file. */
    cache: LibraryCache? = LocalContext.current.let { remember(it) { LibraryCache(java.io.File(it.noBackupFilesDir, LibraryCache.FILE_NAME)) } },
): LibraryState {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val destinations = remember(context) { SaveDestinations(context) }
    val repository = remember(context.contentResolver, destinations) { SavedMediaRepository(context.contentResolver, context.packageName, destinations) }
    val scope = rememberCoroutineScope()
    var load by remember { mutableStateOf<LibraryLoadState>(LibraryLoadState.Loading()) }
    var listedAt by remember { mutableLongStateOf(0L) }
    var refresh by remember { mutableIntStateOf(0) }
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    var singleDeleting by remember { mutableStateOf(false) }
    var batchUris by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var batchIndex by rememberSaveable { mutableIntStateOf(0) }
    var batchFailed by rememberSaveable { mutableIntStateOf(0) }
    var batchWaiting by rememberSaveable { mutableStateOf(false) }
    var batchApproved by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var batchRetry by rememberSaveable { mutableStateOf(false) }
    var batchCancelled by rememberSaveable { mutableStateOf(false) }
    var batchComplete by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    fun message(id: Int) { scope.launch { snackbar.showSnackbar(context.getString(id)) } }
    LaunchedEffect(repository, owner, refresh, observe, ioDispatcher) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            load = LibraryLoadState.Loading(load.lastSuccessfulRows)
            // Cold start only: the last listed rows fill in while the fresh query runs; any result wins.
            if (load.lastSuccessfulRows == null && cache != null) launch {
                val cached = withContext(ioDispatcher) { cache.read() }
                if (cached != null && load == LibraryLoadState.Loading()) load = LibraryLoadState.Loading(cached)
            }
            (observe?.invoke() ?: repository.observe(ioDispatcher)).catch { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                emit(Result.failure(error))
            }.collect { result ->
                load = result.fold(
                    { listedAt = System.currentTimeMillis(); LibraryLoadState.Ready(it) },
                    { LibraryLoadState.Error(load.lastSuccessfulRows) },
                )
            }
        }
    }
    LaunchedEffect(load, sweep) { (load as? LibraryLoadState.Ready)?.let { sweep?.invoke(it.rows, listedAt) } }
    LaunchedEffect(load, cache, ioDispatcher) {
        val ready = load as? LibraryLoadState.Ready ?: return@LaunchedEffect
        if (cache != null) withContext(ioDispatcher) { cache.write(ready.rows) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val uri = pendingUri?.let(android.net.Uri::parse)
        pendingUri = null
        if(result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                try {
                    val deleted = if(Build.VERSION.SDK_INT >= 30) true else uri != null && withContext(ioDispatcher) { repository.delete(uri) }
                    if(deleted) message(R.string.saved_deleted) else message(R.string.saved_action_failed)
                } catch (_: Exception) { message(R.string.saved_action_failed) }
                refresh++
                singleDeleting = false
            }
        } else singleDeleting = false
    }
    fun finishBatch(notify: Boolean = true, cleared: Boolean = !batchCancelled) {
        if (notify) message(if (batchFailed == 0) R.string.saved_deleted else R.string.saved_delete_partial)
        batchUris = emptyList()
        batchWaiting = false
        refresh++
        val complete = batchComplete
        batchComplete = null
        complete?.invoke(cleared)
    }
    val batchLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (batchUris.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= 30 && batchUris.none { android.provider.DocumentsContract.isTreeUri(android.net.Uri.parse(it)) }) {
                // Persist the result before suspending: verification resumes after recreation.
                batchApproved = result.resultCode == Activity.RESULT_OK
                batchWaiting = false
            } else {
                // RecoverableSecurityException grants access; unlike createDeleteRequest it does not delete.
                if (result.resultCode == Activity.RESULT_OK) batchRetry = true
                else { batchCancelled = true; batchFailed++; batchIndex++ }
                batchWaiting = false
            }
        }
    }
    LaunchedEffect(batchUris, batchIndex, batchWaiting, batchApproved, ioDispatcher) {
        if (batchUris.isEmpty() || batchWaiting) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= 30 && batchUris.none { android.provider.DocumentsContract.isTreeUri(android.net.Uri.parse(it)) }) {
            try {
                when (batchApproved) {
                    null -> {
                        val sender = withContext(ioDispatcher) { repository.deleteRequest(batchUris.map(android.net.Uri::parse)) }
                        // No suspension between recording the wait and launching the system confirmation.
                        batchWaiting = true
                        batchLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    }
                    true -> {
                        val remaining = withContext(ioDispatcher) { repository.query().map { it.uri.toString() }.toSet() }
                        batchFailed = batchUris.count { it in remaining }
                        finishBatch()
                    }
                    false -> finishBatch(notify = false, cleared = false)
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (batchApproved == true) {
                    // Consent succeeded; a failed refresh cannot tell us which files still exist.
                    message(R.string.saved_load_failed)
                    finishBatch(notify = false)
                } else {
                    message(R.string.saved_action_failed)
                    finishBatch(notify = false, cleared = false)
                }
            }
            return@LaunchedEffect
        }
        if (batchIndex == batchUris.size) { finishBatch(); return@LaunchedEffect }
        try {
            if (!withContext(ioDispatcher) { repository.delete(android.net.Uri.parse(batchUris[batchIndex])) }) batchFailed++
            batchIndex++
            batchRetry = false
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            val sender = (error as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender
            if (sender != null && !batchRetry) {
                try {
                    batchWaiting = true
                    batchLauncher.launch(IntentSenderRequest.Builder(sender).build())
                } catch (launchError: Exception) {
                    if (launchError is kotlinx.coroutines.CancellationException) throw launchError
                    batchWaiting = false; batchFailed++; batchIndex++; batchRetry = false
                }
            } else { batchFailed++; batchIndex++; batchRetry = false }
        }
    }
    fun start(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: ActivityNotFoundException) { message(R.string.saved_action_failed) }
        catch (_: SecurityException) { message(R.string.saved_action_failed) }
    }
    val destination = destinations.destination
    val selectedTree = (destination as? SaveDestination.Tree)?.uri
    val fileIntents = remember(context, load.lastSuccessfulRows, selectedTree) {
        // Files in the same folder share one package-manager probe; no provider IO on the UI thread.
        buildMap {
            load.lastSuccessfulRows.orEmpty().groupBy { FolderIntents.folderUri(it, destination) }.forEach { (folder, items) ->
                if (folder != null) {
                    val intent = FolderIntents.forItem(items.first(), destination, context.packageManager)
                    if (intent != null) items.forEach { put(it.uri, intent) }
                }
            }
        }
    }
    return LibraryState(load, { start(MediaActions.open(it)) }, { start(MediaActions.share(it)) },
        { start(Intent.createChooser(MediaActions.open(it), null)) }, if (fileIntents.isNotEmpty()) ({ item ->
            val intent = fileIntents[item.uri]
            if (intent != null) start(intent) else message(R.string.saved_action_failed)
        }) else null,
        { item ->
            // This callback is reachable only after the app's delete confirmation.
            if (singleDeleting || pendingUri != null || batchUris.isNotEmpty()) message(R.string.saved_action_failed)
            else {
                singleDeleting = true
                scope.launch {
                    try {
                        val deleted = withContext(ioDispatcher) { repository.delete(item.uri) }
                        if(deleted) message(R.string.saved_deleted) else message(R.string.saved_action_failed)
                        refresh++
                        singleDeleting = false
                    } catch (security: SecurityException) {
                        try {
                            val sender = if (Build.VERSION.SDK_INT >= 30) {
                                withContext(ioDispatcher) { repository.deleteRequest(item) }
                            } else (security as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender
                            if (sender != null) {
                                pendingUri = item.uri.toString()
                                launcher.launch(IntentSenderRequest.Builder(sender).build())
                            } else { singleDeleting = false; message(R.string.saved_action_failed) }
                        } catch (_: Exception) { pendingUri = null; singleDeleting = false; message(R.string.saved_action_failed) }
                    } catch (_: Exception) { pendingUri = null; singleDeleting = false; message(R.string.saved_action_failed) }
                }
            }
        }, onRetry = {
            load = LibraryLoadState.Loading(load.lastSuccessfulRows)
            refresh++
        }, shareSelected = { if (it.isNotEmpty()) start(MediaActions.share(it)) }, deleteSelected = { items, complete ->
            if (items.isEmpty()) complete(true)
            else if (singleDeleting || pendingUri != null || batchUris.isNotEmpty()) {
                message(R.string.saved_action_failed)
                complete(false)
            } else {
                batchComplete = complete
                batchIndex = 0; batchFailed = 0; batchRetry = false; batchCancelled = false
                batchWaiting = false; batchApproved = null
                batchUris = items.map { it.uri.toString() }.distinct()
            }
        }, canShowFiles = { it.uri in fileIntents })
}
