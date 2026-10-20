package dev.ahmed.savlino.update

import android.app.Application
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33, 35], application = Application::class)
class UpdateInstallerTest {
    @Test fun sessionDescribesUserDownloadedUpdateAndRequiresConfirmation() {
        val context = RuntimeEnvironment.getApplication()
        val release = Release("0.4.5", 99999, "update.apk", 100, "https://github.com/asset", "a".repeat(64), emptyList(), emptyList())
        val params = UpdateInstaller.sessionParams(context, release)
        assertEquals(PackageInstaller.SessionParams.MODE_FULL_INSTALL, ReflectionHelpers.getField<Int>(params, "mode"))
        assertEquals(context.packageName, ReflectionHelpers.getField<String>(params, "appPackageName"))
        assertEquals(100L, ReflectionHelpers.getField<Long>(params, "sizeBytes"))
        assertEquals(PackageManager.INSTALL_REASON_USER, ReflectionHelpers.getField<Int>(params, "installReason"))
        if (Build.VERSION.SDK_INT >= 31)
            assertEquals(PackageInstaller.SessionParams.USER_ACTION_REQUIRED, ReflectionHelpers.getField<Int>(params, "requireUserAction"))
        if (Build.VERSION.SDK_INT >= 33)
            assertEquals(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE, ReflectionHelpers.getField<Int>(params, "packageSource"))
    }
}
