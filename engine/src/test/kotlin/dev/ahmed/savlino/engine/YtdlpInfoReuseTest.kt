package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class YtdlpInfoReuseTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = LinkRevision(LinkInput.parse("https://example.org/watch")!!, 1)
    private val json = """{"id":"clip","format_id":"18","ext":"mp4","vcodec":"h264","acodec":"aac","url":"https://example.org/signed?token=private"}"""
    private class Runtime(private val json: String) : YtdlpRuntime {
        override val initialization = EngineInitialization()
        val transfers = mutableListOf<List<String>>()
        var action: (List<String>, (String) -> Unit) -> Unit = { args, _ -> output(args) }
        override fun initialize() = Unit
        override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
            if ("--version" in args) return "version"
            if ("--dump-json" in args) return json
            transfers += args; action(args, line); return ""
        }
        override fun destroy(processId: String) = true
        override fun update() = Unit
        companion object {
            fun output(args: List<String>) {
                val output = File(args[args.indexOf("-o") + 1].replace("%(ext)s", "mp4"))
                output.writeText("media")
                File(output.parentFile, "result-path.txt").writeText(output.path)
            }
        }
    }
    private fun confirm(inspection: Inspection, id: String = "job") =
        assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm(id, inspection, source, "18", "title")).command
    @Test fun freshInspectionUsesItsRawJsonAndExactSelectorAfterProcessRestart() = runBlocking {
        val root = temp.newFolder()
        val runtime = Runtime(json)
        val engine = YoutubedlEngine(runtime, infoCache = YtdlpInfoCache(root))
        val inspection = assertIs<InspectResult.Success>(engine.inspect(source, "inspect")).inspection
        assertTrue(root.listFiles()!!.isEmpty(), "inspection must stay memory-only")
        val command = confirm(inspection)
        engine.prepareDownload(command)
        // Journal restoration has a new inspection identity; only the job file survives.
        val restored = confirm(Inspection(source, "version", inspection.formats))
        val other = YoutubedlEngine(runtime, infoCache = YtdlpInfoCache(root))
        assertIs<TransferResult.Success>(other.download(restored, temp.newFolder()) {})
        val args = runtime.transfers.single()
        assertTrue("--load-info-json" in args)
        assertFalse(source.link.rawUrl in args)
        assertEquals("18", args[args.indexOf("-f") + 1])
        assertEquals(json, File(args[args.indexOf("--load-info-json") + 1]).readText())
    }
    @Test fun missingStaleAndClockRollbackUseUrlWithoutExtendingInspectAge() = runBlocking {
        for (age in listOf(3_600_000L, 3_600_001L, -1L)) {
            var now = 10_000_000L
            val runtime = Runtime(json)
            val cache = YtdlpInfoCache(temp.newFolder()) { now }
            val engine = YoutubedlEngine(runtime, infoCache = cache)
            val command = confirm(assertIs<InspectResult.Success>(engine.inspect(source, "inspect")).inspection)
            engine.prepareDownload(command)
            now += age
            assertIs<TransferResult.Success>(engine.download(command, temp.newFolder()) {})
            assertEquals(source.link.rawUrl, runtime.transfers.single().last())
            assertFalse("--load-info-json" in runtime.transfers.single())
        }
        val runtime = Runtime(json)
        assertIs<TransferResult.Success>(YoutubedlEngine(runtime, infoCache = YtdlpInfoCache(temp.newFolder()))
            .download(confirm(Inspection(source, "version", listOf(Format("18", "mp4", true, true)))), temp.newFolder()) {})
        assertEquals(source.link.rawUrl, runtime.transfers.single().last())
    }
    @Test fun forbiddenAndExpiredUrlsBeforeBytesFallBackExactlyOnce() = runBlocking {
        for (message in listOf("ERROR: unable to download video data: HTTP Error 403: Forbidden",
            "ERROR: media URL has expired", "ERROR: HTTP Error 403: Forbidden")) {
            val runtime = Runtime(json)
            val engine = YoutubedlEngine(runtime, infoCache = YtdlpInfoCache(temp.newFolder()))
            val command = confirm(assertIs<InspectResult.Success>(engine.inspect(source, "inspect")).inspection)
            runtime.action = { args, line ->
                if (runtime.transfers.size == 1) {
                    File(File(args[args.indexOf("-o") + 1]).parentFile, "output.mp4.part").createNewFile()
                    line(message); error("") // Wrapper can throw with no diagnostic message.
                }
                Runtime.output(args)
            }
            assertIs<TransferResult.Success>(engine.download(command, temp.newFolder()) {})
            assertEquals(2, runtime.transfers.size)
            assertTrue("--load-info-json" in runtime.transfers.first())
            assertFalse("--load-info-json" in runtime.transfers.last())
            assertEquals(source.link.rawUrl, runtime.transfers.last().last())
        }
    }
    @Test fun secondFailureNeverLoopsAndCancellationSuppressesFallback() = runBlocking {
        for (mode in listOf("twice", "progress", "disk", "cancel", "unrelated")) {
            val runtime = Runtime(json)
            val engine = YoutubedlEngine(runtime, infoCache = YtdlpInfoCache(temp.newFolder()))
            val command = confirm(assertIs<InspectResult.Success>(engine.inspect(source, "inspect")).inspection)
            runtime.action = { args, line ->
                if (mode == "progress") line("nezzel:18:1:NA:NA")
                if (mode == "disk") File(File(args[args.indexOf("-o") + 1]).parentFile, "output.mp4.part").writeText("byte")
                if (mode == "cancel") engine.cancel(command.jobId)
                error(if (mode == "unrelated") "ERROR: requested format is not available"
                    else "ERROR: HTTP Error 403: Forbidden")
            }
            assertIs<TransferResult.Failure>(engine.download(command, temp.newFolder()) {})
            assertEquals(if (mode in listOf("twice", "progress", "disk")) 2 else 1, runtime.transfers.size, mode)
        }
    }
    @Test fun expiredSignedUrlWithBytesWipesBeforeExactSelectionRefetchAndChangesEpoch() = runBlocking {
        for (code in listOf(401, 403, 410)) {
            val runtime = Runtime(json)
            val cache = YtdlpInfoCache(temp.newFolder())
            val engine = YoutubedlEngine(runtime, infoCache = cache)
            val command = confirm(assertIs<InspectResult.Success>(engine.inspect(source, "inspect")).inspection)
            val dir = temp.newFolder()
            val events = mutableListOf<EngineEvent>()
            runtime.action = { args, line ->
                if (runtime.transfers.size == 1) {
                    File(dir, "output.mp4.part").writeText("old signed URL bytes")
                    line("nezzel:18:100:1000:NA")
                    error("ERROR: unable to download video data: HTTP Error $code")
                }
                assertTrue(dir.list()!!.isEmpty())
                assertEquals("18", args[args.indexOf("-f") + 1])
                assertFalse("--load-info-json" in args)
                line("nezzel:18:1:1000:NA")
                Runtime.output(args)
            }
            assertIs<TransferResult.Success>(engine.download(command, dir) { events += it })
            assertEquals(2, runtime.transfers.size)
            assertEquals(listOf(0L, 1L), events.filterIsInstance<EngineEvent.Progress>().map { it.restartEpoch })
            assertTrue(events.any { it is EngineEvent.Restarting })
            assertFalse(File(dir, "output.mp4.part").exists())
        }
    }
    @Test fun adoptedPartialDirectoryIsAcceptedButForeignFileAndSymlinkAreRejected() {
        val command = confirm(Inspection(source, "version", listOf(Format("18", "mp4", true, true))))
        val dir = temp.newFolder()
        File(dir, "output.mp4.part").writeText("partial")
        File(dir, "output.f140.m4a").writeText("audio")
        assertFalse("--no-continue" in YtdlpCommand.download(command, dir))
        File(dir, "foreign").writeText("unknown")
        assertFailsWith<IllegalArgumentException> { YtdlpCommand.download(command, dir) }
    }

    @Test fun adoptedBytesWithoutCachedInfoWipeBeforeTheFirstUrlTransfer() = runBlocking {
        val runtime = Runtime(json)
        val engine = YoutubedlEngine(runtime)
        val command = confirm(Inspection(source, "version", listOf(Format("18", "mp4", true, true))))
        val dir = temp.newFolder()
        File(dir, "output.mp4.part").writeText("old bytes")
        val events = mutableListOf<EngineEvent>()
        runtime.action = { args, _ ->
            assertTrue(dir.list()!!.isEmpty())
            assertFalse("--load-info-json" in args)
            assertEquals("18", args[args.indexOf("-f") + 1])
            error("ERROR: requested format is not available")
        }
        assertEquals(TransferResult.Failure(DownloadError.FormatExpired), engine.download(command, dir) { events += it })
        assertEquals(1, runtime.transfers.size)
        assertEquals(listOf<EngineEvent>(EngineEvent.Restarting(1)), events)
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test fun resumeAfterInfoJsonExpiredWipesPartialsAndExtractsFromUrl() = runBlocking {
        var now = 10_000_000L
        val runtime = Runtime(json)
        val cache = YtdlpInfoCache(temp.newFolder()) { now }
        val engine = YoutubedlEngine(runtime, infoCache = cache)
        val command = confirm(assertIs<InspectResult.Success>(engine.inspect(source, "inspect")).inspection)
        engine.prepareDownload(command)
        val dir = temp.newFolder()
        File(dir, "output.f18.mp4.part").writeText("bytes from the inspected signed URL")
        File(dir, "output.f18.mp4.ytdl").writeText("state")
        now += YtdlpInfoCache.TTL_MS
        val events = mutableListOf<EngineEvent>()
        runtime.action = { args, line ->
            assertTrue(dir.list()!!.isEmpty())
            assertFalse("--load-info-json" in args)
            assertTrue(source.link.rawUrl in args)
            line("nezzel:18:1:1000:NA")
            Runtime.output(args)
        }
        assertIs<TransferResult.Success>(engine.download(command, dir) { events += it })
        assertEquals(1, runtime.transfers.size)
        assertEquals(EngineEvent.Restarting(1), events.first())
        assertEquals(listOf(1L), events.filterIsInstance<EngineEvent.Progress>().map { it.restartEpoch })
    }

    @Test fun httpRateLimitAndServerErrorsNeverResumeEvenAfterTransportDiagnostics() = runBlocking {
        for (code in listOf(429, 500, 503)) {
            val runtime = Runtime(json)
            val engine = YoutubedlEngine(runtime)
            val command = confirm(Inspection(source, "version", listOf(Format("18", "mp4", true, true))))
            runtime.action = { _, line ->
                line("ERROR: connection reset")
                line("ERROR: HTTP Error $code")
                throw YtdlpExecutionFailure(DownloadError.Network)
            }
            val failure = assertIs<TransferResult.Failure>(engine.download(command, temp.newFolder()) {})
            assertFalse(failure.resumeAllowed)
            assertEquals(1, runtime.transfers.size)
        }
    }

    @Test fun writeFailureIsBestEffortAndSweepDeletesExpiredFilesOnly() = runBlocking {
        val root = temp.newFolder()
        val runtime = Runtime(json)
        var now = 10_000_000L
        val cache = YtdlpInfoCache(root) { now }
        val engine = YoutubedlEngine(runtime, infoCache = cache)
        val command = confirm(assertIs<InspectResult.Success>(engine.inspect(source, "inspect")).inspection)
        engine.prepareDownload(command)
        val fresh = File(root, "ytdlp-info/job.json")
        val stale = File(root, "ytdlp-info/stale.json").apply { writeText(json); setLastModified(now - 3_600_000) }
        val partial = File(root, "ytdlp-info/stale.tmp").apply { writeText(json); setLastModified(now - 3_600_001) }
        cache.sweep()
        assertTrue(fresh.exists()); assertFalse(stale.exists()); assertFalse(partial.exists())
        now += 3_600_000; cache.sweep(); assertFalse(fresh.exists())
        val blocked = temp.newFolder()
        File(blocked, "ytdlp-info").writeText("not a directory")
        val other = YoutubedlEngine(runtime, infoCache = YtdlpInfoCache(blocked))
        val failedCache = confirm(assertIs<InspectResult.Success>(other.inspect(source, "inspect")).inspection)
        assertIs<TransferResult.Success>(other.download(failedCache, temp.newFolder()) {})
        assertFalse("--load-info-json" in runtime.transfers.single())
    }
    @Test fun consumedInspectionCannotRestoreADeletedFileOnRetry() = runBlocking {
        val cache = YtdlpInfoCache(temp.newFolder())
        val runtime = Runtime(json)
        val engine = YoutubedlEngine(runtime, infoCache = cache)
        val command = confirm(assertIs<InspectResult.Success>(engine.inspect(source, "inspect")).inspection)
        engine.prepareDownload(command); cache.remove(command.jobId)
        assertIs<TransferResult.Success>(engine.download(command, temp.newFolder()) {})
        assertFalse("--load-info-json" in runtime.transfers.single())
    }
    @Test fun cachedCommandDiagnosticsNeverLogRawJsonOrItsFields() {
        val logs = mutableListOf<String>()
        val diagnostics = YtdlpDebugDiagnostics(enabled = true, log = { _, message -> logs += message },
            args = listOf("--load-info-json", "/private/cache.json"))
        diagnostics.accept(json)
        diagnostics.finished(1, json)
        assertTrue(logs.isEmpty())
    }
    @Test fun unconfirmedExpiryAndDifferentInspectionNeverReuseJson() = runBlocking {
        var now = 10_000_000L
        val runtime = Runtime(json)
        val engine = YoutubedlEngine(runtime, infoCache = YtdlpInfoCache(temp.newFolder()) { now })
        val inspection = assertIs<InspectResult.Success>(engine.inspect(source, "inspect")).inspection
        // An independently created inspection of the same URL must not borrow its JSON.
        engine.download(confirm(Inspection(source, "version", inspection.formats), "other"), temp.newFolder()) {}
        now += 3_600_000
        engine.download(confirm(inspection), temp.newFolder()) {}
        assertEquals(2, runtime.transfers.size)
        assertTrue(runtime.transfers.all { "--load-info-json" !in it && it.last() == source.link.rawUrl })
    }
    @Test fun runtimePreservesExpiryFlagWithoutKeepingPrivateMessages() {
        val diagnostics = YtdlpDiagnostics()
        diagnostics.accept("ERROR: unable to download video data: HTTP Error 403: Forbidden https://example.org/private")
        val error = diagnostics.failure()
        assertTrue(error.expiredUrl); assertEquals(DownloadError.FormatExpired, error.error)
        assertNull(error.message); assertNull(error.cause)
        assertFalse(YtdlpDiagnostics.expiredUrl("ERROR: failed https://example.org/HTTP%20Error%20403"))
        assertFalse(YtdlpDiagnostics.expiredUrl("WARNING: HTTP Error 403"))
    }

}
