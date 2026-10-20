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
class AutomaticIntakeTest {
    private val url = "https://youtu.be/one"
    private class Engine : EngineAdapter by UnavailableEngine() {
        override val isAvailable = true
        val inspected = mutableListOf<LinkRevision>()
        override suspend fun installedVersion() = "fixture-v1"
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            inspected += source
            return InspectResult.Success(Inspection(source, "fixture-v1",
                listOf(Format("v720", "mp4", true, true, height = 720)),
                listOf(PresetChoice(Preset.BALANCED, "v720"))))
        }
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun share(value: String = url) = ShareInput.from(Intent(Intent.ACTION_SEND)
        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, value))

    @Test fun pendingDurableWriteBlocksNetworkAndRevalidatesEveryIntakeGuard() {
        val changes: List<(LinkViewModel) -> Unit> = listOf(
            { it.edit("https://youtu.be/edited") }, { it.navigate("Settings") },
            { it.editorFocused = true }, { it.automaticOptions = false },
            { it.suggestionsEnabled = false }, { it.clipboardForeground = false },
            { it.onNewShare(share("invalid")) }, { it.dismissSuggestion() })
        for (change in changes) {
            val completed = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val engine = Engine(); val state = SavedStateHandle()
            val vm = LinkViewModel(state, engine = engine, rememberHandledClipboard = { completed.await() })
            vm.suggest(true, true, true) { url }; idle()
            assertTrue(engine.inspected.isEmpty()); assertTrue(vm.hasSuggestion)
            change(vm); completed.complete(true); idle()
            assertTrue("Changed intake must not inspect after commit", engine.inspected.isEmpty())
            assertFalse(state.keys().any { state.get<Any?>(it) == url })
        }
    }
    @Test fun clearedViewModelCannotInspectAfterAnAlreadyRunningDurableWriteFinishes() {
        val completed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val engine = Engine()
        var durable = false
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, rememberHandledClipboard = {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                completed.await(); durable = true
            }
            true
        })
        val owner = androidx.lifecycle.ViewModelStore().apply { put("links", vm) }
        vm.suggest(true, true, true) { url }; idle()
        assertFalse(durable); assertTrue(engine.inspected.isEmpty())
        owner.clear(); completed.complete(Unit); idle()
        assertTrue(durable); assertTrue(engine.inspected.isEmpty())
        assertEquals("", vm.text); assertEquals(OptionsState.Hidden, vm.optionsState)
    }
    @Test fun successfulAwaitedWriteStartsExactlyOneInspectionAndFailureKeepsManualReview() {
        for (success in listOf(true, false)) {
            val completed = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val engine = Engine()
            val vm = LinkViewModel(SavedStateHandle(), engine = engine, rememberHandledClipboard = { completed.await() })
            vm.suggest(true, true, true) { url }; vm.inspectSuggestion(automatically = true); idle()
            assertTrue(engine.inspected.isEmpty())
            completed.complete(success); idle()
            assertEquals(if (success) 1 else 0, engine.inspected.size)
            assertEquals(!success, vm.hasSuggestion)
        }
    }
    @Test fun pendingInitializationDoesNotReadClipboardAndPreservesPersistedDeduplication() {
        val initialized = kotlinx.coroutines.CompletableDeferred<SuggestionGate>()
        val keyer = LinkKeyer.session
        val gate = SuggestionGate(keyer = keyer).apply { dismiss(LinkInput.parse(url)!!) }
        val vm = LinkViewModel(SavedStateHandle(), initializeClipboard = { initialized.await() })
        assertFalse(vm.clipboardEligible)
        vm.suggest(true, true, true) { error("No read while private state loads") }
        initialized.complete(gate); idle()
        assertTrue(vm.clipboardEligible)
        vm.suggest(true, true, true) { url }; idle()
        assertFalse(vm.hasSuggestion); assertEquals("", vm.text)
    }
    @Test fun dismissalsBeforeInitializationUseLoadedKeyAndSurviveRestart() {
        val initialized = kotlinx.coroutines.CompletableDeferred<SuggestionGate>()
        val committed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val prefs = dev.ahmed.savlino.locale.AppPreferences(org.robolectric.RuntimeEnvironment.getApplication())
        val keyer = LinkKeyer.withSecret(ByteArray(32) { 42 })
        val previous = SuggestionGate(keyer = keyer).apply { dismiss(LinkInput.parse("https://youtu.be/previous")!!) }
        assertTrue(prefs.storeDismissedHashes(previous.dismissedHashes()))
        var writes = 0
        val vm = LinkViewModel(SavedStateHandle(), initializeClipboard = { initialized.await() },
            rememberHandledClipboard = { hashes ->
                writes++
                committed.await()
                prefs.storeDismissedHashes(hashes)
            }).apply { automaticOptions = false }
        for (link in listOf(url, "https://youtu.be/two")) {
            vm.edit(link); vm.dismissOptions()
        }
        idle(); assertEquals(0, writes)
        initialized.complete(SuggestionGate(prefs.dismissedHashes, keyer)); idle()
        assertEquals(1, writes)
        assertEquals(1, prefs.dismissedHashes.size)
        committed.complete(Unit); idle()
        assertEquals(3, prefs.dismissedHashes.size)
        val restored = LinkViewModel(SavedStateHandle(), initializeClipboard = {
            SuggestionGate(prefs.dismissedHashes, keyer)
        }).apply { automaticOptions = false }
        idle()
        for (link in listOf(url, "https://youtu.be/two", "https://youtu.be/previous")) {
            restored.suggest(true, true, true) { link }
            assertFalse("Dismissal must survive with the loaded key", restored.hasSuggestion)
        }
    }

    @Test fun newerAutomaticOfferQueuesItsOwnDurableWriteBeforeInspecting() {
        val first = kotlinx.coroutines.CompletableDeferred<Unit>()
        val engine = Engine()
        var writes = 0
        var stored = emptySet<String>()
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, rememberHandledClipboard = { hashes ->
            writes++
            if (writes == 1) first.await()
            stored = hashes; true
        })
        vm.suggest(true, true, true) { url }; idle()
        vm.dismissSuggestion()
        vm.suggest(true, true, true) { "https://youtu.be/two" }; idle()
        assertTrue(engine.inspected.isEmpty())
        first.complete(Unit); idle()
        assertEquals(listOf("https://youtu.be/two"), engine.inspected.map { it.link.rawUrl })
        assertEquals(2, stored.size)
    }
    @Test fun closingManuallyLoadedOptionsPersistsHandledHashForANewProcess() {
        var stored = emptySet<String>()
        val vm = LinkViewModel(SavedStateHandle(), engine = Engine(), rememberHandledClipboard = {
            stored = it; true
        }).apply { automaticOptions = false }
        vm.suggest(true, true, true) { url }; vm.inspectSuggestion(); idle()
        assertTrue(vm.optionsState is OptionsState.Ready)
        vm.dismissOptions(); idle()
        assertEquals(1, stored.size)
        val restored = LinkViewModel(SavedStateHandle(), dismissed = stored).apply { automaticOptions = false }
        restored.suggest(true, true, true) { url }
        assertFalse(restored.hasSuggestion)
    }
    @Test fun delayedAutomaticAndManualWritesKeepNewestHandledSet() {
        val first = kotlinx.coroutines.CompletableDeferred<Unit>()
        val snapshots = mutableListOf<Set<String>>()
        var stored = emptySet<String>()
        val vm = LinkViewModel(SavedStateHandle(), rememberHandledClipboard = { hashes ->
            snapshots += hashes
            if (snapshots.size == 1) first.await()
            stored = hashes; true
        })
        vm.suggest(true, true, true) { url }; idle()
        vm.dismissSuggestion(); vm.automaticOptions = false
        vm.suggest(true, true, true) { "https://youtu.be/two" }; vm.dismissSuggestion(); idle()
        assertEquals(1, snapshots.size)
        first.complete(Unit); idle()
        assertEquals(2, stored.size)
        assertEquals(vm.dismissedHashes(), stored)
    }
    @Test fun foregroundClipboardLoadsOneRealSelectionButNeverConfirmsOrSavesRawClipboard() {
        val state = SavedStateHandle(); val engine = Engine()
        val vm = LinkViewModel(state, engine = engine)
        repeat(3) { vm.suggest(true, true, true) { url }; idle() }
        assertEquals(listOf(url), engine.inspected.map { it.link.rawUrl })
        assertEquals("v720", vm.selectedFormat?.id)
        assertTrue(vm.optionsState is OptionsState.Ready)
        assertFalse(vm.hasSuggestion)
        assertFalse(state.keys().any { state.get<Any?>(it) == url })
        assertNull(vm.takeConfirmedCommand())
        vm.requestDownload()
        assertEquals("v720", vm.takeConfirmedCommand()?.format?.id)
        assertNull(vm.takeConfirmedCommand())
    }
    @Test fun pasteAndEachAcceptedShareLoadOptionsWhileConflictsAndTypingDoNot() {
        val engine = Engine(); val vm = LinkViewModel(SavedStateHandle(), engine = engine)
        vm.edit(url); idle(); assertTrue(engine.inspected.isEmpty())
        vm.paste(url); idle(); assertEquals(1, engine.inspected.size)
        // A pasted link is not a typed draft: the share replaces it with no prompt (UX U1).
        vm.onNewShare(share("https://youtu.be/two")); idle()
        assertEquals("https://youtu.be/two", vm.text); assertFalse(vm.hasPendingShare)
        assertEquals("https://youtu.be/two", engine.inspected.last().link.rawUrl)
        assertEquals(2, engine.inspected.size)
        vm.edit("https://youtu.be/typed"); idle()
        vm.onNewShare(share("https://youtu.be/three")); idle()
        assertEquals("https://youtu.be/typed", vm.text); assertTrue(vm.hasPendingShare)
        assertEquals("youtu.be", vm.pendingShareHost)
        vm.acceptShare(); idle()
        assertEquals("https://youtu.be/three", engine.inspected.last().link.rawUrl)
        assertEquals(3, engine.inspected.size)
        val cold = LinkViewModel(SavedStateHandle(), engine = engine)
        cold.onCreateShare(share()); idle()
        assertEquals(4, engine.inspected.size)
        cold.onCreateShare(share()); idle(); assertEquals(4, engine.inspected.size)
        val warm = LinkViewModel(SavedStateHandle(), engine = engine)
        warm.onNewShare(share()); idle(); assertEquals(5, engine.inspected.size)
        assertNull(vm.takeConfirmedCommand()); assertNull(cold.takeConfirmedCommand())
    }
    @Test fun closingAutomaticOptionsLetsADifferentCopiedLinkLoadWithoutRepeatingTheOldOne() {
        val engine = Engine(); val vm = LinkViewModel(SavedStateHandle(), engine = engine)
        vm.suggest(true, true, true) { url }; idle(); vm.dismissOptions()
        repeat(3) { vm.onForeground(); vm.suggest(true, true, true) { url }; idle() }
        assertEquals(1, engine.inspected.size)
        assertEquals(OptionsState.Hidden, vm.optionsState)
        vm.suggest(true, true, true) { "https://youtu.be/two" }; idle()
        assertEquals(listOf(url, "https://youtu.be/two"), engine.inspected.map { it.link.rawUrl })
        assertNull(vm.takeConfirmedCommand())
    }
    @Test fun handledHashIsDurableBeforeInspectionAndPreventsFreshProcessReplay() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = dev.ahmed.savlino.locale.AppPreferences(app)
        prefs.dismissedHashes = emptySet()
        val engine = Engine()
        val state = SavedStateHandle()
        val vm = LinkViewModel(state, engine = engine, rememberHandledClipboard = { hashes ->
            assertTrue("No inspection before durable handling", engine.inspected.isEmpty())
            prefs.storeDismissedHashes(hashes)
        })
        vm.suggest(true, true, true) { url }; idle()
        assertEquals(1, engine.inspected.size)
        assertEquals(1, prefs.dismissedHashes.size)
        assertFalse(state.keys().any { state.get<Any?>(it) == url })
        val restored = LinkViewModel(SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) }),
            dev.ahmed.savlino.locale.AppPreferences(app).dismissedHashes, engine = engine)
        restored.suggest(true, true, true) { url }; idle()
        assertEquals(1, engine.inspected.size)
        assertEquals(OptionsState.Hidden, restored.optionsState)
    }
    @Test fun persistenceFailureFallsBackToManualReviewAndStaleReviewNeverInspectsAnotherDraft() {
        val engine = Engine()
        val vm = LinkViewModel(SavedStateHandle(), engine = engine, rememberHandledClipboard = { false })
        vm.suggest(true, true, true) { url }; idle()
        assertTrue(vm.hasSuggestion); assertTrue(engine.inspected.isEmpty())
        assertEquals("", vm.text)
        vm.inspectSuggestion(); idle()
        assertEquals(listOf(url), engine.inspected.map { it.link.rawUrl })
        val other = LinkViewModel(SavedStateHandle(), engine = engine).apply { automaticOptions = false }
        other.suggest(true, true, true) { "https://youtu.be/two" }
        other.edit("https://youtu.be/three"); other.inspectSuggestion(); idle()
        assertEquals(1, engine.inspected.size)
    }
    @Test fun disabledAutoModeKeepsEveryEntryManualAndNavigationDropsReview() {
        val engine = Engine(); val vm = LinkViewModel(SavedStateHandle(), engine = engine).apply { automaticOptions = false }
        vm.paste(url); idle(); assertTrue(engine.inspected.isEmpty())
        vm.edit(""); vm.onNewShare(share()); idle(); assertTrue(engine.inspected.isEmpty())
        vm.edit(""); vm.suggest(true, true, true) { url }; idle()
        assertTrue(vm.hasSuggestion); assertTrue(engine.inspected.isEmpty())
        vm.navigate("Downloads"); assertFalse(vm.hasSuggestion)
        vm.inspectSuggestion(); idle(); assertTrue(engine.inspected.isEmpty())
        vm.navigate("Home"); vm.suggest(true, true, true) { "https://youtu.be/two" }
        vm.inspectSuggestion(); idle(); assertEquals(listOf("https://youtu.be/two"), engine.inspected.map { it.link.rawUrl })
        assertNull(vm.takeConfirmedCommand())
    }
    @Test fun modalDraftDisabledAndUnfocusedStatesBlockBeforeClipboardRead() {
        val engine = Engine(); val vm = LinkViewModel(SavedStateHandle(), engine = engine)
        vm.suggest(false, true, true) { error("paused") }
        vm.suggest(true, false, true) { error("unfocused") }
        vm.suggestionsEnabled = false; vm.suggest(true, true, true) { error("disabled") }
        vm.suggestionsEnabled = true; vm.edit("draft"); vm.suggest(true, true, true) { error("draft") }
        vm.edit(""); vm.editorFocused = true; vm.suggest(true, true, true) { error("editing") }
        vm.editorFocused = false; vm.navigate("Settings"); vm.suggest(true, true, true) { error("settings") }
        vm.navigate("Home"); vm.paste(url); idle(); vm.requestDownload()
        vm.suggest(true, true, true) { error("options/confirmation") }
        assertEquals(1, engine.inspected.size)
    }
    @Test fun duplicatePasteAndShareKeepTheCurrentInspectionRatherThanRestartOrAskToReplaceItself() {
        val engine = Engine(); val vm = LinkViewModel(SavedStateHandle(), engine = engine)
        vm.paste(url); idle()
        val options = vm.optionsState
        vm.paste(url); idle()
        assertEquals(1, engine.inspected.size); assertSame(options, vm.optionsState)
        val shared = LinkViewModel(SavedStateHandle(), engine = engine)
        shared.onNewShare(share()); idle()
        val sharedOptions = shared.optionsState
        shared.onNewShare(share()); idle()
        assertEquals(2, engine.inspected.size)
        assertFalse(shared.hasPendingShare); assertSame(sharedOptions, shared.optionsState)
    }
    @Test fun invalidShareDoesNotRelabelClipboardAndExplicitCheckPromotesItToAnEditedDraft() {
        val engine = Engine(); val saved = SavedStateHandle()
        val vm = LinkViewModel(saved, engine = engine)
        vm.suggest(true, true, true) { url }; idle()
        vm.onNewShare(share("invalid")); idle()
        assertFalse(vm.shareActive); assertTrue(vm.copiedLink)
        vm.suggest(true, true, true) { error("invalid share still blocks intake") }
        vm.clearShareMessage(); vm.checkLink(); idle(); vm.dismissOptions()
        assertEquals(url, vm.text)
        assertEquals(url, saved.get<String>("text"))
    }
    @Test fun leavingManualClipboardReviewPersistsDismissalForFreshProcess() {
        val prefs = dev.ahmed.savlino.locale.AppPreferences(org.robolectric.RuntimeEnvironment.getApplication())
        prefs.dismissedHashes = emptySet()
        val vm = LinkViewModel(SavedStateHandle(), rememberHandledClipboard = prefs::storeDismissedHashes).apply { automaticOptions = false }
        vm.suggest(true, true, true) { url }; vm.navigate("Downloads")
        val restored = LinkViewModel(SavedStateHandle(), prefs.dismissedHashes).apply { automaticOptions = false }
        restored.suggest(true, true, true) { url }
        assertFalse(restored.hasSuggestion)
    }
    @Test fun validPasteKeepsCopiedOriginInManualAndAutomaticMode() {
        for (automatic in listOf(false, true)) {
            val vm = LinkViewModel(SavedStateHandle(), engine = Engine()).apply { automaticOptions = automatic }
            vm.paste(url); idle(); assertTrue(vm.copiedLink)
        }
    }
    @Test fun sharingTheCurrentlyPastedUrlKeepsItsExistingOptions() {
        val engine = Engine(); val vm = LinkViewModel(SavedStateHandle(), engine = engine)
        vm.paste(url); idle(); val options = vm.optionsState
        vm.onNewShare(share()); idle()
        assertFalse(vm.hasPendingShare); assertSame(options, vm.optionsState)
        assertEquals(1, engine.inspected.size); assertNull(vm.takeConfirmedCommand())
    }
    @Test fun manualModeRepeatedPasteAndShareKeepExplicitlyLoadedOptions() {
        val engine = Engine(); val vm = LinkViewModel(SavedStateHandle(), engine = engine).apply { automaticOptions = false }
        vm.edit(url); vm.checkLink(); idle(); val options = vm.optionsState
        vm.paste(url); idle(); assertSame(options, vm.optionsState)
        vm.onNewShare(share()); idle()
        assertFalse(vm.hasPendingShare); assertSame(options, vm.optionsState)
        assertEquals(1, engine.inspected.size); assertNull(vm.takeConfirmedCommand())
    }
}
