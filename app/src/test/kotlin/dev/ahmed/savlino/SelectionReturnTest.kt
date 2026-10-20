package dev.ahmed.savlino

import android.content.res.Configuration
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.*
import dev.ahmed.savlino.ui.theme.NezzelTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SelectionReturnTest {
    private val formats = listOf(
        Format("1080", "mp4", true, true, height = 1080, size = Size.Exact(100_000_000)),
        Format("720", "mp4", true, true, height = 720, size = Size.Exact(68_000_000)),
        Format("360", "mp4", true, true, height = 360, size = Size.Exact(20_000_000)),
        Format("a", "m4a", false, true, bitrateKbps = 128, size = Size.Exact(8_000_000)),
        Format("480", "mp4", true, true, height = 480, size = Size.Exact(30_000_000)),
        Format("720-other", "mkv", true, true, height = 720, size = Size.Exact(50_000_000)),
        Format("a-other", "m4a", false, true, bitrateKbps = 96, size = Size.Exact(6_000_000)),
        Format("mp3", "mp3", false, true, conversion = AudioConversion.MP3_128,
            sourceFormatId = "a", size = Size.Estimated(4_000_000)))
    private val sizes = listOf("100", "68", "20", "8", "30", "50", "6", "4").map { "$it\u00a0MB" }

    @Test fun sharedVideoAndAudioRowsStaySelectedAfterReturningEnglish() = returnedSelection("en", 0..3)
    @Test @Config(qualifiers = "ar")
    fun sharedVideoAndAudioRowsStaySelectedAfterReturningArabic() = returnedSelection("ar", 0..3)
    @Test fun outsideVideoAndAudioChoicesStayVisibleAfterReturningEnglish() = returnedSelection("en", 4..7)
    @Test @Config(qualifiers = "ar")
    fun outsideVideoAndAudioChoicesStayVisibleAfterReturningArabic() = returnedSelection("ar", 4..7)

    private fun returnedSelection(language: String, choices: IntRange) {
        choices.forEach { index ->
            val source = LinkRevision(LinkInput.parse("https://youtube.com/watch?v=one")!!, 1)
            val inspection = Inspection(source, "fixture-v1", formats, listOf(
                PresetChoice(Preset.BEST, "1080"), PresetChoice(Preset.BALANCED, "720"),
                PresetChoice(Preset.SMALL, "360"), PresetChoice(Preset.AUDIO_M4A, "a")))
            val engine = FakeEngine(InspectResult.Success(inspection))
            val vm = LinkViewModel(SavedStateHandle(), engine = engine, currentEngineVersion = { "fixture-v1" })
            vm.edit(source.link.rawUrl); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            val clock = BroadcastFrameClock()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
            val recomposer = Recomposer(scope.coroutineContext)
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            try {
                val activity = controller.get()
                val configuration = Configuration(activity.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag("$language-u-nu-latn"))
                }
                val context = activity.createConfigurationContext(configuration)
                activity.setContent(parent = recomposer) {
                    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                        LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        NezzelTheme(ThemePref.Light, false) {
                            if (vm.moreFormats) MoreFormatsScreen(vm, language)
                            else OptionsSheetContent(vm, language, vm::dismissOptions)
                        }
                    }
                }
                fun nodes(): List<SemanticsNode> {
                    repeat(4) {
                        Snapshot.sendApplyNotifications(); clock.sendFrame(System.nanoTime())
                        semanticsNodes(activity, rounds = 1)
                    }
                    return semanticsNodes(activity, rounds = 1)
                }
                click(single("All qualities button", nodes().filter { it.config.getOrNull(SemanticsActions.OnClick) != null &&
                    texts(descendants(it)).contains(context.getString(R.string.all_formats)) }))
                assertTrue(vm.moreFormats)
                var full = nodes()
                if (rows(full, sizes[index]).isEmpty()) {
                    click(single("Other options button", full.filter { node -> node.config.getOrNull(SemanticsActions.OnClick) != null &&
                        texts(descendants(node)).any { it.startsWith(context.getString(R.string.other_versions, "").substringBefore('(')) } }))
                    full = nodes()
                }
                click(single("All qualities row ${formats[index].id}", rows(full, sizes[index])))
                assertEquals(formats[index], vm.selectedFormat)
                assertNull(vm.takeConfirmedCommand())
                assertTrue(engine.commands.isEmpty())
                click(single("Back button", nodes().filter { it.config.getOrNull(SemanticsActions.OnClick) != null &&
                    descendants(it).any { child -> child.config.getOrNull(SemanticsProperties.ContentDescription)
                        .orEmpty().contains(context.getString(R.string.back)) } }))
                assertFalse(vm.moreFormats)
                val sheet = nodes()
                val row = rows(sheet, sizes[index]).singleOrNull()
                assertNotNull("Returned choice ${formats[index].id} must have a sheet row", row)
                // This selected value drives both FormatRow's radio/highlight and TalkBack.
                assertEquals(true, row!!.config.getOrNull(SemanticsProperties.Selected))
                assertTrue(row.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains(sizes[index]) })
                assertEquals(1, sheet.count { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                    it.config.getOrNull(SemanticsProperties.Selected) == true })
                assertTrue(vm.downloadEnabled)
                assertNull(vm.takeConfirmedCommand())
                val download = sheet.single { it.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                    texts(descendants(it)).any { text -> text.startsWith(context.getString(R.string.download)) } }
                assertNull(download.config.getOrNull(SemanticsProperties.Disabled))
                click(download)
                assertEquals(formats[index], vm.takeConfirmedCommand()!!.format)
                assertNull(vm.takeConfirmedCommand())
            } finally { vm.dismissOptions(); recomposer.cancel(); scope.cancel(); controller.pause().stop().destroy() }
        }
    }

    private fun rows(nodes: List<SemanticsNode>, size: String) = nodes.filter {
        it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
            texts(descendants(it)).any { text -> text.filter { c -> c != '\u2066' && c != '\u2069' }
                .removePrefix("≈").trim() == size }
    }
    private fun click(node: SemanticsNode) {
        assertEquals(true, node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke())
    }
    private fun single(label: String, nodes: List<SemanticsNode>): SemanticsNode {
        assertEquals(label, 1, nodes.size)
        return nodes.single()
    }
}
