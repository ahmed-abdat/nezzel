package dev.ahmed.savlino.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.FakeEngine
import dev.ahmed.savlino.engine.InspectResult
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w400dp-h1200dp-mdpi")
class SizePresentationTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val source = LinkRevision(LinkInput.parse("https://example.org/media")!!, 1)
    private fun verify(format: Format, labelResource: Int?, rough: Long? = null) {
        val context = RuntimeEnvironment.getApplication()
        val language = context.resources.configuration.locales[0].language
        val inspection = Inspection(source, "v1", listOf(format),
            roughSizeBytes = rough?.let { mapOf(format.id to it) }.orEmpty())
        val vm = LinkViewModel(SavedStateHandle(), engine = FakeEngine(InspectResult.Success(inspection)), currentEngineVersion = { "v1" })
        vm.edit(source.link.rawUrl); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle(); vm.selectFormat(format.id)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent { NezzelTheme(ThemePref.Light, false) { MoreFormatsScreen(vm, language) } }
            compose.waitForIdle()
            assertTrue(vm.optionsState is OptionsState.Ready)
            assertEquals(format, vm.selectedFormat)
            val bytes = when (val size = format.size) { is Size.Exact -> size.bytes; is Size.Estimated -> size.bytes; Size.Unknown -> rough }
            val label = if (bytes == null) context.getString(R.string.unknown_size) else {
                val amount = NezzelFormat.size(bytes, language)
                NezzelFormat.isolate(labelResource?.let { context.getString(it, amount) } ?: amount)
            }
            compose.onNodeWithText(label, useUnmergedTree = true).assertExists()
            val cta = if (bytes == null) context.getString(R.string.download) else context.getString(R.string.download_size, label)
            compose.onNodeWithText(cta).assertHasClickAction()
            assertEquals("Presentation must not change confirmed size", format.size, vm.selectedFormat!!.size)
        } finally { controller.pause().stop().destroy() }
    }
    private fun merged() = Format("v", "mp4", true, false, height = 720, size = Size.Exact(100_000_000), audioFormatId = "a", requiresMerge = true)
    private fun direct() = Format("v", "mp4", true, true, height = 720, size = Size.Exact(100_000_000))
    private fun extracted() = Format("nezzel:m4a:v", "m4a", false, true, size = Size.Exact(100_000_000), sourceFormatId = "v")
    private fun converted() = Format("nezzel:mp3:128:a", "mp3", false, true, size = Size.Exact(8_000_000), sourceFormatId = "a", conversion = AudioConversion.MP3_128)
    @Test fun exactMergedInputsShowApproximateOutputInRowAndCtaEnglish() = verify(merged(), R.string.estimated_size)
    @Test @Config(qualifiers = "ar-w400dp-h1200dp-mdpi") fun exactMergedInputsShowApproximateOutputInRowAndCtaArabic() = verify(merged(), R.string.estimated_size)
    @Test fun directExactFileRetainsExactLabelEnglish() = verify(direct(), null)
    @Test @Config(qualifiers = "ar-w400dp-h1200dp-mdpi") fun directExactFileRetainsExactLabelArabic() = verify(direct(), null)
    @Test fun exactExtractedSourceNeverPromisesExactOutputEnglish() = verify(extracted(), R.string.estimated_size)
    @Test @Config(qualifiers = "ar-w400dp-h1200dp-mdpi") fun exactExtractedSourceNeverPromisesExactOutputArabic() = verify(extracted(), R.string.estimated_size)
    @Test fun exactConversionSourceNeverPromisesExactOutputEnglish() = verify(converted(), R.string.estimated_size)
    @Test @Config(qualifiers = "ar-w400dp-h1200dp-mdpi") fun exactConversionSourceNeverPromisesExactOutputArabic() = verify(converted(), R.string.estimated_size)
    @Test fun derivedAudioRoughLabelEnglish() = verify(extracted().copy(size = Size.Unknown), R.string.rough_size, 2_000_000)
    @Test @Config(qualifiers = "ar-w400dp-h1200dp-mdpi") fun derivedAudioRoughLabelArabic() = verify(extracted().copy(size = Size.Unknown), R.string.rough_size, 2_000_000)
    @Test fun derivedAudioUnknownRemainsUnknownEnglish() = verify(extracted().copy(size = Size.Unknown), null)
    @Test @Config(qualifiers = "ar-w400dp-h1200dp-mdpi") fun derivedAudioUnknownRemainsUnknownArabic() = verify(extracted().copy(size = Size.Unknown), null)
}
