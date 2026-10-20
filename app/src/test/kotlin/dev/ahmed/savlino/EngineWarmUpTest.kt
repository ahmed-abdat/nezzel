package dev.ahmed.savlino

import android.content.Intent
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.entry.ShareInput
import dev.ahmed.savlino.ui.LinkViewModel
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EngineWarmUpTest {
    private class Engine : EngineAdapter {
        override val isAvailable = true
        var initialized = false
        var succeeds = true
        var attempts = 0
        override suspend fun warmUp(): Boolean { attempts++; initialized = succeeds; return succeeds }
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult = error("intake must not inspect")
        override suspend fun download(command: DownloadCommand, directory: File, progress: (EngineEvent) -> Unit): TransferResult = error("intake must not transfer")
        override fun cancel(operationId: String) = Unit
    }
    @Test fun validPasteWarmsLocallyButInvalidInputDoesNot() {
        val engine = Engine(); val vm = LinkViewModel(SavedStateHandle(), engine = engine).apply { automaticOptions = false }
        vm.paste("not a URL"); shadowOf(Looper.getMainLooper()).idle(); assertFalse(engine.initialized)
        vm.paste("https://youtu.be/one"); shadowOf(Looper.getMainLooper()).idle(); assertTrue(engine.initialized)
        assertNull(vm.takeConfirmedCommand())
    }
    @Test fun validShareWarmsLocallyWithoutReplacingAnExistingDraft() {
        val engine = Engine(); val vm = LinkViewModel(SavedStateHandle(), engine = engine).apply { automaticOptions = false }
        vm.edit("draft")
        vm.onNewShare(ShareInput.from(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://youtu.be/shared")))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(engine.initialized); assertEquals("draft", vm.text); assertTrue(vm.hasPendingShare)
        assertNull(vm.takeConfirmedCommand())
    }
    @Test fun detectedClipboardWarmsBeforeAcceptanceAndRestoredLinkWarmsOnForeground() {
        val engine = Engine(); val vm = LinkViewModel(SavedStateHandle(), engine = engine).apply { automaticOptions = false }
        vm.suggest(true, true, true) { "https://youtu.be/one" }
        shadowOf(Looper.getMainLooper()).idle(); assertTrue(engine.initialized)
        assertEquals("", vm.text); assertTrue(vm.hasSuggestion)
        val restoredEngine = Engine()
        val restored = LinkViewModel(SavedStateHandle(mapOf("text" to "https://youtu.be/two")), engine = restoredEngine)
        restored.onForeground(); shadowOf(Looper.getMainLooper()).idle(); assertTrue(restoredEngine.initialized)
    }
    @Test fun failedWarmUpCanRetryAndSuccessfulWarmUpIsRemembered() {
        val engine = Engine().apply { succeeds = false }
        val vm = LinkViewModel(SavedStateHandle(), engine = engine).apply { automaticOptions = false }
        vm.paste("https://youtu.be/one"); shadowOf(Looper.getMainLooper()).idle()
        assertFalse(engine.initialized); assertEquals(1, engine.attempts)
        engine.succeeds = true
        vm.onForeground(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(engine.initialized); assertEquals(2, engine.attempts)
        vm.onForeground(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, engine.attempts); assertNull(vm.takeConfirmedCommand())
    }

    @Test fun retryOnEngineUnavailableAsksTheEngineToInitializeAgain() {
        val engine = object : EngineAdapter {
            override var isAvailable = false
            var retries = 0
            var inspections = 0
            override fun retryInitialization() { retries++; isAvailable = true }
            override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
                inspections++; return InspectResult.Failure(DownloadError.Network)
            }
            override suspend fun download(command: DownloadCommand, directory: File, progress: (EngineEvent) -> Unit): TransferResult = error("no transfer")
            override fun cancel(operationId: String) = Unit
        }
        val vm = LinkViewModel(SavedStateHandle(), engine = engine).apply { automaticOptions = false }
        vm.paste("https://youtu.be/one"); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(dev.ahmed.savlino.ui.OptionsState.Error(DownloadError.EngineUnavailable), vm.optionsState)
        assertEquals(0, engine.retries)
        vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, engine.retries); assertEquals(1, engine.inspections)
        assertEquals(dev.ahmed.savlino.ui.OptionsState.Error(DownloadError.Network), vm.optionsState)
    }
}
