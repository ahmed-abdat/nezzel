package dev.ahmed.savlino.ui.nav

import android.os.Looper
import android.view.ViewGroup
import androidx.compose.runtime.snapshots.Snapshot
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.NezzelApp
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What a warm tab switch recomposes in the real app tree: the tap frame, then the fade and settle frames. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w400dp-h800dp-mdpi")
class TabSwitchCostTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun frame() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun warmSwitchCost() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val activity = controller.get()
        val links = LinkViewModel(SavedStateHandle())
        try {
            activity.setContent {
                NezzelTheme(ThemePref.Light, false) {
                    NezzelApp(links, "en", ThemePref.Light, false, true, {}, {}, {}, {}, {}, {}, {})
                }
            }
            compose.waitForIdle()
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            RecompositionProbe.attach(root).use { probe ->
                probe.readFilter = Regex("^MutableState\\(value=(Home|Downloads|Statuses|Settings)\\)")
                val route = listOf("Downloads", "Statuses", "Settings", "Home")
                // First lap composes each tab once; the second lap is the warm switch the phone measures.
                for (lap in 1..2) for (destination in route) {
                    compose.mainClock.autoAdvance = false
                    val tap = probe.record {
                        compose.runOnUiThread { links.navigate(destination); Snapshot.sendApplyNotifications() }
                        frame()
                    }
                    val invalid = RecompositionProbe.describe(probe.invalidations)
                    val readers = RecompositionProbe.describe(probe.readers)
                    val rest = probe.record { repeat(60) { frame() } }
                    compose.mainClock.autoAdvance = true
                    compose.waitForIdle()
                    println("PROBE lap$lap $destination tap=${RecompositionProbe.total(tap)} rest=${RecompositionProbe.total(rest)}")
                    if (lap == 2) {
                        println("PROBE   tap: ${RecompositionProbe.describe(tap)}")
                        println("PROBE   invalid: $invalid")
                        println("PROBE   readers: $readers")
                        println("PROBE   rest: ${RecompositionProbe.describe(rest)}")
                    }
                }
            }
        } finally { controller.pause().stop().destroy() }
    }
}
