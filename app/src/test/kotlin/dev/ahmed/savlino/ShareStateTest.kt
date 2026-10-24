package dev.ahmed.savlino

import android.content.Intent
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.entry.ShareInput
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
class ShareStateTest {
    private val first = "https://youtu.be/clipA"
    private val second = "https://youtu.be/clipB"
    private class Engine : EngineAdapter by UnavailableEngine() {
        override val isAvailable = true
        val inspected = mutableListOf<LinkRevision>()
        val cancelled = mutableListOf<String>()
        override suspend fun installedVersion() = "fixture-v1"
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            inspected += source
            return InspectResult.Success(Inspection(source, "fixture-v1",
                listOf(Format("v720", "mp4", true, true, height = 720),
                    Format("v360", "mp4", true, true, height = 360)),
                listOf(PresetChoice(Preset.BALANCED, "v720"))))
        }
        override fun cancel(operationId: String) { cancelled += operationId }
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun share(text: String) = ShareInput.from(Intent(Intent.ACTION_SEND)
        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))
    private fun ready(engine: Engine): LinkViewModel = LinkViewModel(SavedStateHandle(), engine = engine).apply {
        edit(first); checkLink(); idle(); selectFormat("v360")
        assertTrue(optionsState is OptionsState.Ready)
        assertEquals("v360", selectedFormat?.id)
    }

    @Test fun invalidSharePreservesInspectionSelectionAndExpandedOptions() {
        val engine = Engine(); val vm = ready(engine)
        vm.showMoreFormats()
        val options = vm.optionsState
        vm.onNewShare(share("ordinary prose")); idle()
        assertEquals(first, vm.text); assertSame(options, vm.optionsState)
        assertEquals("v360", vm.selectedFormat?.id); assertTrue(vm.moreFormats)
        assertTrue(vm.invalidShare); assertTrue(vm.downloadEnabled)
        assertTrue(engine.cancelled.isEmpty()); assertEquals(1, engine.inspected.size)
        assertNull(vm.takeConfirmedCommand())
    }

    @Test fun dismissedReplacementPreservesInspectionAndSelection() {
        val engine = Engine(); val vm = ready(engine); val options = vm.optionsState
        vm.onNewShare(share(second)); idle()
        assertTrue(vm.hasPendingShare); assertSame(options, vm.optionsState)
        vm.dismissShare(); idle()
        assertEquals(first, vm.text); assertSame(options, vm.optionsState)
        assertEquals("v360", vm.selectedFormat?.id); assertTrue(vm.downloadEnabled)
        assertTrue(engine.cancelled.isEmpty()); assertEquals(1, engine.inspected.size)
        assertNull(vm.takeConfirmedCommand())
    }

    @Test fun acceptedReplacementInvalidatesOldOptionsBeforeManualInspection() {
        val engine = Engine(); val vm = ready(engine)
        vm.automaticOptions = false
        vm.onNewShare(share(second)); vm.acceptShare(); idle()
        assertEquals(second, vm.text); assertFalse(vm.hasPendingShare)
        assertEquals(OptionsState.Hidden, vm.optionsState); assertNull(vm.selectedFormat)
        assertFalse(vm.downloadEnabled); assertEquals(1, engine.inspected.size)
        assertNull(vm.takeConfirmedCommand())
    }

    @Test fun distinctShareWaitsForReplacementAcceptanceThenInspectsIt() {
        val engine = Engine(); val vm = ready(engine); val options = vm.optionsState
        vm.onNewShare(share("$second")); idle()
        assertTrue(vm.hasPendingShare); assertSame(options, vm.optionsState)
        assertEquals(1, engine.inspected.size)
        vm.acceptShare(); idle()
        assertEquals(second, vm.text)
        assertEquals(listOf(first, second), engine.inspected.map { it.link.rawUrl })
        assertNotSame(options, vm.optionsState); assertNull(vm.takeConfirmedCommand())
    }

    @Test fun repeatedIdenticalShareKeepsCurrentInspection() {
        val engine = Engine(); val vm = ready(engine); val options = vm.optionsState
        vm.onNewShare(share("$first $first")); idle()
        assertFalse(vm.invalidShare); assertFalse(vm.hasPendingShare)
        assertSame(options, vm.optionsState); assertEquals("v360", vm.selectedFormat?.id)
        assertEquals(1, engine.inspected.size)
    }

    @Test fun pasteAndColdShareInspectFirstValidCandidateOnly() {
        for (paste in listOf(false, true)) {
            val engine = Engine(); val vm = LinkViewModel(SavedStateHandle(), engine = engine)
            val input = "http://localhost/no https://localhost/no $first"
            if (paste) vm.paste(input) else vm.onCreateShare(share(input))
            idle()
            assertEquals(first, vm.text)
            assertEquals(listOf(first), engine.inspected.map { it.link.rawUrl })
            assertTrue(vm.optionsState is OptionsState.Ready); assertNull(vm.takeConfirmedCommand())
        }
    }
}
