package dev.ahmed.savlino.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.ahmed.savlino.BuildConfig
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.engine.mediaimage.ImageFailure

internal object ReportLink {
    const val WHATSAPP_NUMBER = "22237928327"

    val downloadErrors = setOf(DownloadError.Failed, DownloadError.Unsupported, DownloadError.Unavailable,
        DownloadError.NoFormats, DownloadError.NoVideo, DownloadError.NotAPost, DownloadError.SourceRefused)
    val imageFailures = setOf(ImageFailure.NO_MEDIA, ImageFailure.UNSUPPORTED, ImageFailure.UNAVAILABLE, ImageFailure.DECODE,
        ImageFailure.INVALID_IMAGE, ImageFailure.DIMENSIONS, ImageFailure.HTTP, ImageFailure.BUDGET)

    /** Link and details each sit on their own line, without bidi marks: those would stop WhatsApp linking the URL. */
    fun message(intro: String, sourceLink: String, versionName: String, engineVersion: String?, code: String): String =
        "$intro\n$sourceLink\nNezzel $versionName · engine ${engineVersion ?: "?"} · $code"

    /** Settings' "Message us": the version and Android release only, then a blank line for the user's own words. */
    fun contactMessage(intro: String, versionName: String, androidRelease: String): String =
        "$intro\nNezzel $versionName · Android $androidRelease\n\n"

    fun intent(message: String) = Intent(Intent.ACTION_VIEW,
        Uri.parse("https://wa.me/$WHATSAPP_NUMBER?text=" + Uri.encode(message)))

    fun open(context: Context, message: String) {
        try { context.startActivity(intent(message)) }
        catch (_: ActivityNotFoundException) { }
    }
}

@Composable
internal fun reportLinkAction(links: LinkViewModel): (() -> Unit)? {
    if (!links.canReportLink) return null
    val context = LocalContext.current
    val intro = stringResource(R.string.report_link_intro)
    return { links.reportMessage(intro, BuildConfig.VERSION_NAME)?.let { ReportLink.open(context, it) } }
}
