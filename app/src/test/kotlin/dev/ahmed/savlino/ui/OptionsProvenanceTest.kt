package dev.ahmed.savlino.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.usage.UsageEvents
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OptionsProvenanceTest {
    @Test fun adoptedResultCountsItsProvenanceOnceIncludingReaderPrefetchedCacheHit() {
        for ((provenance, path) in listOf(InspectionProvenance.NPE to "npe", InspectionProvenance.YTDLP to "ytdlp",
            InspectionProvenance.OWN to "own", InspectionProvenance.CACHE to "cache")) {
            val keys = mutableListOf<String>()
            val engine = object : EngineAdapter by FakeEngine() {
                override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
                    // A speculative process phase is not sufficient evidence that this reader won.
                    InspectionTiming.mark(InspectionPhase.YTDLP_SPAWNED)
                    return InspectResult.Success(Inspection(source, "engine", listOf(Format("v", "mp4", true, true))), provenance)
                }
            }
            val vm = LinkViewModel(SavedStateHandle(), engine = engine, currentEngineVersion = { "engine" },
                now = { 1000L }, usage = object : UsageEvents { override fun count(key: String) { keys += key } })
            vm.edit("https://example.org/video"); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(vm.optionsState is OptionsState.Ready)
            vm.optionsShown(); vm.optionsShown()
            assertEquals(listOf("options.$path.t0-2"), keys.filter { it.startsWith("options.") })
            assertEquals(listOf("opt:t0-2:example.org"), keys.filter { it.startsWith("opt:") })
            vm.dismissOptions()
        }
    }

    @Test fun speculativeYtdlpWinnerMetadataCannotOverrideTheImageReaderActuallyShown() {
        val keys = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
                InspectionTiming.mark(InspectionPhase.YTDLP_SPAWNED)
                return InspectResult.Success(Inspection(source, "engine", listOf(Format("v", "mp4", true, true))), InspectionProvenance.CACHE)
            }
        }
        val images = FakeImages().apply { this.gate = gate }
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, currentEngineVersion = { "engine" }, images = images,
            previewDispatcher = Dispatchers.Unconfined, previewImage = ::testBitmap, now = { 1000L },
            usage = object : UsageEvents { override fun count(key: String) { keys += key } })
        vm.edit("https://x.com/synthetic/status/1790000000000000001")
        vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        gate.complete(Unit); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(vm.optionsState is OptionsState.ImageReady)
        vm.optionsShown()
        assertEquals(listOf("options.own.t0-2"), keys.filter { it.startsWith("options.") })
        vm.dismissOptions()
    }
}
