package dev.ahmed.savlino.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PreviewCopyDispatchTest {
    private val url = "https://example.com/photo.jpg"
    private fun settle(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!done() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue("Preview operation did not settle", done())
    }
    @Test fun copyRunsOffMainAndPublishesOnlyAfterEnginePreviewCloses() {
        val preview = TestPreview()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        var worker: Thread? = null
        val vm = LinkViewModel(SavedStateHandle(), images = FakeImages { imageSuccess(it, preview) }, previewImage = {
            worker = Thread.currentThread(); started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            testBitmap(it)
        })
        try {
            vm.edit(url); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertNotSame(Looper.getMainLooper().thread, worker)
            assertTrue(vm.optionsState is OptionsState.Checking)
            assertFalse(preview.isClosed)
            release.countDown()
            settle { vm.optionsState is OptionsState.ImageReady }
            assertTrue(preview.isClosed)
            assertNotNull((vm.optionsState as OptionsState.ImageReady).preview)
        } finally { release.countDown() }
    }
    @Test fun changingDraftDuringCopyClosesEnginePreviewAndCannotPublishStaleOptions() {
        val preview = TestPreview()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val vm = LinkViewModel(SavedStateHandle(), images = FakeImages { imageSuccess(it, preview) }, previewImage = {
            started.countDown(); check(release.await(5, TimeUnit.SECONDS)); testBitmap(it)
        })
        try {
            vm.edit(url); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            vm.edit("new draft"); release.countDown()
            settle { preview.isClosed }
            assertEquals("new draft", vm.text)
            assertEquals(OptionsState.Hidden, vm.optionsState)
            assertTrue(vm.selectedImageRefs.isEmpty())
        } finally { release.countDown() }
    }
    @Test fun clearingWhileCopyIsQueuedClosesResultEvenIfCopyNeverStarts() {
        val preview = TestPreview()
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
        val vm = LinkViewModel(SavedStateHandle(), images = FakeImages { imageSuccess(it, preview) },
            previewDispatcher = dispatcher, previewImage = { error("Cancelled copy must not run") })
        val store = ViewModelStore().apply { put("links", vm) }
        vm.edit(url); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        assertFalse(preview.isClosed)
        store.clear(); dispatcher.scheduler.runCurrent()
        settle { preview.isClosed }
        assertFalse(vm.optionsState is OptionsState.ImageReady)
    }
}
