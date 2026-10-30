package dev.ahmed.savlino.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.jobs.DownloadCoordinator
import dev.ahmed.savlino.jobs.JobStore
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.waitWhile
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** X/Twitter returns one direct mp4 with no codec/height/size facts; it must be one selectable "Video" row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UnreportedDirectMp4Test {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private var link = "https://x.com/someone/status/1"
    private val xRow = Format("http", "mp4", true, true)
    private var rows = listOf(xRow)
    private var presets = listOf(PresetChoice(Preset.BEST, "http"), PresetChoice(Preset.SMALL, "http"))
    private var metadata: String? = null
    private lateinit var store: JobStore
    private lateinit var vm: LinkViewModel
    private val engine = object : EngineAdapter by FakeEngine() {
        override suspend fun installedVersion() = "2026.08.19"
        override suspend fun inspect(source: LinkRevision, operationId: String) =
            InspectResult.Success(metadata?.let { YtdlpMetadataMapper.map(it, source, "2026.08.19") }
                ?: Inspection(source, "2026.08.19", rows, presets, title = "Post"))
    }

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("engine-maintenance", 0).edit().clear().commit()
        store = JobStore(app)
        val coordinator = DownloadCoordinator(app, engine, store, workManager = { error("No background scheduler in this fixture") })
        vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator, currentEngineVersion = { "2026.08.19" })
    }
    @After fun tearDown() { vm.dismissOptions(); store.close() }
    private fun pump() = shadowOf(Looper.getMainLooper()).idle()
    private fun check() { vm.edit(link); vm.checkLink(); pump() }

    @Test fun facebookBestHdIsPreselectedWithoutReportedHeightOrSize() {
        link = "https://www.facebook.com/watch/?v=1058356515425744"
        metadata = """{"extractor_key":"Facebook","title":"Inspected post title","formats":[
            {"format_id":"hd","ext":"mp4","protocol":"https","url":"https://video.example/hd.mp4"},
            {"format_id":"dash","ext":"mp4","vcodec":"vp9","acodec":"none","height":720},
            {"format_id":"audio","ext":"m4a","vcodec":"none","acodec":"aac"}]}"""
        check()
        assertEquals("hd", vm.selectedFormat?.id)
        assertEquals(Preset.BEST, vm.selectedPreset)
        assertNull(vm.selectedFormat?.height)
        assertEquals(Size.Unknown, vm.selectedFormat?.size)
        assertTrue(vm.formatOptions.any { !it.hasVideo })
        assertTrue(vm.downloadEnabled)
        vm.confirmDownload()
        val job = run { waitWhile({ pump() }) { store.all().isEmpty() }; pump(); store.all().single() }
        assertEquals("hd", job.format.sourceFormatId)
        assertEquals("Inspected post title.mp4", job.filename)
    }

    @Test fun genericMp4MovAndWebmMapToOneSelectableVideoWithReportedOrUnknownQuality() {
        for (ext in listOf("mp4", "mov", "webm")) for (height in listOf("null", "720")) {
            link = "https://media.example/clip.$ext"
            metadata = """{"extractor":"generic","title":"Sample clip","formats":[{"format_id":"$ext",
                "ext":"$ext","protocol":"https","url":"https://media.example/clip.$ext",
                "vcodec":"none","acodec":"none","height":$height}]}"""
            check()
            assertTrue("$ext/$height: ${vm.optionsState}", vm.optionsState is OptionsState.Ready)
            assertEquals(listOf(ext), vm.formatOptions.map { it.id })
            assertEquals(ext, vm.primaryFormats.single().container)
            assertEquals(if (height == "null") null else 720, vm.primaryFormats.single().height)
            assertEquals(Size.Unknown, vm.primaryFormats.single().size)
            assertTrue(vm.downloadEnabled)
            vm.dismissOptions()
        }
    }

    @Test fun hlsVideoUsesItsActualSeparateAudioOrIsSilentWhenThePostHasNone() {
        link = "https://www.pinterest.com/pin/1/"
        metadata = """{"extractor":"Pinterest","formats":[
            {"format_id":"v720","ext":"mp4","protocol":"m3u8_native","vcodec":"avc1","acodec":"none","height":720},
            {"format_id":"a128","ext":"mp4","protocol":"m3u8_native","vcodec":"none","acodec":"unknown","tbr":128}]}"""
        check()
        assertTrue(vm.optionsState.toString(), vm.optionsState is OptionsState.Ready)
        val video = vm.primaryFormats.first { it.hasVideo }
        assertEquals("v720", video.id)
        assertEquals("a128", video.audioFormatId)
        assertTrue(video.requiresMerge)
        assertFalse(video.hasAudio)
        assertTrue(vm.downloadEnabled)
        vm.dismissOptions()
        metadata = """{"extractor":"Pinterest","formats":[
            {"format_id":"v720","ext":"mp4","protocol":"m3u8_native","vcodec":"avc1","acodec":"none","height":720}]}"""
        check()
        assertTrue(vm.optionsState.toString(), vm.optionsState is OptionsState.Ready)
        assertTrue(vm.primaryFormats.single().sourceHasNoSound)
        assertTrue(vm.downloadEnabled)
    }

    @Test fun silentSourceRowSaysNoSoundAndQueuesWithoutAnAudioPartner() {
        val silent = Format("v480", "mp4", true, false, videoCodec = "avc1", height = 480, sourceHasNoSound = true)
        rows = listOf(silent)
        presets = listOf(PresetChoice(Preset.BEST, silent.id))
        check()
        vm.showMoreFormats()
        val context = RuntimeEnvironment.getApplication()
        val language = context.resources.configuration.locales[0].language
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent { NezzelTheme(ThemePref.Light, false) { MoreFormatsScreen(vm, language) } }
            compose.waitForIdle()
            compose.onNode(isSelectable() and hasText(context.getString(R.string.video_no_sound), substring = true)).assertExists()
            assertTrue(vm.downloadEnabled)
            vm.confirmDownload()
            val job = run { waitWhile({ pump() }) { store.all().isEmpty() }; pump(); store.all().single() }
            assertEquals(silent, job.format)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun loneUnreportedMp4IsOneSelectableVideoRowAndItsJobUsesItsFormatId() {
        check()
        assertTrue(vm.optionsState.toString(), vm.optionsState is OptionsState.Ready)
        assertEquals(listOf("http"), vm.formatOptions.map { it.id })
        assertEquals(listOf("http"), vm.primaryFormats.map { it.id })
        assertTrue(vm.otherFormats.isEmpty())
        assertEquals("BEST and SMALL share one row", 1, vm.presetRows.size)
        assertNull(vm.roughSizeBytes(xRow))
        vm.selectFormat("http")
        assertEquals("http", vm.selectedFormat?.id)
        assertTrue(vm.downloadEnabled)
        vm.confirmDownload()
        val job = run { waitWhile({ pump() }) { store.all().isEmpty() }; pump(); store.all().single() }
        assertEquals("http", job.format.sourceFormatId)
        assertEquals(Size.Unknown, job.format.size)
    }

    @Test fun rendersPlainVideoTitleWithUnknownQualityAndSize() {
        check()
        vm.showMoreFormats()
        val context = RuntimeEnvironment.getApplication()
        val language = context.resources.configuration.locales[0].language
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent { NezzelTheme(ThemePref.Light, false) { MoreFormatsScreen(vm, language) } }
            compose.waitForIdle()
            compose.onAllNodes(isSelectable()).assertCountEquals(1)
            compose.onNode(isSelectable() and hasText(context.getString(R.string.video_unknown_quality_hint), substring = true))
                .assertExists()
            // No exact size: the row names none, like an estimate.
            compose.onAllNodes(isSelectable() and hasText(context.getString(R.string.unknown_size), substring = true)).assertCountEquals(0)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun animatedGifRowIsLabelledGifWithoutSound() {
        rows = listOf(Format("http", "mp4", true, true, animatedGif = true))
        check()
        vm.showMoreFormats()
        val context = RuntimeEnvironment.getApplication()
        val language = context.resources.configuration.locales[0].language
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent { NezzelTheme(ThemePref.Light, false) { MoreFormatsScreen(vm, language) } }
            compose.waitForIdle()
            compose.onNode(isSelectable() and hasText(context.getString(R.string.video_animated_gif), substring = true)).assertExists()
            compose.onAllNodes(isSelectable() and hasText(context.getString(R.string.video_unknown_quality_hint), substring = true)).assertCountEquals(0)
            compose.onNode(isSelectable() and hasText(context.getString(R.string.video_animated_gif_hint), substring = true)).assertExists()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun videoOnlyGifVariantIsSelectableAndItsJobNeedsNoAudioPartner() {
        val gif = Format("g480", "mp4", true, false, videoCodec = "avc1.4d401e", height = 480, animatedGif = true)
        rows = listOf(gif)
        presets = listOf(PresetChoice(Preset.BEST, "g480"))
        check()
        assertEquals(listOf("g480"), vm.formatOptions.map { it.id })
        vm.selectFormat("g480")
        assertTrue(vm.downloadEnabled)
        vm.confirmDownload()
        val job = run { waitWhile({ pump() }) { store.all().isEmpty() }; pump(); store.all().single() }
        assertEquals(gif, job.format)
    }

    @Test fun normalListStillHidesRowsWithoutHeightOrCodecs() {
        val normal = Format("720", "mp4", true, true, videoCodec = "avc1.4d401f", audioCodec = "mp4a.40.2", height = 720)
        rows = listOf(normal, xRow)
        presets = listOf(PresetChoice(Preset.BALANCED, "720"))
        check()
        assertEquals(listOf("720"), vm.formatOptions.map { it.id })
        vm.selectFormat("http")
        assertNotEquals("http", vm.selectedFormat?.id)
    }
}
