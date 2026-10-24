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
import dev.ahmed.savlino.usage.UsageEvents
import dev.ahmed.savlino.usage.UsageKeys
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

    /** Records what the view model hands to the automatic reporter. */
    private class Recorder : UsageEvents {
        val counts = mutableListOf<String>()
        val failures = mutableListOf<List<String?>>()
        override fun count(key: String) { counts += key }
        override fun failed(stage: String, code: String, link: String?, engineVersion: String?) {
            failures += listOf(stage, code, link, engineVersion)
        }
    }

    private fun failing(error: DownloadError, usage: Recorder, engineVersion: String? = "fixture-engine") = LinkViewModel(SavedStateHandle(),
        engine = FakeEngine(InspectResult.Failure(error)), currentEngineVersion = { engineVersion }, usage = usage).also {
        it.edit(link); it.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(OptionsState.Error(error), it.optionsState)
    }

    @Test fun eligibleReadFailuresAreReportedOnceWithTheExactLinkAndEngine() {
        val usage = Recorder()
        val vm = failing(DownloadError.Failed, usage)
        assertEquals(listOf(listOf(Report.OPTIONS, "Failed", link, "fixture-engine")), usage.failures)
        assertTrue(UsageKeys.FUNNEL_LINK in usage.counts)
        vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, usage.failures.size) // A new failure card is a new report.
        val offline = Recorder()
        failing(DownloadError.Offline, offline)
        assertTrue(offline.failures.isEmpty())
    }

    @Test fun imageFailureUsesItsEnumNameAndUnknownEngineWithoutProbing() {
        val usage = Recorder()
        val vm = LinkViewModel(SavedStateHandle(), images = FakeImages {
            MediaInspectResult.Failure(ImageFailure.DECODE, ImagePhase.DECODE)
        }, usage = usage)
        vm.edit("https://example.com/photo.jpg"); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(OptionsState.ImageError(ImageFailure.DECODE, phase = ImagePhase.DECODE, directImage = true), vm.optionsState)
        assertEquals(listOf(listOf(Report.OPTIONS, "DECODE", "https://example.com/photo.jpg", null)), usage.failures)
    }

    @Test fun refusedRedirectReportsTheReviewedLinkRatherThanTheRedirect() {
        val reviewed = "https://example.com/photo.jpg?value=%2f&other=two#part"
        val usage = Recorder()
        val vm = LinkViewModel(SavedStateHandle(), images = FakeImages { source ->
            MediaInspectResult.VideoRedirect(LinkRevision(LinkInput.parse("https://example.org/resolved/watch")!!, source.revision))
        }, usage = usage)
        vm.edit(reviewed); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(OptionsState.ImageError(ImageFailure.UNSUPPORTED), vm.optionsState)
        assertEquals(reviewed, usage.failures.single()[2])
    }
}
