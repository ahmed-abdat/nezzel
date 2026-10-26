package dev.ahmed.savlino

import android.content.res.Configuration
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.*
import dev.ahmed.savlino.ui.theme.NezzelTheme
import kotlinx.coroutines.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SelectionSemanticsTest {
    private val url = "https://youtube.com/watch?v=one"
    private val formats = listOf(
        Format("720", "mp4", true, true, height = 720, size = Size.Exact(68_000_000)),
        Format("1080", "mp4", true, false, height = 1080, requiresMerge = true, audioFormatId = "a"),
        Format("a", "m4a", false, true, bitrateKbps = 128, size = Size.Estimated(8_100_000)),
        Format("mp3", "mp3", false, true, conversion = AudioConversion.MP3_128, sourceFormatId = "a"))
    private fun vm(rough: Boolean = false): LinkViewModel {
        val inspection = Inspection(LinkRevision(LinkInput.parse(url)!!, 1), "fixture-v1", formats,
            listOf(PresetChoice(Preset.BALANCED, "720"), PresetChoice(Preset.BEST, "1080"), PresetChoice(Preset.AUDIO_M4A, "a")),
            title = "رحلة بحرية 🌊", uploader = "قناة البحر", durationSeconds = 225, roughSizeBytes = if (rough) mapOf("1080" to 90_000_000L) else emptyMap())
        return LinkViewModel(SavedStateHandle(), engine = FakeEngine(InspectResult.Success(inspection)), currentEngineVersion = { "fixture-v1" }).also {
            it.edit(url); it.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        }
    }
    // ModalBottomSheet attaches its own window, so read every root registered with WindowManagerGlobal.
    private fun semantics(activity: ComponentActivity): List<SemanticsNode> = semanticsNodes(activity, rounds = 4, idleMillis = 200)
    private fun click(nodes: List<SemanticsNode>, label: String) {
        val node = nodes.first { it.config.getOrNull(SemanticsActions.OnClick) != null &&
            texts(descendants(it)).any { text -> text.contains(label) } }
        assertEquals(true, node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke())
    }

    @Test fun sheetShowsOnlyMappedPresetsAndAccessibleSizeLabelsInEnglish() = assertSheet("en")
    @Test @Config(qualifiers = "ar") fun sheetShowsOnlyMappedPresetsAndAccessibleSizeLabelsInArabic() = assertSheet("ar")

    @Test fun roughSizesStayHiddenInEnglishRowsAndConfirmationWithoutChangingCommand() = assertSheet("en", true)
    @Test @Config(qualifiers = "ar") fun roughSizesStayHiddenInArabicRowsAndConfirmationWithoutChangingCommand() = assertSheet("ar", true)

    private fun assertSheet(language: String, rough: Boolean = false) {
        run {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            val vm = vm(rough)
            if (rough) vm.selectPreset(Preset.BEST)
            try {
                val activity = controller.get()
                val configuration = Configuration(activity.resources.configuration).apply { setLocale(Locale.forLanguageTag("$language-u-nu-latn")); fontScale = 2f }
                val context = activity.createConfigurationContext(configuration)
                activity.setContent {
                    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                        LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        NezzelTheme(ThemePref.Dark, false) { OptionsSheetContent(vm, language, vm::dismissOptions) }
                    }
                }
                val nodes = semantics(activity)
                val labels = texts(nodes)
                assertTrue(labels.toString(), labels.any { it == context.getString(R.string.preset_balanced) })
                assertTrue(labels.contains("رحلة بحرية 🌊"))
                assertTrue(labels.any { it.contains("قناة البحر") && it.contains("3:45") })
                assertTrue(labels.any { it.contains("youtube.com") })
                assertFalse(labels.any { it.contains("MP3") })
                assertTrue(labels.any { it.contains("68\u00a0MB") })
                // Owner 2026-10-25: estimates show no size and no "≈", in text or for TalkBack.
                assertFalse(labels.any { it.contains("8.1\u00a0MB") || it.contains("≈") })
                assertFalse(nodes.any { node -> node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains("≈") } })
                assertFalse(labels.contains(context.getString(R.string.unknown_size)))
                if (rough) {
                    assertFalse(labels.toString(), labels.any { it.contains("90\u00a0MB") })
                    assertNull(vm.takeConfirmedCommand())
                    vm.confirmDownload()
                    assertEquals(Size.Unknown, vm.takeConfirmedCommand()!!.format.size)
                }
                assertFalse(labels.any { text -> text.any { it in '\u0660'..'\u0669' || it in '\u06f0'..'\u06f9' } })
                assertTrue(nodes.any { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                    it.config.getOrNull(SemanticsProperties.Selected) == true })
            } finally { vm.dismissOptions(); controller.pause().stop().destroy() }
        }
    }
    /** Phone finding: BALANCED and SMALL resolving to one format showed "360p" twice. One row, first label, last-used kept. */
    @Test fun presetsSharingAFormatRenderOneRowWithTheLastUsedMarker() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val inspection = Inspection(LinkRevision(LinkInput.parse(url)!!, 1), "fixture-v1", formats,
            listOf(PresetChoice(Preset.BEST, "1080"), PresetChoice(Preset.BALANCED, "720"), PresetChoice(Preset.SMALL, "720")))
        val vm = LinkViewModel(SavedStateHandle(), engine = FakeEngine(InspectResult.Success(inspection)),
            currentEngineVersion = { "fixture-v1" }, initialPreset = Preset.SMALL).also {
            it.edit(url); it.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            val activity = controller.get()
            activity.setContent { NezzelTheme(ThemePref.Dark, false) { OptionsSheetContent(vm, "en", vm::dismissOptions) } }
            val nodes = semantics(activity)
            val labels = texts(nodes)
            val balanced = labels.filter { it == activity.getString(R.string.preset_balanced) }
            assertEquals(labels.toString(), 1, balanced.size)
            // "Last used" is the amber badge inside that row, not hint text.
            assertFalse(balanced.single(), balanced.single().contains(activity.getString(R.string.last_used)))
            val badge = nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "last_used_badge" }
            assertEquals(listOf(activity.getString(R.string.last_used)), texts(descendants(badge)))
            assertTrue(nodes.any { row -> row.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                descendants(row).any { it.id == badge.id } && texts(descendants(row)).any { t -> t in balanced } })
            assertFalse(labels.toString(), labels.any { it == activity.getString(R.string.preset_small) })
            assertEquals(1, nodes.count { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                it.config.getOrNull(SemanticsProperties.Selected) == true && texts(descendants(it)).any { t -> t in balanced } })
        } finally { vm.dismissOptions(); controller.pause().stop().destroy() }
    }

    @Test fun thumbnailFailureShowsQuietFallback() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume()
        var attempted = false
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        val recomposer = Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        try {
            controller.get().setContent(parent = recomposer) {
                NezzelTheme(ThemePref.Light, false) {
                    androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
                        PreviewThumbnail("https://i.ytimg.com/preview.jpg", load = { attempted = true; null })
                    }
                }
            }
            controller.visible()
            semantics(controller.get())
            clock.sendFrame(System.nanoTime())
            val nodes = semantics(controller.get())
            assertTrue(nodes.map { it.config }.toString(), attempted)
            assertTrue(nodes.any { it.config.getOrNull(SemanticsProperties.TestTag) == "preview_thumbnail_fallback" })
        } finally { recomposer.cancel(); scope.cancel(); controller.pause().stop().destroy() }
    }
    @Test fun removingPreviewCancelsItsInFlightLoad() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume()
        val visible = androidx.compose.runtime.mutableStateOf(true)
        var started = false
        var cancelled = false
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        val recomposer = Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        try {
            controller.get().setContent(parent = recomposer) {
                NezzelTheme(ThemePref.Light, false) {
                    androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
                        if (visible.value) PreviewThumbnail("https://i.ytimg.com/preview.jpg", load = {
                            started = true
                            try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true }
                        })
                    }
                }
            }
            controller.visible()
            semantics(controller.get()); assertTrue(started)
            visible.value = false
            Snapshot.sendApplyNotifications()
            clock.sendFrame(System.nanoTime())
            semantics(controller.get()); assertTrue(cancelled)
        } finally { recomposer.cancel(); scope.cancel(); controller.pause().stop().destroy() }
    }

    @Test fun earlyPreviewStaysWhenOptionsArriveWithAnotherImage() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume()
        val url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
        val ready = Inspection(LinkRevision(LinkInput.parse(url)!!, 1), "fixture-v1", emptyList(), title = "Trip",
            thumbnailUrl = "https://i.ytimg.com/vi/aqz-KE-bpKQ/maxresdefault.jpg")
        val inspection = androidx.compose.runtime.mutableStateOf<Inspection?>(null)
        val bitmap = android.graphics.Bitmap.createBitmap(16, 9, android.graphics.Bitmap.Config.ARGB_8888)
        val loads = mutableListOf<String>()
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        val recomposer = Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        try {
            controller.get().setContent(parent = recomposer) {
                NezzelTheme(ThemePref.Light, false) {
                    androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
                        val current = inspection.value
                        InspectionHeader(current, "youtube.com", checking = current == null,
                            earlyPreview = if (current == null) dev.ahmed.savlino.engine.mediahttp.EarlyPreview("Trip",
                                "https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg") else null,
                            load = { loads += it; bitmap })
                    }
                }
            }
            controller.visible()
            semantics(controller.get()); clock.sendFrame(System.nanoTime())
            inspection.value = ready
            Snapshot.sendApplyNotifications(); clock.sendFrame(System.nanoTime())
            val nodes = semantics(controller.get())
            assertEquals(listOf("https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg"), loads)
            assertFalse(nodes.any { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("preview_thumbnail") == true })
        } finally { recomposer.cancel(); scope.cancel(); controller.pause().stop().destroy(); bitmap.recycle() }
    }

    @Test fun missingEarlyImageFallsBackToTheEngineImage() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume()
        val url = "https://www.youtube.com/shorts/aqz-KE-bpKQ"
        val ready = Inspection(LinkRevision(LinkInput.parse(url)!!, 1), "fixture-v1", emptyList(), title = "Trip",
            thumbnailUrl = "https://i.ytimg.com/vi/aqz-KE-bpKQ/maxresdefault.jpg")
        val inspection = androidx.compose.runtime.mutableStateOf<Inspection?>(null)
        val bitmap = android.graphics.Bitmap.createBitmap(16, 9, android.graphics.Bitmap.Config.ARGB_8888)
        val loads = mutableListOf<String>()
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        val recomposer = Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        try {
            controller.get().setContent(parent = recomposer) {
                NezzelTheme(ThemePref.Light, false) {
                    androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
                        val current = inspection.value
                        InspectionHeader(current, "youtube.com", checking = current == null,
                            earlyPreview = if (current == null) dev.ahmed.savlino.engine.mediahttp.EarlyPreview(null,
                                "https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg") else null,
                            // The derived early image is missing (404 → null); the engine image must still load.
                            load = { loads += it; if (it.endsWith("hqdefault.jpg")) null else bitmap })
                    }
                }
            }
            controller.visible()
            semantics(controller.get()); clock.sendFrame(System.nanoTime())
            inspection.value = ready
            Snapshot.sendApplyNotifications(); clock.sendFrame(System.nanoTime())
            semantics(controller.get()); Snapshot.sendApplyNotifications(); clock.sendFrame(System.nanoTime())
            val nodes = semantics(controller.get())
            assertEquals(listOf("https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg",
                "https://i.ytimg.com/vi/aqz-KE-bpKQ/maxresdefault.jpg"), loads)
            assertFalse(nodes.any { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("preview_thumbnail") == true })
        } finally { recomposer.cancel(); scope.cancel(); controller.pause().stop().destroy(); bitmap.recycle() }
    }

    @Test fun loadedThumbnailIsDecorativeAndUrlChangeImmediatelyClearsOldImage() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume()
        val url = androidx.compose.runtime.mutableStateOf<String?>("https://i.ytimg.com/one.jpg")
        val bitmap = android.graphics.Bitmap.createBitmap(16, 9, android.graphics.Bitmap.Config.ARGB_8888)
        var secondStarted = false
        var secondCancelled = false
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        val recomposer = Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        try {
            controller.get().setContent(parent = recomposer) {
                NezzelTheme(ThemePref.Light, false) {
                    androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
                        androidx.compose.foundation.layout.Column {
                            PreviewThumbnail(url.value, load = {
                                if (it.endsWith("one.jpg")) bitmap else {
                                    secondStarted = true
                                    try { awaitCancellation() } finally { secondCancelled = true }
                                }
                            })
                            androidx.compose.material3.Text("رحلة Trip")
                        }
                    }
                }
            }
            controller.visible()
            semantics(controller.get())
            clock.sendFrame(System.nanoTime())
            val loaded = semantics(controller.get())
            assertFalse(loaded.any { it.config.getOrNull(SemanticsProperties.TestTag) == "preview_thumbnail_placeholder" })
            assertEquals(listOf("رحلة Trip"), texts(loaded))
            assertTrue(loaded.all { it.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() })
            assertTrue(loaded.none { it.config.getOrNull(SemanticsProperties.Role) == Role.Image })
            url.value = "https://i.ytimg.com/two.jpg"
            Snapshot.sendApplyNotifications(); clock.sendFrame(System.nanoTime())
            val changed = semantics(controller.get())
            assertTrue(secondStarted)
            assertTrue(changed.any { it.config.getOrNull(SemanticsProperties.TestTag) == "preview_thumbnail_placeholder" })
            url.value = null
            Snapshot.sendApplyNotifications(); clock.sendFrame(System.nanoTime())
            assertTrue(semantics(controller.get()).any { it.config.getOrNull(SemanticsProperties.TestTag) == "preview_thumbnail_fallback" })
            assertTrue(secondCancelled)
        } finally { recomposer.cancel(); scope.cancel(); controller.pause().stop().destroy(); bitmap.recycle() }
    }

    @Test fun checkingHasPreviewSkeletonAndHostWithoutInventingTitle() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent {
                NezzelTheme(ThemePref.Light, false) {
                    androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
                        InspectionHeader(null, "youtu.be", checking = true)
                    }
                }
            }
            val nodes = semantics(controller.get())
            assertTrue(nodes.any { it.config.getOrNull(SemanticsProperties.TestTag) == "preview_title_skeleton" })
            assertTrue(nodes.any { it.config.getOrNull(SemanticsProperties.TestTag) == "preview_thumbnail_fallback" })
            assertTrue(texts(nodes).any { it.contains("youtu.be") })
            assertEquals(1, texts(nodes).size)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun earlyRealTitleReplacesSkeletonInEnglishArabicAndFrench() {
        for (language in listOf("en", "ar", "fr")) {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                val activity = controller.get()
                val configuration = Configuration(activity.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(language)); fontScale = 2f
                }
                val context = activity.createConfigurationContext(configuration)
                activity.setContent {
                    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                        LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        NezzelTheme(ThemePref.Light, false) {
                            InspectionHeader(null, "youtu.be", checking = true,
                                earlyPreview = dev.ahmed.savlino.engine.mediahttp.EarlyPreview("عنوان réel", null))
                        }
                    }
                }
                val nodes = semantics(activity)
                assertTrue(texts(nodes).any { it == "عنوان réel" })
                assertTrue(texts(nodes).any { it.contains("youtu.be") })
                assertFalse(nodes.any { it.config.getOrNull(SemanticsProperties.TestTag) == "preview_title_skeleton" })
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun moreFormatsConvertsOnlyAfterExplicitTapAndEmitsOneCommandAcrossRecomposition() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val vm = vm()
        try {
            val activity = controller.get()
            vm.showMoreFormats()
            activity.setContent { NezzelTheme(ThemePref.Light, false) { MoreFormatsScreen(vm, "en") } }
            var nodes = semantics(activity)
            val conversionRow = nodes.single { node ->
                node.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                    node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    texts(descendants(node)).any { it.contains("MP3") }
            }
            assertTrue(texts(descendants(conversionRow)).any { it.contains(activity.getString(R.string.mp3_hint)) })
            click(nodes, "MP3")
            assertEquals("mp3", vm.selectedFormat?.id)
            assertNull(vm.takeConfirmedCommand())
            nodes = semantics(activity)
            click(nodes, activity.getString(R.string.download))
            vm.confirmDownload() // A second tap before/after UI recomposition is harmless.
            semantics(activity)
            val command = vm.takeConfirmedCommand()!!
            assertEquals(AudioConversion.MP3_128, command.format.conversion)
            assertEquals("رحلة بحرية 🌊.mp3", command.filename)
            assertEquals("a", command.format.sourceFormatId)
            assertNull(vm.takeConfirmedCommand())
            assertFalse(vm.downloadEnabled)
        } finally { vm.dismissOptions(); controller.pause().stop().destroy() }
    }
}
