package dev.ahmed.savlino.jobs

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.engine.FakeEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Pause/Resume in the notification: which actions each state offers, and that the actions reach the coordinator. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = IsolatedTestApplication::class)
class PauseNotificationsTest {
    private val app get() = RuntimeEnvironment.getApplication() as IsolatedTestApplication
    private val running = DownloadProgress("job", "Clip.mp4", DownloadPhase.DOWNLOADING, bytes = 25, total = Size.Exact(100))
    private fun titles(n: Notification) = n.actions.orEmpty().map { it.title.toString() }
    private fun actionOf(n: Notification, title: String) = shadowOf(n.actions.single { it.title == title }.actionIntent).savedIntent

    @Test fun runningOffersPauseThenCancelAndIsOngoing() {
        val n = DownloadNotifications(app).foreground(running).notification
        assertEquals(listOf("Pause", "Cancel"), titles(n))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(DownloadNotifications.PAUSE, actionOf(n, "Pause").action)
        assertEquals("job", actionOf(n, "Pause").getStringExtra(DownloadWorker.JOB_ID))
    }

    @Test fun pausedOffersResumeAndCancelIsNotOngoingAndKeepsRealNumbersOnly() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val paused = running.copy(phase = DownloadPhase.PREPARING, paused = true, hasPartial = true)
        val manager = app.getSystemService(NotificationManager::class.java)
        DownloadNotifications(app).paused(paused)
        val n = manager.activeNotifications.single().notification
        assertEquals(listOf("Resume", "Cancel"), titles(n))
        assertEquals(0, n.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(DownloadNotifications.RESUME, actionOf(n, "Resume").action)
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text, text.startsWith("Paused · ") && text.contains("25"))
        // Without bytes (after a process restart) only the word.
        DownloadNotifications(app).paused(paused.copy(bytes = null, total = Size.Unknown))
        assertEquals("Paused", manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test fun aPausedProgressEventPostsNoUntaggedNotification() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        DownloadNotifications(app).progress(running.copy(phase = DownloadPhase.PREPARING, paused = true, hasPartial = true))
        assertEquals(0, manager.activeNotifications.size)
        DownloadNotifications(app).progress(running)
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test fun waitingForNetworkAndRestartingOfferOnlyCancelAndNoPercentText() {
        val waiting = DownloadProgress("job", "Clip.mp4", DownloadPhase.PREPARING, waiting = true, hasPartial = true)
        val offline = DownloadNotifications.text(app, waiting, offline = true)
        assertEquals("Waiting for network", offline)
        assertEquals("Queued", DownloadNotifications.text(app, waiting, offline = false))
        assertEquals(listOf("Cancel"), titles(DownloadNotifications(app).foreground(waiting).notification))
        val restarting = DownloadProgress("job", "Clip.mp4", DownloadPhase.PREPARING, restartEpoch = 1)
        assertEquals("Starting again", DownloadNotifications.text(app, restarting))
        assertEquals(listOf("Cancel"), titles(DownloadNotifications(app).foreground(restarting).notification))
    }

    @Test fun pauseAndResumeReachTheCoordinatorAndClearThePausedNotification() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store = JobStore(app)
        val coordinator = DownloadCoordinator(app, engine = FakeEngine(), store = store)
        app.coordinatorOverride = coordinator
        val manager = app.getSystemService(NotificationManager::class.java)
        try {
            assertTrue(coordinator.enqueue(command("job")))
            assertTrue(DownloadControls.pause(app, app, "job"))
            assertEquals(JobStage.PAUSED, store.get("job")!!.stage)
            assertEquals(listOf("Resume", "Cancel"), titles(manager.activeNotifications.single().notification))
            // The notification's own Resume button goes through the receiver.
            DownloadControlReceiver().onReceive(app, Intent(DownloadNotifications.RESUME).putExtra(DownloadWorker.JOB_ID, "job"))
            val deadline = System.currentTimeMillis() + 5000
            while (store.get("job")!!.stage == JobStage.PAUSED && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(JobStage.ENQUEUED, store.get("job")!!.stage)
            assertTrue(manager.activeNotifications.isEmpty())
            assertFalse("a second Resume is refused", DownloadControls.resume(app, app, "job"))
        } finally { app.coordinatorOverride = null; store.close() }
    }

    @Test fun finishingOrCancellingClearsAPausedNotification() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = DownloadNotifications(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        notifications.paused(running.copy(paused = true, phase = DownloadPhase.PREPARING))
        assertEquals(1, manager.activeNotifications.size)
        notifications.finished(running.copy(phase = DownloadPhase.CANCELLED))
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun deniedPermissionPostsNoPausedNotification() {
        val manager = app.getSystemService(NotificationManager::class.java)
        DownloadNotifications(app).paused(running.copy(paused = true, phase = DownloadPhase.PREPARING))
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
