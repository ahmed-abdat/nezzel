package dev.ahmed.savlino.statuses.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import dev.ahmed.savlino.statuses.StatusItem
import dev.ahmed.savlino.statuses.StatusSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A Statuses thumbnail whose caller leaves cancels the provider call, so a stuck file never holds a decode slot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StatusMediaCancelTest {
    private class StuckProvider : ContentProvider() {
        val started = CountDownLatch(1)
        @Volatile var cancelled = false
        override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?, signal: CancellationSignal?): AssetFileDescriptor? {
            started.countDown()
            val end = System.nanoTime() + 5_000_000_000
            while (signal?.isCanceled != true && System.nanoTime() < end) Thread.sleep(5)
            cancelled = signal?.isCanceled == true
            return null
        }
        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
        override fun getType(uri: Uri) = "image/jpeg"
        override fun insert(uri: Uri, values: ContentValues?) = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    @Test fun leavingCancelsTheProviderCall() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val provider = StuckProvider().apply { attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "stuck.status" }) }
        ShadowContentResolver.registerProviderInternal("stuck.status", provider)
        val item = StatusItem(Uri.parse("content://stuck.status/one.jpg"), StatusSource("com.whatsapp", 0), false, "image/jpeg",
            null, null, saved = false, isNew = false)
        val load = async(Dispatchers.Default) { SystemStatusMedia(app).thumbnail(item, 96) }
        assertTrue("provider reached", provider.started.await(5, TimeUnit.SECONDS))
        load.cancel()
        withTimeout(5_000) { while (!provider.cancelled) kotlinx.coroutines.delay(10) }
        assertTrue(provider.cancelled)
    }
}
