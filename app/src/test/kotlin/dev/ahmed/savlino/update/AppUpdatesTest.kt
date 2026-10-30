package dev.ahmed.savlino.update

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ahmed.savlino.locale.AppPreferences
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import dev.ahmed.savlino.BuildConfig
import dev.ahmed.savlino.jobs.IsolatedTestApplication
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.security.MessageDigest
import android.content.Intent
import android.content.pm.*
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.engine.*
import kotlinx.coroutines.flow.first
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowPackageInstaller

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class AppUpdatesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val production get() = object : ContextWrapper(context) { override fun getPackageName() = "dev.ahmed.savlino" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @Before fun setup() {
        context.getSharedPreferences("app-updates", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nezzel", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nezzel-portable", Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.cacheDir, "updates").deleteRecursively()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }
    @After fun teardown() { scope.cancel(); WorkManagerTestInitHelper.closeWorkDatabase() }
    @Test fun switchPersistsAndCancelsPeriodicWork() {
        AppPreferences(context).helpImprove = false
        val updates = AppUpdates(production, scope)
        updates.setAutomatic(true)
        assertTrue(AppPreferences(context).appUpdates)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(AppUpdates.WORK).get().any { !it.state.isFinished })
        updates.setAutomatic(false)
        assertFalse(AppPreferences(context).appUpdates)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(AppUpdates.WORK).get().all { it.state.isFinished })
    }
    @Test fun dailyWorkStaysForTheUsageSendWhileHelpImproveIsOn() {
        val updates = AppUpdates(production, scope)
        updates.setAutomatic(false)
        assertTrue(AppPreferences(context).helpImprove)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(AppUpdates.WORK).get().any { !it.state.isFinished })
    }
    @Test fun schedulingUpdatesExistingPeriodToTwelveHours() {
        val updates = AppUpdates(production, scope)
        val work = WorkManager.getInstance(context)
        val old = androidx.work.PeriodicWorkRequestBuilder<AppUpdateWorker>(24, java.util.concurrent.TimeUnit.HOURS)
            .setInitialDelay(24, java.util.concurrent.TimeUnit.HOURS).build()
        work.enqueueUniquePeriodicWork(AppUpdates.WORK, androidx.work.ExistingPeriodicWorkPolicy.KEEP, old).result.get()
        updates.setAutomatic(true)
        val updated = work.getWorkInfosForUniqueWork(AppUpdates.WORK).get().single { !it.state.isFinished }
        assertEquals(old.id, updated.id)
        assertTrue(updated.initialDelayMillis in 21_600_000L..43_200_000L)
        assertEquals(43_200_000L, updated.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(androidx.work.NetworkType.CONNECTED, updated.constraints.requiredNetworkType)
        assertTrue(updated.constraints.requiresBatteryNotLow())
    }
    @Test fun sixHourSuccessGateChecksOnlyWhenOlder() = runBlocking {
        var now = 100_000_000L
        var requests = 0
        val updates = AppUpdates(production, scope, { now }) { object : UpdateHttp("test") {
            override suspend fun manifest(): ByteArray { requests++; return manifestBytes() }
        } }
        updates.check(manual = false)
        now += 21_600_000L
        updates.check(manual = false)
        assertEquals(1, requests)
        now++
        updates.check(manual = false)
        assertEquals(2, requests)
    }
    @Test fun onlyTheFirstCheckOfAUtcDayIsCounted() = runBlocking {
        var now = 10 * UpdatePolicy.DAY + 1_000L
        val counted = mutableListOf<Boolean>()
        val updates = AppUpdates(production, scope, { now }) { count -> counted += count; object : UpdateHttp("test") {
            override suspend fun manifest(): ByteArray = manifestBytes()
        } }
        updates.check(manual = true)
        now += 7 * 3_600_000L
        updates.check(manual = true)
        now = 11 * UpdatePolicy.DAY
        updates.check(manual = false)
        assertEquals(listOf(true, false, true), counted)
    }
    @Test fun dismissalPersistsPerVersionAndExpiresAtThreeDays() {
        val updates = cachedRelease()
        updates.dismiss()
        val dismissed = updates.state.value.dismissedAt!!
        val restored = AppUpdates(production, scope)
        assertFalse(restored.state.value.card(1, dismissed + 259_199_999L))
        assertTrue(restored.state.value.card(1, dismissed + 259_200_000L))
        assertTrue(restored.state.value.badge(1))
        restored.skip(99999)
        assertFalse(restored.state.value.card(1, dismissed + 259_200_000L))
    }
    @Test fun reminderWaitsThreeDaysAndIsConsumedOnlyWhenActuallyShown() {
        val updates = cachedRelease()
        val firstSeen = updates.prefs.getLong("offered-99999", -1)
        assertTrue(firstSeen > 0)
        fun open(time: Long): AppUpdates = AppUpdates(production, scope, { time }) { error("No network") }
            .also { it.onAppOpen() }
        assertNull(open(firstSeen + 259_199_999L).state.value.offerSheet)
        val due = open(firstSeen + 259_200_000L)
        assertNotNull(due.state.value.offerSheet)
        // A blocked modal has not shown/consumed the prompt.
        val deferred = open(firstSeen + 259_200_001L)
        assertNotNull(deferred.state.value.offerSheet)
        deferred.offerSheetShown(99999)
        deferred.dismissOfferSheet()
        assertNull(deferred.state.value.offerSheet)
        assertNull(open(firstSeen + 259_200_002L).state.value.offerSheet)
    }
    private fun importantRelease(): AppUpdates {
        val manifest = org.json.JSONObject(manifestBytes().toString(Charsets.UTF_8)).put("important", true)
        context.getSharedPreferences("app-updates", Context.MODE_PRIVATE).edit()
            .putString("release", manifest.toString()).putString("release-format", "manifest").commit()
        return AppUpdates(production, scope)
    }
    @Test fun importantSheetReturnsEachOpenEvenWhenSkippedButSwitchWins() {
        val updates = importantRelease()
        updates.skip(99999)
        updates.onAppOpen()
        assertNotNull(updates.state.value.offerSheet)
        assertFalse(updates.state.value.badge(1))
        updates.offerSheetShown(99999); updates.dismissOfferSheet()
        assertNull(updates.state.value.offerSheet)
        updates.onAppOpen() // A configuration change/resume within the same open is not a new reminder.
        assertNull(updates.state.value.offerSheet)
        updates.onAppClosed(); updates.onAppOpen()
        assertNotNull(updates.state.value.offerSheet)
        updates.setAutomatic(false)
        assertNull(updates.state.value.offerSheet)
        updates.onAppClosed(); updates.onAppOpen()
        assertNull(updates.state.value.offerSheet)
    }
    @Test fun importantRenotifiesAtMostOncePerDayAndSkipStillWins() {
        val first = importantRelease()
        val manager = context.getSystemService(NotificationManager::class.java)
        first.postNotification(); manager.cancel(UpdateNotifications.ID)
        val notifiedAt = first.prefs.getLong("notified-at-99999", -1)
        assertTrue(notifiedAt > 0)
        var now = notifiedAt + 86_399_999L
        val updates = AppUpdates(production, scope, { now }) { error("No network") }
        updates.postNotification()
        assertTrue(manager.activeNotifications.isEmpty())
        now++
        updates.postNotification()
        assertEquals(1, manager.activeNotifications.size)
        manager.cancel(UpdateNotifications.ID)
        updates.skip(99999); now += 86_400_000L
        updates.postNotification()
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun jitterIncludesBothBoundsAndUsesInjectedRandom() {
        fun fixed(upper: Boolean) = object : kotlin.random.Random() {
            override fun nextBits(bitCount: Int): Int = error("Use nextLong")
            override fun nextLong(until: Long): Long = if (upper) until - 1 else 0
        }
        assertEquals(0L, updateCheckJitterMillis(fixed(false)))
        assertEquals(21_600_000L, updateCheckJitterMillis(fixed(true)))
        val random = kotlin.random.Random(42)
        val values = List(1000) { updateCheckJitterMillis(random) }
        assertTrue(values.all { it in 0L..21_600_000L })
        assertTrue(values.distinct().size > 1)
    }
    @Test fun allocatableSpaceMustFitTwoCopiesAndMargin() {
        val needed = 69_206_016L // Two 1 MiB copies plus 64 MiB.
        assertEquals(needed, requiredUpdateSpace(1_048_576))
        assertTrue(hasUpdateSpace(context, 1_048_576, { needed }, { error("No fallback on success") }))
        assertFalse(hasUpdateSpace(context, 1_048_576, { needed - 1 }, { Long.MAX_VALUE }))
    }
    @Test fun storageFailureFallsBackToUsableSpaceWithSameThreshold() {
        val unavailable = { throw java.io.IOException("Storage unavailable") }
        assertTrue(hasUpdateSpace(context, 1_048_576, unavailable, { 69_206_016 }))
        assertFalse(hasUpdateSpace(context, 1_048_576, unavailable, { 69_206_015 }))
        assertFalse(hasUpdateSpace(context, 1_048_576, { throw SecurityException() }, { 0 }))
    }
    @Test fun acceptancePackageNeverSchedulesOrOffers() {
        val variant = object : ContextWrapper(context) { override fun getPackageName() = "dev.ahmed.savlino.acceptance" }
        val updates = AppUpdates(variant, scope)
        updates.setAutomatic(true)
        assertFalse(updates.available)
        assertNull(updates.state.value.release)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(AppUpdates.WORK).get().none { !it.state.isFinished })
    }
    @Test fun skippingPersistsAndRemovesPostedNotification() {
        val updates = cachedRelease()
        updates.postNotification()
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == UpdateNotifications.ID })
        updates.skip(99999)
        assertEquals(99999L, AppUpdates(production, scope).state.value.skipped)
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == UpdateNotifications.ID })
    }
    private fun cachedRelease(): AppUpdates {
        val json = org.json.JSONObject().put("tag_name", "v0.4.4").put("body", "<!-- nezzel:versionCode=99999 -->")
            .put("assets", org.json.JSONArray().put(org.json.JSONObject().put("name", "update.apk").put("size", 100)
                .put("browser_download_url", "https://github.com/asset").put("digest", "sha256:" + "a".repeat(64))))
        context.getSharedPreferences("app-updates", Context.MODE_PRIVATE).edit().putString("release", json.toString()).commit()
        return AppUpdates(production, scope)
    }
    @Test fun notificationIsPostedOnceAndSkipCancelsIt() {
        val updates = cachedRelease()
        val manager = context.getSystemService(NotificationManager::class.java)
        updates.postNotification()
        assertEquals(1, manager.activeNotifications.count { it.id == UpdateNotifications.ID })
        manager.cancel(UpdateNotifications.ID)
        updates.postNotification()
        assertTrue(manager.activeNotifications.none { it.id == UpdateNotifications.ID })
        updates.skip(99999)
        assertNotNull(updates.state.value.release) // Manual check can still open the skipped release.
        updates.postNotification()
        assertTrue(manager.activeNotifications.none { it.id == UpdateNotifications.ID })
    }
    @Test fun automaticOffNeverPosts() {
        val updates = cachedRelease()
        updates.setAutomatic(false)
        updates.postNotification()
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == UpdateNotifications.ID })
    }

    @Test fun cancellationWinsOverIoFailureDuringTeardown() = runBlocking {
        cachedRelease()
        val entered = CompletableDeferred<Unit>()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val updates = AppUpdates(production, owner) { object : UpdateHttp("test") {
            override suspend fun download(release: Release, file: java.io.File, progress: (Long) -> Unit) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { throw java.io.IOException() }
            }
        } }
        try {
            updates.download()
            withTimeout(5000) { entered.await() }
            updates.cancel()
            withTimeout(5000) { owner.coroutineContext[Job]!!.children.toList().joinAll() }
            assertEquals(UpdateStage.IDLE, updates.state.value.stage)
            assertNull(updates.state.value.failure)
            assertTrue(updates.directory.listFiles().orEmpty().isEmpty())
        } finally { owner.cancel() }
    }
    @Test fun cancelledManualCheckCanBeRetried() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val updates = AppUpdates(production, scope) { object : UpdateHttp("test") {
            override suspend fun manifest(): ByteArray = throw java.io.IOException()
            override suspend fun latest(): ByteArray { entered.complete(Unit); awaitCancellation() }
        } }
        val check = launch { updates.check(manual = true) }
        withTimeout(5000) { entered.await() }
        assertEquals(UpdateCheck.CHECKING, updates.state.value.check)
        check.cancelAndJoin()
        assertEquals(UpdateCheck.NONE, updates.state.value.check)
    }

    @Test fun manualCheckStillOffersSkippedVersionWhenAutomaticIsOff() = runBlocking {
        cachedRelease()
        val bytes = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE).getString("release", null)!!.toByteArray()
        var requests = 0
        val updates = AppUpdates(production, scope) { object : UpdateHttp("test") {
            override suspend fun manifest(): ByteArray = throw java.io.IOException()
            override suspend fun latest(): ByteArray { requests++; return bytes }
        } }
        updates.skip(99999); updates.setAutomatic(false)
        updates.check(manual = false)
        assertEquals(0, requests)
        updates.check(manual = true)
        assertEquals(1, requests)
        assertEquals(UpdateCheck.AVAILABLE, updates.state.value.check)
        assertNotNull(updates.state.value.release)
        assertFalse(updates.state.value.card(1))
    }
    @Test fun successfulStartupCheckIsThrottledAndStaleFilesAreRemoved() = runBlocking {
        cachedRelease()
        val prefs = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
        prefs.edit().putLong("success", System.currentTimeMillis()).commit()
        val updates = AppUpdates(production, scope) { error("A fresh successful check must not make requests") }
        updates.directory.mkdirs(); java.io.File(updates.directory, "stale.part").writeText("partial")
        updates.start()
        withTimeout(5000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
        assertTrue(updates.directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun startSchedulesInTheBackgroundAndATogglePendingMeanwhileWins() = runBlocking {
        context.getSharedPreferences("app-updates", Context.MODE_PRIVATE).edit().putLong("success", System.currentTimeMillis()).commit()
        AppPreferences(context).helpImprove = false
        val work = WorkManager.getInstance(context)
        fun scheduled() = work.getWorkInfosForUniqueWork(AppUpdates.WORK).get().any { !it.state.isFinished }
        val queued = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val owner = CoroutineScope(SupervisorJob() + java.util.concurrent.Executor { queued += it }.asCoroutineDispatcher())
        try {
            val updates = AppUpdates(production, owner) { error("A fresh successful check must not make requests") }
            updates.start()
            assertFalse("start() returns before WorkManager is touched", scheduled())
            updates.setAutomatic(false)
            while (queued.isNotEmpty()) queued.poll()!!.run()
            assertFalse(scheduled())
            updates.setAutomatic(true)
            assertTrue(scheduled())
        } finally { owner.cancel() }
    }

    private fun manifestBytes(): ByteArray = org.json.JSONObject().put("schema", 1).put("version", "0.4.4").put("versionCode", 99999)
        .put("apk", org.json.JSONObject().put("name", "update.apk").put("size", 100).put("sha256", "a".repeat(64)))
        .put("notes", org.json.JSONObject().put("en", org.json.JSONArray().put("Fixed"))).toString().toByteArray()

    private fun updatedFrom044(cachedCode: Long, french: List<String> = emptyList()): AppUpdates {
        val code = dev.ahmed.savlino.BuildConfig.VERSION_CODE.toLong()
        val manifest = org.json.JSONObject().put("schema", 1).put("version", "0.4.5").put("versionCode", cachedCode)
            .put("apk", org.json.JSONObject().put("name", "update.apk").put("size", 100).put("sha256", "a".repeat(64)))
            .put("notes", org.json.JSONObject().put("en", org.json.JSONArray().put("Fixed")).put("ar", org.json.JSONArray().put("أُصلح")))
        if (french.isNotEmpty()) manifest.getJSONObject("notes").put("fr", org.json.JSONArray(french))
        // What 0.4.4 leaves behind: install-code and its cached offer, no install-notes
        context.getSharedPreferences("app-updates", Context.MODE_PRIVATE).edit().clear().putLong("install-code", code)
            .putString("release", manifest.toString()).putString("release-format", "manifest").commit()
        return AppUpdates(production, scope)
    }
    @Test fun updateFrom044ShowsCachedReleaseNotesOnce() {
        val updates = updatedFrom044(dev.ahmed.savlino.BuildConfig.VERSION_CODE.toLong())
        val whatsNew = updates.state.value.whatsNew!!
        assertEquals(listOf("Fixed"), whatsNew.notes("en"))
        assertEquals(listOf("أُصلح"), whatsNew.notes("ar"))
        assertFalse(updates.consumeUpdated())
        // A later check replaces the cached offer; the migrated notes must survive it
        updates.prefs.edit().putString("release", "{}").putString("release-format", "api").commit()
        assertEquals(listOf("Fixed"), AppUpdates(production, scope).state.value.whatsNew?.notes("en"))
        updates.dismissWhatsNew()
        assertNull(updates.state.value.whatsNew)
        val restarted = AppUpdates(production, scope)
        assertNull(restarted.state.value.whatsNew)
        assertEquals(listOf("Fixed"), restarted.currentNotes()?.notes("en"))
    }
    @Test fun updateFrom044WithOtherCachedReleaseFallsBackToSnackbar() {
        val updates = updatedFrom044(dev.ahmed.savlino.BuildConfig.VERSION_CODE.toLong() + 1)
        assertNull(updates.state.value.whatsNew)
        assertTrue(updates.consumeUpdated())
    }
    @Test fun cachedReleaseMigrationRetainsFrenchAfterTheReleaseCacheIsReplaced() {
        val updates = updatedFrom044(dev.ahmed.savlino.BuildConfig.VERSION_CODE.toLong(), listOf("Corrigé"))
        assertEquals(listOf("Corrigé"), updates.state.value.whatsNew?.notes("fr"))
        updates.prefs.edit().remove("release").commit()
        val restarted = AppUpdates(production, scope)
        assertEquals(listOf("Corrigé"), restarted.state.value.whatsNew?.notes("fr"))
        restarted.dismissWhatsNew()
        assertEquals(listOf("Corrigé"), AppUpdates(production, scope).currentNotes()?.notes("fr"))
    }
    @Test fun manifestSuccessSkipsApiAndRestoresItsCache() = runBlocking {
        val requests = mutableListOf<String>()
        val updates = AppUpdates(production, scope) { object : UpdateHttp("test") {
            override suspend fun manifest(): ByteArray { requests += "manifest"; return manifestBytes() }
            override suspend fun latest(): ByteArray { requests += "api"; error("API must not be called") }
        } }
        updates.check(manual = true)
        assertEquals(listOf("manifest"), requests)
        assertEquals(UpdateCheck.AVAILABLE, updates.state.value.check)
        assertEquals("manifest", updates.prefs.getString("release-format", null))
        assertEquals("https://github.com/ahmed-abdat/nezzel/releases/download/v0.4.4/update.apk",
            AppUpdates(production, scope).state.value.release!!.url)
    }
    @Test fun failedOrMalformedManifestFallsBackOnceAndRestoresApiCache() = runBlocking {
        cachedRelease()
        val apiBytes = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE).getString("release", null)!!.toByteArray()
        for (failure in listOf("404", "403", "network", "garbage")) {
            val requests = mutableListOf<String>()
            val updates = AppUpdates(production, scope) { object : UpdateHttp("test") {
                override suspend fun manifest(): ByteArray {
                    requests += "manifest"
                    if (failure == "garbage") return "broken".toByteArray()
                    throw java.io.IOException(failure)
                }
                override suspend fun latest(): ByteArray { requests += "api"; return apiBytes }
            } }
            updates.check(manual = true)
            assertEquals(listOf("manifest", "api"), requests)
            assertEquals(UpdateCheck.AVAILABLE, updates.state.value.check)
            assertEquals("api", updates.prefs.getString("release-format", null))
            assertEquals("https://github.com/asset", AppUpdates(production, scope).state.value.release!!.url)
        }
    }
    @Test fun bothSourcesFailShowsFailed() = runBlocking {
        val requests = mutableListOf<String>()
        val updates = AppUpdates(production, scope) { object : UpdateHttp("test") {
            override suspend fun manifest(): ByteArray { requests += "manifest"; throw java.io.IOException() }
            override suspend fun latest(): ByteArray { requests += "api"; return "broken".toByteArray() }
        } }
        updates.check(manual = true)
        assertEquals(listOf("manifest", "api"), requests)
        assertEquals(UpdateCheck.FAILED, updates.state.value.check)
    }
    @Test fun legacyApiCacheRestoresAndUnknownCacheFormatIsDropped() {
        val updates = cachedRelease()
        assertNotNull(updates.state.value.release)
        updates.prefs.edit().putString("release-format", "unknown").commit()
        assertNull(AppUpdates(production, scope).state.value.release)
    }
    @Test fun cancellationTeardownDoesNotStartApiFallback() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var apiRequests = 0
        val updates = AppUpdates(production, scope) { object : UpdateHttp("test") {
            override suspend fun manifest(): ByteArray {
                entered.complete(Unit)
                try { awaitCancellation() } finally { throw java.io.IOException() }
            }
            override suspend fun latest(): ByteArray { apiRequests++; error("Cancelled check must not reach API") }
        } }
        val check = launch { updates.check(manual = true) }
        withTimeout(5000) { entered.await() }
        check.cancelAndJoin()
        assertEquals(0, apiRequests)
        assertEquals(UpdateCheck.NONE, updates.state.value.check)
    }

    private suspend fun settle() = withTimeout(5000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
    private fun archive(file: java.io.File, code: Long) {
        val signer = android.content.pm.Signature("01020304")
        fun identity(version: Long) = android.content.pm.PackageInfo().apply {
            packageName = production.packageName
            longVersionCode = version
            signingInfo = android.content.pm.SigningInfo().also {
                org.robolectric.Shadows.shadowOf(it).setSignatures(arrayOf(signer))
            }
        }
        val manager = org.robolectric.Shadows.shadowOf(context.packageManager)
        manager.installPackage(identity(dev.ahmed.savlino.BuildConfig.VERSION_CODE.toLong()))
        manager.setPackageArchiveInfo(file.absolutePath, identity(code))
    }
    private suspend fun downloaded(): AppUpdates {
        val bytes = "verified apk".toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val manifest = org.json.JSONObject(manifestBytes().toString(Charsets.UTF_8))
        manifest.getJSONObject("apk").put("size", bytes.size).put("sha256", digest)
        val updates = AppUpdates(production, scope) { object : UpdateHttp("test") {
            override suspend fun manifest() = manifest.toString().toByteArray()
            override suspend fun download(release: Release, file: java.io.File, progress: (Long) -> Unit) {
                file.writeBytes(bytes)
                archive(file, release.code)
                archive(java.io.File(file.parentFile, release.name), release.code)
            }
        } }
        updates.check(manual = true)
        updates.download(); settle()
        assertEquals(UpdateStage.READY, updates.state.value.stage)
        return updates
    }
    private suspend fun backgroundOffer(valid: Boolean, canPrepare: Boolean = true): AppUpdates {
        val bytes = "verified apk".toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val manifest = org.json.JSONObject(manifestBytes().toString(Charsets.UTF_8))
        manifest.getJSONObject("apk").put("size", bytes.size).put("sha256", digest)
        val manager = context.getSystemService(NotificationManager::class.java)
        val updates = AppUpdates(production, scope) { object : UpdateHttp("test") {
            override suspend fun manifest() = manifest.toString().toByteArray()
            override suspend fun download(release: Release, file: java.io.File, progress: (Long) -> Unit) {
                assertTrue("No ready notification before verification", manager.activeNotifications.isEmpty())
                file.writeBytes(bytes)
                // Matching bytes with a different archive version must still fail closed.
                archive(file, if (valid) release.code else release.code + 1)
                archive(java.io.File(file.parentFile, release.name), release.code)
            }
        } }
        updates.check(manual = true)
        updates.notifyInBackground(canPrepare)
        return updates
    }
    @Test fun backgroundVerifiesBeforePostingReadyAndTapUsesExistingUpdateRoute() = runBlocking {
        val updates = backgroundOffer(valid = true)
        assertEquals(UpdateStage.READY, updates.state.value.stage)
        assertNotNull(updates.readyFile)
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals(context.getString(dev.ahmed.savlino.R.string.update_notification_ready),
            notification.extras.getString(android.app.Notification.EXTRA_TEXT))
        assertEquals(UpdateNotifications.OPEN, org.robolectric.Shadows.shadowOf(notification.contentIntent).savedIntent.action)
        // Preparation must not create an install session or authorize installation.
        assertFalse(updates.prefs.contains("install-session"))
        assertNull(updates.state.value.confirmation)
    }
    @Test fun backgroundVerificationFailureFallsBackToAvailableAndCleansFile() = runBlocking {
        val updates = backgroundOffer(valid = false)
        assertEquals(UpdateFailure.VERIFY, updates.state.value.failure)
        assertNull(updates.readyFile)
        assertTrue(updates.directory.listFiles().orEmpty().isEmpty())
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals(context.getString(dev.ahmed.savlino.R.string.update_notification_text),
            notification.extras.getString(android.app.Notification.EXTRA_TEXT))
    }
    @Test fun meteredOrLowBatteryOnlyNotifiesAvailableWithoutDownloading() = runBlocking {
        val updates = backgroundOffer(valid = true, canPrepare = false)
        assertEquals(UpdateStage.IDLE, updates.state.value.stage)
        assertNull(updates.readyFile)
        assertFalse(updates.directory.exists())
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals(context.getString(dev.ahmed.savlino.R.string.update_notification_text),
            notification.extras.getString(android.app.Notification.EXTRA_TEXT))
    }
    @Test fun backgroundRechecksAnAlreadyReadyFileBeforeAdvertisingIt() = runBlocking {
        val updates = downloaded()
        updates.readyFile!!.writeText("tampered apk")
        updates.notifyInBackground(true)
        assertNull(updates.readyFile)
        assertEquals(UpdateFailure.VERIFY, updates.state.value.failure)
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals(context.getString(dev.ahmed.savlino.R.string.update_notification_text),
            notification.extras.getString(android.app.Notification.EXTRA_TEXT))
    }
    @Test fun preparationNeedsAnUnmeteredNetworkAndKnownNotLowBattery() {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val network = org.robolectric.Shadows.shadowOf(manager)
        fun connection(type: Int) {
            network.setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
                android.net.NetworkInfo.DetailedState.CONNECTED, type, 0, true, true))
        }
        fun battery(low: Boolean) {
            context.sendStickyBroadcast(android.content.Intent(android.content.Intent.ACTION_BATTERY_CHANGED)
                .putExtra(android.os.BatteryManager.EXTRA_BATTERY_LOW, low))
        }
        connection(android.net.ConnectivityManager.TYPE_WIFI)
        context.sendStickyBroadcast(android.content.Intent(android.content.Intent.ACTION_BATTERY_CHANGED))
        assertFalse(canPrepareUpdate(context)) // Missing battery state fails closed.
        battery(false)
        assertTrue(canPrepareUpdate(context))
        battery(true)
        assertFalse(canPrepareUpdate(context))
        battery(false)
        connection(android.net.ConnectivityManager.TYPE_MOBILE)
        assertFalse(canPrepareUpdate(context))
        network.setActiveNetworkInfo(null)
        assertFalse(canPrepareUpdate(context))
    }
    @Test fun backgroundNetworkFailureFallsBackToAvailable() = runBlocking {
        cachedRelease()
        val updates = AppUpdates(production, scope) { object : UpdateHttp("test") {
            override suspend fun download(release: Release, file: java.io.File, progress: (Long) -> Unit) {
                throw java.io.IOException("offline")
            }
        } }
        updates.notifyInBackground(true)
        assertEquals(UpdateFailure.NETWORK, updates.state.value.failure)
        assertNull(updates.readyFile)
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals(context.getString(dev.ahmed.savlino.R.string.update_notification_text),
            notification.extras.getString(android.app.Notification.EXTRA_TEXT))
    }
    @Test fun skippedOrDisabledBackgroundNeverTransfersOrNotifies() = runBlocking {
        val updates = cachedRelease()
        updates.skip(99999)
        updates.notifyInBackground(true)
        assertEquals(UpdateStage.IDLE, updates.state.value.stage)
        assertFalse(updates.directory.exists())
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        updates.prefs.edit().remove("skipped").commit()
        val disabled = AppUpdates(production, scope)
        disabled.setAutomatic(false)
        disabled.notifyInBackground(true)
        assertEquals(UpdateStage.IDLE, disabled.state.value.stage)
        assertFalse(disabled.directory.exists())
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }
    @Test fun verifiedDownloadRestoresReadyAcrossProcessRestart() = runBlocking {
        val first = downloaded()
        val restored = AppUpdates(production, scope) { error("No network for a ready download") }
        restored.start(); settle()
        assertEquals(UpdateStage.READY, restored.state.value.stage)
        assertEquals(99999L, restored.state.value.transfer?.code)
        assertEquals(first.readyFile, restored.readyFile)
        restored.download(); settle()
        assertEquals(UpdateStage.READY, restored.state.value.stage)
    }
    @Test fun tamperedReadyDownloadIsDeletedOnRestart() = runBlocking {
        val first = downloaded()
        val file = first.readyFile!!
        file.writeText("tampered apk")
        val restored = AppUpdates(production, scope) { error("Check is throttled") }
        restored.start(); settle()
        assertEquals(UpdateStage.IDLE, restored.state.value.stage)
        assertNull(restored.readyFile)
        assertFalse(file.exists())
    }
    @Test fun interruptedPartSurvivesRestartButAnotherReleaseIsDiscarded() = runBlocking {
        cachedRelease()
        val lengths = mutableListOf<Long>()
        fun updater() = AppUpdates(production, scope) { object : UpdateHttp("test") {
            override suspend fun download(release: Release, file: java.io.File, progress: (Long) -> Unit) {
                lengths += file.length()
                file.appendText("partial")
                throw java.io.IOException("offline")
            }
        } }
        updater().also { it.download(); settle() }
        updater().also { it.download(); settle() }
        assertEquals(listOf(0L, 7L), lengths)
        val json = org.json.JSONObject(context.getSharedPreferences("app-updates", Context.MODE_PRIVATE).getString("release", null)!!)
        json.put("body", "<!-- nezzel:versionCode=100000 -->")
        context.getSharedPreferences("app-updates", Context.MODE_PRIVATE).edit().putString("release", json.toString()).commit()
        updater().also { it.download(); settle() }
        assertEquals(listOf(0L, 7L, 0L), lengths)
    }
    @Test fun verificationFailureDeletesPartInsteadOfResumingIt() = runBlocking {
        cachedRelease()
        val updates = AppUpdates(production, scope) { object : UpdateHttp("test") {
            override suspend fun download(release: Release, file: java.io.File, progress: (Long) -> Unit) {
                file.writeText("bad apk"); archive(file, release.code)
            }
        } }
        updates.download(); settle()
        assertEquals(UpdateFailure.VERIFY, updates.state.value.failure)
        assertTrue(updates.directory.listFiles().orEmpty().isEmpty())
    }
    @Test fun badgeSurvivesCardDismissalButNotSkipOrInstalledVersion() {
        val updates = cachedRelease()
        assertTrue(updates.state.value.badge(1))
        updates.dismiss()
        assertFalse(updates.state.value.card(1))
        assertTrue(updates.state.value.badge(1))
        assertFalse(updates.state.value.badge(99999))
        updates.skip(99999)
        assertFalse(updates.state.value.badge(1))
        assertFalse(AppUpdateState().badge(1))
    }
    @Test fun lastCheckedOnlyAdvancesOnSuccessfulCheckAndRestores() = runBlocking {
        val updates = AppUpdates(production, scope) { object : UpdateHttp("test") {
            override suspend fun manifest() = manifestBytes()
        } }
        assertNull(updates.state.value.lastChecked)
        updates.check(manual = true)
        val checked = updates.state.value.lastChecked!!
        assertTrue(checked > 0)
        val failed = AppUpdates(production, scope) { object : UpdateHttp("test") {
            override suspend fun manifest(): ByteArray = throw java.io.IOException()
            override suspend fun latest(): ByteArray = throw java.io.IOException()
        } }
        failed.check(manual = true)
        assertEquals(checked, failed.state.value.lastChecked)
    }
    private fun pendingInstall(english: List<String>, arabic: List<String> = emptyList(), code: Long = dev.ahmed.savlino.BuildConfig.VERSION_CODE.toLong(), french: List<String> = emptyList()) {
        val updates = AppUpdates(production, scope)
        assertTrue(updates.recordInstall(123, Release("0.4.5", code, "update.apk", 100, "https://github.com/asset", "a".repeat(64), english, arabic, french)))
    }
    @Test fun whatsNewPersistsUntilDismissedAndSuppressesSnackbar() {
        pendingInstall(listOf("Fixed"), listOf("تحسينات"))
        val updates = AppUpdates(production, scope)
        assertEquals("0.4.5", updates.state.value.whatsNew?.version)
        assertEquals(listOf("Fixed"), updates.state.value.whatsNew?.notes("en"))
        assertFalse(updates.consumeUpdated())
        assertNotNull(AppUpdates(production, scope).state.value.whatsNew)
        updates.dismissWhatsNew()
        assertNull(updates.state.value.whatsNew)
        assertNull(AppUpdates(production, scope).state.value.whatsNew)
        assertFalse(updates.consumeUpdated())
    }
    @Test fun whatsNewKeepsBothLanguagesWithoutReloading() {
        pendingInstall(listOf("Fixed"), listOf("تحسينات"))
        val notes = AppUpdates(production, scope).state.value.whatsNew!!
        assertEquals(listOf("Fixed"), notes.notes("en"))
        assertEquals(listOf("تحسينات"), notes.notes("ar"))
        pendingInstall(listOf("Fixed"))
        assertEquals(listOf("Fixed"), AppUpdates(production, scope).state.value.whatsNew?.notes("ar"))
    }
    @Test fun frenchInstallNotesSurviveDismissalAndRestart() {
        pendingInstall(listOf("Fixed"), listOf("تحسينات"), french = listOf("Corrigé"))
        val updates = AppUpdates(production, scope)
        assertEquals(listOf("Corrigé"), updates.state.value.whatsNew?.notes("fr"))
        assertEquals(listOf("Corrigé"), updates.currentNotes()?.notes("fr"))
        updates.dismissWhatsNew()
        val restarted = AppUpdates(production, scope)
        assertNull(restarted.state.value.whatsNew)
        assertEquals(listOf("Corrigé"), restarted.currentNotes()?.notes("fr"))
    }
    @Test fun oldInstallJsonWithoutFrenchStillLoadsAndFallsBackToEnglish() {
        val prefs = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
        prefs.edit().putLong("install-code", dev.ahmed.savlino.BuildConfig.VERSION_CODE.toLong())
            .putString("install-notes", """{"version":"0.4.5","en":["Fixed"],"ar":["تحسينات"]}""").commit()
        val updates = AppUpdates(production, scope)
        assertEquals(listOf("Fixed"), updates.state.value.whatsNew?.notes("fr"))
        assertEquals(listOf("تحسينات"), updates.state.value.whatsNew?.notes("ar"))
        updates.dismissWhatsNew()
        assertEquals(listOf("Fixed"), AppUpdates(production, scope).currentNotes()?.notes("fr"))
    }
    @Test fun currentNotesSurviveDismissClearAndRestartInBothLanguages() {
        pendingInstall(listOf("Fixed"), listOf("تحسينات"))
        val updates = AppUpdates(production, scope)
        assertEquals(listOf("تحسينات"), updates.currentNotes()?.notes("ar"))
        updates.dismissWhatsNew()
        assertTrue(updates.clearInstall())
        val restarted = AppUpdates(production, scope)
        assertNull(restarted.state.value.whatsNew)
        assertEquals(listOf("Fixed"), restarted.currentNotes()?.notes("en"))
        assertEquals(listOf("تحسينات"), restarted.currentNotes()?.notes("ar"))
    }
    @Test fun currentNotesFallBackToBundledNotesWithoutPromptingOnFreshInstall() {
        val updates = AppUpdates(production, scope) { error("Reading bundled notes must not use HTTP") }
        val bundled = BundledWhatsNew.read(context.assets)!!
        val notes = updates.currentNotes()!!
        assertEquals(bundled.version, notes.version)
        assertEquals(bundled.english, notes.english)
        assertEquals(bundled.arabic, notes.arabic)
        assertNull(updates.state.value.whatsNew)
        assertFalse(updates.consumeUpdated())
        assertFalse(updates.prefs.contains("current-notes"))
    }
    @Test fun currentCachedNotesWinOverBundledNotes() {
        val updates = AppUpdates(production, scope)
        updates.prefs.edit().putString("current-notes", org.json.JSONObject()
            .put("code", dev.ahmed.savlino.BuildConfig.VERSION_CODE)
            .put("version", "cached-release").put("en", org.json.JSONArray().put("Cached fix"))
            .put("ar", org.json.JSONArray().put("إصلاح محفوظ")).toString()).commit()
        val notes = AppUpdates(production, scope).currentNotes()!!
        assertEquals("cached-release", notes.version)
        assertEquals(listOf("Cached fix"), notes.english)
        assertEquals(listOf("إصلاح محفوظ"), notes.arabic)
        assertEquals(listOf("Cached fix"), notes.notes("fr"))
    }
    @Test fun currentNotesForAnotherBuildAreRemovedAndFallBackToBundledNotes() {
        val updates = AppUpdates(production, scope)
        updates.prefs.edit().putString("current-notes", org.json.JSONObject()
            .put("code", dev.ahmed.savlino.BuildConfig.VERSION_CODE - 1)
            .put("version", "0.4.4").put("en", org.json.JSONArray().put("Old"))
            .put("ar", org.json.JSONArray()).toString()).commit()
        assertEquals(BundledWhatsNew.read(context.assets)!!.version, AppUpdates(production, scope).currentNotes()?.version)
        assertFalse(updates.prefs.contains("current-notes"))
    }
    @Test fun noNotesUsesSnackbarOnceAndUnrelatedInstallsShowNothing() {
        val fresh = AppUpdates(production, scope)
        assertNull(fresh.state.value.whatsNew); assertFalse(fresh.consumeUpdated())
        pendingInstall(listOf("Future"), code = dev.ahmed.savlino.BuildConfig.VERSION_CODE + 1L)
        val unrelated = AppUpdates(production, scope)
        assertNull(unrelated.state.value.whatsNew); assertFalse(unrelated.consumeUpdated())
        pendingInstall(emptyList())
        val noNotes = AppUpdates(production, scope)
        assertNull(noNotes.state.value.whatsNew)
        assertTrue(noNotes.consumeUpdated())
        assertFalse(AppUpdates(production, scope).consumeUpdated())
    }

    @Test fun failedInstallClearsPendingWhatsNewAndSnackbar() {
        pendingInstall(listOf("Fixed"))
        val updates = AppUpdates(production, scope)
        updates.fail(UpdateFailure.INSTALL)
        assertNull(updates.state.value.whatsNew)
        val restored = AppUpdates(production, scope)
        assertNull(restored.state.value.whatsNew)
        assertFalse(restored.consumeUpdated())
    }
    @Test fun failedFutureInstallNeverBecomesCurrentNotes() {
        pendingInstall(listOf("Future"), code = dev.ahmed.savlino.BuildConfig.VERSION_CODE + 1L)
        val updates = AppUpdates(production, scope)
        updates.fail(UpdateFailure.INSTALL)
        val bundled = BundledWhatsNew.read(context.assets)!!
        assertEquals(bundled.english, updates.currentNotes()?.english)
        assertEquals(bundled.english, AppUpdates(production, scope).currentNotes()?.english)
        assertFalse(updates.prefs.contains("current-notes"))
    }
    @Test fun installedClearsSettingsBadge() {
        val updates = cachedRelease()
        updates.installed()
        assertFalse(updates.state.value.badge(1))
        assertNull(updates.state.value.release)
    }

    @Test fun lastCheckedDateAndTimeUseLatinDigitsInBothLanguages() {
        val epoch = java.time.Instant.parse("2024-10-20T13:05:00Z").toEpochMilli()
        for (language in listOf("en", "ar")) {
            val formatted = dev.ahmed.savlino.locale.NezzelFormat.shortDateTime(epoch, language)
            assertTrue("$language: $formatted", formatted.contains("2024"))
            assertTrue(Regex("[0-9]{1,2}:[0-9]{2}").containsMatchIn(formatted))
            assertFalse(formatted.any { it in '٠'..'٩' || it in '۰'..'۹' })
        }
    }

}

