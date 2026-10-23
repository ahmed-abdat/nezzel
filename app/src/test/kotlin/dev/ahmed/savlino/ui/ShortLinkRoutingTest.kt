package dev.ahmed.savlino.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.engine.mediaimage.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A known shortener resolves first; its destination takes its own route while staleness stays on the short link. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ShortLinkRoutingTest {
    private val short = "https://tinyurl.com/7synthetic"
    private class Engine : EngineAdapter by FakeEngine() {
        val inspected = java.util.Collections.synchronizedList(mutableListOf<String>())
        override suspend fun installedVersion() = "2026.08.19"
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            inspected += source.link.rawUrl
            return InspectResult.Success(Inspection(source, "2026.08.19", listOf(Format("http", "mp4", true, true)),
                listOf(PresetChoice(Preset.BEST, "http")), title = "Clip"))
        }
    }
    private val engine = Engine()
    private val images = FakeImages()
    private val asked = mutableListOf<String>()
    private var destination: String? = null
    private var hold: CompletableDeferred<Unit>? = null
    private var nextId = 0
    private val vm = LinkViewModel(SavedStateHandle(), engine = engine, images = images,
        newOperationId = { "op-${++nextId}" }, previewDispatcher = Dispatchers.Unconfined, previewImage = ::testBitmap,
        currentEngineVersion = { "2026.08.19" },
        resolveShortLink = { link -> asked += link.rawUrl; hold?.await(); destination?.let(LinkInput::parse) })
    @After fun tearDown() { hold?.complete(Unit); vm.dismissOptions(); pump() }

    private fun pump() = shadowOf(Looper.getMainLooper()).idle()
    private fun settle(done: () -> Boolean) = runBlocking { withTimeout(5000) { while (!done()) { pump(); delay(5) } }; pump() }
    private fun check(link: String) { vm.edit(link); vm.checkLink(); pump() }

    @Test fun aYouTubeDestinationIsReadDirectlyAndBoundToTheShortLink() {
        destination = "https://www.youtube.com/watch?v=abcdefghijk"
        check(short)
        settle { vm.optionsState is OptionsState.Ready }
        assertEquals(listOf(short), asked)
        assertEquals(listOf(destination), engine.inspected)
        val ready = vm.optionsState as OptionsState.Ready
        assertEquals(short, ready.resolvedFrom!!.link.rawUrl)
        assertTrue(vm.downloadEnabled)
    }

    @Test fun anUnresolvedShortLinkKeepsTodaysPath() {
        check(short)
        settle { vm.optionsState is OptionsState.Ready }
        assertEquals(listOf(short), engine.inspected)
        assertNull((vm.optionsState as OptionsState.Ready).resolvedFrom)
    }

    @Test fun aPostDestinationGoesToItsReaderAndItsPhotosStaySavable() {
        destination = "https://www.instagram.com/p/SynthPost01/"
        check("https://t.co/Synth01")
        settle { vm.optionsState is OptionsState.ImageReady }
        assertEquals(listOf(MediaSourceRoute.INSTAGRAM), images.routes)
        val ready = vm.optionsState as OptionsState.ImageReady
        assertEquals("https://t.co/Synth01", ready.origin!!.link.rawUrl)
        assertEquals(destination, ready.inspection.source.link.rawUrl)
    }

    @Test fun editingWhileResolvingDropsTheResolution() {
        destination = "https://www.youtube.com/watch?v=abcdefghijk"
        hold = CompletableDeferred()
        check(short)
        assertTrue(vm.optionsState is OptionsState.Checking)
        vm.edit("https://tinyurl.com/other"); pump()
        hold!!.complete(Unit); pump()
        assertEquals(OptionsState.Hidden, vm.optionsState)
        assertTrue(engine.inspected.isEmpty())
    }

    @Test fun ordinaryLinksNeverAskTheResolver() {
        check("https://www.youtube.com/watch?v=abcdefghijk")
        settle { vm.optionsState is OptionsState.Ready }
        assertTrue(asked.isEmpty())
    }
}
