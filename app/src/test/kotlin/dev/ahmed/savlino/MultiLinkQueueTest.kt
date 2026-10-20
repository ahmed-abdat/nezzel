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
class MultiLinkQueueTest {
    private val first = "https://youtu.be/first"
    private val second = "https://second.org/second?private=token"
    private val third = "https://third.org/third"
    private val input get() = "$first $second $third"
    private class Engine : EngineAdapter by UnavailableEngine() {
        override val isAvailable = true
        override suspend fun installedVersion() = "fixture"
        override suspend fun inspect(source: LinkRevision, operationId: String) = InspectResult.Success(
            Inspection(source, "fixture", listOf(Format("720", "mp4", true, true, height = 720)),
                listOf(PresetChoice(Preset.BALANCED, "720"))))
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun share(value: String) = ShareInput.from(Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, value))
    private fun vm(state: SavedStateHandle = SavedStateHandle()) = LinkViewModel(state, engine = Engine())
    private fun assertNext(vm: LinkViewModel, number: Int, total: Int, host: String) {
        assertEquals(number, vm.nextLinkNumber); assertEquals(total, vm.linkQueueTotal)
        assertEquals(host, vm.nextLinkHost)
    }
    @Test fun shareDismissOpenRepeatsUntilQueueEmptyWithoutAutomaticDownload() {
        val vm = vm(); vm.onNewShare(share(input)); idle()
        assertEquals(first, vm.text); assertTrue(vm.optionsState is OptionsState.Ready)
        assertNull(vm.nextLinkHost)
        vm.dismissOptions(); assertNext(vm, 2, 3, "second.org")
        vm.openNextLink(); idle()
        assertEquals(second, (vm.optionsState as OptionsState.Ready).inspection.source.link.rawUrl)
        assertNull(vm.nextLinkHost); assertNull(vm.takeConfirmedCommand())
        assertFalse(vm.copiedLink); assertFalse(vm.shareActive)
        vm.dismissOptions(); vm.dismissOptions(); assertNext(vm, 3, 3, "third.org")
        vm.openNextLink(); idle(); assertEquals(third, vm.text)
        vm.dismissOptions(); assertNull(vm.nextLinkHost)
    }
    @Test fun queueNeverEntersSavedStateAndProcessRecreationDropsIt() {
        val state = SavedStateHandle(); val vm = vm(state)
        vm.onNewShare(share(input)); idle(); vm.dismissOptions()
        val snapshot = state.keys().associateWith { state.get<Any?>(it) }
        assertFalse(snapshot.values.toString().contains(second))
        assertFalse(snapshot.values.toString().contains(third))
        assertNull(vm(SavedStateHandle(snapshot)).nextLinkHost)
    }
    @Test fun newShareIncludingSameFirstAndPasteReplaceQueueAndSkipClearsIt() {
        val vm = vm(); vm.onNewShare(share(input)); idle()
        vm.onNewShare(share("$first https://replacement.org/new")); idle()
        vm.dismissOptions(); assertNext(vm, 2, 2, "replacement.org")
        vm.paste("https://new.org/one https://pasted.org/two"); idle()
        assertNull(vm.nextLinkHost)
        vm.dismissOptions(); assertNext(vm, 2, 2, "pasted.org")
        vm.skipRemainingLinks(); assertNull(vm.nextLinkHost)
        vm.dismissOptions(); assertNull(vm.nextLinkHost)
    }
    @Test fun openWithAutomaticLoadingDisabledOnlyFillsDraft() {
        val vm = vm(); vm.paste(input); idle(); vm.dismissOptions()
        vm.automaticOptions = false; vm.openNextLink(); idle()
        assertEquals(second, vm.text); assertEquals(OptionsState.Hidden, vm.optionsState)
        assertNull(vm.takeConfirmedCommand()); assertNull(vm.nextLinkHost)
        vm.checkLink(); idle(); vm.dismissOptions(); assertNext(vm, 3, 3, "third.org")
    }
    @Test fun clipboardIsReadOnceQueueWaitsForDurabilityAndBlocksAnotherOffer() {
        val durable = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val vm = LinkViewModel(SavedStateHandle(), engine = Engine(), rememberHandledClipboard = { durable.await() })
        var reads = 0
        vm.suggest(true, true, true) { reads++; input }; idle()
        assertEquals(1, reads); assertEquals(OptionsState.Hidden, vm.optionsState)
        assertNull(vm.nextLinkHost)
        durable.complete(true); idle(); assertEquals(first, vm.text)
        vm.dismissOptions(); assertEquals("", vm.text); assertNext(vm, 2, 3, "second.org")
        vm.suggest(true, true, true) { error("Queue must guard clipboard read") }
        vm.openNextLink(); idle(); assertEquals(second, vm.text)
    }
    @Test fun pasteOfActiveFirstDropsAnOlderPendingShareBeforeReplacingQueue() {
        val vm = vm(); vm.edit(first); vm.checkLink(); idle()
        vm.onNewShare(share("https://unaccepted.org/new https://old.org/remainder"))
        assertTrue(vm.hasPendingShare)
        vm.paste("$first $second")
        assertFalse(vm.hasPendingShare)
        vm.acceptShare(); assertEquals(first, vm.text)
        vm.dismissOptions(); assertNext(vm, 2, 2, "second.org")
    }
    @Test fun dismissedPendingShareDoesNotLeaveUnacceptedRemainder() {
        val vm = vm(); vm.edit("https://typed.org/draft")
        vm.onNewShare(share(input)); assertTrue(vm.hasPendingShare)
        vm.dismissShare(); vm.checkLink(); idle(); vm.dismissOptions()
        assertNull(vm.nextLinkHost)
    }
    @Test fun downloadHandoffClearsFieldButRetainsNextPrompt() {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as dev.ahmed.savlino.jobs.IsolatedTestApplication
        val store = dev.ahmed.savlino.jobs.JobStore(app)
        val coordinator = dev.ahmed.savlino.jobs.DownloadCoordinator(app, engine = Engine(), store = store)
        val parent = kotlinx.coroutines.Job()
        val scope = kotlinx.coroutines.CoroutineScope(parent + kotlinx.coroutines.Dispatchers.Main.immediate)
        try {
            val vm = LinkViewModel(SavedStateHandle(), engine = Engine(), downloads = coordinator, coordinatorScope = scope)
            vm.onNewShare(share(input)); idle(); vm.requestDownload(); idle()
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(5000) {
                    while (parent.children.any { it.isActive }) { idle(); kotlinx.coroutines.delay(10) }
                }
            }
            assertEquals(1, store.all().size)
            assertEquals(OptionsState.Hidden, vm.optionsState); assertEquals("", vm.text)
            assertNext(vm, 2, 3, "second.org")
            assertFalse(vm.clipboardEligible)
        } finally { parent.cancel(); store.close() }
    }
}
