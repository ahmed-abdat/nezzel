package dev.ahmed.savlino.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.engine.mediaimage.ImageFailure

internal object ReportLink {
    /** About's contact row only; failures in these sets are reported to the inbox automatically (UsageReporter). */
    const val WHATSAPP_NUMBER = "22237928327"

    val downloadErrors = setOf(DownloadError.Failed, DownloadError.Unsupported, DownloadError.Unavailable,
        DownloadError.NoFormats, DownloadError.NoVideo, DownloadError.NotAPost, DownloadError.SourceRefused)
    val imageFailures = setOf(ImageFailure.NO_MEDIA, ImageFailure.UNSUPPORTED, ImageFailure.UNAVAILABLE, ImageFailure.DECODE,
        ImageFailure.INVALID_IMAGE, ImageFailure.DIMENSIONS, ImageFailure.HTTP, ImageFailure.BUDGET)

    fun intent(message: String) = Intent(Intent.ACTION_VIEW,
        Uri.parse("https://wa.me/$WHATSAPP_NUMBER?text=" + Uri.encode(message)))

    fun open(context: Context, message: String) {
        try { context.startActivity(intent(message)) }
        catch (_: ActivityNotFoundException) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
            try { context.startActivity(Intent.createChooser(send, null)) }
            catch (_: ActivityNotFoundException) { Toast.makeText(context, R.string.share_unavailable, Toast.LENGTH_SHORT).show() }
            catch (_: SecurityException) { Toast.makeText(context, R.string.share_unavailable, Toast.LENGTH_SHORT).show() }
        }
    }
}
