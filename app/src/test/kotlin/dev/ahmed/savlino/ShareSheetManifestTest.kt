package dev.ahmed.savlino

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Share, Open in Nezzel and tapped links reach the sheet; the launcher still opens the full app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShareSheetManifestTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun ours(intent: Intent, flags: Int = PackageManager.MATCH_DEFAULT_ONLY) = app.packageManager.queryIntentActivities(intent, flags)
        .map { it.activityInfo }.filter { it.packageName == app.packageName }
    private fun target(info: ActivityInfo) = info.targetActivity ?: info.name

    @Test fun shareAndProcessTextOpenTheSheetNotTheApp() {
        val send = ours(Intent(Intent.ACTION_SEND).setType("text/plain"))
        assertEquals(listOf("dev.ahmed.savlino.ShareSheetActivity"), send.map(::target))
        val process = ours(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"))
        assertEquals(listOf("dev.ahmed.savlino.ShareSheetActivity"), process.map(::target))
    }

    @Test fun tappedLinksOpenTheSheetThroughTheAlias() {
        val view = ours(Intent(Intent.ACTION_VIEW, Uri.parse("https://youtu.be/one")).addCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals(listOf("dev.ahmed.savlino.OpenWithLinks"), view.map { it.name })
        assertEquals("dev.ahmed.savlino.ShareSheetActivity", view.single().targetActivity)
    }

    @Test fun launcherStillOpensTheFullApp() {
        val launcher = ours(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        assertEquals(listOf("dev.ahmed.savlino.MainActivity"), launcher.map(::target))
    }

    @Test fun sheetStaysOutOfRecentsInItsOwnTask() {
        val info = app.packageManager.getActivityInfo(ComponentName(app, ShareSheetActivity::class.java), 0)
        assertTrue(info.exported)
        assertTrue(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
        assertEquals("dev.ahmed.savlino.share", info.taskAffinity)
        assertNotEquals(app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0).taskAffinity, info.taskAffinity)
    }
}
