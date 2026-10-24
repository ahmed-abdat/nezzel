package dev.ahmed.savlino

import android.content.Intent
import android.os.Looper
import android.text.SpannableString
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
class ProcessTextInputTest {
    private val first = "https://youtu.be/clipA"
    private val second = "https://youtu.be/clipB"
    private fun process(text: CharSequence? = first) = Intent(Intent.ACTION_PROCESS_TEXT)
        .setType("text/plain").apply { if (text != null) putExtra(Intent.EXTRA_PROCESS_TEXT, text) }
    private fun input(action: String, text: String) = ShareInput.from(
        if (action == Intent.ACTION_PROCESS_TEXT) process(SpannableString(text))
        else Intent(action).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private class Engine : EngineAdapter by UnavailableEngine() {
        override val isAvailable = true
        val inspected = mutableListOf<LinkRevision>()
        override suspend fun installedVersion() = "fixture-v1"
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            inspected += source
            return InspectResult.Success(Inspection(source, "fixture-v1",
                listOf(Format("v720", "mp4", true, true, height = 720))))
        }
    }

    @Test fun processTextRejectsWrongMimeType() {
        assertNull(ShareInput.from(process().setType("text/html")))
        assertNull(ShareInput.from(process().setType(null)))
    }

    @Test fun processTextRequiresItsOwnExtra() {
        assertNull(ShareInput.from(process(null)))
        assertNull(ShareInput.from(process(null).putExtra(Intent.EXTRA_TEXT, first)))
    }

    @Test fun processTextRejectsOversizeWithoutTruncatingToAValidLink() {
        assertNull(ShareInput.from(process(first.padEnd(8193, ' '))))
    }

    @Test fun processTextAcceptsCharSequenceAtTheLengthLimitAndIgnoresReadonly() {
        for (readOnly in listOf(false, true)) {
            val vm = LinkViewModel(SavedStateHandle()).apply { automaticOptions = false }
            vm.onCreateShare(ShareInput.from(process(SpannableString(first.padEnd(8192, ' ')))
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, readOnly)
                .putExtra(Intent.EXTRA_TEXT, second)))
            assertEquals(first, vm.text)
            assertTrue(vm.shareActive)
            assertEquals(OptionsState.Hidden, vm.optionsState)
            assertNull(vm.takeConfirmedCommand())
        }
    }

    @Test fun processTextGuardsRuntimeExceptionWhileReadingText() {
        val intent = object : Intent(Intent.ACTION_PROCESS_TEXT) {
            override fun getCharSequenceExtra(name: String): CharSequence = throw RuntimeException("unreadable extra")
        }.setType("text/plain")
        assertNull(ShareInput.from(intent))
    }

    @Test fun sendStillUsesOnlyExtraTextAndKeepsInvalidTextEnvelopes() {
        assertNull(ShareInput.from(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, first))!!.text)
        assertNull(ShareInput.from(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, first.padEnd(8193, ' ')))!!.text)
    }

    @Test fun coldAndNewProcessTextChooseAndInspectTheSameFirstCandidateAsSend() {
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_PROCESS_TEXT)) {
            for (cold in listOf(true, false)) {
                for (automatic in listOf(true, false)) {
                    val engine = Engine()
                    val vm = LinkViewModel(SavedStateHandle(), engine = engine).apply { automaticOptions = automatic }
                    val incoming = input(action, "Selected text http://localhost/no $first")
                    if (cold) vm.onCreateShare(incoming) else vm.onNewShare(incoming)
                    idle()
                    assertEquals(first, vm.text)
                    assertTrue(vm.shareActive)
                    assertFalse(vm.invalidShare)
                    assertEquals(if (automatic) listOf(first) else emptyList<String>(), engine.inspected.map { it.link.rawUrl })
                    if (automatic) assertTrue(vm.optionsState is OptionsState.Ready)
                    else assertEquals(OptionsState.Hidden, vm.optionsState)
                    assertNull(vm.takeConfirmedCommand())
                }
            }
        }
    }

    @Test fun processTextWithoutUrlMatchesInvalidSendAndPreservesDraft() {
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_PROCESS_TEXT)) {
            for (cold in listOf(true, false)) {
                val engine = Engine()
                val vm = LinkViewModel(SavedStateHandle(), engine = engine)
                if (!cold) vm.edit("unfinished draft")
                val incoming = input(action, "ordinary prose")
                if (cold) vm.onCreateShare(incoming) else vm.onNewShare(incoming)
                idle()
                assertEquals(if (cold) "" else "unfinished draft", vm.text)
                assertTrue(vm.invalidShare)
                assertFalse(vm.hasPendingShare)
                assertTrue(engine.inspected.isEmpty())
                assertNull(vm.takeConfirmedCommand())
            }
        }
    }

    @Test fun newProcessTextWaitsForDraftReplacementBeforeInspecting() {
        val engine = Engine()
        val vm = LinkViewModel(SavedStateHandle(), engine = engine)
        vm.edit("unfinished draft")
        vm.onNewShare(ShareInput.from(process("Selected text $first"))); idle()
        assertEquals("unfinished draft", vm.text)
        assertTrue(vm.hasPendingShare)
        assertTrue(engine.inspected.isEmpty())
        vm.acceptShare(); idle()
        assertEquals(first, vm.text)
        assertFalse(vm.hasPendingShare)
        assertEquals(listOf(first), engine.inspected.map { it.link.rawUrl })
        assertTrue(vm.optionsState is OptionsState.Ready)
        assertNull(vm.takeConfirmedCommand())
    }
}
