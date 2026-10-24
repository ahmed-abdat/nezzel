package dev.ahmed.savlino.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.R
import dev.ahmed.savlino.report.Report
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.domain.LinkInput
import dev.ahmed.savlino.domain.LinkRevision
import dev.ahmed.savlino.engine.FakeEngine
import dev.ahmed.savlino.engine.InspectResult
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import dev.ahmed.savlino.engine.mediaimage.ImagePhase
import dev.ahmed.savlino.engine.mediaimage.MediaInspectResult
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReportLinkTest {
    private val link = "https://EXAMPLE.com/share/post?value=%2f%2B&text=hello+world#part"
    private fun context(language: String): Context {
        val app = RuntimeEnvironment.getApplication()
        return app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        })
    }

    @Test fun reportLabelsExistInEveryLanguage() {
        assertEquals("أبلغ عن هذا الرابط", context("ar").getString(R.string.report_link))
        assertEquals("Sent", context("en").getString(R.string.report_sent))
    }

    @Test fun downloadEligibilityMatchesTheCompleteAllowlist() {
        val expected = setOf(DownloadError.Failed, DownloadError.Unsupported, DownloadError.Unavailable,
            DownloadError.NoFormats, DownloadError.NoVideo, DownloadError.NotAPost, DownloadError.SourceRefused)
        for (error in DownloadError.entries) assertEquals(error.name, error in expected, error in ReportLink.downloadErrors)
    }

    @Test fun imageEligibilityMatchesTheCompleteAllowlist() {
        val expected = setOf(ImageFailure.NO_MEDIA, ImageFailure.UNSUPPORTED, ImageFailure.UNAVAILABLE, ImageFailure.DECODE,
            ImageFailure.INVALID_IMAGE, ImageFailure.DIMENSIONS, ImageFailure.HTTP, ImageFailure.BUDGET)
        for (error in ImageFailure.entries) assertEquals(error.name, error in expected, error in ReportLink.imageFailures)
    }

    @Test fun contactMessageIsOnlyTheGreetingAndStartsInTheUserLanguage() {
        listOf("en", "ar", "fr").forEach { language ->
            val message = context(language).getString(R.string.contact_whatsapp_intro)
            // Owner 2026-10-23: a friendly hello + thanks only, no app or Android version.
            assertEquals(1, message.lines().size)
            assertFalse(message.any(Char::isDigit))
            assertFalse(message.contains('\u2066') || message.contains('\u2069'))
            assertEquals(message, ReportLink.intent(message).data!!.getQueryParameter("text"))
        }
        // Arabic starts with an Arabic word, so WhatsApp lays the chat bubble out right to left.
        assertTrue(Character.getDirectionality(context("ar").getString(R.string.contact_whatsapp_intro)[0]) ==
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC)
    }

    @Test fun missingViewHandlerIsSilentlyIgnored() {
        var attempts = 0
        val context = object : ContextWrapper(context("en")) {
            override fun startActivity(intent: Intent) {
                attempts++
                throw ActivityNotFoundException()
            }
        }
        ReportLink.open(context, "message")
        assertEquals(1, attempts)
    }

    private fun failing(error: DownloadError, engineVersion: String? = "fixture-engine",
                        send: suspend (Report) -> Boolean = { false }) = LinkViewModel(SavedStateHandle(),
        engine = FakeEngine(InspectResult.Failure(error)), currentEngineVersion = { engineVersion }, sendReport = send).also {
        it.edit(link); it.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(OptionsState.Error(error), it.optionsState)
    }

    @Test fun viewModelReportsTheSiteAndCachedEngineVersionOnlyForEligibleFailures() {
        val vm = failing(DownloadError.Failed)
        assertTrue(vm.canReportLink)
        val report = vm.failureReport("1.2.3", 35)!!
        assertEquals("example.com", report.site)
        assertEquals("Failed", report.code)
        assertEquals("fixture-engine", report.engineVersion)
        assertEquals(link, report.link)
        val offline = failing(DownloadError.Offline)
        assertFalse(offline.canReportLink)
        assertNull(offline.failureReport("1.2.3", 35))
        vm.edit("")
        assertFalse(vm.canReportLink)
        assertNull(vm.failureReport("1.2.3", 35))
    }

    @Test fun imageFailureUsesItsEnumNameAndUnknownEngineWithoutProbing() {
        val vm = LinkViewModel(SavedStateHandle(), images = FakeImages {
            MediaInspectResult.Failure(ImageFailure.DECODE, ImagePhase.DECODE)
        })
        vm.edit("https://example.com/photo.jpg"); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(OptionsState.ImageError(ImageFailure.DECODE, phase = ImagePhase.DECODE, directImage = true), vm.optionsState)
        assertTrue(vm.canReportLink)
        val report = vm.failureReport("1.2.3", 35)!!
        assertEquals("DECODE", report.code)
        assertNull(report.engineVersion)
    }

    @Test fun refusedRedirectReportsTheReviewedLinkRatherThanTheRedirect() {
        val reviewed = "https://example.com/photo.jpg?value=%2f&other=two#part"
        val vm = LinkViewModel(SavedStateHandle(), images = FakeImages { source ->
            MediaInspectResult.VideoRedirect(LinkRevision(LinkInput.parse("https://example.org/resolved/watch")!!, source.revision))
        })
        vm.edit(reviewed); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(OptionsState.ImageError(ImageFailure.UNSUPPORTED), vm.optionsState)
        assertEquals(reviewed, vm.failureReport("1.2.3", 35)!!.link)
        assertEquals("example.com", vm.failureReport("1.2.3", 35)!!.site)
    }

    @Test fun oneAcceptedReportPerFailureCard() {
        val sent = mutableListOf<Report>()
        val vm = failing(DownloadError.Failed) { sent += it; true }
        vm.sendFailureReport("1.2.3", 35); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, sent.size)
        assertEquals(link, sent.single().link)
        assertTrue(vm.reportSent)
        assertEquals(true, vm.reportOutcome)
        vm.consumeReportOutcome()
        assertNull(vm.sendFailureReport("1.2.3", 35))
        assertEquals(1, sent.size)
        // A new failure is a new card with its own report.
        vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        assertFalse(vm.reportSent)
    }

    @Test fun failedSendSaysSoAndLeavesTheButtonForALaterTry() {
        var attempts = 0
        val vm = failing(DownloadError.Unsupported) { attempts++; false }
        vm.sendFailureReport("1.2.3", 35); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, attempts)
        assertEquals(false, vm.reportOutcome)
        assertFalse(vm.reportSent)
        assertFalse(vm.reportSending)
    }
}
