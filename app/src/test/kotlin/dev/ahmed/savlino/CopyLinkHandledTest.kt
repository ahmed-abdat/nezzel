package dev.ahmed.savlino

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.downloads.copyHandled
import dev.ahmed.savlino.ui.LinkViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** X2 Copy link: the copied link is handled before it reaches the clipboard (condition A). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CopyLinkHandledTest {
    private val url = "https://youtu.be/copied"
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun copy(vm: LinkViewModel, clips: MutableList<String>) =
        GlobalScope.launch(Dispatchers.Main, CoroutineStart.UNDISPATCHED) { copyHandled(url, vm::markHandled) { clips += it } }

    @Test fun clipWaitsForInitAndPersistenceThenTheLinkIsNeverOffered() {
        val initialized = CompletableDeferred<SuggestionGate>()
        val written = CompletableDeferred<Boolean>()
        val keyer = LinkKeyer.withSecret(ByteArray(32) { 7 })
        var stored = emptySet<String>()
        val vm = LinkViewModel(SavedStateHandle(), initializeClipboard = { initialized.await() },
            rememberHandledClipboard = { hashes -> written.await().also { if (it) stored = hashes } })
            .apply { automaticOptions = false }
        val clips = mutableListOf<String>()
        copy(vm, clips); idle()
        assertTrue("No clip before the gate loads", clips.isEmpty())
        initialized.complete(SuggestionGate(keyer = keyer)); idle()
        assertTrue("No clip before the write lands", clips.isEmpty())
        written.complete(true); idle()
        assertEquals(listOf(url), clips)
        vm.suggest(true, true, true) { url }; idle()
        assertFalse(vm.hasSuggestion)
        val restarted = LinkViewModel(SavedStateHandle(), initializeClipboard = { SuggestionGate(stored, keyer) })
            .apply { automaticOptions = false }
        idle()
        restarted.suggest(true, true, true) { url }; idle()
        assertFalse("Handled after a restart with the same store", restarted.hasSuggestion)
    }

    @Test fun failedWriteStillCopiesAndKeepsTheSessionDismissal() {
        val vm = LinkViewModel(SavedStateHandle(), rememberHandledClipboard = { throw java.io.IOException() })
            .apply { automaticOptions = false }
        val clips = mutableListOf<String>()
        copy(vm, clips); idle()
        assertEquals(listOf(url), clips)
        vm.suggest(true, true, true) { url }; idle()
        assertFalse(vm.hasSuggestion)
    }

    @Test fun notALinkCopiesWithoutTouchingTheGate() {
        var writes = 0
        val vm = LinkViewModel(SavedStateHandle(), rememberHandledClipboard = { writes++; true })
        val clips = mutableListOf<String>()
        GlobalScope.launch(Dispatchers.Main) { copyHandled("not a link", vm::markHandled) { clips += it } }; idle()
        assertEquals(listOf("not a link"), clips)
        assertEquals(0, writes)
    }
}
