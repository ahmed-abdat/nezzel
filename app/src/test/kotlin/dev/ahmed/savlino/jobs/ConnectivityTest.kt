package dev.ahmed.savlino.jobs

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Looper
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ConnectivityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(ConnectivityManager::class.java)
    private fun callback() = shadowOf(manager).networkCallbacks.single()
    private fun caps(internet: Boolean = true, metered: Boolean = false, validated: Boolean = false) =
        ShadowNetworkCapabilities.newInstance().also {
            if (internet) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (!metered) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            if (validated) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    private fun available(network: Network, internet: Boolean = true, metered: Boolean = false) {
        callback().onAvailable(network)
        callback().onCapabilitiesChanged(network, caps(internet, metered))
    }

    @Test fun airplaneModeStopsRunnerEvenWhenManagerStillNamesLostNetwork() {
        val connectivity = Connectivity.get(context)
        val network = checkNotNull(manager.activeNetwork)
        available(network)
        var losses = 0
        NetworkWatch.Adapter(connectivity).watch { losses++ }.use {
            callback().onLost(network)
            assertNotNull(manager.activeNetwork) // Android's stale-onLost snapshot must never win.
            assertFalse(connectivity.snapshot.online)
            assertTrue(dev.ahmed.savlino.downloads.isOffline(context))
            assertFalse(dev.ahmed.savlino.downloads.isMetered(context))
            assertEquals(1, losses)
            assertFalse(connectivity.snapshot.offlineSettled)
        }
    }

    @Test fun restoredDefaultRecoversImmediatelyFromCapabilitiesEvenWithStaleManager() {
        shadowOf(manager).setActiveNetworkInfo(null)
        val connectivity = Connectivity.get(context)
        assertTrue(connectivity.snapshot.offlineSettled)
        val restored = ShadowNetwork.newInstance(108)
        callback().onAvailable(restored)
        assertTrue(connectivity.snapshot.online)
        // Availability alone says nothing about INTERNET. Android follows it with capabilities (API 26+).
        callback().onCapabilitiesChanged(restored, caps(internet = false))
        assertTrue(connectivity.snapshot.offline)
        assertTrue(connectivity.snapshot.offlineSettled)
        callback().onCapabilitiesChanged(restored, caps())
        assertNull(manager.activeNetwork) // Callback arguments, rather than a manager query, drive recovery.
        assertFalse(connectivity.snapshot.offline)
        assertFalse(connectivity.snapshot.offlineSettled)
        assertFalse(dev.ahmed.savlino.downloads.isOffline(context))
    }

    @Test fun wifiToMobileHandoverNeverGoesOfflineWhenOldNetworkIsLost() {
        val connectivity = Connectivity.get(context)
        val wifi = ShadowNetwork.newInstance(100)
        val mobile = ShadowNetwork.newInstance(101)
        available(wifi)
        var losses = 0
        NetworkWatch.Adapter(connectivity).watch { losses++ }.use {
            available(mobile, metered = true)
            callback().onLost(wifi)
            callback().onCapabilitiesChanged(wifi, caps(internet = false))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertTrue(connectivity.snapshot.online)
            assertFalse(connectivity.snapshot.offline)
            assertFalse(connectivity.snapshot.offlineSettled)
            assertTrue(connectivity.snapshot.metered)
            assertEquals(0, losses)
        }
    }

    @Test fun uiSettlesAfterOneSecondButBackOnlineIsImmediate() {
        val connectivity = Connectivity.get(context)
        val network = checkNotNull(manager.activeNetwork)
        available(network)
        callback().onLost(network)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
        assertFalse(connectivity.snapshot.offlineSettled)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue(connectivity.snapshot.offlineSettled)
        available(ShadowNetwork.newInstance(102))
        assertFalse(connectivity.snapshot.offlineSettled)
    }

    @Test fun aBriefDropNeverSettlesAndCapabilityUpdatesDoNotRestartTheTimer() {
        val connectivity = Connectivity.get(context)
        val network = ShadowNetwork.newInstance(103)
        available(network)
        callback().onCapabilitiesChanged(network, caps(internet = false))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        callback().onCapabilitiesChanged(network, caps(internet = false, metered = true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertTrue(connectivity.snapshot.offlineSettled)
        available(network)
        callback().onLost(network)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
        available(ShadowNetwork.newInstance(104))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(connectivity.snapshot.offlineSettled)
    }

    @Test fun unknownCapabilitiesAreUnmeteredAndRunnerStillTries() {
        val connectivity = Connectivity.get(context)
        available(ShadowNetwork.newInstance(105), metered = true)
        callback().onAvailable(ShadowNetwork.newInstance(106))
        assertTrue(connectivity.snapshot.online)
        assertFalse(connectivity.snapshot.metered)
        callback().onCapabilitiesChanged(ShadowNetwork.newInstance(106), caps(internet = false))
        assertTrue(connectivity.snapshot.online)
        assertTrue(connectivity.snapshot.offline)
    }

    @Test fun missingCapabilitiesAtStartupAreOfflineButNotMetered() {
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, null)
        val connectivity = Connectivity.get(context)
        assertTrue(connectivity.snapshot.online)
        assertTrue(connectivity.snapshot.offline)
        assertTrue(connectivity.snapshot.offlineSettled)
        assertFalse(connectivity.snapshot.metered)
    }

    @Test fun validationStaysSeparateFromInternetCapabilityForPlaylistRecovery() {
        val connectivity = Connectivity.get(context)
        val network = ShadowNetwork.newInstance(107)
        available(network)
        assertFalse(connectivity.snapshot.validated)
        callback().onCapabilitiesChanged(network, caps(validated = true))
        assertTrue(connectivity.snapshot.validated)
        callback().onLost(network)
        assertFalse(connectivity.snapshot.validated)
    }

    @Test fun snapshotsAndMultipleRunnerObserversShareOneProcessCallback() {
        val connectivity = Connectivity.get(context)
        assertSame(connectivity, Connectivity.get(context.createConfigurationContext(context.resources.configuration)))
        val watch = NetworkWatch.Android(context)
        watch.watch {}.use {
            watch.watch {}.use {
                assertEquals(1, shadowOf(manager).networkCallbacks.size)
                repeat(3) { dev.ahmed.savlino.downloads.isOffline(context); dev.ahmed.savlino.downloads.isMetered(context) }
                assertEquals(1, shadowOf(manager).networkCallbacks.size)
            }
        }
        assertEquals(1, shadowOf(manager).networkCallbacks.size)
    }

    @Test fun fakeAdapterNotifiesRunnerAndClosingObserverStopsDelivery() {
        val fake = FakeConnectivity()
        var losses = 0
        val observer = NetworkWatch.Adapter(fake).watch { losses++ }
        fake.set(ConnectivityState(online = false, offline = true))
        assertEquals(1, losses)
        assertFalse(NetworkWatch.Adapter(fake).online())
        observer.close()
        fake.set(ConnectivityState())
        fake.set(ConnectivityState(online = false, offline = true))
        assertEquals(1, losses)
    }
}
