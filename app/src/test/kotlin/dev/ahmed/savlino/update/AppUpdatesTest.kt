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
        context.getSharedPreferences("nezzel-portable", Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.cacheDir, "updates").deleteRecursively()
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
    @Test fun schedulingKeepsExistingWorkAndItsJitter() {
        val updates = AppUpdates(production, scope)
        updates.setAutomatic(true)
        val work = WorkManager.getInstance(context)
        val first = work.getWorkInfosForUniqueWork(AppUpdates.WORK).get().single { !it.state.isFinished }
        assertTrue(first.initialDelayMillis in 86_400_000L..108_000_000L)
        updates.setAutomatic(true)
        val kept = work.getWorkInfosForUniqueWork(AppUpdates.WORK).get().single { !it.state.isFinished }
        assertEquals(first.id, kept.id)
        assertEquals(first.initialDelayMillis, kept.initialDelayMillis)
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
