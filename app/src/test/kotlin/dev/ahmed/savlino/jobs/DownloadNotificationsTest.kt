package dev.ahmed.savlino.jobs

import android.Manifest
import android.app.NotificationManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DownloadNotificationsTest {
    @Test fun foregroundRetryClearsFailureResultBeforeCancellation() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = DownloadNotifications(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        val failed = DownloadProgress("retry-job", "Retry.mp4", DownloadPhase.FAILED,
            error = dev.ahmed.savlino.domain.DownloadError.Network)
        notifications.finished(failed)
        val oldResultId = manager.activeNotifications.single().id
        val unrelated = failed.copy(jobId = "other-job", filename = "Other.mp4")
        notifications.finished(unrelated)
        val otherResultId = manager.activeNotifications.single { it.id != oldResultId }.id

        val retry = failed.copy(phase = DownloadPhase.PREPARING, error = null)
        val payload = notifications.foreground(retry)
        assertNull(shadowOf(manager).getNotification(oldResultId))
        // WorkManager posts the returned foreground payload.
        manager.notify(payload.notificationId, payload.notification)
        notifications.progress(retry.copy(phase = DownloadPhase.DOWNLOADING))
        assertNull(shadowOf(manager).getNotification(oldResultId))
        assertNotNull(shadowOf(manager).getNotification(otherResultId))
        assertEquals(2, manager.activeNotifications.size)
        notifications.finished(retry.copy(phase = DownloadPhase.CANCELLED))
        assertNull(shadowOf(manager).getNotification(oldResultId))
        assertEquals(otherResultId, manager.activeNotifications.single().id)
    }

    @Test fun terminalResultSurvivesPendingProgressFlush() = kotlinx.coroutines.runBlocking {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = DownloadNotifications(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        val running = DownloadProgress("job", "Video.mp4", DownloadPhase.DOWNLOADING, bytes = 1)
        val persistence = ProgressPersistence({ 0L }) { event ->
            if (!event.terminal) notifications.progress(event)
        }
        persistence.report(running)
        persistence.report(running.copy(bytes = 2)) // Held until the next phase.
        val failed = running.copy(phase = DownloadPhase.FAILED, error = dev.ahmed.savlino.domain.DownloadError.Network)
        // DownloadWorker posts the result before flushing pending WorkManager progress.
        notifications.finished(failed)
        val resultId = manager.activeNotifications.single { it.notification.channelId == "completed" }.id
        persistence.report(failed)
        assertNotNull(shadowOf(manager).getNotification(resultId))
    }

    private fun queuedJobNotification(language: String) {
        val app = RuntimeEnvironment.getApplication()
        dev.ahmed.savlino.locale.AppLocale.choose(app, language)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = DownloadNotifications(app)
        val running = DownloadProgress("running", "Running.mp4", DownloadPhase.DOWNLOADING,
            bytes = 1, total = dev.ahmed.savlino.domain.Size.Exact(4))
        val queued = DownloadProgress("queued", "Queued.mp4", DownloadPhase.PREPARING, waiting = true)
        val active = notifications.foreground(running)
        val waiting = notifications.foreground(queued)
        assertNotEquals(active.notificationId, waiting.notificationId)
        val label = if (language == "ar") "في قائمة الانتظار" else "Queued"
        assertEquals(label, waiting.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        val cancel = shadowOf(waiting.notification.actions.single().actionIntent).savedIntent
        assertEquals("queued", cancel.getStringExtra(DownloadWorker.JOB_ID))
        notifications.progress(running); notifications.progress(queued)
        notifications.finished(queued.copy(phase = DownloadPhase.CANCELLED, waiting = false))
        val remaining = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals("Running.mp4", remaining.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
    }
    @Test fun queuedJobKeepsItsOwnEnglishNotificationAndCancellation() = queuedJobNotification("en")
    @Test fun queuedJobKeepsItsOwnArabicNotificationAndCancellation() = queuedJobNotification("ar")

    @Test fun completionUsesAlertingChannelWhileProgressStaysSilent() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = DownloadNotifications(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        val active = notifications.foreground(DownloadProgress("a", "Actual title.mp4", DownloadPhase.DOWNLOADING))
        assertEquals("downloads", active.notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel("downloads").importance)
        assertTrue(active.notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
        notifications.finished(DownloadProgress("a", "Actual title.mp4", DownloadPhase.COMPLETED))
        val result = shadowOf(manager).allNotifications.single()
        assertEquals("completed", result.channelId)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel("completed").importance)
        assertEquals("Actual title.mp4", result.extras.getString(android.app.Notification.EXTRA_TITLE))
    }
    @Test fun permissionDenialSuppressesNotificationsButKeepsForegroundPayload() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = DownloadNotifications(app)
        assertNotNull(notifications.foreground(DownloadProgress("a", "Title.mp4", DownloadPhase.DOWNLOADING)))
        notifications.progress(DownloadProgress("a", "Title.mp4", DownloadPhase.DOWNLOADING))
        notifications.finished(DownloadProgress("a", "Title.mp4", DownloadPhase.COMPLETED))
        assertTrue(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }
    @Test fun batteryStopNamesBatteryNotDownloadFailed() {
        val app = RuntimeEnvironment.getApplication()
        val stopped = DownloadProgress("a", "Title.mp4", DownloadPhase.FAILED, error = dev.ahmed.savlino.domain.DownloadError.StoppedByBattery)
        assertEquals(app.getString(dev.ahmed.savlino.R.string.download_battery_error), DownloadNotifications.phase(app, stopped))
        assertTrue(DownloadNotifications.text(app, stopped).contains(app.getString(dev.ahmed.savlino.R.string.download_battery_error)))
    }
    private fun metrics() = DownloadProgress("a", "Actual.mp4", DownloadPhase.DOWNLOADING,
        bytes = 25_000_000, total = dev.ahmed.savlino.domain.Size.Exact(100_000_000),
        bytesPerSecond = 2_500_000.0, remainingTransferSeconds = 30, stalled = false)
    private fun metricPayload(language: String) {
        val app = RuntimeEnvironment.getApplication()
        dev.ahmed.savlino.locale.AppLocale.choose(app, language)
        val config = android.content.res.Configuration(app.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag("$language-u-nu-latn"))
        }
        val localized = app.createConfigurationContext(config)
        val speed = "2.5\u00a0MB/s"
        val remaining = if (language == "ar") "متبقٍّ للتنزيل ≈ \u20660:30\u2069" else "≈ \u20660:30\u2069 left to download"
        val text = DownloadNotifications.text(localized, metrics())
        assertTrue(text, text.contains(speed))
        assertTrue(text, text.contains(remaining))
        // The sentence itself is not wrapped in an LTR isolate, so Arabic keeps its reading order.
        assertEquals(remaining, DownloadNotifications.transferParts(localized, metrics())?.remaining)
        assertFalse(text, text.any { it in '\u0660'..'\u0669' })
        val notification = DownloadNotifications(app).foreground(metrics()).notification
        val actual = notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue(actual, actual.contains(speed)); assertTrue(actual, actual.contains(remaining))
        val expanded = notification.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString()
        assertTrue("Expanded notification must retain readable metrics: $expanded", expanded.contains(speed) && expanded.contains(remaining))
    }
    @Test fun observedMetricsAppearInActualEnglishNotification() = metricPayload("en")
    @Test fun observedMetricsAppearInActualArabicNotificationWithLatinDigits() = metricPayload("ar")
    @Test fun unknownAndEstimatedTotalsKeepRateButRejectSuppliedEtaAndPercent() {
        val app = RuntimeEnvironment.getApplication()
        listOf(dev.ahmed.savlino.domain.Size.Unknown, dev.ahmed.savlino.domain.Size.Estimated(100_000_000)).forEach { total ->
            val text = DownloadNotifications.text(app, metrics().copy(total = total))
            assertTrue(text, text.contains("2.5\u00a0MB/s"))
            assertFalse(text, text.contains("left to download"))
            assertFalse(text, text.contains("25%"))
        }
    }
    @Test fun nonTransferPhasesNeverLeakStaleMetrics() {
        val app = RuntimeEnvironment.getApplication()
        val live = DownloadNotifications.text(app, metrics())
        assertTrue(live, live.contains("2.5\u00a0MB/s"))
        DownloadPhase.entries.filter { it != DownloadPhase.DOWNLOADING }.forEach { phase ->
            val text = DownloadNotifications.text(app, metrics().copy(phase = phase))
            assertFalse("$phase: $text", text.contains("/s") || text.contains("left to download"))
        }
    }
    @Test fun stalledMissingInvalidRateAndWaitingSuppressNotificationTimeAndSpeed() {
        val app = RuntimeEnvironment.getApplication()
        assertTrue(DownloadNotifications.text(app, metrics()).contains("2.5\u00a0MB/s"))
        val invalid = listOf<Double?>(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE)
            .map { metrics().copy(bytesPerSecond = it) } + listOf(metrics().copy(stalled = true), metrics().copy(waiting = true))
        invalid.forEach { progress ->
            val text = DownloadNotifications.text(app, progress)
            assertFalse(text, text.contains("/s") || text.contains("left to download") || text.contains("NaN") || text.contains("Infinity"))
        }
    }

    private fun measuredBytesOnly(language: String) {
        val app = RuntimeEnvironment.getApplication()
        val config = android.content.res.Configuration(app.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag("$language-u-nu-latn"))
        }
        val context = app.createConfigurationContext(config)
        listOf(dev.ahmed.savlino.domain.Size.Estimated(100_000_000), dev.ahmed.savlino.domain.Size.Unknown).forEach { total ->
            val progress = metrics().copy(total = total)
            val bytes = DownloadNotifications.bytes(context, progress)
            assertEquals("Nonexact total must not be a transfer denominator", dev.ahmed.savlino.locale.NezzelFormat.isolate("25\u00a0MB"), bytes)
            val text = DownloadNotifications.text(context, progress)
            assertTrue(text, text.contains("25\u00a0MB"))
            assertFalse(text, text.contains("100\u00a0MB") || text.contains(context.getString(dev.ahmed.savlino.R.string.unknown_size)))
        }
    }
    @Test fun nonExactTransferTotalsNeverAppearAsNotificationDenominatorEnglish() = measuredBytesOnly("en")
    @Test fun nonExactTransferTotalsNeverAppearAsNotificationDenominatorArabic() = measuredBytesOnly("ar")

    private fun truthfulStallNotification(language: String) {
        val app = RuntimeEnvironment.getApplication()
        dev.ahmed.savlino.locale.AppLocale.choose(app, language)
        val config = android.content.res.Configuration(app.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag("$language-u-nu-latn"))
        }
        val context = app.createConfigurationContext(config)
        val notifications = DownloadNotifications(app)
        val stalledLabel = if (language == "ar") "في انتظار البيانات…" else "Waiting for data…"
        val speed = "2.5\u00a0MB/s"
        val remaining = if (language == "ar") "متبقٍّ للتنزيل" else "left to download"
        fun payload(progress: DownloadProgress): String = notifications.foreground(progress).notification.extras
            .getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        val stalled = payload(metrics().copy(stalled = true))
        assertTrue(stalled, stalled.contains(stalledLabel))
        assertFalse(stalled, stalled.contains(speed) || stalled.contains(remaining))
        val recovered = payload(metrics().copy(stalled = false))
        assertTrue(recovered, recovered.contains(context.getString(dev.ahmed.savlino.R.string.download_transferring)))
        assertTrue(recovered, recovered.contains(speed) && recovered.contains(remaining))
        assertFalse(recovered, recovered.contains(stalledLabel))
        val waiting = payload(metrics().copy(stalled = true, waiting = true))
        assertTrue(waiting, waiting.contains(context.getString(dev.ahmed.savlino.R.string.download_waiting)))
        assertFalse(waiting, waiting.contains(stalledLabel) || waiting.contains(speed) || waiting.contains(remaining))
        DownloadPhase.entries.filter { it != DownloadPhase.DOWNLOADING }.forEach { phase ->
            val text = DownloadNotifications.text(context, metrics().copy(phase = phase, stalled = true))
            assertFalse("$phase: $text", text.contains(stalledLabel) || text.contains(speed) || text.contains(remaining))
        }
    }
    @Test fun actualEnglishNotificationShowsOnlyFactualTransferStallAndRecovery() = truthfulStallNotification("en")
    @Test fun actualArabicNotificationShowsOnlyFactualTransferStallAndRecovery() = truthfulStallNotification("ar")

    private fun honestHlsNotification(language: String) {
        val app = RuntimeEnvironment.getApplication()
        dev.ahmed.savlino.locale.AppLocale.choose(app, language)
        val context = dev.ahmed.savlino.locale.AppLocale.localized(app)
        val notifications = DownloadNotifications(app)
        val progress = metrics().copy(bytes = 160_900_000,
            total = dev.ahmed.savlino.domain.Size.Estimated(156_700_000), fragmentIndex = 25, fragmentCount = 100)
        fun payload(event: DownloadProgress) = notifications.foreground(event).notification
        val measured = payload(progress)
        val text = measured.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue(text, text.contains("25%") && text.contains("160.9\u00a0MB"))
        assertFalse(text, text.contains("156.7") || text.contains(if (language == "ar") "متبقٍّ" else "left to download"))
        assertFalse(measured.extras.getBoolean(android.app.Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertEquals(25, measured.extras.getInt(android.app.Notification.EXTRA_PROGRESS))
        listOf(progress.copy(fragmentIndex = null, fragmentCount = null), progress.copy(stalled = true)).forEach { event ->
            val unknown = payload(event)
            assertTrue(unknown.extras.getBoolean(android.app.Notification.EXTRA_PROGRESS_INDETERMINATE))
            assertFalse(DownloadNotifications.text(context, event).contains("25%"))
        }
        val overrun = progress.copy(fragmentIndex = null, fragmentCount = null,
            total = dev.ahmed.savlino.domain.Size.Exact(156_700_000))
        assertFalse(DownloadNotifications.text(context, overrun).contains("156.7"))
        assertEquals(context.getString(dev.ahmed.savlino.R.string.download_preparing),
            DownloadNotifications.text(context, progress.copy(phase = DownloadPhase.PREPARING, bytes = 1,
                total = dev.ahmed.savlino.domain.Size.Exact(1024))))
    }
    @Test fun actualEnglishNotificationKeepsFragmentPercentAndHonestBytes() = honestHlsNotification("en")
    @Test fun actualArabicNotificationKeepsFragmentPercentAndHonestBytes() = honestHlsNotification("ar")

    private fun twoRunning(language: String) {
        val app = RuntimeEnvironment.getApplication()
        dev.ahmed.savlino.locale.AppLocale.choose(app, language)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = DownloadNotifications(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        val a = DownloadProgress("a", "A.mp4", DownloadPhase.DOWNLOADING, bytes = 1, total = dev.ahmed.savlino.domain.Size.Exact(4))
        val b = a.copy(jobId = "b", filename = "B.mp4")
        fun summary() = manager.activeNotifications.singleOrNull { it.tag == DownloadNotifications.SUMMARY_TAG }
        fun title(n: android.app.Notification) = n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString()
        // WorkManager posts each returned foreground payload.
        val first = notifications.foreground(a)
        manager.notify(first.notificationId, first.notification)
        assertNull("One job needs no summary", summary())
        val second = notifications.foreground(b)
        manager.notify(second.notificationId, second.notification)
        val group = summary()!!.notification
        assertTrue(group.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals(DownloadNotifications.GROUP, group.group)
        assertEquals(DownloadNotifications.CHANNEL, group.channelId)
        assertEquals(dev.ahmed.savlino.locale.AppLocale.localized(app).resources.getQuantityString(dev.ahmed.savlino.R.plurals.downloads_running, 2,
            dev.ahmed.savlino.locale.NezzelFormat.count(2, language)), title(group))
        assertEquals(listOf(DownloadNotifications.GROUP, DownloadNotifications.GROUP),
            listOf(first, second).map { it.notification.group })
        assertNotEquals(first.notificationId, second.notificationId)

        // A ends while B runs: A's result stands alone, B's live notification stays as it was, the summary stays over it.
        notifications.progress(b.copy(bytes = 2))
        notifications.finished(a.copy(phase = DownloadPhase.COMPLETED, publishedUri = android.net.Uri.parse("content://media/1")))
        manager.cancel(first.notificationId) // WorkManager drops the ended worker's foreground notification.
        notifications.settled("a")
        val live = manager.activeNotifications.filter { it.tag == null && it.notification.channelId == DownloadNotifications.CHANNEL }
        assertEquals(listOf(second.notificationId), live.map { it.id })
        assertEquals("B.mp4", title(live.single().notification))
        assertTrue(live.single().notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        val result = manager.activeNotifications.single { it.notification.channelId == DownloadNotifications.COMPLETED_CHANNEL }
        assertEquals("A.mp4", title(result.notification))
        assertNull("A result never joins the live group", result.notification.group)
        assertNotNull("The summary outlives one ending job: cancelling it would take B's plain notification along", summary())

        // The last job ends: no orphaned summary.
        notifications.finished(b.copy(phase = DownloadPhase.CANCELLED))
        notifications.settled("b")
        assertNull(summary())
        assertTrue(manager.activeNotifications.none { it.notification.channelId == DownloadNotifications.CHANNEL })
    }
    @Test fun twoRunningJobsGroupUnderOneEnglishSummaryThatSurvivesOneEnding() = twoRunning("en")
    @Test fun twoRunningJobsGroupUnderOneArabicSummaryThatSurvivesOneEnding() = twoRunning("ar")
    private fun runningTitle(count: Int) = dev.ahmed.savlino.locale.AppLocale.localized(RuntimeEnvironment.getApplication()).resources
        .getQuantityString(dev.ahmed.savlino.R.plurals.downloads_running, count, dev.ahmed.savlino.locale.NezzelFormat.count(count, "en"))

    @Test fun jobsWhoseIdsShareAHashKeepTheirOwnNotificationsAndBothCount() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val notifications = DownloadNotifications(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        val a = DownloadProgress("Aa", "A.mp4", DownloadPhase.DOWNLOADING, bytes = 1, total = dev.ahmed.savlino.domain.Size.Exact(4))
        val b = a.copy(jobId = "BB", filename = "B.mp4")
        val infos = listOf(a, b).map { notifications.foreground(it).also { info -> manager.notify(info.notificationId, info.notification) } }
        assertNotEquals(infos[0].notificationId, infos[1].notificationId)
        notifications.progress(a.copy(bytes = 2)); notifications.progress(b.copy(bytes = 3))
        val live = manager.activeNotifications.filter { it.tag == null }
        assertEquals(setOf("A.mp4", "B.mp4"), live.map { it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString() }.toSet())
        val summary = manager.activeNotifications.single { it.tag == DownloadNotifications.SUMMARY_TAG }.notification
        assertEquals(runningTitle(2), summary.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
    }

    @Test fun anOldAttemptSettlingAfterItsRerunPromotedLeavesTheJobCounted() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = DownloadNotifications(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        val a = DownloadProgress("a", "A.mp4", DownloadPhase.DOWNLOADING, bytes = 1, total = dev.ahmed.savlino.domain.Size.Exact(4))
        val b = a.copy(jobId = "b", filename = "B.mp4")
        val old = Any(); val rerun = Any()
        fun promote(job: DownloadProgress, attempt: Any) =
            notifications.foreground(job, attempt).also { manager.notify(it.notificationId, it.notification) }
        fun summaryTitle() = manager.activeNotifications.singleOrNull { it.tag == DownloadNotifications.SUMMARY_TAG }
            ?.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        promote(a, old); promote(b, Any())
        assertEquals(runningTitle(2), summaryTitle())
        // The stopped attempt released the job; its rerun promotes before the old worker's finally runs.
        val again = promote(a.copy(phase = DownloadPhase.PREPARING), rerun)
        notifications.settled("a", old)
        assertEquals("The rerun still runs under the same ID", runningTitle(2), summaryTitle())
        manager.cancel(again.notificationId) // WorkManager drops the rerun's foreground notification when it ends.
        notifications.settled("a", rerun)
        notifications.finished(b.copy(phase = DownloadPhase.CANCELLED))
        assertNull(summaryTitle())
    }

    @Test fun pausedJobLeavesTheGroupAndItsCount() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = DownloadNotifications(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        val a = DownloadProgress("a", "A.mp4", DownloadPhase.DOWNLOADING)
        listOf(a, a.copy(jobId = "b", filename = "B.mp4")).forEach { job ->
            notifications.foreground(job).let { manager.notify(it.notificationId, it.notification) }
        }
        val first = manager.activeNotifications.single { it.tag == null && it.notification.extras.getString(android.app.Notification.EXTRA_TITLE) == "A.mp4" }
        notifications.paused(a.copy(paused = true))
        manager.cancel(first.id)
        val paused = manager.activeNotifications.single { it.tag == "paused" }.notification
        assertNull(paused.group)
        val summary = manager.activeNotifications.single { it.tag == DownloadNotifications.SUMMARY_TAG }.notification
        assertEquals(app.resources.getQuantityString(dev.ahmed.savlino.R.plurals.downloads_running, 1,
            dev.ahmed.savlino.locale.NezzelFormat.count(1, "en")), summary.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
    }
}
