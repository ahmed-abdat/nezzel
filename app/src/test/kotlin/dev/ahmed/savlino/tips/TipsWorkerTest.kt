package dev.ahmed.savlino.tips

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ahmed.savlino.MainActivity
import dev.ahmed.savlino.jobs.IsolatedTestApplication
import dev.ahmed.savlino.locale.AppPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = IsolatedTestApplication::class)
class TipsWorkerTest {
    private val app get() = RuntimeEnvironment.getApplication() as IsolatedTestApplication
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val day = 86_400_000L
    @Test fun twoIgnoredStretchesSurviveReopeningAndStopFutureTips() {
        var clock = day
        val tips = Tips(app) { clock }
        tips.onOpen()
        clock = 4 * day; tips.check(false)
        clock = 5 * day; tips.onOpen()
        clock = 8 * day; tips.check(false)
        clock = 9 * day; tips.onOpen()
        // A fresh process and additional ordinary opens must not reset the ignored count.
        Tips(app) { clock }.onOpen()
        clock = 12 * day
        Tips(app) { clock }.check(false)
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(0, tips.history.sent)
        assertEquals(2, app.getSharedPreferences("tips", Context.MODE_PRIVATE).getInt("ignored", 0))
    }
    @Test fun turningTipsOffAndOnRestartsAfterTwoIgnoredStretches() {
        val prefs = app.getSharedPreferences("tips", Context.MODE_PRIVATE)
        prefs.edit().putInt("ignored", 2).putLong("last-open", day).commit()
        AppPreferences(app).tips = false
        AppPreferences(app).tips = true
        assertEquals(0, prefs.getInt("ignored", -1))
        Tips(app) { 8 * day }.check(false)
        assertEquals(1, manager.activeNotifications.size)
    }
    @Test fun tipTapResetsIgnoredStretchesForColdAndWarmActivityEntry() {
        val prefs = app.getSharedPreferences("tips", Context.MODE_PRIVATE)
        prefs.edit().putInt("ignored", 2).putInt("sent", 1).commit()
        AppPreferences(app).apply { appUpdates = false; helpImprove = false; suggestionsEnabled = false; automaticOptions = false }
        val tap = android.content.Intent(app, MainActivity::class.java).setAction(Tips.OPEN)
        val activity = org.robolectric.Robolectric.buildActivity(MainActivity::class.java, tap).setup()
        try {
            assertEquals(0, prefs.getInt("ignored", -1))
            assertEquals(0, app.tips.history.sent)
            prefs.edit().putInt("ignored", 2).putInt("sent", 1).commit()
            activity.newIntent(android.content.Intent(tap))
            assertEquals(0, prefs.getInt("ignored", -1))
            assertEquals(0, app.tips.history.sent)
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun disabledNotificationsDoNotAdvanceTheFeatureRotation() {
        val tips = Tips(app) { 8 * day }
        tips.onOpen(day)
        shadowOf(manager).setNotificationsEnabled(false)
        tips.check(false)
        assertEquals(0, tips.history.sent)
        assertEquals(0, tips.history.nextFeature)
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun whatsappTipIsSkippedUntilAnEnabledWhatsappFamilyAppIsInstalled() {
        val prefs = app.getSharedPreferences("tips", Context.MODE_PRIVATE)
        val tips = Tips(app) { 8 * day }
        tips.onOpen(day)
        prefs.edit().putInt("next-feature", 3).commit()
        tips.check(false)
        assertEquals("Nezzel on your Home screen", manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TITLE))
        shadowOf(app.packageManager).installPackage(android.content.pm.PackageInfo().apply {
            packageName = "com.whatsapp.w4b"
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "com.whatsapp.w4b"; enabled = true }
        })
        tips.onOpen(day)
        prefs.edit().putInt("next-feature", 3).putLong("last-tip", 0).commit()
        tips.check(false)
        assertEquals("Keep a WhatsApp status", manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TITLE))
    }
    @Test fun workerPostsOnDayThreeAndTapOpensHome() = runBlocking {
        app.tips.onOpen(System.currentTimeMillis() - 3 * day)
        val worker = TestListenableWorkerBuilder<TipsWorker>(app).build()
        worker.doWork()
        val notification = manager.activeNotifications.single { it.id == Tips.ID }.notification
        assertEquals("Save up to 50 links", notification.extras.getString(Notification.EXTRA_TITLE))
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component!!.className)
        assertEquals(Tips.OPEN, intent.action)
        assertEquals(1, app.tips.history.sent)
        worker.doWork()
        assertEquals(1, app.tips.history.sent)
    }
    @Test fun daysCapsOpeningBusyAndMutedChannelControlActualPosts() {
        var clock = day
        val tips = Tips(app) { clock }
        tips.onOpen()
        clock = 4 * day - 1; tips.check(false)
        assertTrue(manager.activeNotifications.isEmpty())
        clock = 4 * day; tips.check(true)
        assertTrue(manager.activeNotifications.isEmpty())
        AppPreferences(app).tips = false; tips.check(false)
        assertTrue(manager.activeNotifications.isEmpty())
        AppPreferences(app).tips = true; tips.check(false)
        assertEquals(1, tips.history.sent)
        clock = 8 * day; tips.check(false)
        assertEquals(2, tips.history.sent)
        clock = 100 * day; tips.check(false)
        assertEquals(2, tips.history.sent)
        tips.onOpen(); assertEquals(0, tips.history.sent)
        assertTrue(manager.activeNotifications.isEmpty())
        clock = 103 * day; tips.check(false)
        assertEquals(1, tips.history.sent)
        manager.deleteNotificationChannel(Tips.CHANNEL)
        manager.createNotificationChannel(android.app.NotificationChannel(Tips.CHANNEL, "Muted", NotificationManager.IMPORTANCE_NONE))
        clock = 107 * day; tips.check(false)
        assertEquals(1, tips.history.sent)
    }
    @Test fun dailyScheduleIsUniqueKeepBatteryNotLowAndNetworkFree() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        try {
            app.tips.schedule().result.get()
            val work = WorkManager.getInstance(app)
            val first = work.getWorkInfosForUniqueWork(Tips.WORK).get().single()
            app.tips.schedule().result.get()
            assertEquals(first.id, work.getWorkInfosForUniqueWork(Tips.WORK).get().single().id)
            assertEquals(day, first.periodicityInfo!!.repeatIntervalMillis)
            assertTrue(first.constraints.requiresBatteryNotLow())
            assertEquals(androidx.work.NetworkType.NOT_REQUIRED, first.constraints.requiredNetworkType)
        } finally { WorkManagerTestInitHelper.closeWorkDatabase() }
    }
    @Test fun tipsHaveArabicAndEnglishAndSkipWhatsappWhenMissing() {
        for (language in listOf("ar", "en")) {
            val config = android.content.res.Configuration(app.resources.configuration).apply { setLocale(java.util.Locale(language)) }
            val context = app.createConfigurationContext(config)
            Tips.FEATURES.forEach { feature ->
                assertTrue(context.getString(feature.first).isNotBlank())
                assertTrue(context.getString(feature.second).isNotBlank())
                if (language == "ar") assertTrue(context.getString(feature.first).any { it in '\u0600'..'\u06ff' })
            }
        }
    }
}
