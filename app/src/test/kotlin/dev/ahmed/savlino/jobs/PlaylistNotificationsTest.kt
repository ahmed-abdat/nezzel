package dev.ahmed.savlino.jobs

import android.app.Notification
import android.app.NotificationManager
import dev.ahmed.savlino.downloads.playlistFixture
import dev.ahmed.savlino.domain.DownloadError
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class PlaylistNotificationsTest {
    @Test @Config(application = PlaylistNotificationApplication::class)
    fun durableMembershipSuppressesResultsEvenBeforeQueuedReconciliation() {
        val app = RuntimeEnvironment.getApplication() as PlaylistNotificationApplication
        app.deleteDatabase(JobStore.DATABASE_NAME)
        try {
            val batches = BatchStore(app.jobStore, batchCipher())
            assertTrue(batches.create("list", "Fixture", dev.ahmed.savlino.domain.Preset.BALANCED, batchItems(2)))
            val notifications = DownloadNotifications(app) // Exercise the production membership lookup.
            val manager = app.getSystemService(NotificationManager::class.java)
            batches.items("list").forEach { item ->
                assertEquals(BatchItemState.WAITING, item.state)
                assertTrue(batches.containsJob(item.jobId))
                notifications.finished(DownloadProgress(item.jobId, "Fixture.mp4", DownloadPhase.COMPLETED))
            }
            assertTrue(manager.activeNotifications.isEmpty())
            notifications.finished(DownloadProgress("standalone", "Single.mp4", DownloadPhase.COMPLETED))
            assertEquals(1, manager.activeNotifications.size)
        } finally { app.jobStore.close() }
    }

    @Test @Config(sdk = [33]) fun deniedPermissionDoesNotConsumeTheFinalNotification() {
        val app = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val notifications = DownloadNotifications(app, batchMember = { true })
        val final = playlistFixture(BatchState.COMPLETED, failed = true)
        notifications.playlist(final)
        assertTrue(manager.activeNotifications.isEmpty())
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        notifications.playlist(final)
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test fun batchForegroundItemsStayInTheExistingGroupAndSilent() {
        val app = RuntimeEnvironment.getApplication()
        val notifications = DownloadNotifications(app, batchMember = { true })
        val foreground = notifications.foreground(DownloadProgress("batch-job", "Fixture.mp4", DownloadPhase.DOWNLOADING)).notification
        assertEquals(DownloadNotifications.GROUP, foreground.group)
        assertNull(foreground.sound)
        assertNull(foreground.vibrate)
        assertEquals(0, foreground.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))
    }

    @Test fun stoppedAndExpiredUseStoppedCopyInsteadOfCallingSkippedItemsFailures() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(NotificationManager::class.java)
        val notifications = DownloadNotifications(app, batchMember = { true })
        for (state in listOf(BatchState.STOPPED, BatchState.EXPIRED)) {
            val fixture = playlistFixture(state)
            val skipped = BatchItemRecord("list", 1, "b", "Not started", BatchItemState.SKIPPED, "job-b", DownloadError.Cancelled, false, false)
            notifications.playlist(PlaylistBatch(fixture.record, listOf(fixture.items.first(), skipped)))
            assertEquals("Stopped: 1 not started", manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TEXT))
        }
    }

    @Test fun completedSummaryCountsOnlyFailedItems() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(NotificationManager::class.java)
        val fixture = playlistFixture(BatchState.COMPLETED, failed = true)
        val skipped = BatchItemRecord("list", 2, "c", "Skipped", BatchItemState.SKIPPED, "job-c", DownloadError.Cancelled, false, false)
        DownloadNotifications(app, batchMember = { true }).playlist(PlaylistBatch(fixture.record, fixture.items + skipped))
        assertEquals("Playlist saved: 1 videos, 1 failed", manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test fun oneSummaryUpdatedInPlaceOneFinalAndZeroItemResults() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(NotificationManager::class.java)
        val notifications = DownloadNotifications(app, batchMember = { it.startsWith("job-") })
        notifications.playlist(playlistFixture())
        notifications.playlist(playlistFixture())
        val active = manager.activeNotifications.single()
        assertTrue(active.notification.extras.getString(Notification.EXTRA_TEXT)!!.contains("1 of 2"))
        repeat(2) { notifications.finished(DownloadProgress("job-$it", "Video.mp4", DownloadPhase.COMPLETED)) }
        assertEquals(1, manager.activeNotifications.size)
        notifications.playlist(playlistFixture(BatchState.COMPLETED, failed = true))
        val finalTime = manager.activeNotifications.single().postTime
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        notifications.playlist(playlistFixture(BatchState.COMPLETED, failed = true))
        val final = manager.activeNotifications.single()
        assertEquals(finalTime, final.postTime)
        // Even a new presenter/process cannot re-post a dismissed final result.
        manager.cancel(final.tag, final.id)
        DownloadNotifications(app, batchMember = { true }).playlist(playlistFixture(BatchState.COMPLETED, failed = true))
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(active.id, final.id); assertEquals(active.tag, final.tag)
        assertEquals("Playlist saved: 1 videos, 1 failed", final.notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(0, final.notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(0, final.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }
}

/** No startup workers/engine warm-up in the notification membership fixture. */
class PlaylistNotificationApplication : NezzelApplication() {
    override fun onCreate() = Unit
    val jobStore by lazy { JobStore(this) }
    override val downloads by lazy { DownloadCoordinator(this, engine = dev.ahmed.savlino.engine.FakeEngine(), store = jobStore) }
}
