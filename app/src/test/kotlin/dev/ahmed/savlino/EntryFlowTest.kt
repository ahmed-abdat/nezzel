package dev.ahmed.savlino

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.entry.*
import dev.ahmed.savlino.ui.LinkViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EntryFlowTest {
    private val url = "https://youtube.com/watch?v=one"
    private fun share(text: String = url) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    private fun model(state: SavedStateHandle = SavedStateHandle(), dismissed: Set<String> = emptySet()) = LinkViewModel(state, dismissed).apply { automaticOptions = false }

    @Test fun createShareAcceptsOneLinkWithoutEngineWork() {
        val vm = model()
        vm.onCreateShare(ShareInput.from(share()))
        assertEquals(url, vm.text)
        assertTrue(vm.shareActive)
        assertFalse(vm.downloadEnabled)
    }
    @Test fun newIntentDoesNotOverwriteEditingAndCanBeExplicitlyAccepted() {
        val vm = model(); vm.edit("unfinished link")
        vm.onNewShare(ShareInput.from(share()))
        assertEquals("unfinished link", vm.text)
        assertTrue(vm.hasPendingShare)
        vm.acceptShare()
        assertEquals(url, vm.text)
    }
    @Test fun newIntentAfterShareReplacesTheUntouchedLinkWithoutAPrompt() {
        val vm = model(); vm.onCreateShare(ShareInput.from(share()))
        vm.onNewShare(ShareInput.from(share("https://youtu.be/two")))
        assertEquals("https://youtu.be/two", vm.text)
        assertFalse(vm.hasPendingShare); assertTrue(vm.shareActive)
    }
    @Test fun newIntentWhileTheSharedLinkIsBeingEditedStillAsks() {
        val vm = model(); vm.onCreateShare(ShareInput.from(share()))
        vm.editorFocused = true
        vm.onNewShare(ShareInput.from(share("https://youtu.be/two")))
        assertEquals(url, vm.text); assertTrue(vm.hasPendingShare)
        vm.dismissShare()
        assertEquals(url, vm.text)
    }
    @Test fun processRestoreKeepsEditedTextAndDoesNotReplayLaunchShare() {
        val state = SavedStateHandle(); val first = model(state)
        first.onCreateShare(ShareInput.from(share())); first.edit("edited")
        val restored = model(SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) }))
        restored.onCreateShare(ShareInput.from(share()))
        assertEquals("edited", restored.text)
    }
    @Test fun onlyPlainTextSendIsAccepted() {
        assertNull(ShareInput.from(Intent(Intent.ACTION_VIEW).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)))
        assertNull(ShareInput.from(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_TEXT, url)))
        val vm = model(); vm.onCreateShare(ShareInput.from(share("invalid")))
        assertEquals("", vm.text); assertFalse(vm.invalid); assertTrue(vm.invalidShare)
    }
    @Test fun clipboardIsNotReadOutsideResumedFocusedEmptyHome() {
        val vm = model(); var reads = 0
        val read = { reads++; url }
        vm.suggest(false, true, true, read); vm.suggest(true, false, true, read)
        vm.suggest(true, true, false, read)
        vm.edit("draft"); vm.suggest(true, true, true, read)
        assertEquals(0, reads)
    }
    @Test fun clipboardOfferIsMemoryOnlyAndNeverOverwritesEditing() {
        val state = SavedStateHandle(); val vm = model(state)
        vm.suggest(true, true, true) { url }
        assertTrue(vm.hasSuggestion); assertEquals("", vm.text)
        assertFalse(state.keys().any { state.get<Any?>(it) == url })
        vm.edit("draft"); vm.inspectSuggestion()
        assertEquals("draft", vm.text)
    }
    @Test fun rotationDoesNotRereadPendingOfferAndDismissalPersistsOnlyHash() {
        val vm = model(); var reads = 0
        repeat(2) { vm.suggest(true, true, true) { reads++; url } }
        assertEquals(1, reads)
        vm.dismissSuggestion()
        val hashes = vm.dismissedHashes()
        assertEquals(1, hashes.size); assertTrue(hashes.single().matches(Regex("[0-9a-f]{64}")))
        val next = model(dismissed = hashes)
        next.suggest(true, true, true) { url }
        assertFalse(next.hasSuggestion)
    }
    @Test fun shareAndDisabledSuggestionsBlockClipboardBeforeRead() {
        val vm = model(); vm.onCreateShare(ShareInput.from(share("invalid")))
        vm.suggest(true, true, true) { error("share must block clipboard") }
        val other = model(); other.suggestionsEnabled = false
        other.suggest(true, true, true) { error("disabled suggestions must block clipboard") }
    }
    @Test fun clearingSharedLinkAllowsLaterClipboardOffer() {
        val vm = model(); vm.onCreateShare(ShareInput.from(share()))
        vm.edit("")
        vm.suggest(true, true, true) { "https://youtu.be/new" }
        assertTrue(vm.hasSuggestion)
    }
    @Test fun leavingFocusedEditorAllowsShareAndSuggestionOnReturn() {
        val vm = model(); vm.editorFocused = true
        vm.navigate("Settings"); vm.navigate("Home")
        vm.onNewShare(ShareInput.from(share()))
        assertEquals(url, vm.text); assertFalse(vm.hasPendingShare)
        vm.edit(""); vm.editorFocused = true
        vm.navigate("Settings"); vm.navigate("Home")
        vm.suggest(true, true, true) { "https://youtu.be/new" }
        assertTrue(vm.hasSuggestion)
    }
    @Test fun acceptanceSuppressesOnlyThisSessionWhileDismissalSurvivesRestore() {
        val vm = model()
        vm.suggest(true, true, true) { url }; vm.inspectSuggestion()
        assertEquals(url, vm.text); assertTrue(vm.dismissedHashes().isEmpty())
        vm.edit(""); vm.suggest(true, true, true) { url }
        assertFalse(vm.hasSuggestion)
        val next = model(dismissed = vm.dismissedHashes())
        next.suggest(true, true, true) { url }; assertTrue(next.hasSuggestion)
        next.dismissSuggestion()
        val restored = model(dismissed = next.dismissedHashes())
        restored.suggest(true, true, true) { url }; assertFalse(restored.hasSuggestion)
    }
    @Test fun invalidSharePreservesDraftValidationAndClearsStaleReplacement() {
        val state = SavedStateHandle(); val vm = model(state)
        vm.edit(url); vm.checkLink()
        vm.onNewShare(ShareInput.from(share("https://youtu.be/two")))
        assertTrue(vm.hasPendingShare)
        vm.onNewShare(ShareInput.from(share("invalid")))
        assertEquals(url, vm.text); assertFalse(vm.invalid); assertTrue(vm.invalidShare)
        assertFalse(vm.hasPendingShare)
        val restored = model(SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) }))
        assertFalse(restored.hasPendingShare); assertFalse(restored.invalidShare)
        vm.clearShareMessage(); assertFalse(vm.invalidShare)
        vm.edit("bad draft"); vm.checkLink()
        vm.onNewShare(ShareInput.from(share("invalid")))
        assertTrue(vm.invalid)
    }
    @Test fun intakeNeverInvokesEngineOperations() {
        val engine = object : dev.ahmed.savlino.engine.EngineAdapter by dev.ahmed.savlino.engine.UnavailableEngine() {
            override suspend fun inspect(source: dev.ahmed.savlino.domain.LinkRevision, operationId: String): dev.ahmed.savlino.engine.InspectResult = error("Unexpected inspection")
            override suspend fun download(command: dev.ahmed.savlino.domain.DownloadCommand, directory: java.io.File, progress: (dev.ahmed.savlino.engine.EngineEvent) -> Unit): dev.ahmed.savlino.engine.TransferResult = error("Unexpected transfer")
            override fun cancel(operationId: String) = error("Unexpected engine operation")
        }
        val vm = LinkViewModel(SavedStateHandle(), engine = engine)
        vm.onCreateShare(ShareInput.from(share())); vm.checkLink()
        vm.paste(url); vm.checkLink()
        vm.edit(""); vm.suggest(true, true, true) { url }; vm.inspectSuggestion(); vm.checkLink()
        assertFalse(vm.downloadEnabled)
    }
    @Test fun pasteIsExplicitEditableAndDoesNotValidateOrSubmit() {
        val vm = model(); vm.paste("draft from clipboard")
        assertEquals("draft from clipboard", vm.text); assertFalse(vm.invalid)
        vm.checkLink(); assertTrue(vm.invalid); assertFalse(vm.downloadEnabled)
        vm.edit(url); vm.checkLink(); assertFalse(vm.invalid); assertFalse(vm.downloadEnabled)
    }
}
