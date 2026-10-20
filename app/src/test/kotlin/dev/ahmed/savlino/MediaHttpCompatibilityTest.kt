package dev.ahmed.savlino

import android.app.Application
import android.content.ContentProvider
import android.content.pm.PackageManager
import android.os.Build
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaHttpCompatibilityTest {
    @Test @Config(sdk = [29])
    fun startupAndPublicClientConstructionOnApi29() = smoke(29)

    @Test @Config(sdk = [33])
    fun startupAndPublicClientConstructionOnApi33() = smoke(33)

    @Test @Config(sdk = [35])
    fun startupAndPublicClientConstructionOnApi35() = smoke(35)

    private fun smoke(expectedApi: Int) {
        assertEquals(expectedApi, Build.VERSION.SDK_INT)
        val application = RuntimeEnvironment.getApplication()
        val authority = "${application.packageName}.androidx-startup"
        val info = application.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        assertFalse(info.exported)
        val initializerName = "okhttp3.internal.platform.PlatformInitializer"
        assertEquals("androidx.startup", info.metaData.getString(initializerName))

        // Startup is runtime-only in this app; inspect its public API without a compile dependency.
        val providerClass = Class.forName("androidx.startup.InitializationProvider")
            .asSubclass(ContentProvider::class.java)
        Robolectric.setupContentProvider(providerClass, authority)
        val appInitializerClass = Class.forName("androidx.startup.AppInitializer")
        val appInitializer = appInitializerClass.getMethod("getInstance", android.content.Context::class.java)
            .invoke(null, application)
        val eagerlyInitialized = appInitializerClass.getMethod("isEagerlyInitialized", Class::class.java)
            .invoke(appInitializer, Class.forName(initializerName))
        assertEquals(true, eagerlyInitialized)

        var dnsLookups = 0
        var callsStarted = 0
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                dnsLookups++
                error("Construction must not resolve DNS")
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        val pool = ConnectionPool()
        try {
            val client = OkHttpClient.Builder()
                .dispatcher(Dispatcher(executor))
                .connectionPool(pool)
                .dns(dns)
                .eventListener(object : EventListener() {
                    override fun callStart(call: Call) { callsStarted++ }
                })
                .build()
            val request = Request.Builder().url("https://example.invalid/compatibility").build()
            val call = client.newCall(request)
            assertSame(request, call.request())
            assertFalse(call.isExecuted())
            assertFalse(call.isCanceled())
            call.cancel()
            assertTrue(call.isCanceled())
            assertFalse(call.isExecuted())
            assertEquals(0, dnsLookups)
            assertEquals(0, callsStarted)
            assertEquals(0, client.connectionPool.connectionCount())
        } finally {
            pool.evictAll()
            executor.shutdownNow()
        }
    }
}
