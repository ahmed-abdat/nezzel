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
    private fun allQualities(text: String) = ShareInput.from(Intent(Intent.ACTION_SEND)
        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).also { ShareInput.handoffAllQualities(it) })
    private fun ready(engine: Engine): LinkViewModel = LinkViewModel(SavedStateHandle(), engine = engine).apply {
        edit(first); checkLink(); idle(); selectFormat("v360")
        assertTrue(optionsState is OptionsState.Ready)
        assertEquals("v360", selectedFormat?.id)
    }

    @Test fun singleAndMultiLinkSharesCloseSearch() {
        for (input in listOf(first, "$first $second")) {
            val vm = LinkViewModel(SavedStateHandle(), engine = Engine())
            vm.automaticOptions = false
            vm.search.open("cats"); idle()
            assertTrue(vm.search.visible)
            vm.onNewShare(share(input)); idle()
            assertFalse(vm.search.visible)
            assertEquals(input != first, vm.multi.visible)
        }
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

    @Test fun foreignAllQualitiesExtraDoesNotInspectWithAutomaticLoadingOff() {
        val engine = Engine()
        val vm = LinkViewModel(SavedStateHandle(), engine = engine).apply { automaticOptions = false }
        val foreign = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, first)
            .putExtra("dev.ahmed.savlino.ALL_QUALITIES", true)
        vm.onCreateShare(ShareInput.from(foreign)); idle()
        assertEquals(first, vm.text)
        assertEquals(OptionsState.Hidden, vm.optionsState)
        assertTrue(engine.inspected.isEmpty())
        assertFalse(vm.moreFormats)
        vm.checkLink(); idle()
        assertEquals(1, engine.inspected.size)
        assertTrue(vm.optionsState is OptionsState.Ready)
        assertFalse(vm.moreFormats)
    }

    @Test fun allQualitiesHandoffIsBoundToTheTextAndConsumedOnce() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, first)
        ShareInput.handoffAllQualities(intent)
        assertFalse(ShareInput.from(Intent(intent).putExtra(Intent.EXTRA_TEXT, second))!!.allQualities)
        assertFalse(share(first)!!.allQualities)
        assertTrue(ShareInput.from(intent)!!.allQualities)
        assertFalse(ShareInput.from(intent)!!.allQualities)
    }

    @Test fun allQualitiesHandoffWaitsForTheInspectionEvenWithAutomaticLoadingOff() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val engine = Engine()
        val waiting = object : EngineAdapter by engine {
            override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
                gate.await()
                return engine.inspect(source, operationId)
            }
        }
        val vm = LinkViewModel(SavedStateHandle(), engine = waiting).apply { automaticOptions = false }
        vm.onCreateShare(allQualities(first)); idle()
        assertTrue(vm.optionsState is OptionsState.Checking)
        assertFalse(vm.moreFormats)
        gate.complete(Unit); idle()
        assertTrue(vm.optionsState is OptionsState.Ready)
        assertTrue(vm.moreFormats)
        assertNull(vm.takeConfirmedCommand())
    }

    @Test fun allQualitiesHandoffReusesReadyOptionsAndRespectsTheDraftGuard() {
        val engine = Engine(); val vm = ready(engine)
        vm.onNewShare(allQualities(first)); idle()
        assertTrue(vm.moreFormats)
        assertEquals("v360", vm.selectedFormat?.id)
        assertEquals(1, engine.inspected.size)
        vm.automaticOptions = false
        vm.onNewShare(allQualities(second)); idle()
        assertTrue(vm.hasPendingShare)
        assertEquals(first, vm.text)
        vm.acceptShare(); idle()
        assertEquals(second, vm.text)
        assertTrue(vm.moreFormats)
        assertEquals(listOf(first, second), engine.inspected.map { it.link.rawUrl })
        assertNull(vm.takeConfirmedCommand())
    }

    @Test fun failedAllQualitiesHandoffDoesNotOpenTheFullListOnRetry() {
        val engine = Engine()
        var fail = true
        val failing = object : EngineAdapter by engine {
            override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult =
                if (fail) InspectResult.Failure(DownloadError.Failed) else engine.inspect(source, operationId)
        }
        val vm = LinkViewModel(SavedStateHandle(), engine = failing).apply { automaticOptions = false }
        vm.onCreateShare(allQualities(first)); idle()
        assertTrue(vm.optionsState is OptionsState.Error)
        assertFalse(vm.moreFormats)
        fail = false
        vm.checkLink(); idle()
        assertTrue(vm.optionsState is OptionsState.Ready)
        assertFalse(vm.moreFormats)
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
