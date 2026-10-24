package dev.ahmed.savlino.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.jobs.DownloadCoordinator
import dev.ahmed.savlino.jobs.JobStore
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** B2: "Look for better quality" re-reads the kept link, preselects the next higher tier, never downloads by itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BetterQualityFlowTest {
    private val link = "https://media.example/watch/1"
    private fun video(id: String, h: Int) = Format(id, "mp4", true, true, "avc1", "mp4a", width = h * 16 / 9, height = h)
    private var rows = listOf(video("v360", 360), video("v720", 720), video("v1080", 1080))
    private var inspections = 0
    private lateinit var store: JobStore
    private lateinit var vm: LinkViewModel
    private val engine = object : EngineAdapter by FakeEngine() {
        override suspend fun installedVersion() = "2026.08.19"
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            inspections++
            return InspectResult.Success(Inspection(source, "2026.08.19", rows,
                listOf(PresetChoice(Preset.BEST, rows.last().id), PresetChoice(Preset.SMALL, rows.first().id)), title = "Clip"))
        }
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

    @Test fun inspectsTheKeptLinkNotesTheSavedTierAndPreselectsTheNextOneUp() {
        vm.lookForBetter(link, video("v360", 360)); pump()
        assertEquals(1, inspections)
        assertEquals(360, vm.betterThan)
        assertEquals("v720", vm.selectedFormat?.id)
        // Nothing transfers until the explicit Download tap.
        assertTrue(store.all().isEmpty())
        vm.dismissOptions()
        assertNull(vm.betterThan)
    }

    @Test fun nothingHigherSendsTheNoneEventAndKeepsTheUsualChoice() {
        rows = listOf(video("v360", 360), video("v720", 720))
        val none = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        scope.launch { vm.betterNoneEvents.collect { none.complete(Unit) } }
        vm.lookForBetter(link, video("v720", 720)); pump()
        assertTrue(none.isCompleted)
        assertTrue(store.all().isEmpty())
        scope.cancel()
    }

    @Test fun editingTheFieldDropsTheBetterNote() {
        vm.lookForBetter(link, video("v360", 360)); pump()
        vm.edit("$link?x=2")
        assertNull(vm.betterThan)
    }
}
