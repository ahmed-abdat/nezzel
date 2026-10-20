package dev.ahmed.savlino.ui

import android.content.Intent
import android.content.pm.ProviderInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.ahmed.savlino.R
import dev.ahmed.savlino.storage.SaveDestination
import dev.ahmed.savlino.storage.SaveDestinations
import dev.ahmed.savlino.storage.SaveDestinationTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w360dp-h800dp-mdpi")
class SaveFolderSettingTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun defaultRowLaunchesSystemTreePicker() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.setContent { MaterialTheme { SaveFolderSetting() } }
            compose.onNodeWithText(activity.getString(R.string.change_save_folder)).performClick()
            val launch = shadowOf(activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, launch.intent.action)
            compose.onNodeWithText(activity.getString(R.string.save_folder_default)).assertDoesNotExist()
        } finally { controller.pause().stop().destroy() }
    }

    @Test @Config(qualifiers = "ar-w360dp-h800dp-mdpi")
    fun unavailableFolderIsIsolatedAndCanResetAtDoubleFont() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val provider = SaveDestinationTest.Provider()
        provider.attachInfo(activity, ProviderInfo().apply {
            authority = "test.documents"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        })
        ShadowContentResolver.registerProviderInternal("test.documents", provider)
        val settings = SaveDestinations(activity)
        settings.choose(SaveDestinationTest.tree)
        provider.denied = true
        try {
            activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    MaterialTheme { SaveFolderSetting() }
                }
            }
            compose.waitUntil(5000) {
                compose.onAllNodesWithText(activity.getString(R.string.save_folder_unavailable)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("\u2066Chosen folder\u2069", useUnmergedTree = true).assertExists()
            compose.onNodeWithText(activity.getString(R.string.save_folder_default)).performClick()
            compose.waitUntil(5000) { settings.destination is SaveDestination.Default }
            compose.waitUntil(5000) {
                compose.onAllNodesWithText(activity.getString(R.string.save_folder_default)).fetchSemanticsNodes().isEmpty()
            }
            assertTrue(activity.contentResolver.persistedUriPermissions.isEmpty())
            assertEquals("تعذّر الحفظ في المجلد المختار. اختره مجددًا من الإعدادات.", activity.getString(R.string.save_folder_error))
        } finally { controller.pause().stop().destroy() }
    }
}
