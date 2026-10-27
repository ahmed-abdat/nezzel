package dev.ahmed.savlino.ui.nav

import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.NezzelApp
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.engine.FakeEngine
import dev.ahmed.savlino.jobs.DownloadCoordinator
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.theme.NezzelTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What one download progress tick recomposes in the real app tree, with the ticking job's tab shown and hidden. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w400dp-h800dp-mdpi")
class ProgressTickCostTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun frame() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun job(id: String, bytes: Long) =
        DownloadProgress(id, "$id.mp4", DownloadPhase.DOWNLOADING, bytes = bytes, total = Size.Exact(100_000_000))

    @Suppress("UNCHECKED_CAST")
    @Test fun aTickRecomposesOnlyTheTickingCard() {
        val coordinator = DownloadCoordinator(RuntimeEnvironment.getApplication(), engine = FakeEngine())
        // Test-only: the coordinator's own list, so a tick needs no engine run.
        val progress = DownloadCoordinator::class.java.getDeclaredField("mutableProgress").apply { isAccessible = true }
            .get(coordinator) as MutableStateFlow<List<DownloadProgress>>
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val activity = controller.get()
        val links = LinkViewModel(SavedStateHandle(), downloads = coordinator)
        try {
            activity.setContent {
                NezzelTheme(ThemePref.Light, false) {
                    NezzelApp(links, "en", ThemePref.Light, false, true, {}, {}, {}, {}, {}, {}, {})
                }
            }
            compose.waitForIdle()
            compose.runOnUiThread { progress.value = listOf(job("r1", 1_000_000), job("r2", 2_000_000), job("r3", 3_000_000)) }
            compose.waitForIdle()
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            RecompositionProbe.attach(root).use { probe ->
                // Shown: Home's job cards, then Downloads' rows. Hidden: Downloads while Home is in front.
                for ((step, destination) in listOf("home" to "Home", "downloads" to "Downloads", "hidden-downloads" to "Home")) {
                    compose.runOnUiThread { links.navigate(destination) }
                    compose.waitForIdle()
                    compose.mainClock.autoAdvance = false
                    var tick = emptyMap<String, Int>()
                    // The first tick may still settle a card; the second is the steady state.
                    repeat(2) {
                        tick = probe.record {
                            compose.runOnUiThread {
                                progress.value = progress.value.map { if (it.jobId == "r1") it.copy(bytes = it.bytes!! + 1_000_000) else it }
                                Snapshot.sendApplyNotifications()
                            }
                            frame()
                        }
                    }
                    val app = probe.detailed.filterKeys { it.startsWith("NezzelAppKt.") || it.startsWith("DownloadsScreenKt.") }
                    compose.mainClock.autoAdvance = true
                    compose.waitForIdle()
                    println("PROBE $step tick=${RecompositionProbe.total(tick)}")
                    println("PROBE   tick: ${RecompositionProbe.describe(tick)}")
                    println("PROBE   app: ${RecompositionProbe.describe(app)}")
                    println("PROBE   invalid: ${RecompositionProbe.describe(probe.invalidations)}")
                    // Before: 80 (Home) and 201 (Downloads, shown or hidden), with the app body and every screen re-running.
                    assertTrue("$step re-ran the app or Home: $app", app.keys.none { it.startsWith("NezzelAppKt.") })
                    if (step != "downloads") {
                        assertTrue("$step re-ran Downloads: $app", app.keys.none { it.startsWith("DownloadsScreenKt.DownloadsScreen") })
                        assertTrue("$step tick: ${RecompositionProbe.total(tick)}", RecompositionProbe.total(tick) <= 45)
                    } else assertTrue("$step tick: ${RecompositionProbe.total(tick)}", RecompositionProbe.total(tick) <= 130)
                }
            }
        } finally { controller.pause().stop().destroy() }
    }
}
