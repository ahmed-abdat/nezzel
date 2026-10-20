package dev.ahmed.savlino.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.EngineAdapter
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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w320dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActualFormatsTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val source = LinkRevision(LinkInput.parse("https://example.org/media")!!, 1)
    private val first = Format("video-one", "mp4", true, true, videoCodec = "avc1.4d401f", audioCodec = "aac", height = 720, bitrateKbps = 1000)
    private val second = first.copy(id = "video-two", sourceFormatId = "video-two", hasAudio = false, audioCodec = null, audioFormatId = "audio-two", requiresMerge = true)
    private val audio = Format("audio-one", "m4a", false, true, audioCodec = "aac", bitrateKbps = 128)
    private val rows = listOf(first, second, audio, audio.copy(id = "audio-two", sourceFormatId = "audio-two"),
        Format("convert128", "mp3", false, true, conversion = AudioConversion.MP3_128, sourceFormatId = audio.id, sourceContainer = "m4a"),
        Format("convert320", "mp3", false, true, conversion = AudioConversion.MP3_320, sourceFormatId = audio.id, sourceContainer = "m4a"))
    private fun renderAndChoose() {
        RuntimeEnvironment.setFontScale(2f)
        val context = RuntimeEnvironment.getApplication()
        val language = context.resources.configuration.locales[0].language
        val fake = FakeEngine(InspectResult.Success(Inspection(source, "v1", rows,
            listOf(PresetChoice(Preset.BALANCED, first.id)))))
        var inspections = 0
        val counting = object : EngineAdapter by fake {
            override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
                inspections++
                return fake.inspect(source, operationId)
            }
        }
        val vm = LinkViewModel(SavedStateHandle(), engine = counting, currentEngineVersion = { "v1" })
        vm.edit(source.link.rawUrl); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle(); vm.showMoreFormats()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent { NezzelTheme(ThemePref.Light, false) { MoreFormatsScreen(vm, language) } }
            compose.waitForIdle()
            // Simple list first: one plain row per quality, one audio and one MP3; no codec or source facts.
            compose.onAllNodes(isSelectable()).assertCountEquals(3)
            compose.onNode(isSelectable() and hasText(context.getString(R.string.audio_only), substring = true) and
                hasText("M4A", substring = true)).assertExists()
            compose.onAllNodes(hasText("avc1", substring = true), useUnmergedTree = true).assertCountEquals(0)
            compose.onNodeWithText(context.getString(R.string.other_versions, NezzelFormat.count(3, language)))
                .performScrollTo().performClick()
            compose.onAllNodes(isSelectable()).assertCountEquals(6)
            val descriptions = compose.onAllNodes(isSelectable()).fetchSemanticsNodes().map { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
            }
            assertEquals("Every rendition must have a distinct description", 6, descriptions.toSet().size)
            for (id in listOf("video-two", "audio-two")) {
                val sourceLabel = context.getString(R.string.format_source_detail, NezzelFormat.isolate(id))
                val container = if (id.startsWith("audio")) "M4A" else "MP4"
                compose.onNode(isSelectable() and hasText(sourceLabel, substring = true) and
                    hasText(container, substring = true)).assertExists()
            }
            val secondRow = compose.onNode(isSelectable() and hasText("video-two", substring = true))
            secondRow.performScrollTo().assertHasClickAction()
            val bounds = secondRow.fetchSemanticsNode().boundsInRoot
            val density = context.resources.displayMetrics.density
            assertTrue("48dp row target", bounds.height / density >= 48f)
            assertTrue("Row fits narrow screen", bounds.left >= -.5f && bounds.right <= context.resources.displayMetrics.widthPixels + .5f)
            val detail = descriptions.single { it.contains("video-two") }
            assertTrue("Actual video codec", detail.contains("avc1.4d401f"))
            assertTrue("Actual bitrate", detail.contains("1000"))
            assertTrue("Exact audio merge partner", detail.contains("audio-two"))
            compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
                val results = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                // hasVisualOverflow flags wrap-content text whose paragraph was laid out at the
                // full max width; real clipping is height overflow, a hit line limit, or a
                // box narrower than the text's longest unbreakable run.
                results.forEach { assertFalse("Text must fit at200%: ${it.layoutInput.text}", it.didOverflowHeight ||
                    it.multiParagraph.didExceedMaxLines || it.multiParagraph.minIntrinsicWidth > it.size.width + 1f) }
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().forEach { text ->
                    assertFalse("Use Latin digits in either locale", text.text.any { it in '\u0660'..'\u0669' })
                }
            }
            secondRow.performClick()
            assertEquals("video-two", vm.selectedFormat?.id)
            // Hiding works even while the current choice sits among the other options; it stays chosen.
            compose.onNodeWithText(context.getString(R.string.other_versions_hide)).performScrollTo().performClick()
            compose.onAllNodes(isSelectable()).assertCountEquals(3)
            assertEquals("video-two", vm.selectedFormat?.id)
            assertEquals("audio-two", vm.selectedFormat?.audioFormatId)
            assertNull(vm.takeConfirmedCommand()); assertTrue(fake.commands.isEmpty()); assertEquals(1, inspections)
            val download = compose.onNodeWithText(context.getString(R.string.download))
            download.assertHasClickAction()
            val ctaBounds = download.fetchSemanticsNode().boundsInRoot
            assertTrue("48dp confirmation target", ctaBounds.height / density >= 48f)
            assertTrue("Confirmation fits320dp", ctaBounds.left >= -.5f && ctaBounds.right <= context.resources.displayMetrics.widthPixels + .5f)
            download.performClick()
            val command = vm.takeConfirmedCommand()!!
            assertEquals(second, command.format)
            assertNull(vm.takeConfirmedCommand()); assertTrue(fake.commands.isEmpty()); assertEquals(1, inspections)
        } finally { controller.pause().stop().destroy(); RuntimeEnvironment.setFontScale(1f) }
    }
    private fun conversionSizeLabel(rough: Long?) {
        val context = RuntimeEnvironment.getApplication()
        val language = context.resources.configuration.locales[0].language
        val row = rows.last()
        val vm = LinkViewModel(SavedStateHandle(), engine = FakeEngine(InspectResult.Success(
            Inspection(source, "v1", listOf(row), roughSizeBytes = rough?.let { mapOf(row.id to it) }.orEmpty()))),
            currentEngineVersion = { "v1" })
        vm.edit(source.link.rawUrl); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle(); vm.selectFormat(row.id)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent { NezzelTheme(ThemePref.Light, false) { MoreFormatsScreen(vm, language) } }
            compose.waitForIdle()
            val expected = if (rough == null) context.getString(R.string.unknown_size)
                else NezzelFormat.isolate(context.getString(R.string.rough_size, NezzelFormat.size(rough, language)))
            compose.onNodeWithText(expected, useUnmergedTree = true).assertExists()
            assertEquals(Size.Unknown, vm.selectedFormat!!.size)
            assertTrue(vm.downloadEnabled)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun selectedConversionRoughSizeIsExplicitlyAnEstimateEnglish() = conversionSizeLabel(420000L)
    @Test @Config(qualifiers = "ar-w320dp-h1600dp-mdpi")
    fun selectedConversionUnknownDurationStaysUnknownArabic() = conversionSizeLabel(null)
    @Test fun englishAllActualRowsFitAndSecondSameHeightClickConfirmsExactPartner() = renderAndChoose()
    @Test @Config(qualifiers = "ar-w320dp-h1600dp-mdpi")
    fun arabicAllActualRowsFitAndSecondSameHeightClickConfirmsExactPartner() = renderAndChoose()
}
