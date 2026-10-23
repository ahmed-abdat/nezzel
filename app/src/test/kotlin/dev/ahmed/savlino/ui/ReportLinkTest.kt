package dev.ahmed.savlino.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.R
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

    @Test fun englishMessageKeepsTheReviewedLinkVerbatim() {
        val intro = context("en").getString(R.string.report_link_intro)
        assertEquals("Nezzel couldn't read this link:", intro)
        assertEquals(listOf(intro, link, "Nezzel 1.2.3 · engine 2026.01.01 · Failed"),
            ReportLink.message(intro, link, "1.2.3", "2026.01.01", "Failed").lines())
    }

    @Test fun arabicMessageKeepsTheLinkLinkable() {
        val intro = context("ar").getString(R.string.report_link_intro)
        assertEquals("لم يستطع Nezzel قراءة هذا الرابط:", intro)
        assertEquals(listOf(intro, link, "Nezzel 1.2.3 · engine 2026.01.01 · NO_MEDIA"),
            ReportLink.message(intro, link, "1.2.3", "2026.01.01", "NO_MEDIA").lines())
        assertEquals("أبلغ عن هذا الرابط", context("ar").getString(R.string.report_link))
    }

    @Test fun unknownEngineVersionIsAQuestionMark() {
        val message = ReportLink.message(context("en").getString(R.string.report_link_intro), link, "1.2.3", null, "Unavailable")
        assertEquals("Nezzel 1.2.3 · engine ? · Unavailable", message.lines().last())
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

    @Test fun viewIntentEncodesTheWholeMessageAsOneQueryValue() {
        val message = ReportLink.message(context("ar").getString(R.string.report_link_intro), link, "1.2.3", null, "HTTP")
        val intent = ReportLink.intent(message)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://wa.me/${ReportLink.WHATSAPP_NUMBER}?text=" + Uri.encode(message), intent.dataString)
        assertEquals(message, intent.data!!.getQueryParameter("text"))
        assertEquals(setOf("text"), intent.data!!.queryParameterNames)
        assertTrue(intent.dataString!!.contains("%0A"))
        assertNull(intent.data!!.fragment)
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

    private fun failing(error: DownloadError, engineVersion: String? = "fixture-engine") = LinkViewModel(SavedStateHandle(),
        engine = FakeEngine(InspectResult.Failure(error)), currentEngineVersion = { engineVersion }).also {
        it.edit(link); it.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(OptionsState.Error(error), it.optionsState)
    }

    @Test fun viewModelReportsTheOriginalLinkAndCachedEngineVersionOnlyForEligibleFailures() {
        val vm = failing(DownloadError.Failed)
        assertTrue(vm.canReportLink)
        assertEquals(listOf("intro", link, "Nezzel 1.2.3 · engine fixture-engine · Failed"),
            vm.reportMessage("intro", "1.2.3")!!.lines())
        val offline = failing(DownloadError.Offline)
        assertFalse(offline.canReportLink)
        assertNull(offline.reportMessage("intro", "1.2.3"))
        vm.edit("")
        assertFalse(vm.canReportLink)
        assertNull(vm.reportMessage("intro", "1.2.3"))
    }

    @Test fun imageFailureUsesItsEnumNameAndUnknownEngineWithoutProbing() {
        val vm = LinkViewModel(SavedStateHandle(), images = FakeImages {
            MediaInspectResult.Failure(ImageFailure.DECODE, ImagePhase.DECODE)
        })
        vm.edit("https://example.com/photo.jpg"); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(OptionsState.ImageError(ImageFailure.DECODE, phase = ImagePhase.DECODE, directImage = true), vm.optionsState)
        assertTrue(vm.canReportLink)
        assertEquals("Nezzel 1.2.3 · engine ? · DECODE", vm.reportMessage("intro", "1.2.3")!!.lines().last())
    }

    @Test fun refusedRedirectReportsTheReviewedLinkRatherThanTheRedirect() {
        val reviewed = "https://example.com/photo.jpg?value=%2f&other=two#part"
        val vm = LinkViewModel(SavedStateHandle(), images = FakeImages { source ->
            MediaInspectResult.VideoRedirect(LinkRevision(LinkInput.parse("https://example.com/resolved/watch")!!, source.revision))
        })
        vm.edit(reviewed); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(OptionsState.ImageError(ImageFailure.UNSUPPORTED), vm.optionsState)
        assertEquals(reviewed, vm.reportMessage("intro", "1.2.3")!!.lines()[1])
    }
}