@Implements(PackageInstaller::class)
class ChangedUpdateInstaller : ShadowPackageInstaller() {
    @Implementation override fun createSession(params: PackageInstaller.SessionParams): Int {
        // The archive was verified, but its bytes change before the installer reads it.
        File(RuntimeEnvironment.getApplication().cacheDir, "updates/update.apk").writeText("tampered update")
        return super.createSession(params)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = IsolatedTestApplication::class)
class SilentUpdateIntegrationTest {
    private val app get() = RuntimeEnvironment.getApplication() as dev.ahmed.savlino.jobs.NezzelApplication
    private fun prepared(scope: CoroutineScope): AppUpdates {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val bytes = "verified update".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = File(app.cacheDir, "updates/update.apk").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val manifest = JSONObject().put("schema", 1).put("version", "0.5.0").put("versionCode", 99999)
            .put("apk", JSONObject().put("name", file.name).put("size", bytes.size).put("sha256", digest))
            .put("notes", JSONObject().put("en", JSONArray().put("Improved")))
        app.getSharedPreferences("app-updates", Context.MODE_PRIVATE).edit()
            .putString("release", manifest.toString()).putString("release-format", "manifest")
            .putLong("ready-code", 99999).putString("ready-file", file.name).commit()
        fun identity(code: Long) = PackageInfo().apply {
            packageName = app.packageName; longVersionCode = code
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature("01020304"))) }
        }
        shadowOf(app.packageManager).apply {
            installPackage(identity(BuildConfig.VERSION_CODE.toLong()))
            setPackageArchiveInfo(file.absolutePath, identity(99999))
            setCanRequestPackageInstalls(true)
        }
        return AppUpdates(app, scope) { error("A cached update must not need network") }
    }
    @Test fun coldWorkerInstallsVerifiedCachedApkWithoutAnotherDownload() = runBlocking {
        val updates = prepared(this)
        updates.notifyInBackground(canPrepare = false)
        assertEquals(UpdateStage.INSTALLING, updates.state.value.stage)
        val id = updates.prefs.getInt("install-session", -1)
        assertTrue(id >= 0)
        assertNotNull(app.packageManager.packageInstaller.getSessionInfo(id))
        assertTrue(updates.prefs.getBoolean("install-silent", false))
    }
    @Test fun foregroundDefersAndNextBackgroundRunUsesTheSameVerifiedFile() = runBlocking {
        val updates = prepared(this)
        updates.onAppOpen()
        updates.notifyInBackground(canPrepare = false)
        assertFalse(updates.prefs.contains("install-session"))
        assertEquals(UpdateStage.READY, updates.state.value.stage)
        updates.onAppClosed()
        updates.notifyInBackground(canPrepare = false)
        assertTrue(updates.prefs.contains("install-session"))
    }
    @Test fun modifiedCachedApkNeverCreatesASession() = runBlocking {
        val updates = prepared(this)
        File(app.cacheDir, "updates/update.apk").appendText("tampered")
        updates.notifyInBackground(canPrepare = false)
        assertFalse(updates.prefs.contains("install-session"))
        assertTrue(app.packageManager.packageInstaller.allSessions.isEmpty())
    }
    @Test fun coldProcessKeepsSilentSessionUntilAnExplicitManualRetry() = runBlocking {
        val first = prepared(this)
        first.notifyInBackground(canPrepare = false)
        val old = first.prefs.getInt("install-session", -1)
        assertTrue(old >= 0)
        val cold = AppUpdates(app, this) { error("No network") }
        cold.notifyInBackground(canPrepare = false)
        assertEquals(old, cold.prefs.getInt("install-session", -1))
        assertNotNull(app.packageManager.packageInstaller.getSessionInfo(old))
        assertTrue(UpdateInstaller.install(app, cold))
        val replacement = cold.prefs.getInt("install-session", -1)
        assertTrue(replacement >= 0)
        assertNotEquals(old, replacement)
        assertNull(app.packageManager.packageInstaller.getSessionInfo(old))
    }
    @Test @Config(application = GateFlipApplication::class)
    fun stoppedDuringFinalCommitGateKeepsTheVerifiedFileReadyForNextRun() = interruptedInstall(true)
    @Test @Config(application = GateFlipApplication::class)
    fun installationErrorKeepsTheVerifiedFileReadyForManualRetry() = interruptedInstall(false)
    private fun interruptedInstall(cancelled: Boolean) = runBlocking {
        val updates = prepared(this)
        var checks = 0
        (app as GateFlipApplication).whenDownloadsRead = {
            if (++checks == 3) {
                if (cancelled) throw CancellationException("Work stopped")
                else error("Install interrupted")
            }
        }
        try { updates.notifyInBackground(canPrepare = false) }
        catch (failure: CancellationException) { if (!cancelled) throw failure }
        assertEquals(3, checks)
        assertEquals(UpdateStage.READY, updates.state.value.stage)
        assertTrue(updates.readyFile!!.isFile)
        assertFalse(updates.prefs.contains("install-session"))
        assertTrue(app.packageManager.packageInstaller.allSessions.isEmpty())
    }
    @Test fun queuedPlaylistDefersInstallationBeforeAnyJobEntersTheProgressStream() = runBlocking {
        val updates = prepared(this)
        dev.ahmed.savlino.jobs.JobStore(app, links = dev.ahmed.savlino.jobs.batchCipher()).use { jobs ->
            val batches = dev.ahmed.savlino.jobs.BatchStore(jobs, dev.ahmed.savlino.jobs.batchCipher())
            assertTrue(batches.createLinks("pending", "Fixture", listOf(dev.ahmed.savlino.domain.LinkInput.parse("https://example.org/video")!!), dev.ahmed.savlino.domain.Preset.BEST))
            (app as IsolatedTestApplication).coordinatorOverride = dev.ahmed.savlino.jobs.DownloadCoordinator(app, store = jobs)
            try {
                assertTrue(app.downloads.progress.value.isEmpty())
                updates.notifyInBackground(canPrepare = false)
                assertEquals(UpdateStage.READY, updates.state.value.stage)
                assertFalse(updates.prefs.contains("install-session"))
                assertEquals(99999L, updates.prefs.getLong("notified", -1))
            } finally { (app as IsolatedTestApplication).coordinatorOverride = null }
        }
    }

    // Silent-install retry and notification lifecycle.

    private val retryApp get() = RuntimeEnvironment.getApplication() as IsolatedTestApplication
    private val manager get() = retryApp.getSystemService(NotificationManager::class.java)
    private val apk get() = File(retryApp.cacheDir, "updates/update.apk")
    private var downloads = 0
    private fun prepare(code: Long = 99999) {
        shadowOf(retryApp).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val bytes = "verified update".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        apk.apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val manifest = JSONObject().put("schema", 1).put("version", "0.5.0").put("versionCode", code)
            .put("apk", JSONObject().put("name", apk.name).put("size", bytes.size).put("sha256", digest))
            .put("notes", JSONObject().put("en", JSONArray().put("Improved")))
        retryApp.getSharedPreferences("app-updates", Context.MODE_PRIVATE).edit()
            .putString("release", manifest.toString()).putString("release-format", "manifest")
            .putLong("ready-code", code).putString("ready-file", apk.name).commit()
        fun identity(version: Long) = PackageInfo().apply {
            packageName = retryApp.packageName; longVersionCode = version
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature("01020304"))) }
        }
        shadowOf(retryApp.packageManager).apply {
            installPackage(identity(BuildConfig.VERSION_CODE.toLong()))
            setPackageArchiveInfo(apk.absolutePath, identity(code))
            setCanRequestPackageInstalls(true)
        }
    }
    private fun cold(scope: CoroutineScope) = AppUpdates(retryApp, scope) {
        downloads++
        error("A verified APK must be reused")
    }
    private fun status(updates: AppUpdates, result: Int, confirmation: Boolean = false) {
        UpdateInstallReceiver().onReceive(retryApp, Intent(UpdateInstaller.STATUS)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, updates.prefs.getInt("install-session", -1))
            .putExtra(PackageInstaller.EXTRA_STATUS, result).apply {
                if (confirmation) putExtra(Intent.EXTRA_INTENT, Intent("dev.ahmed.savlino.TEST_CONFIRM"))
            })
    }
    @Test fun aSilentTryDeferredBeforeCommitIsGivenBack() = runBlocking {
        prepare()
        val updates = cold(this)
        assertTrue(updates.reserveSilentAttempt(99999))
        assertTrue(updates.silentAttempted(99999))
        updates.releaseSilentAttempt(99999)
        assertFalse(updates.silentAttempted(99999))
        assertTrue(updates.reserveSilentAttempt(99999))
        coroutineContext.cancelChildren()
    }
    @Test fun rejectedSilentSessionKeepsApkAndNewerReleaseGetsItsOwnAttempt() = runBlocking {
        prepare()
        val first = retryApp.appUpdates
        first.notifyInBackground(false)
        status(first, PackageInstaller.STATUS_FAILURE_ABORTED)
        assertTrue("Install rejection must retain the verified APK", apk.isFile)
        val next = cold(this)
        next.notifyInBackground(true)
        assertEquals(0, downloads)
        assertTrue(retryApp.packageManager.packageInstaller.allSessions.isEmpty())
        assertEquals(99999L, next.prefs.getLong("notified", -1))
        prepare(100000)
        val newer = cold(this)
        newer.notifyInBackground(true)
        assertEquals(0, downloads)
        assertEquals(100000L, newer.prefs.getLong("install-code", -1))
        assertEquals(1, retryApp.packageManager.packageInstaller.allSessions.size)
    }
    @Test fun pendingPromptIsNotRecreatedOrRenotifiedByColdWorkers() = runBlocking {
        prepare()
        val first = retryApp.appUpdates
        first.notifyInBackground(false)
        val session = first.prefs.getInt("install-session", -1)
        status(first, PackageInstaller.STATUS_PENDING_USER_ACTION, true)
        assertEquals(99999L, first.prefs.getLong("notified", -1))
        assertEquals(1, manager.activeNotifications.size)
        manager.cancel(UpdateNotifications.ID) // User dismisses it.
        cold(this).notifyInBackground(true)
        cold(this).notifyInBackground(true)
        assertEquals(0, downloads)
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(listOf(session), retryApp.packageManager.packageInstaller.allSessions.map { it.sessionId })
    }
    @Test fun mutedPendingPromptConsumesTheAttemptWithoutDeletingApk() = runBlocking {
        prepare()
        val first = retryApp.appUpdates
        first.notifyInBackground(false)
        shadowOf(manager).setNotificationsEnabled(false)
        status(first, PackageInstaller.STATUS_PENDING_USER_ACTION, true)
        cold(this).notifyInBackground(true)
        cold(this).notifyInBackground(true)
        assertEquals(0, downloads)
        assertTrue(apk.isFile)
        assertTrue(retryApp.packageManager.packageInstaller.allSessions.isEmpty())
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun pendingPromptHonorsAnEarlierNotification() = runBlocking {
        prepare()
        val first = retryApp.appUpdates
        first.postNotification()
        assertEquals(99999L, first.prefs.getLong("notified", -1))
        manager.cancel(UpdateNotifications.ID)
        first.notifyInBackground(false)
        status(first, PackageInstaller.STATUS_PENDING_USER_ACTION, true)
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun pendingPromptHonorsTheThreeDayDismissalWindow() = runBlocking {
        prepare()
        val first = retryApp.appUpdates
        first.dismiss()
        first.notifyInBackground(false)
        status(first, PackageInstaller.STATUS_PENDING_USER_ACTION, true)
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun busyEngineMaintenanceFallsBackToReadyNotification() = runBlocking {
        prepare()
        val release = CompletableDeferred<Unit>()
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun updateYtdlp(): EngineUpdateResult {
                release.await()
                return EngineUpdateResult.Success("test", "test")
            }
        }
        JobStore(retryApp).use { store ->
            val coordinator = DownloadCoordinator(retryApp, engine, store)
            retryApp.coordinatorOverride = coordinator
            val updating = async(Dispatchers.IO) { coordinator.maintenance.updateNow() }
            try {
                withTimeout(5000) { coordinator.maintenance.state.first { it.status == EngineUpdateStatus.UPDATING } }
                val updates = cold(this)
                updates.notifyInBackground(false)
                assertFalse(updates.prefs.contains("install-session"))
                assertEquals(1, manager.activeNotifications.size)
                assertEquals(99999L, updates.prefs.getLong("notified", -1))
            } finally {
                release.complete(Unit); updating.cancelAndJoin(); retryApp.coordinatorOverride = null
            }
        }
    }
    @Test @Config(shadows = [ChangedUpdateInstaller::class])
    fun changedBytesAfterVerificationAreNeverCommitted() = runBlocking {
        prepare()
        val updates = cold(this)
        updates.notifyInBackground(false)
        assertEquals(UpdateFailure.VERIFY, updates.state.value.failure)
        assertTrue(retryApp.packageManager.packageInstaller.allSessions.isEmpty())
        assertFalse(updates.prefs.contains("install-session"))
    }
}
