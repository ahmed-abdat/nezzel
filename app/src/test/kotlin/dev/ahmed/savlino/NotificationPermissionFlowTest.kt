package dev.ahmed.savlino

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.jobs.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationPermissionFlowTest {
    // Installs the test frame policy, so a Popup's every-frame position poll (Compose 1.12) can't spin the looper forever.
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    // The rule settles composition/layout across all windows without four forced 150ms looper advances.
    private fun nodes(): List<SemanticsNode> = compose.onAllNodes(
        SemanticsMatcher("all nodes") { true }, useUnmergedTree = true,
    ).fetchSemanticsNodes()
    private fun click(nodes: List<SemanticsNode>, label: String) {
        nodes.first { node -> node.config.getOrNull(SemanticsActions.OnClick) != null &&
            descendants(node).any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true }
        }.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke()
    }
    @Test fun firstDownloadShowsRationaleThenRequestsPermissionAndDenialStillEnqueues() = assertDownloadPermission(true)
    @Test @Config(sdk = [35])
    fun api35RequestsPermissionBeforeDownload() = assertDownloadPermission(true)
    @Test fun grantedPermissionContinuesWithoutPrompt() = assertDownloadPermission(false, expectsPrompt = false, granted = true)
    @Test @Config(qualifiers = "ar")
    fun arabicRationaleCanBeSkippedAndDownloadStillEnqueues() = assertDownloadPermission(false)
    @Test @Config(sdk = [29])
    fun pre33DownloadNeedsNoPermissionPrompt() = assertDownloadPermission(false, expectsPrompt = false)

    @Test fun rationaleRecreationRetainsOriginalChoiceAndSkipEnqueuesOnce() =
        assertDownloadPermission(false, recreateRationale = true)
    @Test fun permissionResultAfterRecreationEnqueuesOriginalChoiceOnce() =
        assertDownloadPermission(true, recreatePermission = true)

    private fun assertDownloadPermission(request: Boolean, expectsPrompt: Boolean = true, granted: Boolean = false,
        recreateRationale: Boolean = false, recreatePermission: Boolean = false) {
        val app = RuntimeEnvironment.getApplication() as IsolatedTestApplication
        if (granted) shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        else shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store = JobStore(app)
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun installedVersion() = "2026.08.19"
            override suspend fun inspect(source: LinkRevision, operationId: String) = InspectResult.Success(
                Inspection(source, "2026.08.19", listOf(Format("720", "mp4", true, true, height = 720)),
                    listOf(PresetChoice(Preset.BALANCED, "720")), title = "Real title"))
        }
        app.coordinatorOverride = DownloadCoordinator(app, engine, store, workManager = { error("No background scheduler in UI fixture") })
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        var activity = controller.get()
        try {
            activity.getPreferences(0).edit().clear().commit()
            activity.links.edit("https://example.com/video"); activity.links.checkLink()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.links.optionsState.toString(), activity.links.downloadEnabled)
            activity.links.requestDownload()
            controller.visible()
            if (expectsPrompt) {
                if (recreateRationale) {
                    val originalActivity = activity
                    val original = activity.links
                    controller.configurationChange().visible()
                    activity = controller.get()
                    assertNotSame(originalActivity, activity)
                    assertSame(original, activity.links)
                }
                val all = nodes()
                assertTrue(all.map { it.config }.toString(), all.any { it.config.getOrNull(SemanticsProperties.Text)?.any { t ->
                    t.text == activity.getString(R.string.notification_rationale_body) } == true })
                assertTrue(store.all().isEmpty())
                assertNull(shadowOf(activity).lastRequestedPermission)
                click(all, activity.getString(if (request) R.string.allow_notifications else R.string.notification_skip))
                if (request) {
                    val permission = shadowOf(activity).lastRequestedPermission!!
                    assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), permission.requestedPermissions)
                    assertTrue(store.all().isEmpty())
                    if (recreatePermission) {
                        val originalActivity = activity
                        val original = activity.links
                        controller.configurationChange().visible()
                        activity = controller.get()
                        assertNotSame(originalActivity, activity)
                        assertSame(original, activity.links)
                    }
                    activity.onRequestPermissionsResult(permission.requestCode, permission.requestedPermissions,
                        intArrayOf(PackageManager.PERMISSION_DENIED))
                }
            }
            waitWhile({ shadowOf(Looper.getMainLooper()).idle() }) { store.all().isEmpty() }
            assertEquals("Real title.mp4", store.all().single().filename)
            assertEquals(JobStage.ENQUEUED, store.all().single().stage)
            // Further confirmed downloads bypass the first-run explanation even after denial/skip.
            activity.links.edit("https://example.com/second"); activity.links.checkLink()
            shadowOf(Looper.getMainLooper()).idle(); activity.links.requestDownload()
            runBlocking { withTimeout(5000) {
                while (store.all().size < 2) { shadowOf(Looper.getMainLooper()).idle(); delay(5) }
            } }
            assertEquals(2, store.all().size) // A repeated prompt would block the second enqueue.
        } finally {
            runBlocking { withTimeout(5000) {
                while (app.scope.coroutineContext[Job]!!.children.any()) { shadowOf(Looper.getMainLooper()).idle(); delay(5) }
            } }
            controller.pause().stop().destroy(); store.close(); app.coordinatorOverride = null
        }
    }
}
