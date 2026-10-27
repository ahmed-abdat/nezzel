package dev.ahmed.savlino.jobs

import android.content.Context
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DownloadCoordinatorRecipeTest {
    @Test fun legacyYtdlpRecipeAdoptsItsPartialWithoutRestarting() = checkResume("legacy")
    @Test fun nativeFallbackMarkerAdoptsYtdlpBytesOnNextAttempt() = checkResume("fallback")
    @Test fun differentArgvRecipeDiscardsOldBytes() = checkResume("changed")
    // S8: an app update alone keeps the bytes; another yt-dlp or argv still discards them.
    @Test fun olderVersionCodeWithTheSameEngineAndArgvAdoptsItsPartial() = checkResume("update")
    @Test fun olderVersionCodeWithAnotherEngineDiscardsOldBytes() = checkResume("update-engine")
    @Test fun olderVersionCodeWithAnotherArgvDiscardsOldBytes() = checkResume("update-argv")
    @Test fun nativeFallbackFromAnOlderVersionCodeAdoptsYtdlpBytes() = checkResume("update-fallback")
    // F5: a current recipe whose engine version is all digits is not mistaken for the old "<versionCode>:" form.
    @Test fun allDigitEngineVersionKeepsItsCurrentRecipe() = checkResume("digits")
    // F2: a finished native file (publish removed its sidecar, so the rerun sees ytdlp) is saved, not downloaded again.
    @Test fun finishedNativeFileWithoutItsSidecarIsAdopted() = checkResume("native-finished")
    @Test fun controlRecordNeverCountsAsPartialBytes() {
        val directory = File(RuntimeEnvironment.getApplication().filesDir, "empty-partial").canonicalFile.apply { mkdirs() }
        try {
            directory.resolve(DirectResume.PART).writeBytes(byteArrayOf())
            DirectResume.write(directory, DirectResume.Record("ETag", "\"a\"", 6))
            assertEquals(0L, stagingBytes(directory))
            assertFalse(stagingBytes(directory) > 0)
        } finally { directory.deleteRecursively() }
    }

    private fun checkResume(mode: String) = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder()
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, params: WorkerParameters) =
                    object : Worker(context, params) { override fun doWork() = Result.success() }
            }).build())
        val store = JobStore(app)
        val id = "recipe-$mode"
        val command = command(id)
        val engineVersion = if (mode == "digits") "20251112" else "engine-42"
        val base = "$engineVersion:${YtdlpCommand.ARGV_VERSION}"
        val older = dev.ahmed.savlino.BuildConfig.VERSION_CODE - 1
        val previous = when (mode) {
            "legacy" -> "0:engine-42:${YtdlpCommand.ARGV_VERSION}"
            "fallback" -> "$base:engine=native"
            "update" -> "$older:$base:engine=ytdlp"
            "update-engine" -> "$older:engine-41:${YtdlpCommand.ARGV_VERSION}:engine=ytdlp"
            "update-argv" -> "$older:engine-42:${YtdlpCommand.ARGV_VERSION - 1}:engine=ytdlp"
            "update-fallback" -> "$older:$base:engine=native"
            "digits" -> "$base:engine=ytdlp"
            "native-finished" -> "$base:engine=native"
            else -> "old:recipe"
        }
        val dir = File(app.filesDir, "downloads/$id").apply { mkdirs() }
        val kept = if (mode == "native-finished") YtdlpCommand.finishedSource(command.format)!! else DirectResume.PART
        dir.resolve(kept).writeText("kept")
        if (mode.endsWith("fallback")) DirectResume.write(dir, DirectResume.Record(null, null, null))
        assertTrue(store.enqueue(command)); assertTrue(store.startTransfer(id, previous)); assertTrue(store.requeue(id, true))
        var actualPartial: String? = null
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun installedVersion() = engineVersion
            override fun resumeEngine(command: DownloadCommand, directory: File) = "ytdlp"
            override suspend fun download(command: DownloadCommand, directory: File, progress: (EngineEvent) -> Unit): TransferResult {
                actualPartial = directory.resolve(kept).takeIf { it.isFile }?.readText()
                return TransferResult.Failure(DownloadError.Failed, resumeAllowed = false)
            }
        }
        val coordinator = DownloadCoordinator(app, engine, store,
            space = dev.ahmed.savlino.storage.SpaceBudget(app.filesDir, app.cacheDir,
                dev.ahmed.savlino.storage.FreeSpace { Long.MAX_VALUE }))
        try {
            coordinator.execute(id, {}, {})
            assertEquals(if (mode in setOf("changed", "update-engine", "update-argv")) null else "kept", actualPartial)
            assertEquals("$base:engine=ytdlp", store.get(id)?.resumeRecipe)
        } finally { store.close(); dir.deleteRecursively() }
    }
}
