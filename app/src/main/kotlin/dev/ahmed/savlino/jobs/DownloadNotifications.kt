package dev.ahmed.savlino.jobs

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import dev.ahmed.savlino.MainActivity
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.locale.AppLocale
import dev.ahmed.savlino.locale.NezzelFormat
import kotlinx.coroutines.launch

/** Display-ready transfer numbers; each is null when the journal has no honest value for it. */
data class TransferParts(val percent: String?, val bytes: String, val speed: String?, val remaining: String?)

class DownloadNotifications(private val base: Context) {
    private val context: Context get() = AppLocale.localized(base)
    private val manager = base.getSystemService(NotificationManager::class.java)
    private fun channel() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.download_channel),
            NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(COMPLETED_CHANNEL, context.getString(R.string.completed_channel),
            NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun foreground(progress: DownloadProgress): ForegroundInfo {
        channel()
        // Retry reuses the job ID; its previous result no longer describes this attempt.
        manager.cancel(activeId(progress.jobId) xor Int.MIN_VALUE)
        return ForegroundInfo(activeId(progress.jobId), notification(progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    fun progress(progress: DownloadProgress) { post(activeId(progress.jobId), notification(progress)) }
    fun finished(progress: DownloadProgress) {
        // Foreground service removes the active ID when work stops; use a separate result ID.
        if (progress.phase == DownloadPhase.CANCELLED) manager.cancel(activeId(progress.jobId))
        else post(activeId(progress.jobId) xor Int.MIN_VALUE, notification(progress))
    }
    private fun post(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(base,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try { channel(); manager.notify(id, notification) } catch (_: SecurityException) { /* In-app progress remains available. */ }
    }
    private fun notification(progress: DownloadProgress): Notification {
        val ctx = context
        val description = text(ctx, progress)
        val builder = NotificationCompat.Builder(ctx, if (progress.terminal) COMPLETED_CHANNEL else CHANNEL).setSmallIcon(R.drawable.ic_stat_nezzel)
            .setContentTitle(progress.filename).setContentText(description)
            .setStyle(NotificationCompat.BigTextStyle().bigText(description)).setOnlyAlertOnce(true)
            .setOngoing(!progress.terminal).setAutoCancel(progress.terminal)
            .setContentIntent(PendingIntent.getActivity(base, 0, Intent(base, MainActivity::class.java), FLAGS))
        if (!progress.terminal) {
            builder.setProgress(100, progress.visiblePercent ?: 0, progress.visiblePercent == null)
            val cancel = Intent(base, DownloadCancelReceiver::class.java)
                .setAction(CANCEL).setData(android.net.Uri.parse("nezzel:cancel/${progress.jobId}"))
                .putExtra(DownloadWorker.JOB_ID, progress.jobId)
            builder.addAction(0, ctx.getString(R.string.cancel), PendingIntent.getBroadcast(base, 0, cancel, FLAGS))
        } else if (progress.phase == DownloadPhase.COMPLETED && progress.publishedUri != null) {
            val open = openIntent(progress)
            val share = shareIntent(progress)
            builder.setContentIntent(PendingIntent.getActivity(base, 0, open, FLAGS))
                .addAction(0, ctx.getString(R.string.open_file), PendingIntent.getActivity(base, 0, open, FLAGS))
                .addAction(0, ctx.getString(R.string.share_file), PendingIntent.getActivity(base, activeId(progress.jobId), share, FLAGS))
        } else if (progress.phase == DownloadPhase.FAILED) {
            // Opens the app for explicit review. No notification action silently retries retained failed URLs.
            builder.addAction(0, ctx.getString(R.string.try_again), PendingIntent.getActivity(base, 0,
                Intent(base, MainActivity::class.java), FLAGS))
        }
        return builder.build()
    }
    companion object {
        const val CHANNEL = "downloads"
        const val COMPLETED_CHANNEL = "completed"
        const val CANCEL = "dev.ahmed.savlino.CANCEL_DOWNLOAD"
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private fun activeId(id: String) = id.hashCode() and Int.MAX_VALUE
        fun openIntent(progress: DownloadProgress) = Intent(Intent.ACTION_VIEW)
            .setDataAndType(progress.publishedUri, progress.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        fun shareIntent(progress: DownloadProgress): Intent = Intent.createChooser(Intent(Intent.ACTION_SEND)
            .setType(progress.mimeType).putExtra(Intent.EXTRA_STREAM, progress.publishedUri)
            .apply { clipData = ClipData.newRawUri("", progress.publishedUri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private fun languageOf(context: Context): String = context.resources.configuration.locales[0].language

        fun phase(context: Context, progress: DownloadProgress): String {
            if (progress.waiting && !progress.awaitingSchedule) return context.getString(R.string.download_waiting)
            if (progress.phase == DownloadPhase.DOWNLOADING && progress.stalled == true)
                return context.getString(R.string.download_waiting_for_data)
            // Every byte is in but the worker has not moved on yet: "Downloading" at 100% reads as stuck.
            if (progress.visiblePercent == 100)
                return context.getString(R.string.download_finishing)
            return context.getString(when (progress.phase) {
                DownloadPhase.PREPARING -> R.string.download_preparing
                DownloadPhase.DOWNLOADING -> R.string.download_transferring
                DownloadPhase.JOINING -> R.string.download_joining
                DownloadPhase.CONVERTING -> R.string.download_converting
                DownloadPhase.SAVING -> R.string.download_saving
                DownloadPhase.COMPLETED -> R.string.download_saved
                DownloadPhase.CANCELLED -> R.string.download_cancelled
                DownloadPhase.FAILED -> errorTitle(progress.error)
            })
        }

        fun text(context: Context, progress: DownloadProgress): String {
            val language = languageOf(context)
            val phase = phase(context, progress)
            return if (progress.phase == DownloadPhase.DOWNLOADING) {
                (listOf(phase) + transferDetails(context, progress)).joinToString(" · ")
            } else if (progress.phase == DownloadPhase.COMPLETED) {
                listOfNotNull(phase, progress.qualityHeight?.let { context.getString(R.string.video_quality,
                    NezzelFormat.count(it, language)) }, bytes(context, progress, language)).joinToString(" · ")
            } else phase
        }

        /** One presentation policy for the app and notifications; no estimation or Format.size fallback. */
        fun transferDetails(context: Context, progress: DownloadProgress): List<String> =
            transferParts(context, progress)?.run { listOfNotNull(percent, bytes, speed, remaining) }.orEmpty()

        /** The same policy as [transferDetails], by field, for layouts that place each number separately. */
        fun transferParts(context: Context, progress: DownloadProgress): TransferParts? {
            if (progress.phase != DownloadPhase.DOWNLOADING || progress.waiting) return null
            val language = languageOf(context)
            val rate = progress.bytesPerSecond?.takeIf {
                progress.stalled != true && progress.bytes?.let { bytes -> bytes >= 0 } == true &&
                    it.isFinite() && it > 0 && it <= Long.MAX_VALUE.toDouble()
            }
            val speed = rate?.let { speed(context, it, language) }
            val exact = progress.exactTransferTotal
            val remaining = progress.remainingTransferSeconds?.takeIf {
                speed != null && exact != null && exact > 0 && progress.bytes?.let { bytes -> bytes in 0 until exact } == true && it > 0
            }?.let { context.getString(R.string.download_transfer_remaining, NezzelFormat.isolate(NezzelFormat.duration(it))) }
            return TransferParts(
                progress.visiblePercent?.let { NezzelFormat.isolate(NezzelFormat.percent(it, language)) },
                bytes(context, progress, language),
                speed?.let(NezzelFormat::isolate),
                // A localized sentence: its duration is isolated inside; isolating the whole would force LTR.
                remaining,
            )
        }

        private fun speed(context: Context, rate: Double, language: String): String? {
            if (rate >= 1000) return context.getString(R.string.download_speed, NezzelFormat.size(rate.toLong(), language))
            // Keep fractional bytes/sec; truncating a valid small rate would fabricate 0 B/s.
            val amount = NezzelFormat.number(rate, language, 1)
            if (amount == "0") return null // Below display precision: omit rather than show a stopped rate.
            return context.getString(R.string.download_bytes_per_second, amount)
        }

        fun bytes(context: Context, progress: DownloadProgress, language: String = languageOf(context)): String {
            val downloaded = progress.bytes?.takeIf { it >= 0 }?.let { NezzelFormat.size(it, language) }
            if (progress.phase == DownloadPhase.DOWNLOADING && progress.exactTransferTotal == null) {
                return NezzelFormat.isolate(downloaded ?: context.getString(R.string.unknown_size))
            }
            val total = when (val size = progress.total) {
                is Size.Exact -> NezzelFormat.size(size.bytes, language)
                is Size.Estimated -> context.getString(R.string.download_estimate, NezzelFormat.size(size.bytes, language))
                Size.Unknown -> context.getString(R.string.unknown_size)
            }
            return NezzelFormat.isolate(if (downloaded == null || progress.phase == DownloadPhase.COMPLETED) total else "$downloaded / $total")
        }
        private fun errorTitle(error: DownloadError?) = when (error) {
            DownloadError.Network, DownloadError.Offline -> R.string.download_network_error
            DownloadError.InsufficientSpace -> R.string.download_space_error
            DownloadError.FolderUnavailable -> R.string.save_folder_error
            DownloadError.SaveFailed -> R.string.download_save_error
            DownloadError.FormatExpired -> R.string.download_expired_error
            DownloadError.Interrupted -> R.string.download_interrupted_error
            DownloadError.MergeFailed -> R.string.download_merge_error
            DownloadError.ExecutionRestricted -> R.string.download_restricted_error
            DownloadError.LoginRequired -> R.string.private_title
            DownloadError.SourceRefused -> R.string.source_refused_title
            DownloadError.DrmProtected -> R.string.protected_title
            DownloadError.Unsupported -> R.string.unsupported_title
            DownloadError.NoFormats -> R.string.no_formats_title
            DownloadError.NoVideo -> R.string.no_video_title
            DownloadError.Unavailable -> R.string.video_gone_title
            DownloadError.NotAPost -> R.string.not_a_post_title
            DownloadError.EngineUnavailable -> R.string.unavailable_title
            else -> R.string.download_failed
        }
    }
}

class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadNotifications.CANCEL) return
        val id = intent.getStringExtra(DownloadWorker.JOB_ID)?.takeIf(JobStore::validJobId) ?: return
        val app = context.applicationContext as NezzelApplication
        app.downloads.requestCancellation(id)
        val pending = goAsync()
        app.scope.launch { try { app.downloads.cancel(id) } finally { pending.finish() } }
    }
}
