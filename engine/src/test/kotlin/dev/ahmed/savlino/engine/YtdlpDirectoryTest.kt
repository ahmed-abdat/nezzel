package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class YtdlpDirectoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dir get() = File(temporary.root, "yt-dlp").apply { mkdirs() }
    private val executable get() = File(dir, "yt-dlp")
    private val archive get() = File(dir, "yt-dlp.zip")
    private val staging get() = File(dir, ".materialize-tmp")
    private fun zip(file: File, version: String = "bundled", entry: String = "__main__.py", prefixed: Boolean = true) {
        requireNotNull(file.parentFile).mkdirs()
        file.outputStream().use { output ->
            // Real yt-dlp is a self-executing zip with a shebang prefix.
            if (prefixed) output.write("#!/usr/bin/env python3\n".toByteArray())
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry(entry)); zip.write(version.toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("yt_dlp/")); zip.closeEntry()
                zip.putNextEntry(ZipEntry("yt_dlp/version.py")); zip.write(version.toByteArray()); zip.closeEntry()
                if (version == "bundled") {
                    zip.putNextEntry(ZipEntry("bundle-only")); zip.closeEntry()
                }
            }
        }
    }
    private inner class Native(val prefs: MutableMap<String, String> = mutableMapOf()) : YtdlpRuntime {
        override val initialization = EngineInitialization()
        var probes = 0
        var directoryAtProbe = false
        val warm = mutableMapOf<String, String>()
        var updateAction: (() -> Unit)? = null
        var clearFailure: Exception? = null
        override fun bundledYtdlpSize(): Long? {
            if (prefs["dlpVersion"] == null) return null
            val bundle = File(temporary.root, "bundled.zip")
            zip(bundle)
            return bundle.length()
        }
        override fun clearUpdatedYtdlpVersion() {
            clearFailure?.let { throw it }
            prefs.remove("dlpVersion"); prefs.remove("dlpVersionName")
        }
        override fun initialize() { if (!executable.exists()) zip(executable) }
        override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
            if ("--dump-json" in args) {
                check(if (executable.isDirectory) File(executable, "__main__.py").isFile
                    else ZipFile(executable).use { it.getEntry("__main__.py") != null })
                return """{"formats":[{"format_id":"18","ext":"mp4","vcodec":"h264","acodec":"aac"}]}"""
            }
            assertEquals(listOf("--ignore-config", "--version"), args)
            probes++
            directoryAtProbe = executable.isDirectory
            return if (executable.isDirectory) File(executable, "__main__.py").readText()
            else ZipFile(executable).use { it.getInputStream(it.getEntry("__main__.py")).reader().readText() }
        }
        override fun destroy(processId: String) = false
        override fun update() {
            updateAction?.let { it(); return }
            dir.deleteRecursively(); zip(executable, "updated")
            prefs["dlpVersion"] = "updated"; prefs["dlpVersionName"] = "stable"
        }
        override fun restoreBundledYtdlp() {
            dir.deleteRecursively(); prefs.remove("dlpVersion"); initialize()
        }
        private fun key() = prefs["dlpVersion"].orEmpty() + ytdlpInstallationStamp(dir)
        override fun warmedVersion() = warm[key()]
        override fun rememberWarmedVersion(version: String) { warm[key()] = version }
    }
    private fun runtime(native: Native = Native(), enabled: Boolean = true) =
        DirectoryYtdlpRuntime(native, dir, enabled)

    @Test fun freshRuntimeRestoresInterruptedBundledCopyBeforeVersionAndInspection() = runBlocking {
        val bundle = File(temporary.root, "bundle-fixture.zip"); zip(bundle)
        val bytes = bundle.readBytes()
        for (size in listOf(0, 12, bytes.size - 1)) {
            for (materialize in listOf(false, true)) {
                dir.deleteRecursively(); executable.writeBytes(bytes.copyOf(size))
                val native = Native() // Fresh process: no in-memory initialization failure to trigger reset.
                val engine = YoutubedlEngine(repairingRuntime(native, bytes.size.toLong(), materialize), cancellationPollMs = 1)
                assertEquals("bundled", engine.installedVersion(), "truncated at $size, materialize=$materialize")
                assertEquals(bytes.size.toLong(), (if (materialize) archive else executable).length())
                val source = LinkRevision(assertNotNull(LinkInput.parse("https://example.org/video")), 1)
                assertIs<InspectResult.Success>(engine.inspect(source, "restored-inspection"))
                assertTrue(native.prefs.isEmpty())
            }
        }
    }
    private fun repairingRuntime(native: Native, bundledSize: Long, enabled: Boolean = true) =
        DirectoryYtdlpRuntime(object : YtdlpRuntime by native {
            override fun bundledYtdlpSizeForRepair() = bundledSize.takeIf { native.prefs["dlpVersion"] == null }
        }, dir, enabled)

    @Test fun truncatedRecordedUpdateIsReplacedByTheBundledCopyAtTheNextStart() = runBlocking {
        val bundle = File(temporary.root, "bundle-fixture.zip"); zip(bundle)
        val update = File(temporary.root, "update-fixture.zip"); zip(update, "updated")
        for (materialize in listOf(false, true)) {
            dir.deleteRecursively(); executable.writeBytes(update.readBytes().copyOf(12)) // Killed mid-copy.
            val native = Native(mutableMapOf("dlpVersion" to "updated", "dlpVersionName" to "stable"))
            val runtime = DirectoryYtdlpRuntime(object : YtdlpRuntime by native {
                override fun bundledYtdlpSizeForRepair() =
                    YtdlpStagedInstall.expectedSize(native.prefs["dlpVersion"], update.length(), { bundle.length() })
            }, dir, materialize)
            assertEquals("bundled", YoutubedlEngine(runtime, cancellationPollMs = 1).installedVersion(), "materialize=$materialize")
            assertTrue(native.prefs.isEmpty())
        }
    }

    /** Like the real native: repair size from the recorded install or the bundle, and the recorded tag. */
    private fun upgradingRuntime(native: Native, bundledSize: Long, enabled: Boolean, recordedSize: Long? = null) =
        DirectoryYtdlpRuntime(object : YtdlpRuntime by native {
            private val installer = YtdlpStagedInstall(dir, "yt-dlp", {}, { _, _ -> true }, {})
            override fun bundledYtdlpSizeForRepair() =
                YtdlpStagedInstall.expectedSize(native.prefs["dlpVersion"], recordedSize, { bundledSize })
            override fun updatedYtdlpVersion() = native.prefs["dlpVersion"]
            override fun sweepStagedInstall() = installer.sweep()
            override fun initialize() { installer.sweep(); native.initialize() } // As the real native init does.
        }, dir, enabled)

    @Test fun killAfterTheNewBinaryMovedInNeverBringsBackTheRetiredOlderUpdate() = runBlocking {
        val bundle = File(temporary.root, "bundle-fixture.zip"); zip(bundle)
        val retired = File(temporary.root, "yt-dlp.retired")
        for (materialize in listOf(false, true)) {
            // Killed between the rename of the new binary and the preferences: they still name the retired update.
            dir.deleteRecursively(); retired.deleteRecursively()
            zip(File(retired, "yt-dlp"), "2026.10.21")
            val retiredSize = File(retired, "yt-dlp").length()
            zip(executable, "2026.11.02")
            val native = Native(mutableMapOf("dlpVersion" to "2026.10.21", "dlpVersionName" to "stable"))
            val engine = YoutubedlEngine(upgradingRuntime(native, bundle.length(), materialize, retiredSize), cancellationPollMs = 1)
            val label = "materialize=$materialize"
            // The unrecorded new binary cannot be trusted, so the bundled copy runs; the retired update is gone.
            assertEquals("bundled", engine.installedVersion(), label)
            assertFalse(retired.exists(), label)
            assertEquals(bundle.length(), (if (materialize) archive else executable).length(), label)
            assertTrue(native.prefs.isEmpty(), label)
        }
    }

    @Test fun olderAppsBundledCopyIsReplacedOfflineWhetherPlainOrMaterialized() = runBlocking {
        val bundle = File(temporary.root, "bundle-fixture.zip"); zip(bundle)
        val old = File(temporary.root, "old-bundle.zip"); zip(old, "2025.11.12")
        assertNotEquals(bundle.length(), old.length())
        for (materialize in listOf(false, true)) {
            dir.deleteRecursively(); zip(executable, "2025.11.12")
            // The previous app version's start: its own bundle, possibly already materialized.
            upgradingRuntime(Native(), old.length(), materialize).initialize()
            assertEquals(materialize, executable.isDirectory)
            val native = Native()
            assertEquals("bundled", YoutubedlEngine(upgradingRuntime(native, bundle.length(), materialize), cancellationPollMs = 1)
                .installedVersion(), "materialize=$materialize")
            // Fixture zips carry their creation second; the bundled copy is identified by size, as on the phone.
            assertEquals(bundle.length(), (if (materialize) archive else executable).length())
            assertTrue(native.prefs.isEmpty())
        }
    }

    @Test fun recordedUpdateOlderThanTheBundleIsDroppedButNewerOrUnknownOnesStay() = runBlocking {
        val bundle = File(temporary.root, "bundle-fixture.zip"); zip(bundle)
        for (materialize in listOf(false, true)) {
            for ((tag, kept) in listOf("2026.10.21" to false, "2026.10.21.1" to false, YtdlpRelease.BUNDLED_TAG to true,
                "2099.01.01" to true, "updated" to true)) {
                dir.deleteRecursively(); zip(executable, tag)
                val update = executable.readBytes()
                val native = Native(mutableMapOf("dlpVersion" to tag, "dlpVersionName" to "stable"))
                // The archive differs from the bundle: the bundle-size check must never touch a recorded update.
                val engine = YoutubedlEngine(upgradingRuntime(native, bundle.length(), materialize, update.size.toLong()), cancellationPollMs = 1)
                val label = "$tag materialize=$materialize"
                assertEquals(if (kept) tag else "bundled", engine.installedVersion(), label)
                val installed = if (materialize) archive else executable
                if (kept) assertContentEquals(update, installed.readBytes(), label)
                else assertEquals(bundle.length(), installed.length(), label)
                assertEquals(if (kept) tag else null, native.prefs["dlpVersion"], label)
                assertEquals(if (kept) "stable" else null, native.prefs["dlpVersionName"], label)
            }
        }
    }

    @Test fun bundledRawResourceIsTheTaggedRelease() {
        val raw = File("src/main/res/raw/ytdlp")
        val version = ZipFile(raw).use { zip -> zip.getInputStream(zip.getEntry("yt_dlp/version.py")).reader().readText() }
        assertTrue("__version__ = '${YtdlpRelease.BUNDLED_TAG}'" in version)
        assertTrue("CHANNEL = 'stable'" in version)
    }

    @Test fun bundledRepairPreservesRecordedUpdatesAndValidPlainOrMaterializedBundles() {
        val bundle = File(temporary.root, "bundle-fixture.zip"); zip(bundle)
        val native = Native()
        zip(executable)
        val bytes = executable.readBytes()
        val stamp = ytdlpInstallationStamp(dir)
        repairingRuntime(native, bundle.length(), enabled = false).initialize()
        assertContentEquals(bytes, executable.readBytes())
        assertEquals(stamp, ytdlpInstallationStamp(dir))
        repairingRuntime(native, bundle.length()).initialize()
        val directoryStamp = ytdlpInstallationStamp(dir)
        File(executable, "__pycache__").mkdir()
        repairingRuntime(Native(), bundle.length()).initialize()
        assertEquals(directoryStamp, ytdlpInstallationStamp(dir))

        dir.deleteRecursively(); zip(executable, "updated")
        val updated = executable.readBytes()
        val prefs = mutableMapOf("dlpVersion" to "updated", "dlpVersionName" to "stable")
        repairingRuntime(Native(prefs), bundle.length(), enabled = false).initialize()
        assertContentEquals(updated, executable.readBytes())
        assertEquals(mapOf("dlpVersion" to "updated", "dlpVersionName" to "stable"), prefs)
    }

    @Test fun failedUpdateAfterDeleteClearsBothPreferencesAndReportsBundled() = runBlocking {
        for (recoverInUpdater in listOf(false, true)) {
            val native = Native(); native.update()
            val runtime = runtime(native); runtime.initialize()
            val failure = IllegalStateException("update interrupted")
            native.updateAction = {
                dir.deleteRecursively()
                if (recoverInUpdater) native.initialize()
                throw failure
            }
            assertSame(failure, assertFailsWith<IllegalStateException> { runtime.update() })
            assertNull(native.prefs["dlpVersion"])
            assertNull(native.prefs["dlpVersionName"])
            runtime.initialize()
            assertEquals("bundled", YoutubedlEngine(runtime, cancellationPollMs = 1).installedVersion())
        }
    }
    @Test fun initClearsStalePreferencesForBundledZipAndMaterializedBundle() {
        val native = Native(); val runtime = runtime(native)
        native.prefs.putAll(mapOf("dlpVersion" to "old-update", "dlpVersionName" to "stable"))
        runtime.initialize() // Upstream recovers a missing binary from the bundle.
        assertTrue(native.prefs.isEmpty())
        native.prefs.putAll(mapOf("dlpVersion" to "old-update", "dlpVersionName" to "stable"))
        runtime.initialize() // Already materialized bundled copy left by an earlier process.
        assertTrue(native.prefs.isEmpty())
        assertEquals(0, native.probes)
    }
    @Test fun successfulUpdateAndRestartKeepBothPreferences() {
        val native = Native(); val runtime = runtime(native)
        runtime.initialize(); runtime.update()
        assertEquals(mapOf("dlpVersion" to "updated", "dlpVersionName" to "stable"), native.prefs)
        runtime(native).initialize()
        assertEquals(mapOf("dlpVersion" to "updated", "dlpVersionName" to "stable"), native.prefs)
        assertEquals("updated", File(executable, "__main__.py").readText())
        assertEquals(0, native.probes)
    }
    @Test fun failedUpdateBeforeDeleteKeepsInstalledUpdateAndPreferences() {
        val native = Native(); native.update()
        val runtime = runtime(native); runtime.initialize()
        val failure = IllegalStateException("network failure")
        native.updateAction = { throw failure }
        assertSame(failure, assertFailsWith<IllegalStateException> { runtime.update() })
        assertEquals(mapOf("dlpVersion" to "updated", "dlpVersionName" to "stable"), native.prefs)
        assertEquals("updated", File(executable, "__main__.py").readText())
    }
    @Test fun preferenceRepairFailureDoesNotReplaceOriginalUpdateFailure() {
        val native = Native(); native.update()
        val runtime = runtime(native); runtime.initialize()
        val failure = IllegalStateException("update interrupted")
        native.updateAction = { dir.deleteRecursively(); throw failure }
        native.clearFailure = IllegalArgumentException("preferences unavailable")
        assertSame(failure, assertFailsWith<IllegalStateException> { runtime.update() })
    }

    @Test fun swapsZipForDirectoryWithoutChangingArchiveOrVersionPreferences() {
        zip(executable, "2026.08.19")
        val original = executable.readBytes()
        val prefs = mutableMapOf("dlpVersion" to "2026.08.19", "dlpVersionName" to "stable")
        runtime(Native(prefs)).initialize()
        assertEquals("2026.08.19", File(executable, "__main__.py").readText())
        assertContentEquals(original, archive.readBytes())
        assertEquals(mapOf("dlpVersion" to "2026.08.19", "dlpVersionName" to "stable"), prefs)
        assertFalse(staging.exists())
    }
    @Test fun nonPrefixedZipMaterializesWithDirectoryEntries() {
        zip(executable, prefixed = false)
        runtime().initialize()
        assertEquals("bundled", File(executable, "__main__.py").readText())
        assertEquals("bundled", File(executable, "yt_dlp/version.py").readText())
        assertTrue(archive.isFile)
    }
    @Test fun freshInstallMaterializesBeforeFirstWarmProbe() = runBlocking {
        val native = Native()
        assertFalse(executable.exists())
        val engine = YoutubedlEngine(runtime(native), cancellationPollMs = 1)
        assertTrue(engine.warmUp())
        assertTrue(native.directoryAtProbe)
        assertEquals(1, native.probes)
        assertTrue(archive.isFile)
        assertTrue(engine.warmUp())
        assertEquals(1, native.probes)
    }
    @Test fun corruptEntryFallsBackToOriginalZipAndDisablesMaterialize() {
        zip(executable)
        val bytes = executable.readBytes()
        // Corrupt the first data descriptor's CRC, leaving the central directory readable.
        val descriptor = bytes.indices.first { i -> i + 4 < bytes.size &&
            bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
            bytes[i + 2] == 0x07.toByte() && bytes[i + 3] == 0x08.toByte() }
        bytes[descriptor + 4] = (bytes[descriptor + 4].toInt() xor 1).toByte()
        executable.writeBytes(bytes)
        val runtime = runtime()
        runtime.initialize()
        assertTrue(executable.isFile)
        assertContentEquals(bytes, executable.readBytes())
        assertFalse(archive.exists()); assertFalse(staging.exists())
        runtime.update()
        assertTrue(executable.isFile)
    }
    @Test fun debugLogsMaterializeBeforeFirstExecutionAndModeOnceAcrossRuntimes() = runBlocking {
        val logs = mutableListOf<Pair<String, String>>()
        var now = 0L
        val timing = YtdlpPreparationTiming(true, { tag, line -> logs += tag to line },
            { now.also { now += 3_000_000 } })
        val runtime = DirectoryYtdlpRuntime(Native(), dir, true, timing)
        assertTrue(YoutubedlEngine(runtime, cancellationPollMs = 1).warmUp())
        val second = DirectoryYtdlpRuntime(Native(), dir, true, timing)
        second.execute(listOf("--ignore-config", "--version"), "second", {})
        assertEquals(listOf("NezzelTiming" to "ytdlp_materialize ok 3ms",
            "NezzelTiming" to "ytdlp_mode dir"), logs)
    }
    @Test fun corruptArchiveLogsOnlyExceptionClassAndKeepsOriginalFile() {
        val logs = mutableListOf<Pair<String, String>>()
        val timing = YtdlpPreparationTiming(true, { tag, line -> logs += tag to line })
        executable.writeText("#!/usr/bin/env python3\nprivate-corrupt-data")
        val bytes = executable.readBytes()
        val runtime = DirectoryYtdlpRuntime(Native(), dir, true, timing)
        runtime.initialize()
        assertContentEquals(bytes, executable.readBytes())
        assertFalse(staging.exists()); assertFalse(archive.exists())
        assertEquals(listOf("NezzelTiming" to "ytdlp_materialize failed ZipException"), logs)
        runtime.update() // Disabled after failure; replacement remains executable as a zip.
        runtime.execute(listOf("--ignore-config", "--version"), "zip", {})
        assertEquals("NezzelTiming" to "ytdlp_mode zip", logs.last())
        assertEquals(2, logs.size)
    }
    @Test fun recoveryFailureIsLoggedBeforeMaterializeIsDisabled() {
        val logs = mutableListOf<String>()
        zip(archive)
        val timing = YtdlpPreparationTiming(true, { _, line -> logs += line })
        val runtime = DirectoryYtdlpRuntime(Native(), dir, true, timing) { _, _ -> false }
        runtime.initialize()
        assertTrue(executable.isFile)
        assertEquals(listOf("ytdlp_materialize failed IllegalStateException"), logs)
    }
    @Test fun releaseTimingEmitsNothingForSuccessFailureOrExecution() {
        val logs = mutableListOf<String>()
        val timing = YtdlpPreparationTiming(false, { _, line -> logs += line })
        val runtime = DirectoryYtdlpRuntime(Native(), dir, true, timing)
        runtime.initialize()
        runtime.execute(listOf("--ignore-config", "--version"), "release", {})
        executable.deleteRecursively(); executable.writeText("corrupt")
        runtime.initialize()
        assertTrue(executable.isFile)
        assertTrue(logs.isEmpty())
    }
    @Test fun repeatedInitPreservesBytecodeAndWarmStamp() {
        val runtime = runtime(); runtime.initialize()
        val stamp = ytdlpInstallationStamp(dir)
        val bytecode = File(executable, "yt_dlp/__pycache__/version.pyc")
        requireNotNull(bytecode.parentFile).mkdirs(); bytecode.writeText("compiled")
        runtime.initialize()
        assertEquals("compiled", bytecode.readText())
        assertEquals(stamp, ytdlpInstallationStamp(dir))
    }
    @Test fun incompleteStagingIsDeletedAndRebuilt() {
        zip(executable)
        staging.mkdirs(); File(staging, "partial.py").writeText("partial")
        runtime().initialize()
        assertTrue(File(executable, "__main__.py").isFile)
        assertFalse(File(executable, "partial.py").exists())
        assertFalse(staging.exists())
    }
    @Test fun crashBetweenRenamesRecoversUpdatedArchiveBeforeNativeInit() {
        zip(archive, "updated")
        staging.mkdirs(); File(staging, "__main__.py").writeText("partial")
        runtime().initialize()
        assertEquals("updated", File(executable, "__main__.py").readText())
        assertFalse(staging.exists())
    }
    @Test fun corruptDirectoryIsRebuiltFromArchiveAndRewarmsSameVersion() = runBlocking {
        val native = Native(); val runtime = runtime(native)
        assertTrue(YoutubedlEngine(runtime, cancellationPollMs = 1).warmUp())
        val oldStamp = ytdlpInstallationStamp(dir)
        File(executable, "__main__.py").delete()
        runtime.initialize()
        assertNotEquals(oldStamp, ytdlpInstallationStamp(dir))
        assertNull(runtime.warmedVersion())
        assertEquals("bundled", File(executable, "__main__.py").readText())
    }
    @Test fun updateAndRestoreMaterializeBeforeTheirVersionSelfTests() = runBlocking {
        val native = Native(); val runtime = runtime(native); val engine = YoutubedlEngine(runtime, cancellationPollMs = 1)
        assertTrue(engine.warmUp()); assertTrue(native.directoryAtProbe)
        assertIs<EngineUpdateResult.Success>(engine.updateYtdlp())
        assertTrue(native.directoryAtProbe)
        assertEquals("updated", File(executable, "__main__.py").readText())
        assertEquals("updated", native.prefs["dlpVersion"])
        runtime.restoreBundledYtdlp()
        assertEquals("bundled", File(executable, "__main__.py").readText())
        assertNull(native.prefs["dlpVersion"])
    }
    @Test fun warmProbeRunsAfterMaterializeAndCacheWritesDoNotInvalidateIt() = runBlocking {
        val native = Native(); val runtime = runtime(native)
        native.initialize(); native.rememberWarmedVersion("bundled") // Old zipapp warm marker.
        assertTrue(YoutubedlEngine(runtime, cancellationPollMs = 1).warmUp())
        assertTrue(native.directoryAtProbe); assertEquals(1, native.probes)
        File(executable, "__pycache__").mkdirs()
        assertEquals("bundled", runtime.warmedVersion())
        runtime.initialize()
        assertEquals("bundled", runtime.warmedVersion())
    }
    @Test fun disabledLeavesZipAndCrashLeftoversUntouched() {
        zip(executable); val bytes = executable.readBytes()
        staging.mkdirs(); File(staging, "partial").writeText("partial")
        runtime(enabled = false).initialize()
        assertContentEquals(bytes, executable.readBytes())
        assertFalse(archive.exists()); assertTrue(File(staging, "partial").exists())
    }
    @Test fun invalidArchiveFallsBackAndDisablesMaterializeForTheRun() {
        zip(executable, entry = "missing-main.py"); val bytes = executable.readBytes()
        val runtime = runtime(); runtime.initialize()
        assertContentEquals(bytes, executable.readBytes()); assertFalse(staging.exists())
        runtime.update() // A valid replacement must still run as a zip for this run.
        assertTrue(executable.isFile)
        ZipFile(executable).use { assertNotNull(it.getEntry("__main__.py")) }
    }
    @Test fun failedBackupSwapPreservesExecutableZip() {
        zip(executable); val bytes = executable.readBytes()
        archive.mkdirs(); File(archive, "obstruction").writeText("keep")
        runtime().initialize()
        assertContentEquals(bytes, executable.readBytes()); assertFalse(staging.exists())
    }
    @Test fun failedDirectoryRenameRestoresOriginalZipAndDisablesExperiment() {
        zip(executable); val bytes = executable.readBytes()
        val runtime = DirectoryYtdlpRuntime(Native(), dir, true) { source, target ->
            if (source.name == ".materialize-tmp") false else source.renameTo(target)
        }
        runtime.initialize()
        assertContentEquals(bytes, executable.readBytes())
        assertFalse(archive.exists()); assertFalse(staging.exists())
        runtime.update()
        assertTrue(executable.isFile)
    }
    @Test fun disabledLeavesExistingMaterializedDirectoryUntouched() {
        runtime().initialize()
        val stamp = ytdlpInstallationStamp(dir)
        val bytes = archive.readBytes()
        runtime(enabled = false).initialize()
        assertEquals(stamp, ytdlpInstallationStamp(dir))
        assertContentEquals(bytes, archive.readBytes())
        assertTrue(File(executable, "__main__.py").isFile)
    }
    @Test fun corruptDirectoryWithoutBackupAllowsBundledInit() {
        executable.mkdirs(); File(executable, "partial.py").writeText("partial")
        runtime().initialize()
        assertEquals("bundled", File(executable, "__main__.py").readText())
        assertFalse(File(executable, "partial.py").exists())
    }
    @Test fun archiveCannotWriteOutsideStaging() {
        zip(executable, entry = "../escaped.py"); val bytes = executable.readBytes()
        runtime().initialize()
        assertContentEquals(bytes, executable.readBytes())
        assertFalse(File(dir, "escaped.py").exists()); assertFalse(staging.exists())
    }
    @Test fun stagingSymlinkDoesNotWriteOrDeleteOutsideDirectory() {
        zip(executable)
        val outside = temporary.newFolder("outside")
        File(outside, "keep").writeText("keep")
        Files.createSymbolicLink(staging.toPath(), outside.toPath())
        runtime().initialize()
        assertEquals("keep", File(outside, "keep").readText())
        assertFalse(File(outside, "__main__.py").exists())
    }
}
