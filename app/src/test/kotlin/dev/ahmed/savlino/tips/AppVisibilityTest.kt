package dev.ahmed.savlino.tips

import android.app.Activity
import android.app.Application
import dev.ahmed.savlino.jobs.AppVisibility
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class AppVisibilityTest {
    private class RotatingActivity : Activity() {
        override fun isChangingConfigurations() = true
    }
    @Test fun overlappingWindowsResetTipsOnlyOnceAndStayForegroundUntilBothClose() {
        var opens = 0
        val visibility = AppVisibility { opens++ }
        val home = Activity()
        val share = Activity()
        visibility.onActivityStarted(home)
        visibility.onActivityStarted(share)
        assertEquals(1, opens)
        visibility.onActivityStopped(home)
        assertTrue(visibility.foreground)
        visibility.onActivityStopped(share)
        assertFalse(visibility.foreground)
        visibility.onActivityStarted(home)
        assertEquals(2, opens)
    }
    @Test fun rotationNeverOpensASilentInstallGapOrResetsTheStretch() {
        var opens = 0
        val visibility = AppVisibility { opens++ }
        val old = RotatingActivity()
        visibility.onActivityStarted(old)
        visibility.onActivityStopped(old)
        assertTrue(visibility.foreground)
        val replacement = Activity()
        visibility.onActivityStarted(replacement)
        assertEquals(1, opens)
        visibility.onActivityStopped(replacement)
        assertFalse(visibility.foreground)
    }
}
