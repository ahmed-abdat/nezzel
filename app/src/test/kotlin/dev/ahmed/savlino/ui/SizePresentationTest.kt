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
    /** Owner 2026-10-25: only an exact file size shows, with no "≈"; any estimate or unknown size shows none. */
    private fun verify(format: Format, shown: Boolean, rough: Long? = null) {
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
            val amount = bytes?.let { NezzelFormat.size(it, language) }
            if (shown) {
                val label = NezzelFormat.isolate(amount!!)
                compose.onNodeWithText(label, useUnmergedTree = true).assertExists()
                compose.onNodeWithText(context.getString(R.string.download_size, label)).assertHasClickAction()
            } else {
                amount?.let { compose.onNodeWithText(it, substring = true, useUnmergedTree = true).assertDoesNotExist() }
                compose.onNodeWithText(context.getString(R.string.unknown_size), useUnmergedTree = true).assertDoesNotExist()
                compose.onNodeWithText(context.getString(R.string.download)).assertHasClickAction()
            }
            compose.onNodeWithText("≈", substring = true, useUnmergedTree = true).assertDoesNotExist()
            assertEquals("Presentation must not change confirmed size", format.size, vm.selectedFormat!!.size)
        } finally { controller.pause().stop().destroy() }
    }
    private fun merged() = Format("v", "mp4", true, false, height = 720, size = Size.Exact(100_000_000), audioFormatId = "a", requiresMerge = true)
    private fun direct() = Format("v", "mp4", true, true, height = 720, size = Size.Exact(100_000_000))
    private fun extracted() = Format("nezzel:m4a:v", "m4a", false, true, size = Size.Exact(100_000_000), sourceFormatId = "v")
    private fun converted() = Format("nezzel:mp3:128:a", "mp3", false, true, size = Size.Exact(8_000_000), sourceFormatId = "a", conversion = AudioConversion.MP3_128)
    @Test fun sizeLabelFollowsExactFileOnly() {
        data class Case(val qualifiers: String, val shown: Boolean, val format: () -> Format, val rough: Long? = null)
        listOf(
            Case("en-w400dp-h1200dp-mdpi", false, ::merged),
            Case("ar-w400dp-h1200dp-mdpi", false, ::merged),
            Case("en-w400dp-h1200dp-mdpi", true, ::direct),
            Case("ar-w400dp-h1200dp-mdpi", true, ::direct),
            Case("en-w400dp-h1200dp-mdpi", false, ::extracted),
            Case("ar-w400dp-h1200dp-mdpi", false, ::extracted),
            Case("en-w400dp-h1200dp-mdpi", false, ::converted),
            Case("ar-w400dp-h1200dp-mdpi", false, ::converted),
            Case("en-w400dp-h1200dp-mdpi", false, { extracted().copy(size = Size.Unknown) }, 2_000_000),
            Case("ar-w400dp-h1200dp-mdpi", false, { extracted().copy(size = Size.Unknown) }, 2_000_000),
            Case("en-w400dp-h1200dp-mdpi", false, { extracted().copy(size = Size.Unknown) }),
            Case("ar-w400dp-h1200dp-mdpi", false, { extracted().copy(size = Size.Unknown) }),
        ).forEach { case ->
            RuntimeEnvironment.setQualifiers(case.qualifiers)
            verify(case.format(), case.shown, case.rough)
        }
    }
}
