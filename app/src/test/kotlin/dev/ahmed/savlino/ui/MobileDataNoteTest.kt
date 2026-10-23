package dev.ahmed.savlino.ui

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.FakeEngine
import dev.ahmed.savlino.engine.InspectResult
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.texts
import dev.ahmed.savlino.descendants
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.ahmed.savlino.ui.theme.NezzelTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetworkCapabilities

/** The "large file on mobile data" line: shown only for a known size ≥ 100 MB on a metered network; never blocks Download. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MobileDataNoteTest {
    private val url = "https://example.org/watch?v=one"

    @Test fun ruleNeedsMeteredAndAKnownLargeSize() {
        val big = Format("b", "mp4", true, true, size = Size.Exact(LARGE_ON_MOBILE_DATA_BYTES))
        assertTrue(largeOnMobileData(big, null, metered = true))
        assertFalse(largeOnMobileData(big, null, metered = false))
        assertFalse(largeOnMobileData(big.copy(size = Size.Exact(LARGE_ON_MOBILE_DATA_BYTES - 1)), null, metered = true))
        assertTrue(largeOnMobileData(big.copy(size = Size.Estimated(250_000_000)), null, metered = true))
        assertFalse(largeOnMobileData(big.copy(size = Size.Unknown), null, metered = true))
        // A rough size is the same ≈ figure the Download button already shows.
        assertTrue(largeOnMobileData(big.copy(size = Size.Unknown), 300_000_000, metered = true))
    }

    @Test fun meteredAndLargeShowsTheNoteAndDownloadStaysEnabled() = render(Size.Exact(240_000_000), metered = true) { activity, vm ->
        assertTrue(note(activity))
        assertEquals(listOf("Uses \u2066240\u00a0MB\u2069 of your mobile data"), noteTexts(activity))
        assertTrue(vm.downloadEnabled)
    }

    @Test fun estimatedSizeSaysAboutWithoutTheApproxSign() = render(Size.Estimated(240_000_000), metered = true) { activity, _ ->
        assertEquals(listOf("Uses about \u2066240\u00a0MB\u2069 of your mobile data"), noteTexts(activity))
    }

    @Test @Config(qualifiers = "ar") fun arabicKeepsTheSizeIsolated() = render(Size.Estimated(240_000_000), metered = true) { activity, _ ->
        assertEquals(listOf("يستهلك نحو \u2066240\u00a0MB\u2069 من بيانات هاتفك"), noteTexts(activity))
    }

    @Test fun unmeteredShowsNoNote() = render(Size.Exact(240_000_000), metered = false) { activity, _ -> assertFalse(note(activity)) }

    @Test fun unknownSizeShowsNoNote() = render(Size.Unknown, metered = true) { activity, _ -> assertFalse(note(activity)) }

    @Test fun smallFileShowsNoNote() = render(Size.Exact(40_000_000), metered = true) { activity, _ -> assertFalse(note(activity)) }

    @Test fun networkSwitchUpdatesTheNoteAndDismissUnregisters() {
        render(Size.Exact(240_000_000), metered = false, onClose = { assertTrue(callbacks().isEmpty()) }) { activity, _ ->
            assertFalse(note(activity))
            assertEquals(1, callbacks().size)
            switchTo(metered = true)
            assertTrue(note(activity))
            switchTo(metered = false)
            assertFalse(note(activity))
        }
    }

    @Test fun lostNetworkHidesTheNoteUntilTheNextOneReports() =
        render(Size.Exact(240_000_000), metered = true) { activity, _ ->
            assertTrue(note(activity))
            val network = connectivity().activeNetwork!!
            callbacks().forEach { it.onLost(network) }
            assertFalse(note(activity))
            switchTo(metered = true)
            assertTrue(note(activity))
        }

    @Test fun unmeteredVpnShowsNoNote() = render(Size.Exact(240_000_000), metered = true) { activity, _ ->
        assertTrue(note(activity))
        val vpn = capabilities(metered = false).also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_VPN) }
        callbacks().forEach { it.onCapabilitiesChanged(connectivity().activeNetwork!!, vpn) }
        assertFalse(note(activity))
    }

    @Test @Config(shadows = [RefusingConnectivity::class]) fun failedRegistrationShowsNoNoteAndDoesNotCrash() =
        render(Size.Exact(240_000_000), metered = true) { activity, vm ->
            assertTrue(callbacks().isEmpty())
            assertFalse(note(activity))
            assertTrue(vm.downloadEnabled)
        }

    private lateinit var activity: ComponentActivity
    // Own recomposer and frame clock: the shared Android one may be tied to an earlier test's looper and never send a frame.
    private val clock = BroadcastFrameClock()

    // The sheet reads the network through its own (activity) context, so the fixture drives that manager.
    private fun connectivity(): ConnectivityManager = activity.getSystemService(ConnectivityManager::class.java)

    private fun callbacks() = shadowOf(connectivity()).networkCallbacks

    private fun capabilities(metered: Boolean): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also {
        shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!metered) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun setNetwork(metered: Boolean): NetworkCapabilities {
        val cm = connectivity()
        return capabilities(metered).also { shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, it) }
    }

    private fun switchTo(metered: Boolean) {
        val caps = setNetwork(metered)
        val network = connectivity().activeNetwork!!
        callbacks().forEach { it.onCapabilitiesChanged(network, caps) }
    }

    private fun noteNodes(activity: ComponentActivity): List<SemanticsNode> {
        semanticsNodes(activity, allWindows = false)
        clock.sendFrame(System.nanoTime())
        return semanticsNodes(activity, allWindows = false)
            .filter { it.config.getOrNull(SemanticsProperties.TestTag) == "mobile_data_note" }
    }

    private fun note(activity: ComponentActivity) = noteNodes(activity).isNotEmpty()

    private fun noteTexts(activity: ComponentActivity) = noteNodes(activity).flatMap { texts(descendants(it)) }

    private fun render(size: Size, metered: Boolean, onClose: () -> Unit = {}, check: (ComponentActivity, LinkViewModel) -> Unit) {
        val format = Format("v", "mp4", true, true, height = 1080, size = size)
        val inspection = Inspection(LinkRevision(LinkInput.parse(url)!!, 1), "fixture-v1", listOf(format),
            listOf(PresetChoice(Preset.BEST, "v")), title = "Clip", durationSeconds = 60)
        val vm = LinkViewModel(SavedStateHandle(), engine = FakeEngine(InspectResult.Success(inspection)), currentEngineVersion = { "fixture-v1" })
        vm.edit(url); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle(); vm.selectPreset(Preset.BEST)
        val language = org.robolectric.RuntimeEnvironment.getQualifiers().let { if ("ar" in it.split('-')) "ar" else "en" }
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        activity = controller.get()
        setNetwork(metered)
        var shown by mutableStateOf(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        val recomposer = Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        try {
            controller.get().setContent(parent = recomposer) {
                NezzelTheme(ThemePref.Light, false) { Surface { if (shown) ConfirmationScreen(vm, language) } }
            }
            check(controller.get(), vm)
            // Dismissing the sheet leaves composition, which must drop the network callback.
            shown = false
            note(controller.get())
            onClose()
        } finally { recomposer.cancel(); scope.cancel(); controller.pause().stop().destroy() }
    }
}

/** A platform that refuses the callback (some OEM builds throw SecurityException on register). */
@Implements(ConnectivityManager::class)
class RefusingConnectivity : ShadowConnectivityManager() {
    @Implementation
    override fun registerDefaultNetworkCallback(networkCallback: ConnectivityManager.NetworkCallback) =
        throw SecurityException("refused")
}
