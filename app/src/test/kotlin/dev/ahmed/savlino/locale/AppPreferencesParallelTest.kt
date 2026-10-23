package dev.ahmed.savlino.locale

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AppPreferencesParallelTest {
    @Test fun defaultAndClampedChoicesArePortableAndObservable() {
        val app = RuntimeEnvironment.getApplication()
        val portable = app.getSharedPreferences("nezzel-portable", Context.MODE_PRIVATE)
        portable.edit().clear().commit()
        val prefs = AppPreferences(app)
        assertEquals(2, prefs.parallelDownloads); assertEquals(2, prefs.parallelDownloadsFlow.value)
        for ((requested, expected) in listOf(0 to 1, 3 to 3, 99 to 3, -9 to 1, 2 to 2)) {
            prefs.chooseParallelDownloads(requested)
            assertEquals(expected, prefs.parallelDownloads)
            assertEquals(expected, prefs.parallelDownloadsFlow.value)
            assertEquals(expected, portable.getInt("parallel_downloads", -1))
            assertEquals(expected, AppPreferences(app).parallelDownloadsFlow.value)
        }
        AppPreferences(app).chooseParallelDownloads(1)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, prefs.parallelDownloadsFlow.value)
        portable.edit().putInt("parallel_downloads", 20).commit()
        assertEquals(3, AppPreferences(app).parallelDownloads)
    }
}
