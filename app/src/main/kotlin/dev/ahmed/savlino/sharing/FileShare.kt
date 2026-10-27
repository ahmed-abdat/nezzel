package dev.ahmed.savlino.sharing

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import dev.ahmed.savlino.R
import dev.ahmed.savlino.usage.UsageEvents
import dev.ahmed.savlino.usage.UsageKeys
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An installed WhatsApp that takes this file type, named as the phone names it. */
data class ShareTarget(val packageName: String, val label: String)

/**
 * Share one saved file straight to WhatsApp or WhatsApp Business through the public ACTION_SEND, else the Android chooser.
 * Only that file is granted, read-only and for this send; the type is the file's own (an MKV is never sent as MP4).
 * Launching WhatsApp says nothing about what the person does there, so nothing here ever claims "Shared".
 */
object FileShare {
    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    /** Installed, enabled WhatsApp apps that take [mime]; empty means the chooser. Never prompts an install. */
    fun targets(pm: PackageManager, mime: String): List<ShareTarget> = listOf(WHATSAPP, WHATSAPP_BUSINESS).mapNotNull { pkg ->
        val info = try { pm.getApplicationInfo(pkg, 0) } catch (_: PackageManager.NameNotFoundException) { return@mapNotNull null }
        @Suppress("DEPRECATION") // The flags overload needs API 33; min is 29.
        val takes = info.enabled && pm.queryIntentActivities(send(Uri.EMPTY, mime).setPackage(pkg), 0).isNotEmpty()
        if (takes) ShareTarget(pkg, pm.getApplicationLabel(info).toString()) else null
    }

    fun send(uri: Uri, mime: String): Intent = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("", uri) }

    /**
     * Through the Android chooser, limited to [target] when there is one: with a single copy of the app it opens straight away.
     * A direct start breaks with a second copy (Samsung Dual Messenger, clone apps): the phone wraps it in its own
     * profile picker, and Android 16 blocks that picker's launch (IntentRedirect hardening, no creator token).
     */
    fun intent(uri: Uri, mime: String, target: ShareTarget?): Intent =
        Intent.createChooser(send(uri, mime).apply { target?.let { setPackage(it.packageName) } }, null)
}

/** Launches shares from an Activity context; failures come back as a message id for the screen's snackbar. */
class FileSharer(
    private val context: Context,
    private val usage: UsageEvents,
    private val scope: CoroutineScope,
    private val message: (Int) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun targets(mime: String): List<ShareTarget> =
        withContext(io) { try { FileShare.targets(context.packageManager, mime) } catch (_: RuntimeException) { emptyList() } }

    fun share(uri: Uri, mime: String, target: ShareTarget?) { scope.launch { launch(uri, mime, target) } }

    /** Checks the file still opens, then starts the share. A cancelled share comes back quietly. */
    internal suspend fun launch(uri: Uri, mime: String, target: ShareTarget?) {
        val readable = withContext(io) {
            try { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } == true } catch (_: Exception) { false }
        }
        if (!readable) return message(R.string.share_file_unavailable)
        try {
            context.startActivity(FileShare.intent(uri, mime, target))
            if (target != null) usage.count(UsageKeys.SHARE_WHATSAPP)
        } catch (_: ActivityNotFoundException) { message(R.string.share_unavailable) }
        catch (_: SecurityException) { message(R.string.share_unavailable) }
    }
}

/** The screen's sharer; null in tests and captures that don't share. */
val LocalFileSharer = compositionLocalOf<FileSharer?> { null }

@Composable
fun rememberFileSharer(message: (Int) -> Unit): FileSharer {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(message)
    return remember(context, scope) {
        val usage = (context.applicationContext as? dev.ahmed.savlino.jobs.NezzelApplication)?.usage ?: UsageEvents.None
        FileSharer(context, usage, scope, { latest.value(it) })
    }
}
