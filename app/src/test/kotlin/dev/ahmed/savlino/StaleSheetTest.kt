package dev.ahmed.savlino

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** snappy-gaps item 8: Download on a stale sheet reads the link again and downloads the FRESH row, or shows the sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StaleSheetTest {
    private val url = "https://youtube.com/watch?v=one"
    private val source = LinkRevision(LinkInput.parse(url)!!, 1)
    private val audio = Format("140", "m4a", false, true, audioCodec = "mp4a.40.2", bitrateKbps = 128)
    private val hd = Format("22", "mp4", true, true, videoCodec = "avc1.64001F", audioCodec = "mp4a.40.2", height = 720,
        size = Size.Exact(10_000_000))
    private fun inspection(row: Format = hd, version: String = "v1") =
        Inspection(source, version, listOf(row, audio), listOf(PresetChoice(Preset.BALANCED, row.id)))
    private var clock = 1_000_000L
    private var version = "v1"
    private val ids = mutableListOf<String>()
    private fun model(engine: FakeEngine) = LinkViewModel(SavedStateHandle(), engine = engine, currentEngineVersion = { version },
        newOperationId = { (ids.size + 1).toString().also { ids += it } }, now = { clock }).also {
        it.edit(url); it.checkLink(); idle()
        assertEquals("22", it.selectedFormat?.id)
    }
    /** Operation "1" is the sheet's first read; "2" is the read Download starts on a stale sheet. */
    private fun engine(fresh: InspectResult, hold: Boolean = false) = FakeEngine(inspectionScripts = mapOf(
        "1" to FakeEngine.InspectionScript(InspectResult.Success(inspection())),
        "2" to FakeEngine.InspectionScript(fresh, awaitRelease = hold)))
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun age() { clock += SHEET_FRESH_MS }

    @Test fun thresholdLeavesFiveMinutesOfTheEnginesInfoJson() {
        assertEquals(YtdlpInfoCache.TTL_MS - 5 * 60 * 1000L, SHEET_FRESH_MS)
    }

    @Test fun freshSheetDownloadsWithoutReadingAgain() {
        val vm = model(engine(InspectResult.Failure(DownloadError.Failed)))
        clock += SHEET_FRESH_MS - 1
        vm.confirmDownload(); idle()
        assertEquals(listOf("1"), ids)
        assertEquals("22", vm.takeConfirmedCommand()?.format?.id)
    }

    @Test fun agedSheetReadsAgainAndDownloadsTheFreshRow() {
        val freshRow = hd.copy(size = Size.Exact(11_000_000), bitrateKbps = 900)
        val fresh = inspection(freshRow)
        val vm = model(engine(InspectResult.Success(fresh)))
        age()
        assertTrue(vm.downloadEnabled)
        vm.confirmDownload(); idle()
        assertEquals(listOf("1", "2"), ids)
        val command = vm.takeConfirmedCommand()!!
        assertEquals(fresh.inspectionId, command.inspectionId)
        assertEquals(freshRow, command.format)
        assertFalse(vm.optionsChanged)
        assertFalse(vm.refreshing)
    }

    @Test fun engineUpdateKeepsDownloadTappableAndConfirmsWithTheNewVersion() {
        val vm = model(engine(InspectResult.Success(inspection(version = "v2"))))
        version = "v2"
        assertTrue(vm.downloadEnabled)
        vm.confirmDownload(); idle()
        assertEquals("v2", vm.takeConfirmedCommand()?.engineVersion)
    }

    @Test fun goneIdShowsTheFreshSheetWithANote() {
        val vm = model(engine(InspectResult.Success(inspection(hd.copy(id = "136", sourceFormatId = "136")))))
        age(); vm.confirmDownload(); idle()
        assertNull(vm.takeConfirmedCommand())
        assertTrue(vm.optionsChanged)
        assertEquals("136", vm.selectedFormat?.id) // normal preselect on the fresh options
        assertTrue(vm.downloadEnabled)
    }

    @Test fun changedHeightCodecOrMuchBiggerFileShowsTheSheet() {
        for (changed in listOf(hd.copy(height = 480), hd.copy(videoCodec = "avc1.4D401E"), hd.copy(size = Size.Exact(12_600_000)))) {
            ids.clear(); clock = 1_000_000L
            val vm = model(engine(InspectResult.Success(inspection(changed))))
            age(); vm.confirmDownload(); idle()
            assertNull(vm.takeConfirmedCommand())
            assertTrue(vm.optionsChanged)
        }
    }

    @Test fun failedReadShowsTheErrorAndQueuesNothing() {
        val vm = model(engine(InspectResult.Failure(DownloadError.Unavailable)))
        age(); vm.confirmDownload(); idle()
        assertNull(vm.takeConfirmedCommand())
        assertEquals(OptionsState.Error(DownloadError.Unavailable), vm.optionsState)
    }

    @Test fun dismissingOrEditingDuringTheReadQueuesNothing() {
        val dismissEngine = engine(InspectResult.Success(inspection()), hold = true)
        val dismissed = model(dismissEngine)
        age(); dismissed.confirmDownload(); idle()
        assertTrue(dismissed.refreshing)
        assertFalse(dismissed.downloadEnabled)
        dismissed.dismissOptions(); dismissEngine.releaseInspection("2"); idle()
        assertNull(dismissed.takeConfirmedCommand())
        assertFalse(dismissed.refreshing)
        assertEquals(OptionsState.Hidden, dismissed.optionsState)

        ids.clear(); clock = 1_000_000L
        val editEngine = engine(InspectResult.Success(inspection()), hold = true)
        val edited = model(editEngine)
        age(); edited.confirmDownload(); idle()
        edited.edit("https://youtube.com/watch?v=two"); editEngine.releaseInspection("2"); idle()
        assertNull(edited.takeConfirmedCommand())
        assertEquals(OptionsState.Hidden, edited.optionsState)
    }

    @Test fun secondTapDuringTheReadStartsNothingMore() {
        val held = engine(InspectResult.Success(inspection()), hold = true)
        val vm = model(held)
        age(); vm.confirmDownload(); vm.confirmDownload(); vm.requestDownload(); idle()
        vm.selectFormat("140")
        held.releaseInspection("2"); idle()
        assertEquals(listOf("1", "2"), ids)
        assertEquals("22", vm.takeConfirmedCommand()?.format?.id)
    }
}
