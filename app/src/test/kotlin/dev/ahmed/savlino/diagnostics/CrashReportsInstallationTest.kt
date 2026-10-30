package dev.ahmed.savlino.diagnostics

import android.app.Application
import android.os.Build
import dev.ahmed.savlino.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// SDK coverage: CrashReports.kt:9-16 records SDK as metadata; handler installation has no API branch.
@Config(sdk = [29], application = Application::class)
class CrashReportsInstallationTest {
    @Test fun installedHandlerWritesBuildMetadataAndDelegatesWithoutDoubleWrapping() {
        val app = RuntimeEnvironment.getApplication()
        val store = CrashReports.store(app)
        store.deleteReports()
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val thread = Thread("main"); val error = IllegalStateException("https://example.invalid/x")
        var calls = 0
        try {
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                calls++; assertSame(thread, t); assertSame(error, e)
            }
            CrashReports.install(app)
            val handler = Thread.getDefaultUncaughtExceptionHandler()!!
            CrashReports.install(app)
            assertSame(handler, Thread.getDefaultUncaughtExceptionHandler())
            handler.uncaughtException(thread, error)
            assertEquals(1, calls)
            val text = store.latestReports()!!
            assertTrue("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" in text)
            assertTrue("Android SDK: ${Build.VERSION.SDK_INT}" in text)
            assertFalse("https:" in text)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
            store.deleteReports()
        }
    }
}
