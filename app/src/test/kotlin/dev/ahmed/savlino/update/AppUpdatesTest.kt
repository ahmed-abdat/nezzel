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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class AppUpdatesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val production get() = object : ContextWrapper(context) { override fun getPackageName() = "dev.ahmed.savlino" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @Before fun setup() {
        context.getSharedPreferences("app-updates", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nezzel", Context.MODE_PRIVATE).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }
    @After fun teardown() { scope.cancel(); WorkManagerTestInitHelper.closeWorkDatabase() }
    @Test fun switchPersistsAndCancelsPeriodicWork() {
        val updates = AppUpdates(production, scope)
        updates.setAutomatic(true)
        assertTrue(AppPreferences(context).appUpdates)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(AppUpdates.WORK).get().any { !it.state.isFinished })
        updates.setAutomatic(false)
        assertFalse(AppPreferences(context).appUpdates)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(AppUpdates.WORK).get().all { it.state.isFinished })
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

    private fun manifestBytes(): ByteArray = org.json.JSONObject().put("schema", 1).put("version", "0.4.4").put("versionCode", 99999)
        .put("apk", org.json.JSONObject().put("name", "update.apk").put("size", 100).put("sha256", "a".repeat(64)))
        .put("notes", org.json.JSONObject().put("en", org.json.JSONArray().put("Fixed"))).toString().toByteArray()

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

}
