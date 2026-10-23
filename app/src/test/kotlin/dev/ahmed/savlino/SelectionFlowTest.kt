package dev.ahmed.savlino

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.OptionsState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SelectionFlowTest {
    private val url = "https://youtube.com/watch?v=one"
    private val video = Format("v720", "mp4", true, true, height = 720, size = Size.Exact(68_000_000))
    private val best = Format("v1080+a", "mp4", true, false, height = 1080, audioFormatId = "audio-source", requiresMerge = true)
    private val audio = Format("m4a", "m4a", false, true, bitrateKbps = 128, size = Size.Estimated(8_100_000))
    private val mp3 = Format("mp3-320", "mp3", false, true, sourceFormatId = "m4a", conversion = AudioConversion.MP3_320)
    private fun inspection(revision: Long = 1, link: String = url, formats: List<Format> = listOf(best, video, audio, mp3),
        presets: List<PresetChoice> = listOf(PresetChoice(Preset.BEST, best.id), PresetChoice(Preset.BALANCED, video.id), PresetChoice(Preset.AUDIO_M4A, audio.id))) =
        Inspection(LinkRevision(LinkInput.parse(link)!!, revision), "fixture-v1", formats, presets)
    private fun model(result: InspectResult = InspectResult.Success(inspection()), version: () -> String? = { "fixture-v1" }): LinkViewModel =
        LinkViewModel(SavedStateHandle(), engine = FakeEngine(result), currentEngineVersion = version)
    private fun check(vm: LinkViewModel) { vm.edit(url); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun facebookProgressiveFilesWithoutReportedHeightStayVisibleBesideDashAndAudio() {
        val sd = Format("sd", "mp4", true, true)
        val hd = Format("hd", "mp4", true, true)
        val vm = model(InspectResult.Success(inspection(formats = listOf(best, sd, hd, audio),
            presets = listOf(PresetChoice(Preset.BEST, hd.id)))))
        check(vm)
        assertEquals(listOf(best.id, "sd", "hd", audio.id), vm.formatOptions.map { it.id })
        assertTrue(vm.primaryFormats.any { it.id == "sd" })
        assertTrue(vm.primaryFormats.any { it.id == "hd" })
        vm.selectFormat("hd"); vm.confirmDownload()
        val command = vm.takeConfirmedCommand()!!
        assertEquals("hd", command.format.sourceFormatId)
        assertFalse(command.format.requiresMerge)
        assertTrue(command.filename.endsWith(".mp4"))
    }

    @Test fun h264AacMp4IsTheDefaultAt720pWhenAv1AndVp9AlsoExist() {
        val result = YtdlpMetadataMapper.map("""{"formats":[
            {"format_id":"av1","ext":"mp4","vcodec":"av01.0.05M.08","acodec":"none","height":720},
            {"format_id":"vp9","ext":"webm","vcodec":"vp9","acodec":"none","height":720},
            {"format_id":"h264","ext":"mp4","vcodec":"avc1.64001f","acodec":"none","height":720},
            {"format_id":"aac","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2"}]}""",
            LinkRevision(LinkInput.parse(url)!!, 1), "fixture-v1")
        val vm = model(InspectResult.Success(result)); check(vm)
        assertEquals("h264", vm.selectedFormat?.id)
        assertEquals("aac", vm.selectedFormat?.audioFormatId)
        assertEquals("mp4", vm.selectedFormat?.container)
        assertTrue(vm.downloadEnabled)
        vm.confirmDownload()
        assertEquals("h264", vm.takeConfirmedCommand()!!.format.id)
    }

    @Test fun fullProjectionKeepsEqualHeightAndEqualBitrateRowsWithoutUnsupportedContainers() {
        val second = video.copy(id = "second720", hasAudio = false, audioFormatId = "audio-second", requiresMerge = true)
        val audioSecond = audio.copy(id = "audio-second")
        val webm = video.copy(id = "unsupported-webm", container = "webm")
        val nativeMp3 = Format("native-mp3", "mp3", false, true, audioCodec = "mp3")
        val fake = FakeEngine(InspectResult.Success(inspection(formats = listOf(video, second, audio, audioSecond, mp3, webm, nativeMp3),
            presets = listOf(PresetChoice(Preset.BALANCED, video.id)))))
        var inspections = 0
        val counting = object : EngineAdapter by fake {
            override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
                inspections++
                return fake.inspect(source, operationId)
            }
        }
        val vm = LinkViewModel(SavedStateHandle(), engine = counting, currentEngineVersion = { "fixture-v1" })
        check(vm)
        assertEquals(listOf("v720", "second720", "m4a", "audio-second", "mp3-320"), vm.formatOptions.map { it.id })
        vm.showMoreFormats(); vm.selectFormat(second.id)
        assertEquals(second, vm.selectedFormat)
        assertEquals(5, vm.formatOptions.size)
        assertNull(vm.takeConfirmedCommand()); assertTrue(fake.commands.isEmpty()); assertEquals(1, inspections)
        vm.confirmDownload()
        assertEquals(second, vm.takeConfirmedCommand()!!.format)
        assertNull(vm.takeConfirmedCommand()); assertTrue(fake.commands.isEmpty()); assertEquals(1, inspections)
    }

    @Test fun notificationDecisionWaitsBeforeConfirmingAndDenialStillEmitsExactlyOneCommand() {
        val vm = model(); check(vm)
        var prompted = false
        vm.notificationPrompt = { prompted = true }
        vm.requestDownload()
        assertTrue(prompted)
        assertNull(vm.takeConfirmedCommand())
        vm.finishNotificationRequest()
        assertEquals("v720", vm.takeConfirmedCommand()!!.format.id)
        vm.finishNotificationRequest()
        assertNull(vm.takeConfirmedCommand())
    }
    @Test fun permissionCallbackCannotConfirmAnEditedLinkOrDifferentFormat() {
        val vm = model(); check(vm)
        vm.notificationPrompt = {}
        vm.requestDownload(); vm.selectFormat(best.id); vm.finishNotificationRequest()
        assertNull(vm.takeConfirmedCommand())
        vm.requestDownload(); vm.edit("https://youtu.be/other"); vm.finishNotificationRequest()
        assertNull(vm.takeConfirmedCommand())
        assertEquals(OptionsState.Hidden, vm.optionsState)
    }

    @Test fun manualModeOnlyCheckLoadsReturnedRowsAndDownloadEmitsOneExactCommand() {
        val vm = model().apply { automaticOptions = false }
        vm.paste(url)
        assertEquals(OptionsState.Hidden, vm.optionsState)
        assertNull(vm.takeConfirmedCommand())
        vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("v1080+a", "v720", "m4a", "mp3-320"), vm.formatOptions.map { it.id })
        assertEquals(listOf(Preset.BEST, Preset.BALANCED, Preset.AUDIO_M4A), vm.presetOptions.map { it.first })
        assertEquals("v720", vm.selectedFormat?.id)
        assertTrue(vm.downloadEnabled)
        assertNull(vm.takeConfirmedCommand())
        vm.selectFormat(best.id)
        repeat(2) { vm.confirmDownload() }
        val command = vm.takeConfirmedCommand()!!
        assertEquals("v1080+a", command.format.id)
        assertEquals("audio-source", command.format.audioFormatId)
        assertTrue(command.format.requiresMerge)
        assertEquals(url, command.source.link.rawUrl)
        assertEquals("fixture-v1", command.engineVersion)
        assertTrue(command.filename.endsWith(".mp4"))
        assertNull(vm.takeConfirmedCommand())
        assertFalse(vm.downloadEnabled)
    }
    @Test fun missingBalancedDoesNotSilentlyChooseAnotherPresetAndMp3RequiresExplicitSelection() {
        val vm = model(InspectResult.Success(inspection(presets = listOf(PresetChoice(Preset.AUDIO_M4A, audio.id)))))
        check(vm)
        assertNull(vm.selectedFormat); assertFalse(vm.downloadEnabled)
        vm.selectFormat("invented-quality"); vm.confirmDownload()
        assertNull(vm.takeConfirmedCommand())
        vm.selectFormat(mp3.id); vm.confirmDownload()
        val command = vm.takeConfirmedCommand()!!
        assertEquals("m4a", command.format.sourceFormatId)
        assertEquals(AudioConversion.MP3_320, command.format.conversion)
        assertTrue(command.filename.endsWith(".mp3"))
    }
    @Test fun editsEvenBackToSameUrlInvalidateSelectionAndPendingCommand() {
        val vm = model(); check(vm)
        vm.confirmDownload()
        vm.edit("https://youtu.be/two"); vm.edit(url)
        assertEquals(OptionsState.Hidden, vm.optionsState)
        assertNull(vm.selectedFormat); assertTrue(vm.formatOptions.isEmpty())
        vm.confirmDownload(); assertNull(vm.takeConfirmedCommand()); assertFalse(vm.downloadEnabled)
    }
    @Test fun newerInspectionWinsWhenOlderSameUrlResultArrivesLate() {
        val first = inspection()
        val second = inspection(formats = listOf(audio), presets = emptyList())
        val fake = FakeEngine(inspectionScripts = mapOf(
            "inspect-1" to FakeEngine.InspectionScript(InspectResult.Success(first), true),
            "inspect-2" to FakeEngine.InspectionScript(InspectResult.Success(second), true)))
        var operation = 0
        val vm = LinkViewModel(SavedStateHandle(), engine = fake, currentEngineVersion = { "fixture-v1" }, newOperationId = { "inspect-${++operation}" })
        vm.edit(url); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        fake.releaseInspection("inspect-2"); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("m4a"), vm.formatOptions.map { it.id })
        fake.releaseInspection("inspect-1"); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("m4a"), vm.formatOptions.map { it.id }); assertEquals("m4a", vm.selectedFormat?.id) // lone row preselected
        vm.dismissOptions()
    }
    @Test fun mismatchedRevisionAndEngineVersionCannotConfirm() {
        val stale = model(InspectResult.Success(inspection(revision = 0)))
        check(stale)
        assertTrue(stale.optionsState is OptionsState.Error); assertTrue(stale.formatOptions.isEmpty())
        var version: String? = "fixture-v1"
        val vm = model(version = { version }); check(vm)
        vm.showMoreFormats()
        version = "fixture-v2"
        // Item 8: Download reads the link again; the fresh read still reports v1, so nothing is confirmed.
        vm.confirmDownload(); shadowOf(Looper.getMainLooper()).idle()
        assertNull(vm.takeConfirmedCommand()); assertNull(vm.selectedFormat)
        assertTrue(vm.optionsState is OptionsState.Error)
        assertFalse(vm.moreFormats)
        val absent = model(version = { null }); check(absent)
        assertFalse(absent.downloadEnabled); absent.confirmDownload(); assertNull(absent.takeConfirmedCommand())
    }
    @Test fun errorsAndUnavailableEngineNeverInventFormats() {
        for (error in listOf(DownloadError.NoFormats, DownloadError.LoginRequired, DownloadError.Offline, DownloadError.Unsupported, DownloadError.Failed)) {
            val vm = model(InspectResult.Failure(error)); check(vm)
            assertEquals(error, (vm.optionsState as OptionsState.Error).error)
            assertTrue(vm.formatOptions.isEmpty()); assertFalse(vm.downloadEnabled)
        }
        val vm = LinkViewModel(SavedStateHandle()); check(vm)
        assertEquals(DownloadError.EngineUnavailable, (vm.optionsState as OptionsState.Error).error)
        vm.confirmDownload(); assertNull(vm.takeConfirmedCommand())
    }
    @Test fun noFormatsAndUnplayableRowsCannotBecomeSelections() {
        val rawVideo = Format("silent", "mp4", true, false, height = 1080)
        val storyboard = Format("storyboard", "mhtml", true, false, height = 90)
        val vm = model(InspectResult.Success(inspection(formats = listOf(rawVideo, storyboard), presets = emptyList())))
        check(vm)
        assertEquals(DownloadError.NoFormats, (vm.optionsState as OptionsState.Error).error)
        vm.selectFormat("silent"); vm.confirmDownload(); assertNull(vm.takeConfirmedCommand())
    }
    @Test fun dismissalInvalidatesAndRecreationDoesNotRestoreFormatOrReplayCommand() {
        val saved = SavedStateHandle()
        val vm = LinkViewModel(saved, engine = FakeEngine(InspectResult.Success(inspection())), currentEngineVersion = { "fixture-v1" })
        check(vm); vm.selectFormat(audio.id); vm.showMoreFormats()
        assertTrue(vm.moreFormats)
        vm.hideMoreFormats(); assertEquals("m4a", vm.selectedFormat?.id)
        vm.confirmDownload()
        val restored = LinkViewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals(url, restored.text); assertNull(restored.selectedFormat); assertNull(restored.takeConfirmedCommand())
        vm.dismissOptions(); assertFalse(vm.downloadEnabled); assertNull(vm.selectedFormat)
    }
    @Test fun rememberedPresetIsUsedOnlyWhenEngineStillOffersIt() {
        var remembered: Preset? = null
        val fake = FakeEngine(InspectResult.Success(inspection()))
        val vm = LinkViewModel(SavedStateHandle(), engine = fake, currentEngineVersion = { "fixture-v1" }, rememberPreset = { remembered = it })
        check(vm); vm.selectPreset(Preset.AUDIO_M4A)
        assertNull(remembered)
        vm.confirmDownload()
        assertNull(remembered) // audio picks are not remembered as the next default
        assertTrue(fake.commands.isEmpty()) // Confirmation is an outbox, never a transfer.
        val next = LinkViewModel(SavedStateHandle(), engine = fake, currentEngineVersion = { "fixture-v1" }, initialPreset = remembered)
        check(next); assertEquals("v720", next.selectedFormat?.id) // audio is never the next link's default
        assertNull(next.lastUsedPreset)
        vm.dismissOptions()
        val small = LinkViewModel(SavedStateHandle(), engine = FakeEngine(InspectResult.Success(inspection(
            presets = listOf(PresetChoice(Preset.BEST, best.id), PresetChoice(Preset.SMALL, video.id), PresetChoice(Preset.AUDIO_M4A, audio.id))))),
            currentEngineVersion = { "fixture-v1" }, rememberPreset = { remembered = it })
        check(small); small.selectPreset(Preset.SMALL); small.confirmDownload()
        assertEquals(Preset.SMALL, remembered)
        val audioPick = LinkViewModel(SavedStateHandle(), engine = fake, currentEngineVersion = { "fixture-v1" },
            initialPreset = remembered, rememberPreset = { remembered = it })
        check(audioPick); audioPick.selectPreset(Preset.AUDIO_M4A); audioPick.confirmDownload()
        assertEquals(Preset.SMALL, remembered) // an audio pick leaves the remembered video preset alone
        val missing = LinkViewModel(SavedStateHandle(), engine = FakeEngine(InspectResult.Success(inspection(
            formats = listOf(video, audio), presets = listOf(PresetChoice(Preset.BALANCED, video.id))))),
            currentEngineVersion = { "fixture-v1" }, initialPreset = remembered)
        check(missing); assertNull(missing.selectedFormat); assertFalse(missing.downloadEnabled)
        val loneMissing = LinkViewModel(SavedStateHandle(), engine = FakeEngine(InspectResult.Success(inspection(
            formats = listOf(video), presets = listOf(PresetChoice(Preset.BALANCED, video.id))))),
            currentEngineVersion = { "fixture-v1" }, initialPreset = remembered)
        check(loneMissing); assertEquals(video.id, loneMissing.selectedFormat?.id) // a lone row is always the choice
        val audioOnly = LinkViewModel(SavedStateHandle(), engine = FakeEngine(InspectResult.Success(inspection(
            formats = listOf(audio), presets = listOf(PresetChoice(Preset.AUDIO_M4A, audio.id))))),
            currentEngineVersion = { "fixture-v1" }, initialPreset = Preset.SMALL)
        check(audioOnly); assertEquals("m4a", audioOnly.selectedFormat?.id)
    }
    @Test fun aLinkWithOnlyMp3ShowsItInTheMainListAndDownloadNeedsASelection() {
        val mp3Only = Format("mp3-128", "mp3", false, true, audioCodec = "mp3", bitrateKbps = 128,
            sourceFormatId = "m4a", conversion = AudioConversion.MP3_128)
        val vm = model(InspectResult.Success(inspection(formats = listOf(mp3Only),
            presets = listOf(PresetChoice(Preset.AUDIO_MP3, mp3Only.id)))))
        check(vm)
        assertEquals(listOf(Preset.AUDIO_MP3), vm.presetRows.map { it.preset })
        assertEquals(mp3Only.id, vm.selectedFormat?.id); assertTrue(vm.downloadEnabled)
        // With video choices present, MP3 stays under All qualities.
        val withVideo = model(InspectResult.Success(inspection(formats = listOf(video, mp3Only),
            presets = listOf(PresetChoice(Preset.BALANCED, video.id), PresetChoice(Preset.AUDIO_MP3, mp3Only.id)))))
        check(withVideo); assertEquals(listOf(Preset.BALANCED), withVideo.presetRows.map { it.preset })
        // Audio-only result without engine presets: the main row and default come from the returned audio.
        val noPresets = model(InspectResult.Success(inspection(formats = listOf(audio, mp3Only), presets = emptyList())))
        check(noPresets)
        assertEquals(listOf(Preset.AUDIO_M4A to "m4a"), noPresets.presetRows.map { it.preset to it.format.id })
        assertEquals("m4a", noPresets.selectedFormat?.id); assertTrue(noPresets.downloadEnabled)
        val mp3NoPresets = model(InspectResult.Success(inspection(formats = listOf(mp3Only), presets = emptyList())))
        check(mp3NoPresets); assertEquals(listOf(Preset.AUDIO_MP3), mp3NoPresets.presetRows.map { it.preset })
        assertEquals(mp3Only.id, mp3NoPresets.selectedFormat?.id)
        // Nothing preselectable (several rows, video present, no presets): Download stays disabled.
        val none = model(InspectResult.Success(inspection(formats = listOf(video, best, mp3Only), presets = emptyList())))
        check(none); assertTrue(none.presetRows.isEmpty()); assertNull(none.selectedFormat); assertFalse(none.downloadEnabled)
        none.selectFormat(mp3Only.id); assertTrue(none.downloadEnabled)
    }
    @Test fun aLoneSelectableRowIsAlwaysPreselected() {
        // LinkedIn/X: one codec-less direct mp4, no presets and no remembered choice.
        val lone = Format("http-832", "mp4", true, true, width = 720, height = 1280)
        val vm = model(InspectResult.Success(inspection(formats = listOf(lone), presets = emptyList())))
        check(vm)
        assertEquals("http-832", vm.selectedFormat?.id); assertTrue(vm.downloadEnabled)
        vm.confirmDownload(); assertEquals("http-832", vm.takeConfirmedCommand()!!.format.id)
        val remembered = LinkViewModel(SavedStateHandle(), engine = FakeEngine(InspectResult.Success(inspection(formats = listOf(lone),
            presets = listOf(PresetChoice(Preset.BEST, lone.id))))), currentEngineVersion = { "fixture-v1" }, initialPreset = Preset.SMALL)
        check(remembered); assertEquals("http-832", remembered.selectedFormat?.id); assertEquals(Preset.BEST, remembered.selectedPreset)
    }
    @Test fun presetsWhoseRowsReadTheSameCollapseOntoTheEarlierPreset() {
        // Instagram reel: 720x1280 best, 316x562 balanced and 240x426 small; both lower rows read "240p".
        val hd = Format("hd", "mp4", true, true, width = 720, height = 1280)
        val mid = Format("mid", "mp4", true, true, width = 316, height = 562)
        val low = Format("low", "mp4", true, true, width = 240, height = 426)
        val vm = LinkViewModel(SavedStateHandle(), engine = FakeEngine(InspectResult.Success(inspection(formats = listOf(hd, mid, low, audio),
            presets = listOf(PresetChoice(Preset.BEST, hd.id), PresetChoice(Preset.BALANCED, mid.id), PresetChoice(Preset.SMALL, low.id),
                PresetChoice(Preset.AUDIO_M4A, audio.id))))), currentEngineVersion = { "fixture-v1" }, initialPreset = Preset.SMALL)
        check(vm)
        assertEquals(listOf(Preset.BEST, Preset.BALANCED, Preset.AUDIO_M4A), vm.presetRows.map { it.preset })
        assertEquals(listOf("hd", "mid", "m4a"), vm.presetRows.map { it.format.id })
        assertEquals(setOf(Preset.BALANCED, Preset.SMALL), vm.presetRows[1].presets)
        assertEquals("mid", vm.selectedFormat?.id) // remembered SMALL resolves to the visible row's rendition
        vm.selectPreset(Preset.SMALL); assertEquals("mid", vm.selectedFormat?.id)
        assertTrue(vm.formatOptions.any { it.id == "low" }) // still one tap away under All qualities
        vm.confirmDownload(); assertEquals("mid", vm.takeConfirmedCommand()!!.format.id)
    }
    @Test fun sameHeightRowsAndInvalidBalancedMappingDoNotInventChoices() {
        val duplicate = video.copy(id = "duplicate")
        val silent = video.copy(id = "silent", hasAudio = false)
        val tooHigh = model(InspectResult.Success(inspection(formats = listOf(best, video, duplicate, silent),
            presets = listOf(PresetChoice(Preset.BALANCED, best.id)))))
        check(tooHigh)
        assertEquals(listOf("v1080+a", "v720", "duplicate"), tooHigh.formatOptions.map { it.id })
        assertTrue(tooHigh.presetOptions.isEmpty()); assertNull(tooHigh.selectedFormat)
    }
    @Test fun sameHeightPresetReferencesKeepTheirExactEngineIds() {
        val alternate = video.copy(id = "alternate720", requiresMerge = true, audioFormatId = "separate-audio")
        val vm = model(InspectResult.Success(inspection(formats = listOf(alternate, video),
            presets = listOf(PresetChoice(Preset.BEST, alternate.id), PresetChoice(Preset.BALANCED, video.id)))))
        check(vm)
        assertEquals(listOf(Preset.BEST, Preset.BALANCED), vm.presetOptions.map { it.first })
        // Both rows would read "HD · 720p": one row, the earlier (BEST) preset's exact engine id.
        assertEquals(listOf(setOf(Preset.BEST, Preset.BALANCED)), vm.presetRows.map { it.presets })
        assertEquals("alternate720", vm.selectedFormat?.id)
        assertEquals(listOf("alternate720", "v720"), vm.formatOptions.map { it.id })
        vm.selectPreset(Preset.BEST)
        assertEquals(listOf("alternate720", "v720"), vm.formatOptions.map { it.id })
        vm.confirmDownload()
        val command = vm.takeConfirmedCommand()!!
        assertEquals("alternate720", command.format.id)
        assertEquals("separate-audio", command.format.audioFormatId)
    }
    @Test fun presetsSharingAFormatShareOneRowAndConfirmThatFormat() {
        val shared = inspection(presets = listOf(PresetChoice(Preset.BEST, best.id), PresetChoice(Preset.BALANCED, video.id),
            PresetChoice(Preset.SMALL, video.id), PresetChoice(Preset.AUDIO_M4A, audio.id)))
        val vm = LinkViewModel(SavedStateHandle(), engine = FakeEngine(InspectResult.Success(shared)),
            currentEngineVersion = { "fixture-v1" }, initialPreset = Preset.SMALL)
        check(vm)
        assertEquals(listOf(Preset.BEST, Preset.BALANCED, Preset.AUDIO_M4A), vm.presetRows.map { it.preset })
        assertEquals(setOf(Preset.BALANCED, Preset.SMALL), vm.presetRows[1].presets)
        assertEquals(video.id, vm.selectedFormat?.id) // last used SMALL still resolves to the shared row's format
        vm.selectPreset(Preset.BEST); vm.selectPreset(vm.presetRows[1].preset)
        assertEquals(video.id, vm.selectedFormat?.id)
        vm.confirmDownload()
        assertEquals(video.id, vm.takeConfirmedCommand()!!.format.id)
        val distinct = model(); check(distinct)
        assertEquals(listOf(Preset.BEST, Preset.BALANCED, Preset.AUDIO_M4A), distinct.presetRows.map { it.preset })
        assertTrue(distinct.presetRows.all { it.presets == setOf(it.preset) })
    }
}
