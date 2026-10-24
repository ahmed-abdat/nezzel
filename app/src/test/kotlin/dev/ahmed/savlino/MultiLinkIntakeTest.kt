package dev.ahmed.savlino

import android.content.Intent
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.entry.ShareInput
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.OptionsState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MultiLinkIntakeTest {
    private val links = listOf("https://www.tiktok.com/@a/video/1", "https://x.com/a/status/2?private=token", "https://youtu.be/abcdefghijk")
    private val text = "watch these: " + links.joinToString("\n")
    private class Engine : EngineAdapter by UnavailableEngine() {
        override val isAvailable = true
        var calls = 0
        override suspend fun installedVersion() = "fixture"
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            calls++
            return InspectResult.Success(Inspection(source, "fixture", listOf(Format("720", "mp4", true, true, height = 720)),
                listOf(PresetChoice(Preset.BALANCED, "720"))))
        }
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun share(value: String) = ShareInput.from(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value))

    @Test fun singleLinkPasteKeepsTodaysFlow() {
        val engine = Engine(); val vm = LinkViewModel(SavedStateHandle(), engine = engine)
        vm.paste(links[0]); idle()
        assertFalse(vm.multi.visible); assertEquals(links[0], vm.text)
        assertTrue(vm.optionsState is OptionsState.Ready); assertEquals(1, engine.calls)
    }
    @Test fun pasteAndShareOfSeveralLinksOpenTheSheetWithZeroEngineCallsEvenWithAutomaticLoading() {
        val engine = Engine(); val state = SavedStateHandle()
        val vm = LinkViewModel(state, engine = engine)
        assertTrue(vm.automaticOptions)
        vm.paste(text); idle()
        assertTrue(vm.multi.visible); assertEquals(links, vm.multi.links.map { it.rawUrl })
        assertEquals("", vm.text); assertEquals(OptionsState.Hidden, vm.optionsState); assertNull(vm.nextLinkHost)
        vm.dismissMulti(); assertFalse(vm.multi.visible)
        vm.onNewShare(share(text)); idle()
        assertTrue(vm.multi.visible); assertEquals(3, vm.multi.links.size)
        assertEquals(0, engine.calls)
        assertFalse(vm.clipboardEligible)
        // Memory only: no link reaches SavedStateHandle.
        val snapshot = state.keys().associateWith { state.get<Any?>(it) }.toString()
        links.forEach { assertFalse(snapshot.contains(it.substringAfter("://"))) }
    }
    @Test fun automaticClipboardSuggestionNeverOpensTheSheet() {
        val engine = Engine(); val vm = LinkViewModel(SavedStateHandle(), engine = engine)
        vm.suggest(true, true, true) { text }; idle()
        assertFalse(vm.multi.visible); assertEquals(links[0], vm.text)
        vm.dismissOptions(); assertEquals(2, vm.nextLinkNumber); assertEquals(3, vm.linkQueueTotal)
    }
    @Test fun removeAndPresetChangeTheSelectionOnly() {
        val vm = LinkViewModel(SavedStateHandle(), engine = Engine())
        vm.paste(text); vm.multi.remove(1); vm.multi.quality(Preset.AUDIO_M4A)
        assertEquals(listOf(links[0], links[2]), vm.multi.links.map { it.rawUrl }); assertEquals(Preset.AUDIO_M4A, vm.multi.preset)
        vm.multi.remove(0); vm.multi.remove(0); assertFalse(vm.multi.visible)
    }
    @Test fun oneConfirmCreatesOneBatchWithEveryLinkSealedAndNoInspectionBeforeIt() {
        val app = RuntimeEnvironment.getApplication() as IsolatedTestApplication
        app.deleteDatabase(JobStore.DATABASE_NAME)
        val store = JobStore(app)
        val engine = Engine()
        val coordinator = DownloadCoordinator(app, engine = engine, store = store)
        val parent = Job()
        val scope = CoroutineScope(parent + Dispatchers.Main.immediate)
        dev.ahmed.savlino.storage.KeystoreLinkCipher.testOverride = batchCipher() // no Android Keystore in the JVM
        try {
            val vm = LinkViewModel(SavedStateHandle(), engine = engine, downloads = coordinator, coordinatorScope = scope)
            vm.onNewShare(share(text)); idle()
            assertEquals(0, engine.calls)
            vm.multi.confirm("3 links"); idle()
            runBlocking { withTimeout(5000) { while (vm.multi.submitting) { idle(); delay(10) } } }
            assertFalse(vm.multi.submitError); assertFalse(vm.multi.visible)
            val batch = coordinator.batches.all().single()
            assertEquals("3 links", batch.title)
            val rows = coordinator.batches.items(batch.id)
            assertEquals(3, rows.size); assertEquals(links, rows.map { coordinator.batches.source(it) })
            assertEquals(batch.id, vm.acceptedJob)
        } finally { parent.cancel(); store.close(); dev.ahmed.savlino.storage.KeystoreLinkCipher.testOverride = null }
    }
}
