package dev.ahmed.savlino.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.engine.mediahttp.EarlyPreview
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class EarlyPreviewTest {
    private val youtube = "https://youtu.be/abcdefghijk"
    private val preview = EarlyPreview("Real early title", "https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg")
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private class HeldEngine : EngineAdapter by FakeEngine() {
        val result = CompletableDeferred<InspectResult>()
        override suspend fun inspect(source: LinkRevision, operationId: String) = result.await()
    }

    @Test fun earlyTitleAppearsWhileEngineWaitsAndEngineMetadataWins() {
        val engine = HeldEngine()
        val response = CompletableDeferred<EarlyPreview?>()
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, earlyPreview = { response.await() })
        vm.paste(youtube); idle()
        response.complete(preview); idle()
        assertEquals("Real early title", (vm.optionsState as OptionsState.Checking).earlyPreview?.title)
        val source = (vm.optionsState as OptionsState.Checking).source
        engine.result.complete(InspectResult.Success(Inspection(source, "test-version", listOf(
            Format("18", "mp4", true, true, height = 360)), title = "Final title", thumbnailUrl = "https://i.ytimg.com/final.jpg")))
        idle()
        val ready = vm.optionsState as OptionsState.Ready
        assertEquals("Final title", ready.inspection.title)
        assertEquals("https://i.ytimg.com/final.jpg", ready.inspection.thumbnailUrl)
        vm.dismissOptions(); idle()
    }

    @Test fun youtubeImageIsKnownBeforeOEmbedAnswers() {
        val response = CompletableDeferred<EarlyPreview?>()
        val vm = LinkViewModel(SavedStateHandle(), engine = HeldEngine(), earlyPreview = { response.await() })
        vm.paste(youtube); idle()
        val early = (vm.optionsState as OptionsState.Checking).earlyPreview
        assertNull(early?.title)
        assertEquals(preview.thumbnailUrl, early?.thumbnailUrl)
        response.complete(EarlyPreview("Real early title", null)); idle()
        val filled = (vm.optionsState as OptionsState.Checking).earlyPreview
        assertEquals("Real early title", filled?.title)
        assertEquals(preview.thumbnailUrl, filled?.thumbnailUrl)
        vm.dismissOptions(); idle()
    }

    @Test fun lateResponseCannotReplaceFinishedInspection() {
        val engine = HeldEngine()
        val response = CompletableDeferred<EarlyPreview?>()
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, earlyPreview = {
            withContext(NonCancellable) { response.await() }
        })
        vm.paste(youtube); idle()
        val source = (vm.optionsState as OptionsState.Checking).source
        engine.result.complete(InspectResult.Success(Inspection(source, "test-version",
            listOf(Format("18", "mp4", true, true, height = 360)), title = "Engine title"))); idle()
        val ready = vm.optionsState
        response.complete(preview); idle()
        assertSame(ready, vm.optionsState)
        assertEquals("Engine title", (vm.optionsState as OptionsState.Ready).inspection.title)
        vm.dismissOptions(); idle()
    }

    @Test fun failureIsSilentAndCancelClearsPreview() {
        val vm = LinkViewModel(SavedStateHandle(), engine = HeldEngine(), earlyPreview = { error("HTTP failure") })
        vm.paste(youtube); idle()
        // The id-derived image stays; a failed oEmbed only means no early title.
        val early = (vm.optionsState as OptionsState.Checking).earlyPreview
        assertNull(early?.title)
        assertEquals(preview.thumbnailUrl, early?.thumbnailUrl)
        vm.dismissOptions(); idle()
        assertEquals(OptionsState.Hidden, vm.optionsState)
    }

    @Test fun engineFailureAndDismissalDropAnAlreadyVisiblePreview() {
        val engine = HeldEngine()
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, earlyPreview = { preview })
        vm.paste(youtube); idle()
        assertNotNull((vm.optionsState as OptionsState.Checking).earlyPreview)
        engine.result.complete(InspectResult.Failure(DownloadError.Network)); idle()
        assertEquals(OptionsState.Error(DownloadError.Network), vm.optionsState)
        vm.checkLink(); idle()
        vm.dismissOptions(); idle()
        assertEquals(OptionsState.Hidden, vm.optionsState)
    }

    @Test fun clipboardPreviewWaitsForDurableAuthorization() {
        val persisted = CompletableDeferred<Boolean>()
        var requests = 0
        val vm = LinkViewModel(SavedStateHandle(), engine = HeldEngine(),
            rememberHandledClipboard = { persisted.await() }, earlyPreview = { requests++; preview })
        vm.suggest(true, true, true) { youtube }; idle()
        assertEquals(0, requests)
        assertEquals(OptionsState.Hidden, vm.optionsState)
        persisted.complete(true); idle()
        assertEquals("Real early title", (vm.optionsState as OptionsState.Checking).earlyPreview?.title)
        vm.dismissOptions(); idle()
    }

    @Test fun autoLoadOffDoesNotRequestUntilExplicitCheckAndOtherHostsNeverRequest() {
        var requests = 0
        val vm = LinkViewModel(SavedStateHandle(), engine = HeldEngine(), earlyPreview = { requests++; preview })
        vm.automaticOptions = false
        vm.paste(youtube); idle()
        assertEquals(0, requests)
        vm.checkLink(); idle()
        assertEquals("Real early title", (vm.optionsState as OptionsState.Checking).earlyPreview?.title)
        vm.paste("https://example.com/video"); vm.checkLink(); idle()
        assertNull((vm.optionsState as OptionsState.Checking).earlyPreview)
        assertEquals(1, requests)
        vm.dismissOptions(); idle()
    }
}
