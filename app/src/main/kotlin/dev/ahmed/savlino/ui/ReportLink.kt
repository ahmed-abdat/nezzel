package dev.ahmed.savlino.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import dev.ahmed.savlino.BuildConfig
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import dev.ahmed.savlino.report.FailureReportSheet

internal object ReportLink {
    /** About's contact row only; failure reports go to the inbox after a preview. */
    const val WHATSAPP_NUMBER = "22237928327"

    val downloadErrors = setOf(DownloadError.Failed, DownloadError.Unsupported, DownloadError.Unavailable,
        DownloadError.NoFormats, DownloadError.NoVideo, DownloadError.NotAPost, DownloadError.SourceRefused)
    val imageFailures = setOf(ImageFailure.NO_MEDIA, ImageFailure.UNSUPPORTED, ImageFailure.UNAVAILABLE, ImageFailure.DECODE,
        ImageFailure.INVALID_IMAGE, ImageFailure.DIMENSIONS, ImageFailure.HTTP, ImageFailure.BUDGET)

    fun intent(message: String) = Intent(Intent.ACTION_VIEW,
        Uri.parse("https://wa.me/$WHATSAPP_NUMBER?text=" + Uri.encode(message)))

    fun open(context: Context, message: String) {
        try { context.startActivity(intent(message)) }
        catch (_: ActivityNotFoundException) { }
    }
}

/** The failure card's report button: [sent] once this failure was reported, [busy] while a Send is in flight. */
internal class ReportAction(val sent: Boolean, val busy: Boolean, val onClick: () -> Unit)

@Composable
internal fun reportLinkAction(links: LinkViewModel): ReportAction? {
    if (!links.canReportLink) return null
    var preview by rememberSaveable { mutableStateOf(false) }
    if (preview) FailureReportSheet({ links.failureReport(BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT) },
        onSend = { links.sendFailureReport(BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT) },
        onDismiss = { preview = false })
    return ReportAction(links.reportSent, links.reportSending) { preview = true }
}
