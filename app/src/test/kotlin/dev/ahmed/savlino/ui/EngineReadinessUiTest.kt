package dev.ahmed.savlino.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.LinkRevision
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.texts
import dev.ahmed.savlino.ui.theme.NezzelTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EngineReadinessUiTest {
    private class WaitingEngine : EngineAdapter by FakeEngine() {
        override val waitingOperations = MutableStateFlow<Set<String>>(emptySet())
        var cancelled = false
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            waitingOperations.value = setOf(operationId)
            try { awaitCancellation() }
            finally { waitingOperations.value = emptySet(); cancelled = true }
        }
    }
    @Test fun englishReadinessIsShownAndDismissCancelsWaitingInspection() = sheet("en", "Getting ready…")
    @Test @Config(qualifiers = "ar") fun arabicReadinessIsShownAndDismissCancelsWaitingInspection() = sheet("ar", "جارٍ التجهيز…")
    private fun sheet(language: String, expected: String) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        val recomposer = Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        val engine = WaitingEngine()
        val vm = LinkViewModel(SavedStateHandle(), engine = engine)
        try {
            vm.paste("https://example.com/video")
            shadowOf(Looper.getMainLooper()).idle()
            controller.get().setContent(parent = recomposer) {
                CompositionLocalProvider(LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    NezzelTheme(ThemePref.Dark, false) { OptionsSheetContent(vm, language, vm::dismissOptions) }
                }
            }
            repeat(4) {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                clock.sendFrame(System.nanoTime())
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            }
            val labels = texts(semanticsNodes(controller.get(), rounds = 4, idleMillis = 200))
            assertTrue(labels.toString(), expected in labels)
            assertTrue(vm.optionsState is OptionsState.Checking)
            vm.dismissOptions(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(engine.cancelled)
            assertEquals(OptionsState.Hidden, vm.optionsState)
        } finally { vm.dismissOptions(); recomposer.cancel(); scope.cancel(); controller.pause().stop().destroy() }
    }
}
