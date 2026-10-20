package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/** U-TIMEOUT / U-INIT: bounded engine waits, shared executions, init retry and update rollback (offline fakes). */
class EngineTimeoutRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = LinkRevision(assertNotNull(LinkInput.parse("https://example.org/video")), 1)
    private val row = Format("18", "mp4", true, true, size = Size.Exact(3))
    private val metadata = """{"formats":[{"format_id":"18","ext":"mp4","vcodec":"h264","acodec":"aac"}]}"""
    private fun command(id: String = "job-1") = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm(
        id, Inspection(source, "2025.11.12", listOf(row)), source, row.id, "title")).command
    private val fast = EngineLimits(inspectMs = 300, updateMs = 300, stallMs = 300)

    /** A process that runs until the canceller destroys it, like a stuck extractor or a dead socket. */
    private class Runtime(override val initialization: EngineInitialization = EngineInitialization()) : YtdlpRuntime {
        @Volatile var version = "2025.11.12"
        @Volatile var bundled = "2025.11.12"
        @Volatile var versionWorks = true
        @Volatile var versionHangs = false
        val destroyed = CountDownLatch(1)
        val destroys = AtomicInteger()
        val restores = AtomicInteger()
        @Volatile var action: (List<String>, (String) -> Unit) -> String = { _, _ -> "" }
        @Volatile var onUpdate: () -> Unit = { version = "2026.08.19" }
        var onInitialize: () -> Unit = {}
        override fun initialize() = onInitialize()
        override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
            if (args.contains("--version")) {
                if (versionHangs) hang()
                check(versionWorks) { "broken yt-dlp" }; return version
            }
            return action(args, line)
        }
        override fun destroy(processId: String): Boolean { destroys.incrementAndGet(); destroyed.countDown(); return true }
        override fun update() = onUpdate()
        override fun restoreBundledYtdlp() { restores.incrementAndGet(); version = bundled; versionWorks = true; versionHangs = false }
        fun hang(): String { check(destroyed.await(5, TimeUnit.SECONDS)); error("process destroyed") }
    }
    private fun download(dir: File) = { _: List<String>, _: (String) -> Unit ->
        File(dir, "output.mp4").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "result-path.txt").writeText(File(dir, "output.mp4").path); ""
    }

    @Test fun coldInstalledVersionDestroysHungProbeAndAllowsSubsequentInspection() = runBlocking {
        val runtime = Runtime().apply { versionHangs = true; action = { _, _ -> metadata } }
        val engine = YoutubedlEngine(runtime, EngineLimits(selfTestMs = 100))
        try { assertNull(withTimeout(2000) { engine.installedVersion() }) }
        finally { runtime.destroyed.countDown() }
        assertTrue(runtime.destroys.get() >= 1, "cold --version tree destroyed")
        assertNull(runtime.initialization.installedVersion, "failed probe is not cached")
        runtime.versionHangs = false
        assertIs<InspectResult.Success>(withTimeout(2000) { engine.inspect(source, "after-version-timeout") })
        // A writer needs every permit; this also detects a leaked shared version permit.
        assertIs<EngineUpdateResult.Success>(withTimeout(2000) { engine.updateYtdlp() })
        Unit
    }

    @Test fun preUpdateVersionDestroysHungProbeAndReleasesExclusiveGate() = runBlocking {
        val updates = AtomicInteger()
        val runtime = Runtime().apply {
            versionHangs = true; action = { _, _ -> metadata }
            onUpdate = { updates.incrementAndGet() }
        }
        val engine = YoutubedlEngine(runtime, EngineLimits(selfTestMs = 100, updateMs = 4000))
        try {
            assertEquals(EngineUpdateResult.Failure(DownloadError.Network), withTimeout(2000) { engine.updateYtdlp() })
        } finally { runtime.destroyed.countDown() } // Also unwind the engine-owned updater on a red run.
        assertTrue(runtime.destroys.get() >= 1, "pre-update --version tree destroyed")
        assertEquals(0, updates.get(), "no update after failed discovery")
        runtime.versionHangs = false
        assertIs<InspectResult.Success>(withTimeout(2000) { engine.inspect(source, "after-update-version-timeout") })
        assertIs<EngineUpdateResult.Success>(withTimeout(2000) { engine.updateYtdlp() })
        Unit
    }

    @Test fun coldMediaVersionProbesUseSelfTestDeadlineAndReleaseAdmission() = runBlocking {
        for (inspection in listOf(true, false)) {
            val runtime = Runtime().apply { versionHangs = true; action = { _, _ -> metadata } }
            val engine = YoutubedlEngine(runtime, EngineLimits(selfTestMs = 100, inspectMs = 5000))
            try {
                if (inspection) assertEquals(InspectResult.Failure(DownloadError.Network), withTimeout(2000) {
                    engine.inspect(source, "cold-inspect")
                }) else assertEquals(TransferResult.Failure(DownloadError.Network), withTimeout(2000) {
                    engine.download(command(), temp.newFolder()) {}
                })
            } finally { runtime.destroyed.countDown() }
            assertTrue(runtime.destroys.get() >= 1)
            runtime.versionHangs = false
            assertIs<InspectResult.Success>(withTimeout(2000) { engine.inspect(source, "after-cold-media") })
        }
    }

    @Test fun successfulColdVersionDoesNotLimitTheFollowingInspectionToSelfTestBudget() = runBlocking {
        val runtime = Runtime().apply { action = { _, _ -> Thread.sleep(300); metadata } }
        val engine = YoutubedlEngine(runtime, EngineLimits(selfTestMs = 100, inspectMs = 2000))
        assertIs<InspectResult.Success>(withTimeout(3000) { engine.inspect(source, "metadata-budget") })
        assertEquals(0, runtime.destroys.get())
    }

    @Test fun inspectAndDownloadCarryBoundedNetworkRetries() {
        val flags = listOf("--socket-timeout", "20", "--retries", "5", "--fragment-retries", "10")
        val inspect = YtdlpCommand.inspect(source)
        val transfer = YtdlpCommand.download(command(), temp.newFolder())
        for (args in listOf(inspect, transfer)) {
            assertEquals(flags, args.subList(args.indexOf("--socket-timeout"), args.indexOf("--socket-timeout") + 6))
            assertTrue(args.indexOf("--socket-timeout") < args.indexOf("--"))
            listOf("--socket-timeout", "--retries", "--fragment-retries").forEach { flag -> assertEquals(1, args.count { it == flag }) }
        }
    }

    @Test fun hangingInspectTimesOutAsRetryableNetworkAndDestroysTheProcess() = runBlocking {
        val runtime = Runtime()
        runtime.action = { _, _ -> runtime.hang() }
        val started = System.nanoTime()
        val result = withTimeout(5000) { YoutubedlEngine(runtime, fast).inspect(source, "inspect") }
        assertEquals(InspectResult.Failure(DownloadError.Network), result)
        assertTrue(runtime.destroys.get() >= 1, "process tree destroyed via the canceller")
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 300)
    }

    @Test fun inspectIsNotBlockedByAnActiveDownload() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val dir = temp.newFolder()
        val runtime = Runtime()
        runtime.action = { args, line ->
            if (args.contains("--dump-json")) metadata
            else { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); download(dir)(args, line) }
        }
        val engine = YoutubedlEngine(runtime)
        val transfer = async(Dispatchers.Default) { engine.download(command(), dir) {} }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            assertIs<InspectResult.Success>(withTimeout(2000) { engine.inspect(source, "inspect") })
        } finally { release.countDown() }
        assertIs<TransferResult.Success>(withTimeout(3000) { transfer.await() })
        Unit
    }

    @Test fun inspectionWaitsBeyondReadBudgetThenUsesCompletedUpdate() = runBlocking {
        val updating = CountDownLatch(1); val release = CountDownLatch(1)
        val runtime = Runtime().apply { action = { _, _ -> metadata } }
        runtime.onUpdate = { updating.countDown(); check(release.await(5, TimeUnit.SECONDS)); runtime.version = "2026.08.19" }
        val engine = YoutubedlEngine(runtime, EngineLimits(inspectMs = 300, updateMs = 5000))
        val update = async(Dispatchers.Default) { engine.updateYtdlp() }
        var inspection: Deferred<InspectResult>? = null
        try {
            assertTrue(withContext(Dispatchers.IO) { updating.await(3, TimeUnit.SECONDS) })
            inspection = async(Dispatchers.Default) { engine.inspect(source, "inspect") }
            // Replacement wait is separate from the 300 ms metadata/network budget.
            assertNull(withTimeoutOrNull(700) { inspection.await() })
        } finally { release.countDown() }
        assertEquals("2026.08.19", assertIs<InspectResult.Success>(withTimeout(3000) { inspection.await() }).inspection.engineVersion)
        assertEquals(EngineUpdateResult.Success("2025.11.12", "2026.08.19"), withTimeout(3000) { update.await() })
        assertIs<InspectResult.Success>(engine.inspect(source, "inspect-after"))
        Unit
    }

    @Test fun inspectionAfterFailedMaintenanceUsesExistingEngine() = runBlocking {
        val updating = CountDownLatch(1); val release = CountDownLatch(1)
        val runtime = Runtime().apply { action = { _, _ -> metadata } }
        runtime.onUpdate = { updating.countDown(); check(release.await(5, TimeUnit.SECONDS)); error("update failed") }
        val engine = YoutubedlEngine(runtime, EngineLimits(inspectMs = 300, updateMs = 5000))
        val update = async(Dispatchers.Default) { engine.updateYtdlp() }
        var inspection: Deferred<InspectResult>? = null
        try {
            assertTrue(withContext(Dispatchers.IO) { updating.await(3, TimeUnit.SECONDS) })
            inspection = async(Dispatchers.Default) { engine.inspect(source, "failed-update-inspect") }
            assertNull(withTimeoutOrNull(700) { inspection.await() })
        } finally { release.countDown() }
        assertIs<EngineUpdateResult.Failure>(withTimeout(3000) { update.await() })
        assertEquals("2025.11.12", assertIs<InspectResult.Success>(withTimeout(3000) { inspection.await() }).inspection.engineVersion)
    }

    @Test fun cancellationInterruptsMaintenanceWaitBeforeUpdaterFinishes() = runBlocking {
        val updating = CountDownLatch(1); val release = CountDownLatch(1); val executions = AtomicInteger()
        val runtime = Runtime().apply { action = { _, _ -> executions.incrementAndGet(); metadata } }
        runtime.onUpdate = { updating.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val engine = YoutubedlEngine(runtime)
        val update = async(Dispatchers.Default) { engine.updateYtdlp() }
        try {
            assertTrue(withContext(Dispatchers.IO) { updating.await(3, TimeUnit.SECONDS) })
            val inspection = async(Dispatchers.Default) { engine.inspect(source, "cancel-wait") }
            withTimeout(2000) { engine.waitingOperations.first { "cancel-wait" in it } }
            engine.cancel("cancel-wait")
            assertEquals(InspectResult.Failure(DownloadError.Cancelled), withTimeout(2000) { inspection.await() })
            assertFalse(update.isCompleted)
            assertEquals(0, executions.get())
            assertFalse("cancel-wait" in engine.waitingOperations.value)
        } finally { release.countDown(); withTimeout(3000) { update.await() } }
    }

    @Test fun coroutineCancellationReleasesWaitWithoutEnteringEngine() = runBlocking {
        val updating = CountDownLatch(1); val release = CountDownLatch(1); val executions = AtomicInteger()
        val runtime = Runtime().apply { action = { _, _ -> executions.incrementAndGet(); metadata } }
        runtime.onUpdate = { updating.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val engine = YoutubedlEngine(runtime)
        val update = async(Dispatchers.Default) { engine.updateYtdlp() }
        try {
            assertTrue(withContext(Dispatchers.IO) { updating.await(3, TimeUnit.SECONDS) })
            val inspection = async(Dispatchers.Default) { engine.inspect(source, "dismiss-wait") }
            withTimeout(2000) { engine.waitingOperations.first { "dismiss-wait" in it } }
            withTimeout(2000) { inspection.cancelAndJoin() }
            assertEquals(0, executions.get())
            assertFalse(update.isCompleted)
            assertFalse("dismiss-wait" in engine.waitingOperations.value)
        } finally { release.countDown(); withTimeout(3000) { update.await() } }
        assertIs<InspectResult.Success>(withTimeout(2000) { engine.inspect(source, "after-dismiss") })
        Unit
    }

    @Test fun downloadWaitsForFailedUpdateThenUsesExistingEngine() = runBlocking {
        val updating = CountDownLatch(1); val release = CountDownLatch(1); val executions = AtomicInteger()
        val dir = temp.newFolder()
        val runtime = Runtime().apply { action = { args, line -> executions.incrementAndGet(); download(dir)(args, line) } }
        runtime.onUpdate = { updating.countDown(); check(release.await(5, TimeUnit.SECONDS)); error("update failed") }
        val engine = YoutubedlEngine(runtime)
        val update = async(Dispatchers.Default) { engine.updateYtdlp() }
        var transfer: Deferred<TransferResult>? = null
        try {
            assertTrue(withContext(Dispatchers.IO) { updating.await(3, TimeUnit.SECONDS) })
            transfer = async(Dispatchers.Default) { engine.download(command(), dir) {} }
            assertNull(withTimeoutOrNull(200) { transfer.await() })
            assertEquals(0, executions.get())
        } finally { release.countDown() }
        assertIs<EngineUpdateResult.Failure>(withTimeout(3000) { update.await() })
        assertIs<TransferResult.Success>(withTimeout(3000) { transfer.await() })
        assertEquals(1, executions.get())
    }

    @Test fun nativeAdmissionUsesRemainingAppReadinessBudget() = runBlocking {
        val updating = CountDownLatch(1); val release = CountDownLatch(1); val executions = AtomicInteger()
        val runtime = Runtime().apply { action = { _, _ -> executions.incrementAndGet(); metadata } }
        runtime.onUpdate = { updating.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val engine = YoutubedlEngine(runtime)
        val update = async(Dispatchers.Default) { engine.updateYtdlp() }
        try {
            assertTrue(withContext(Dispatchers.IO) { updating.await(3, TimeUnit.SECONDS) })
            val result = withTimeout(2000) { EngineReadiness.within(300) {
                delay(200) // App admission already consumed most of the shared readiness budget.
                engine.inspect(source, "remaining-budget")
            } }
            assertEquals(InspectResult.Failure(DownloadError.Network), result)
            assertEquals(0, executions.get())
            assertFalse(update.isCompleted)
        } finally { release.countDown(); withTimeout(3000) { update.await() } }
    }

    @Test fun updaterAdmissionOutlastsMediaReadinessBudgetDuringFirstExtraction() = runBlocking {
        val extracting = CountDownLatch(1); val release = CountDownLatch(1); val updates = AtomicInteger()
        val runtime = Runtime().apply {
            onInitialize = { extracting.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            onUpdate = { updates.incrementAndGet() }
        }
        val engine = YoutubedlEngine(runtime, EngineLimits(updateMs = 2000, maintenanceWaitMs = 90))
        val warm = async(Dispatchers.Default) { engine.warmUp() }
        var update: Deferred<EngineUpdateResult>? = null
        try {
            assertTrue(withContext(Dispatchers.IO) { extracting.await(2, TimeUnit.SECONDS) })
            update = async(Dispatchers.Default) { engine.updateYtdlp() }
            // 90/120-second timing scaled to milliseconds; the updater's caller still has 2 seconds.
            assertNull(withTimeoutOrNull(300) { update.await() })
            assertEquals(0, updates.get())
        } finally { release.countDown() }
        assertTrue(withTimeout(2000) { warm.await() })
        assertIs<EngineUpdateResult.Success>(withTimeout(2000) { update.await() })
        assertEquals(1, updates.get())
    }

    @Test fun nativeDownloadAdmissionTimeoutReportsNetworkWithoutExecutingTransfer() = runBlocking {
        val updating = CountDownLatch(1); val release = CountDownLatch(1); val executions = AtomicInteger()
        val runtime = Runtime().apply { action = { _, _ -> executions.incrementAndGet(); metadata } }
        runtime.onUpdate = { updating.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val engine = YoutubedlEngine(runtime, EngineLimits(updateMs = 2000, maintenanceWaitMs = 90))
        val update = async(Dispatchers.Default) { engine.updateYtdlp() }
        val directory = temp.newFolder()
        try {
            assertTrue(withContext(Dispatchers.IO) { updating.await(2, TimeUnit.SECONDS) })
            assertEquals(TransferResult.Failure(DownloadError.Network), withTimeout(2000) {
                engine.download(command(), directory) {}
            })
            assertEquals(0, executions.get())
            assertTrue(directory.listFiles()!!.isEmpty())
            assertFalse(update.isCompleted)
        } finally { release.countDown(); withTimeout(2000) { update.await() } }
    }

    @Test fun downloadWithNoNewBytesForTheWatchdogWindowFailsAsRetryableNetwork() = runBlocking {
        val runtime = Runtime()
        runtime.action = { _, line -> line("nezzel:18:1:3:NA"); line("nezzel:18:1:3:NA"); runtime.hang() }
        val events = mutableListOf<EngineEvent>()
        val result = withTimeout(5000) { YoutubedlEngine(runtime, fast).download(command(), temp.newFolder(), events::add) }
        assertEquals(TransferResult.Failure(DownloadError.Network), result)
        assertTrue(runtime.destroys.get() >= 1)
        assertEquals(listOf<EngineEvent>(EngineEvent.Progress(1, Size.Exact(3)), EngineEvent.Progress(1, Size.Exact(3))), events)
    }

    @Test fun steadyBytesAndLongPostProcessingAreNotStalls() = runBlocking {
        for (tail in listOf("[Merger] Merging formats into output.mp4", "[FixupM3u8] Fixing MPEG-TS in MP4 container")) {
            val dir = temp.newFolder()
            val runtime = Runtime()
            runtime.action = { args, line ->
                for (bytes in 1..6) { line("nezzel:18:$bytes:6:NA"); Thread.sleep(120) } // 720 ms > 300 ms window
                line(tail); Thread.sleep(700)
                download(dir)(args, line)
            }
            assertIs<TransferResult.Success>(withTimeout(5000) { YoutubedlEngine(runtime, fast).download(command(), dir) {} })
            assertEquals(0, runtime.destroys.get())
        }
    }

    @Test fun userCancelDuringATimeoutWindowStaysCancelled() = runBlocking {
        val runtime = Runtime(); val entered = CountDownLatch(1)
        runtime.action = { _, _ -> entered.countDown(); runtime.hang() }
        val engine = YoutubedlEngine(runtime, EngineLimits(inspectMs = 5000))
        val task = async(Dispatchers.Default) { engine.inspect(source, "inspect") }
        assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
        engine.cancel("inspect")
        assertEquals(InspectResult.Failure(DownloadError.Cancelled), withTimeout(3000) { task.await() })
    }

    @Test fun callerStopsWaitingButTheGateStaysClosedUntilTheUpdateIsResolved() = runBlocking {
        val release = CountDownLatch(1); val executedDuringUpdate = AtomicInteger()
        val runtime = Runtime()
        val updating = java.util.concurrent.atomic.AtomicBoolean()
        runtime.action = { _, _ -> if (updating.get()) executedDuringUpdate.incrementAndGet(); metadata }
        runtime.onUpdate = { updating.set(true); check(release.await(5, TimeUnit.SECONDS)); runtime.versionWorks = false; updating.set(false) }
        val engine = YoutubedlEngine(runtime, EngineLimits(inspectMs = 300, updateMs = 300, maintenanceWaitMs = 300))
        assertEquals(EngineUpdateResult.Failure(DownloadError.Network), withTimeout(3000) { engine.updateYtdlp() })
        // A second request joins the same updater; nothing reads or runs the binary meanwhile.
        assertEquals(EngineUpdateResult.Failure(DownloadError.Network), withTimeout(3000) { engine.updateYtdlp() })
        assertNull(engine.installedVersion())
        assertEquals(InspectResult.Failure(DownloadError.Network), withTimeout(3000) { engine.inspect(source, "during") })
        assertEquals(0, executedDuringUpdate.get()); assertEquals(0, runtime.restores.get())
        release.countDown()
        withTimeout(3000) { while (runtime.restores.get() == 0) delay(20) }
        assertIs<InspectResult.Success>(withTimeout(3000) { engine.inspect(source, "after") })
        assertEquals("2025.11.12", engine.installedVersion())
        Unit
    }

    @Test fun hangingSelfTestIsDestroyedAndRolledBackThenTheGateOpens() = runBlocking {
        val runtime = Runtime().apply { action = { _, _ -> metadata } }
        runtime.onUpdate = { runtime.version = "2026.08.19"; runtime.versionHangs = true }
        val engine = YoutubedlEngine(runtime, EngineLimits(updateMs = 5000, selfTestMs = 300))
        assertEquals(EngineUpdateResult.Failure(DownloadError.Failed, usingBuiltIn = true), withTimeout(4000) { engine.updateYtdlp() })
        assertTrue(runtime.destroys.get() >= 1, "hung --version tree destroyed")
        assertEquals(1, runtime.restores.get())
        assertEquals("2025.11.12", engine.installedVersion())
        assertIs<InspectResult.Success>(withTimeout(2000) { engine.inspect(source, "after") })
        Unit
    }

    @Test fun sameArgvExecutionsNeverOverlapSoCancellingOneSparesTheOther() = runBlocking {
        class Table : ProcTable {
            val rows = java.util.concurrent.ConcurrentHashMap<Int, ProcProcess>()
            val signalled = java.util.concurrent.ConcurrentLinkedQueue<Int>()
            override fun snapshot() = rows.values.toList()
            override fun read(pid: Int) = rows[pid]
            override fun signal(pid: Int, signal: Int) { signalled += pid; rows.remove(pid) }
        }
        val table = Table(); val nextPid = AtomicInteger(10); val firstRunning = CountDownLatch(1)
        val release = CountDownLatch(1)
        val native = object : YtdlpRuntime {
            override val initialization = EngineInitialization().apply { installedVersion = "2025.11.12" }
            override fun initialize() = Unit
            override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
                val pid = nextPid.getAndIncrement()
                table.rows[pid] = ProcProcess(pid, 1, pid.toLong(), args)
                if (pid == 10) firstRunning.countDown()
                val deadline = System.nanoTime() + 5_000_000_000
                while (table.rows.containsKey(pid) && !(pid == 10 && release.count == 0L) && System.nanoTime() < deadline) Thread.sleep(10)
                val alive = table.rows.remove(pid) != null
                check(alive) { "killed" }
                return metadata
            }
            override fun destroy(processId: String) = false
            override fun update() = Unit
        }
        val runtime = AndroidYtdlpRuntime(native, { args -> ProcessTree(table, pause = {}) { it.command == args } }, pause = {})
        val engine = YoutubedlEngine(runtime)
        val first = async(Dispatchers.Default) { engine.inspect(source, "first") }
        assertTrue(withContext(Dispatchers.IO) { firstRunning.await(3, TimeUnit.SECONDS) })
        val second = async(Dispatchers.Default) { engine.inspect(source, "second") }
        delay(200) // The second inspection's canceller polls /proc while the first child runs.
        engine.cancel("second")
        assertEquals(InspectResult.Failure(DownloadError.Cancelled), withTimeout(3000) { second.await() })
        release.countDown()
        assertIs<InspectResult.Success>(withTimeout(3000) { first.await() })
        assertTrue(10 !in table.signalled, "first inspection's process was never signalled")
        Unit
    }

    @Test fun brokenUpdateIsRolledBackToTheBundledYtdlp() = runBlocking {
        val runtime = Runtime()
        runtime.onUpdate = { runtime.version = "2026.08.19"; runtime.versionWorks = false }
        val engine = YoutubedlEngine(runtime)
        assertEquals("2025.11.12", engine.installedVersion())
        assertEquals(EngineUpdateResult.Failure(DownloadError.Failed, usingBuiltIn = true), engine.updateYtdlp())
        assertEquals(1, runtime.restores.get())
        assertEquals("2025.11.12", engine.installedVersion(), "version shown is the bundled one")
        // A working update is not rolled back.
        runtime.onUpdate = { runtime.version = "2026.09.01" }
        assertEquals(EngineUpdateResult.Success("2025.11.12", "2026.09.01"), engine.updateYtdlp())
        assertEquals(1, runtime.restores.get())
    }

    @Test fun failedInitializationRetriesAfterBackoffOrUserRetryAndClearsPartialInstallFirst() = runBlocking {
        var now = 0L
        val initialization = EngineInitialization({ now }, backoffMs = 30_000)
        var fail = true; var attempts = 0; var resets = 0
        val ensure = { initialization.ensure({ resets++ }) { attempts++; if (fail) throw UnsatisfiedLinkError("partial") } }
        assertFalse(ensure()); assertEquals(1, attempts); assertEquals(0, resets)
        assertFalse(initialization.isAvailable)
        assertFalse(ensure()); assertEquals(1, attempts, "no retry inside the backoff")
        initialization.requestRetry()
        assertTrue(initialization.isAvailable)
        assertFalse(ensure()); assertEquals(2, attempts); assertEquals(1, resets)
        now += 29_999_000_000
        assertFalse(ensure()); assertEquals(2, attempts)
        now += 1_000_000
        assertTrue(initialization.isAvailable)
        fail = false
        assertTrue(ensure()); assertEquals(3, attempts); assertEquals(2, resets)
        assertTrue(ensure()); assertEquals(3, attempts, "success is cached")
        initialization.requestRetry(); assertTrue(ensure()); assertEquals(3, attempts)
    }

    @Test fun engineInitFailsThenSucceedsOnUserRetry() = runBlocking {
        var fail = true; var starts = 0; var resets = 0
        val runtime = object : YtdlpRuntime {
            override val initialization = EngineInitialization()
            override fun initialize() { starts++; if (fail) throw IllegalStateException("no space") }
            override fun resetPartialInstall() { resets++ }
            override fun execute(args: List<String>, processId: String, line: (String) -> Unit) =
                if (args.contains("--version")) "2025.11.12" else metadata
            override fun destroy(processId: String) = true
            override fun update() = Unit
        }
        val engine = YoutubedlEngine(runtime)
        assertEquals(InspectResult.Failure(DownloadError.EngineUnavailable), engine.inspect(source, "first"))
        assertFalse(engine.isAvailable)
        fail = false
        engine.retryInitialization()
        assertTrue(engine.isAvailable)
        assertIs<InspectResult.Success>(engine.inspect(source, "retry"))
        assertEquals(2, starts); assertEquals(1, resets)
    }

    @Test fun preparingIsVisibleOnlyDuringAFirstExtraction() = runBlocking {
        for (first in listOf(true, false)) {
            val seen = mutableListOf<Boolean>()
            val initialization = EngineInitialization()
            assertFalse(initialization.preparing.value)
            assertTrue(initialization.ensure(needsExtraction = { first }) { seen += initialization.preparing.value })
            assertEquals(listOf(first), seen)
            assertFalse(initialization.preparing.value)
        }
    }
}
