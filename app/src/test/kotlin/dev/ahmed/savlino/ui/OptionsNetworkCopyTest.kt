package dev.ahmed.savlino.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w384dp-h832dp-mdpi")
class OptionsNetworkCopyTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun englishNetworkFailureFollowsCurrentConnectivity() = check()
    @Test @Config(qualifiers = "+ar") fun arabicNetworkFailureFollowsCurrentConnectivity() = check()

    private fun check() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            val activity = controller.get()
            val manager = activity.getSystemService(android.net.ConnectivityManager::class.java)
            shadowOf(manager).setActiveNetworkInfo(null)
            assertTrue(dev.ahmed.savlino.downloads.isOffline(activity))
            val engine = object : EngineAdapter by FakeEngine() {
                override suspend fun inspect(source: LinkRevision, operationId: String) = InspectResult.Failure(DownloadError.Network)
            }
            val links = LinkViewModel(SavedStateHandle(), engine = engine)
            links.paste("https://example.com/video")
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(links.optionsState is OptionsState.Error)
            val language = activity.resources.configuration.locales[0].language
            activity.setContent {
                CompositionLocalProvider(LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    NezzelTheme(ThemePref.Light, false) { OptionsSheetContent(links, language, {}) }
                }
            }
            compose.onNodeWithText(activity.getString(R.string.offline_title)).assertIsDisplayed()
            compose.onNodeWithText(activity.getString(R.string.offline_body)).assertIsDisplayed()
            compose.onNodeWithText(activity.getString(R.string.network_title)).assertDoesNotExist()
            // Only Robolectric networking changes: the composed error follows the restored default network.
            @Suppress("DEPRECATION")
            val online = org.robolectric.shadows.ShadowNetworkInfo.newInstance(android.net.NetworkInfo.DetailedState.CONNECTED,
                android.net.ConnectivityManager.TYPE_WIFI, 0, true, true)
            shadowOf(manager).setActiveNetworkInfo(online)
            val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
            shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
            // Shadow setters do not deliver Android's API 26+ availability -> capabilities callback pair.
            compose.runOnIdle {
                shadowOf(manager).networkCallbacks.forEach {
                    it.onAvailable(checkNotNull(manager.activeNetwork))
                    it.onCapabilitiesChanged(checkNotNull(manager.activeNetwork), capabilities)
                }
            }
            assertFalse(dev.ahmed.savlino.downloads.isOffline(activity))
            compose.onNodeWithText(activity.getString(R.string.network_title)).assertIsDisplayed()
            compose.onNodeWithText(activity.getString(R.string.network_body)).assertIsDisplayed()
            compose.onNodeWithText(activity.getString(R.string.offline_title)).assertDoesNotExist()
        } finally { controller.pause().stop().destroy() }
    }
}
