package dev.ahmed.savlino

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.setContent
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import dev.ahmed.savlino.entry.plainText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowClipboardManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [CountingClipboard::class])
class ClipboardEntryTest {
    @Test fun navigatingHomeRetriesPreviouslyBlockedForegroundRead() {
        val controller = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).create().start().resume()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
        val shadow = Shadow.extract<CountingClipboard>(clipboard)
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "https://youtu.be/one"))
        val vm = dev.ahmed.savlino.ui.LinkViewModel(androidx.lifecycle.SavedStateHandle()).apply { automaticOptions = false; navigate("Settings") }
        val clock = androidx.compose.runtime.BroadcastFrameClock()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate + clock)
        val recomposer = androidx.compose.runtime.Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        try {
            controller.get().setContent(parent = recomposer) {
                dev.ahmed.savlino.entry.ClipboardEntryEffect(vm) { vm.suggest(true, true, true) { clipboard.plainText() } }
            }
            controller.visible()
            fun frames() = repeat(4) {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                clock.sendFrame(System.nanoTime())
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            }
            frames(); assertEquals(0, shadow.reads)
            vm.navigate("Home"); frames()
            assertEquals(1, shadow.reads); assertTrue(vm.hasSuggestion)
            frames(); assertEquals(1, shadow.reads)
        } finally { recomposer.cancel(); scope.cancel(); controller.pause().stop().destroy() }
    }
    @Test fun initializationAfterFocusRetriesOnceAndWaitsForDurableHashBeforeNetwork() {
        val controller = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).create().start().resume().visible()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
        val shadow = Shadow.extract<CountingClipboard>(clipboard)
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "https://youtu.be/one"))
        val initialized = kotlinx.coroutines.CompletableDeferred<dev.ahmed.savlino.domain.SuggestionGate>()
        val commit = kotlinx.coroutines.CompletableDeferred<Unit>()
        var durable = false
        var inspections = 0
        val engine = object : dev.ahmed.savlino.engine.EngineAdapter by dev.ahmed.savlino.engine.UnavailableEngine() {
            override val isAvailable = true
            override suspend fun inspect(source: dev.ahmed.savlino.domain.LinkRevision, operationId: String): dev.ahmed.savlino.engine.InspectResult {
                assertTrue("Network must wait for durable handling", durable)
                inspections++
                return dev.ahmed.savlino.engine.InspectResult.Failure(dev.ahmed.savlino.domain.DownloadError.Network)
            }
        }
        val vm = dev.ahmed.savlino.ui.LinkViewModel(androidx.lifecycle.SavedStateHandle(), engine = engine,
            initializeClipboard = { initialized.await() }, rememberHandledClipboard = {
                commit.await(); durable = true; true
            })
        val clock = androidx.compose.runtime.BroadcastFrameClock()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate + clock)
        val recomposer = androidx.compose.runtime.Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        try {
            // The activity's reader still enforces resume/focus and once-per-entry semantics.
            var readThisEntry = false
            controller.windowFocusChanged(true)
            val read = {
                if (!readThisEntry) vm.suggest(true, controller.get().hasWindowFocus(), true) {
                    readThisEntry = true; clipboard.plainText()
                }
            }
            controller.get().setContent(parent = recomposer) {
                dev.ahmed.savlino.entry.ClipboardEntryEffect(vm, read)
            }
            fun frames() = repeat(4) {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                clock.sendFrame(System.nanoTime())
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            }
            read(); frames()
            assertEquals(0, shadow.reads); assertEquals(0, inspections)
            initialized.complete(dev.ahmed.savlino.domain.SuggestionGate()); frames()
            assertEquals(1, shadow.reads); assertTrue(vm.hasSuggestion)
            frames(); assertEquals(1, shadow.reads); assertEquals(0, inspections)
            commit.complete(Unit); frames()
            assertEquals(1, inspections); assertFalse(vm.hasSuggestion)
            read(); frames(); assertEquals(1, shadow.reads); assertEquals(1, inspections)
        } finally { recomposer.cancel(); scope.cancel(); controller.pause().stop().destroy() }
    }

    @Test fun nonPlainDescriptionDoesNotReadClipboardContent() {
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData("image", arrayOf("image/png"), ClipData.Item(Uri.parse("content://test/image"))))
        assertNull(clipboard.plainText())
        assertEquals(0, Shadow.extract<CountingClipboard>(clipboard).reads)
    }
    @Test fun activityRequiresResumeAndFocusAndReadsOncePerForegroundEntry() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().visible()
        val clipboard = controller.get().getSystemService(ClipboardManager::class.java)
        val shadow = Shadow.extract<CountingClipboard>(clipboard)
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "not a link"))
        try {
            controller.windowFocusChanged(true)
            assertEquals(0, shadow.reads)
            controller.windowFocusChanged(false).resume()
            assertEquals(0, shadow.reads)
            controller.windowFocusChanged(true)
            assertFalse("Home must wait for an explicit edit", controller.get().links.editorFocused)
            assertEquals(1, shadow.reads)
            controller.windowFocusChanged(false).windowFocusChanged(true)
            assertEquals(1, shadow.reads)
            controller.pause().resume().windowFocusChanged(true)
            assertEquals(2, shadow.reads)
        } finally { controller.pause().stop().destroy() }
    }
}

@Implements(ClipboardManager::class)
class CountingClipboard : ShadowClipboardManager() {
    var reads = 0
    @Implementation override fun getPrimaryClip(): ClipData? {
        reads++
        return super.getPrimaryClip()
    }
}
