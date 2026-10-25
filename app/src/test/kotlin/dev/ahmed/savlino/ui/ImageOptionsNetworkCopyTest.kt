package dev.ahmed.savlino.ui

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import dev.ahmed.savlino.R
import dev.ahmed.savlino.downloads.isOffline
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import dev.ahmed.savlino.engine.mediaimage.ImagePhase
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
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w384dp-h832dp-mdpi")
class ImageOptionsNetworkCopyTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun englishPhotoPostNetworkFailureFollowsCurrentConnectivity() = check()
    @Test @Config(qualifiers = "+ar")
    fun arabicPhotoPostNetworkFailureFollowsCurrentConnectivity() = check()

    private fun check() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            val activity = controller.get()
            val manager = activity.getSystemService(ConnectivityManager::class.java)
            shadowOf(manager).setActiveNetworkInfo(null)
            assertTrue(isOffline(activity))
            val language = activity.resources.configuration.locales[0].language
            activity.setContent {
                CompositionLocalProvider(LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    NezzelTheme(ThemePref.Light, false) {
                        ImageInspectionError(OptionsState.ImageError(ImageFailure.NETWORK, phase = ImagePhase.PAGE), {}, {})
                    }
                }
            }
            compose.onNodeWithText(activity.getString(R.string.offline_title)).assertIsDisplayed()
            compose.onNodeWithText(activity.getString(R.string.offline_body)).assertIsDisplayed()
            compose.onNodeWithText(activity.getString(R.string.network_title)).assertDoesNotExist()
            @Suppress("DEPRECATION")
            val online = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI, 0, true, true)
            shadowOf(manager).setActiveNetworkInfo(online)
            val capabilities = ShadowNetworkCapabilities.newInstance()
            shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
            assertFalse(isOffline(activity))
            compose.runOnIdle { shadowOf(manager).networkCallbacks.forEach { it.onAvailable(checkNotNull(manager.activeNetwork)) } }
            compose.onNodeWithText(activity.getString(R.string.network_title)).assertIsDisplayed()
            compose.onNodeWithText(activity.getString(R.string.network_body)).assertIsDisplayed()
            compose.onNodeWithText(activity.getString(R.string.offline_title)).assertDoesNotExist()
        } finally { controller.pause().stop().destroy() }
    }
}
