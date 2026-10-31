package dev.ahmed.savlino.engine.youtube

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class NpeDownloadTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = LinkRevision(LinkInput.parse("https://www.youtube.com/watch?v=fixture-only")!!, 1)
    private val row = Format("137+140", "mp4", true, true, sourceFormatId = "137",
        audioFormatId = "140", requiresMerge = true)
    private fun json() = assertNotNull(NpeInfoJson.build(NpeFixtures.golden()))
    private fun command(format: Format = row, link: LinkRevision = source, version: String = "fixture-version",
        job: String = "job"): DownloadCommand {
        val inspection = Inspection(link, version, listOf(format))
        return assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm(job, inspection, link, format.id, "Fixture")).command
    }

    /** [action] returns a JSON string, a [FastResult] or null (= Miss). */
    private class Fast(var action: () -> Any?) : YoutubeFastSource {
        val calls = CopyOnWriteArrayList<Pair<String, Long>>()
        val cancelled = CopyOnWriteArrayList<String>()
        override fun infoJson(url: String, operationId: String, timeoutMs: Long): FastResult {
            calls += operationId to timeoutMs
            return when (val result = action()) { is FastResult -> result; is String -> FastResult.Json(result); else -> FastResult.Miss }
        }
        override fun cancel(operationId: String) { cancelled += operationId }
    }
    private class Runtime : YtdlpRuntime {
        override val initialization = EngineInitialization()
        val transfers = mutableListOf<List<String>>()
        /** yt-dlp page reads (--dump-json) a download makes when no confirmed info JSON is left. */
        val pages = mutableListOf<List<String>>()
        var page: () -> String = { "" }
        var action: (List<String>, (String) -> Unit) -> Unit = { args, _ -> output(args) }
        override fun initialize() = Unit
        override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
            if ("--version" in args) return "fixture-version"
            if ("--dump-json" in args) { pages += args; return page() }
            transfers += args
            action(args, line)
            return ""
        }
        override fun destroy(processId: String) = true
        override fun update() = Unit
        companion object {
            fun output(args: List<String>) {
                val file = File(args[args.indexOf("-o") + 1].replace("%(ext)s", "mp4"))
                file.writeText("fixture bytes")
                File(file.parentFile, "result-path.txt").writeText(file.path)
            }
        }
    }
    private inner class Run(var cmd: DownloadCommand = command(), val root: File = temp.newFolder(),
        val dir: File = temp.newFolder(), val fast: Fast? = Fast { json() },
        val clock: () -> Long = System::currentTimeMillis, limits: EngineLimits = EngineLimits(), cancellationPollMs: Long = 50) {
        val cache = YtdlpInfoCache(root, clock)
        val runtime = Runtime()
        val events = mutableListOf<EngineEvent>()
        val engine = YoutubedlEngine(runtime, limits, cache, fast, cancellationPollMs = cancellationPollMs)
        init { runtime.page = { json() } }
        suspend fun download() = engine.download(cmd, dir) { events += it }
        fun inspected(npe: Boolean = true) {
            val inspection = Inspection(cmd.source, cmd.engineVersion, listOf(cmd.format))
            cmd = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm(cmd.jobId, inspection, cmd.source,
                cmd.format.id, "Fixture")).command
            cache.remember(inspection, json(), trimToChosen = npe)
            engine.prepareDownload(cmd)
            assertNotNull(cache.freshFile(cmd.jobId))
        }
        fun partial() { File(dir, "output.f137.mp4.part").writeText("abandoned bytes") }
        fun failAttempt(line: (String) -> Unit, message: String = "ERROR: unable to download video data: HTTP Error 403") {
            partial()
            line(message)
            throw IllegalStateException()
        }
        fun assertClean(epoch: Long) {
            assertTrue(dir.listFiles()!!.isEmpty())
            assertEquals(EngineEvent.Restarting(epoch), events.last())
        }
    }
    private fun info(args: List<String>): File? = args.indexOf("--load-info-json").takeIf { it >= 0 }?.let { File(args[it + 1]) }
    /** A page transfer loads the checked yt-dlp page JSON, never the NPE origin. */
    private fun assertPage(args: List<String>, job: String = "job") = assertEquals("$job.json", assertNotNull(info(args)).name)
    private fun ids(file: File) = ObjectMapper().readTree(file).path("formats").map { it.path("format_id").asText() }.toSet()

    @Test fun missingInfoWipesAndRestartsBeforeRebuildThenUsesOnlyConfirmedIds() = runBlocking {
        val run = Run(cancellationPollMs = 1)
        run.partial()
        run.fast!!.action = { run.assertClean(1); json() }
        run.runtime.action = { args, line ->
            run.assertClean(1)
            assertEquals(setOf("137", "140"), ids(assertNotNull(info(args))))
            assertEquals("137+140", args[args.indexOf("-f") + 1])
            line("nezzel:137:1:1000:NA")
            Runtime.output(args)
        }
        assertIs<TransferResult.Success>(run.download())
        assertEquals(listOf("job" to 10_000L), run.fast.calls)
        assertEquals(listOf(1L), run.events.filterIsInstance<EngineEvent.Progress>().map { it.restartEpoch })
    }

    @Test fun rebuiltRowWithAnotherRecipeIsNeverDownloaded() = runBlocking {
        // NPE now returns 137 at another height: the checked page read decides, and it changed too.
        val confirmed = row.copy(height = 720)
        val run = Run(command(confirmed), cancellationPollMs = 1)
        run.runtime.action = { _, _ -> fail("no transfer for a changed row") }
        assertEquals(DownloadError.FormatExpired, assertIs<TransferResult.Failure>(run.download()).error)
        assertEquals(1, run.fast!!.calls.size)
        assertEquals(1, run.runtime.pages.size)
        assertTrue(run.runtime.transfers.isEmpty())
        assertNull(run.cache.freshFile("job"))
    }

    @Test fun fullDrcAndTrackIdsMustMatchInsteadOfBorrowingThePlainItag() = runBlocking {
        for (audio in listOf("140-drc", "251-ten.4", "251-ten.4-drc")) {
            val chosen = row.copy(id = "137+$audio", audioFormatId = audio, container = if (audio.startsWith("251")) "mkv" else "mp4") // Opus merges are MKV.
            val run = Run(command(chosen), cancellationPollMs = 1)
            assertIs<TransferResult.Success>(run.download())
            assertEquals(setOf("137", audio), ids(assertNotNull(info(run.runtime.transfers.single()))))
        }
        for (chosen in listOf(row.copy(audioFormatId = "140-drc"), row.copy(audioFormatId = "251-ten.4-drc", container = "mkv"))) {
            val run = Run(command(chosen), cancellationPollMs = 1)
            run.fast!!.action = { NpeInfoJson.build(NpeFixtures.info()) }
            assertIs<TransferResult.Success>(run.download())
            assertPage(run.runtime.transfers.single())
            assertEquals(setOf("137", chosen.audioFormatId), ids(assertNotNull(info(run.runtime.transfers.single()))))
            assertEquals(chosen.sourceFormatId + "+" + chosen.audioFormatId,
                run.runtime.transfers.single().let { it[it.indexOf("-f") + 1] })
        }
        // The page no longer returns the confirmed video id: nothing else is downloaded in its place.
        val run = Run(command(row.copy(sourceFormatId = "missing")), cancellationPollMs = 1)
        run.fast!!.action = { NpeInfoJson.build(NpeFixtures.info()) }
        assertEquals(DownloadError.FormatExpired, assertIs<TransferResult.Failure>(run.download()).error)
        assertTrue(run.runtime.transfers.isEmpty())
        assertEquals(1, run.runtime.pages.size)
    }

    @Test fun nullThrowMalformedAndUnwritableRebuildsFallBackWithoutPrivateDetails() = runBlocking {
        for (action in listOf<() -> String?>({ null }, { error("private fixture") }, { "{" })) {
            val run = Run(fast = Fast(action), cancellationPollMs = 1)
            // Before the output lands in staging.
            val expected = YtdlpCommand.download(run.cmd, run.dir, File(run.root, "ytdlp-info/job.json"))
            assertIs<TransferResult.Success>(run.download())
            assertEquals(expected, run.runtime.transfers.single())
            assertEquals(1, run.runtime.pages.size)
        }
        val root = temp.newFolder()
        File(root, "ytdlp-info").writeText("blocked")
        val run = Run(root = root, cancellationPollMs = 1)
        assertIs<TransferResult.Success>(run.download())
        assertNull(info(run.runtime.transfers.single()))
    }

    @Test fun nonYoutubeAndNullFastSourceUseTheCheckedPageAndNeverRebuild() = runBlocking {
        val other = LinkRevision(LinkInput.parse("https://example.org/video")!!, 1)
        for (run in listOf(Run(command(link = other), cancellationPollMs = 1), Run(fast = null, cancellationPollMs = 1))) {
            val expected = YtdlpCommand.download(run.cmd, run.dir, File(run.root, "ytdlp-info/job.json"))
            assertIs<TransferResult.Success>(run.download())
            assertEquals(expected, run.runtime.transfers.single())
            assertTrue(run.fast?.calls.orEmpty().isEmpty())
        }
    }

    @Test fun reconfirmedVersionWithoutInfoRebuildsExactSelection() = runBlocking {
        val original = command(version = "previous-version")
        val updated = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm(original.jobId,
            Inspection(source, "fixture-version", listOf(original.format)), source, original.format.id, "Fixture")).command
        val run = Run(updated, cancellationPollMs = 1)
        assertIs<TransferResult.Success>(run.download())
        assertEquals(setOf("137", "140"), ids(assertNotNull(info(run.runtime.transfers.single()))))
        assertEquals(1, run.fast!!.calls.size)
    }

    @Test fun inspectedExpiryRebuildsOnceThenSecondExpiryFallsToPage() = runBlocking {
        for (npe in listOf(true, false)) {
            val run = Run(cancellationPollMs = 1)
            run.inspected(npe)
            run.fast!!.action = { run.assertClean(1); json() }
            run.runtime.action = { args, line ->
                when (run.runtime.transfers.size) {
                    1 -> run.failAttempt(line)
                    2 -> { run.assertClean(1); assertNotNull(info(args)); run.failAttempt(line) }
                    else -> {
                        run.assertClean(2)
                        assertPage(args)
                        line("nezzel:137:1:1000:NA"); Runtime.output(args)
                    }
                }
            }
            assertIs<TransferResult.Success>(run.download())
            assertEquals(3, run.runtime.transfers.size)
            assertEquals(1, run.fast.calls.size)
            assertEquals(listOf(2L), run.events.filterIsInstance<EngineEvent.Progress>().map { it.restartEpoch })
        }
    }

    @Test fun inspectedNpeRejectionFallsDirectlyToPageWithoutRebuild() = runBlocking {
        for (error in listOf(DownloadError.FormatExpired, DownloadError.Failed, DownloadError.Unsupported, DownloadError.NoFormats)) {
            val run = Run(cancellationPollMs = 1)
            run.inspected()
            run.runtime.action = { args, _ ->
                if (run.runtime.transfers.size == 1) { run.partial(); throw YtdlpExecutionFailure(error) }
                run.assertClean(1)
                assertPage(args)
                Runtime.output(args)
            }
            assertIs<TransferResult.Success>(run.download())
            assertEquals(2, run.runtime.transfers.size)
            assertTrue(run.fast!!.calls.isEmpty())
        }
    }

    @Test fun unavailablePrivateAndLoginRejectionsAreSurfacedWithoutFallback() = runBlocking {
        for (error in listOf(DownloadError.Unavailable, DownloadError.LoginRequired, DownloadError.SourceRefused)) {
            val run = Run(cancellationPollMs = 1); run.inspected()
            run.runtime.action = { _, _ -> throw YtdlpExecutionFailure(error) }
            assertEquals(error, assertIs<TransferResult.Failure>(run.download()).error)
            assertEquals(1, run.runtime.transfers.size); assertTrue(run.fast!!.calls.isEmpty())
        }
    }

    @Test fun pageMissingMultiAudioIdSurfacesFormatExpiredWithoutSubstitution() = runBlocking {
        val run = Run(command(row.copy(id = "137+251-ten.4", audioFormatId = "251-ten.4", container = "mkv")), cancellationPollMs = 1)
        run.inspected()
        run.runtime.page = { NpeInfoJson.build(NpeFixtures.info())!! }
        run.runtime.action = { _, _ -> throw YtdlpExecutionFailure(DownloadError.Unsupported) }
        assertEquals(DownloadError.FormatExpired, assertIs<TransferResult.Failure>(run.download()).error)
        assertEquals(1, run.runtime.transfers.size)
        assertEquals(1, run.runtime.pages.size)
        assertTrue(run.fast!!.calls.isEmpty())
    }

    @Test fun rebuildRejectionThenTwoPageExpiriesStopsAtThreeTransfers() = runBlocking {
        val run = Run(cancellationPollMs = 1)
        run.runtime.action = { args, line ->
            when (run.runtime.transfers.size) {
                1 -> { assertNotNull(info(args)); run.failAttempt(line, "ERROR: Requested format is not available") }
                else -> {
                    run.assertClean((run.runtime.transfers.size - 1).toLong())
                    assertPage(args)
                    run.failAttempt(line, if (run.runtime.transfers.size == 3) "ERROR: HTTP Error 403"
                        else "ERROR: unable to download video data: HTTP Error 403")
                }
            }
        }
        assertEquals(DownloadError.SourceRefused, assertIs<TransferResult.Failure>(run.download()).error)
        assertEquals(3, run.runtime.transfers.size)
        assertEquals(1, run.fast!!.calls.size)
        assertEquals(listOf(1L, 2L), run.events.filterIsInstance<EngineEvent.Restarting>().map { it.restartEpoch })
    }

    @Test fun failedRebuildThenTwoPageExpiriesStopsAfterOnePageRetry() = runBlocking {
        val run = Run(fast = Fast { null }, cancellationPollMs = 1)
        run.runtime.action = { args, line ->
            assertPage(args)
            run.failAttempt(line, if (run.runtime.transfers.size == 2) "ERROR: HTTP Error 403"
                else "ERROR: unable to download video data: HTTP Error 403")
        }
        assertEquals(DownloadError.SourceRefused, assertIs<TransferResult.Failure>(run.download()).error)
        assertEquals(2, run.runtime.transfers.size)
        assertEquals(1, run.fast!!.calls.size)
        assertEquals(listOf(1L), run.events.filterIsInstance<EngineEvent.Restarting>().map { it.restartEpoch })
    }

    @Test fun httpNetworkStorageMergeAndInterruptedSignalsDominateEarlierOrLaterExpiry() = runBlocking {
        val exclusions = listOf("ERROR: HTTP Error 429", "ERROR: HTTP Error 503", "ERROR: connection reset",
            "ERROR: network is unreachable", "ERROR: no space left on device", "ERROR: Postprocessing failed",
            "ERROR: HTTP Error 403: connection reset", "ERROR: HTTP Error 403: Postprocessing failed",
            "ERROR: Interrupted by user", "[Merger] Merging formats into output.mp4",
            "WARNING: HTTP Error 503", "WARNING: connection reset",
            "[download] Got error: HTTP Error 503. Retrying (1/5)",
            "[download] Got error: connection reset. Retrying (1/5)")
        for (excluded in exclusions) for (expiryFirst in listOf(true, false)) {
            val run = Run(cancellationPollMs = 1); run.inspected()
            run.runtime.action = { _, line ->
                val expiry = "ERROR: unable to download video data: HTTP Error 403"
                (if (expiryFirst) listOf(expiry, excluded) else listOf(excluded, expiry)).forEach(line)
                run.partial(); throw IllegalStateException()
            }
            assertIs<TransferResult.Failure>(run.download())
            assertEquals(1, run.runtime.transfers.size, excluded)
            assertTrue(run.fast!!.calls.isEmpty(), excluded)
            assertTrue(run.events.none { it is EngineEvent.Restarting }, excluded)
            assertEquals("abandoned bytes", File(run.dir, "output.f137.mp4.part").readText(), excluded)
        }
        for (error in listOf(DownloadError.SaveFailed, DownloadError.FolderUnavailable, DownloadError.ExecutionRestricted,
            DownloadError.Interrupted, DownloadError.Cancelled, DownloadError.MergeFailed, DownloadError.Network, DownloadError.Offline)) {
            val run = Run(cancellationPollMs = 1); run.inspected()
            run.runtime.action = { _, line ->
                line("ERROR: unable to download video data: HTTP Error 403")
                run.partial()
                throw YtdlpExecutionFailure(error)
            }
            assertEquals(error, assertIs<TransferResult.Failure>(run.download()).error)
            assertEquals(1, run.runtime.transfers.size); assertTrue(run.fast!!.calls.isEmpty())
            assertEquals("abandoned bytes", File(run.dir, "output.f137.mp4.part").readText(), error.name)
        }
    }

    @Test fun exclusionsAlsoBlockRejectionAndLegacyPageExpiryRecovery() = runBlocking {
        for (inspected in listOf(true, false)) {
            val run = Run(fast = if (inspected) Fast { json() } else null, cancellationPollMs = 1)
            if (inspected) run.inspected()
            run.runtime.action = { _, line ->
                line("ERROR: HTTP Error 503")
                run.partial()
                throw YtdlpExecutionFailure(DownloadError.FormatExpired, expiredUrl = !inspected)
            }
            assertIs<TransferResult.Failure>(run.download())
            assertEquals(1, run.runtime.transfers.size)
            assertTrue(run.events.none { it is EngineEvent.Restarting })
            assertEquals("abandoned bytes", File(run.dir, "output.f137.mp4.part").readText())
        }
    }

    @Test fun unreachableRebuildFailsAsTransportWithoutPageTransfer() = runBlocking {
        val unreachable = Run(fast = Fast { FastResult.Unreachable }, cancellationPollMs = 1)
        val failure = assertIs<TransferResult.Failure>(unreachable.download())
        assertEquals(DownloadError.Network, failure.error)
        assertEquals(DownloadFailureCause.TRANSPORT, failure.cause)
        assertTrue(unreachable.runtime.transfers.isEmpty())
        assertNull(unreachable.cache.freshFile("job"))
        // Expiry recovery: the rebuild sees a dead network, so no page-URL attempt follows.
        val run = Run(cancellationPollMs = 1); run.inspected()
        run.fast!!.action = { FastResult.Unreachable }
        run.runtime.action = { _, line -> run.failAttempt(line) }
        assertEquals(DownloadError.Network, assertIs<TransferResult.Failure>(run.download()).error)
        assertEquals(1, run.runtime.transfers.size)
        assertEquals(1, run.fast.calls.size)
        assertNull(run.cache.freshFile("job"))
    }

    @Test fun cancelAfterRebuildReturnsButBeforeThePostStoreCheckLeavesNoInfoAndNoTransfer() = runBlocking {
        var armed = false
        lateinit var run: Run
        // The cache reads the clock inside store(): cancel exactly between NPE's return and the post-store check.
        run = Run(clock = { if (armed) { armed = false; run.engine.cancel("job") }; System.currentTimeMillis() }, cancellationPollMs = 1)
        run.fast!!.action = { armed = true; json() }
        assertEquals(DownloadError.Cancelled, assertIs<TransferResult.Failure>(run.download()).error)
        assertFalse(armed)
        assertNull(run.cache.freshFile("job"))
        assertTrue(run.runtime.transfers.isEmpty())
        assertTrue(run.fast.cancelled.isEmpty())
    }

    @Test fun typedRuntimeFailurePreservesStickyExclusionWithoutPrivateDiagnostics() {
        val diagnostic = YtdlpDiagnostics()
        diagnostic.accept("ERROR: connection reset https://example.org/private-fixture")
        diagnostic.accept("ERROR: unable to download video data: HTTP Error 403")
        val native = diagnostic.failure()
        val wrapped = YtdlpDiagnostics().failure(native)
        assertTrue(wrapped.reextractionBlocked)
        assertTrue(wrapped.expiredUrl)
        assertNull(wrapped.message); assertNull(wrapped.cause)
    }

    @Test fun tagRetryOnThirdTransferKeepsCompletedAudioWithoutSpendingRecoveryBudget() = runBlocking {
        val run = Run(command(Format("140", "m4a", false, true)), cancellationPollMs = 1)
        run.inspected()
        val output = File(run.dir, "output.m4a")
        run.runtime.action = { args, line ->
            when (run.runtime.transfers.size) {
                1, 2 -> run.failAttempt(line)
                3 -> {
                    run.assertClean(2); assertPage(args)
                    assertTrue("--embed-thumbnail" in args)
                    output.writeText("completed audio")
                    File(run.dir, "output.jpg").writeText("artwork")
                    File(run.dir, "result-path.txt").writeText("stale")
                    line("[EmbedThumbnail] Adding thumbnail")
                    throw IllegalStateException("ERROR: Postprocessing: cover failed")
                }
                else -> {
                    assertFalse("--embed-thumbnail" in args)
                    assertPage(args)
                    assertEquals("completed audio", output.readText())
                    assertEquals(listOf("output.m4a"), run.dir.list()!!.toList())
                    File(run.dir, "result-path.txt").writeText(output.path)
                }
            }
        }
        assertEquals(output.canonicalFile, assertIs<TransferResult.Success>(run.download()).output.file.canonicalFile)
        assertEquals(4, run.runtime.transfers.size)
        assertEquals(1, run.fast!!.calls.size)
        assertEquals(listOf(1L, 2L), run.events.filterIsInstance<EngineEvent.Restarting>().map { it.restartEpoch })
    }

    @Test fun rejectionAndRebuildExpiryRemoveDurableOriginBeforeInterruptedPageAndRestart() = runBlocking {
        for (expiry in listOf(true, false)) {
            val run = Run(cancellationPollMs = 1)
            run.runtime.action = { args, line ->
                if (run.runtime.transfers.size == 1) {
                    assertNotNull(info(args))
                    run.failAttempt(line, if (expiry) "ERROR: media URL expired" else "ERROR: Requested format is not available")
                }
                run.assertClean(1)
                assertPage(args); assertFalse(File(run.root, "ytdlp-info/job.npe.json").exists())
                run.partial(); throw YtdlpExecutionFailure(DownloadError.Interrupted)
            }
            assertEquals(DownloadError.Interrupted, assertIs<TransferResult.Failure>(run.download()).error)
            // The checked page JSON is durable: a resume continues its bytes without another page read.
            val resumed = Run(run.cmd, run.root, run.dir, fast = null, cancellationPollMs = 1)
            resumed.runtime.action = { args, _ ->
                assertPage(args)
                assertTrue(File(resumed.dir, "output.f137.mp4.part").delete()) // yt-dlp finishes the kept part.
                Runtime.output(args)
            }
            assertIs<TransferResult.Success>(resumed.download())
            assertTrue(resumed.runtime.pages.isEmpty())
            assertEquals(listOf("job.json"), File(run.root, "ytdlp-info").list()!!.toList())
        }
    }

    @Test fun removalFailureStopsPageFallbackWithExecutionRestricted() = runBlocking {
        for (expiry in listOf(true, false)) {
            val run = Run(cancellationPollMs = 1)
            if (!expiry) run.inspected()
            run.runtime.action = { args, line ->
                val file = assertNotNull(info(args))
                assertTrue(file.delete()); assertTrue(file.mkdir())
                File(file, "blocked").writeText("fixture")
                run.failAttempt(line, if (expiry) "ERROR: media URL expired" else "ERROR: Requested format is not available")
            }
            assertEquals(DownloadError.ExecutionRestricted, assertIs<TransferResult.Failure>(run.download()).error)
            assertEquals(1, run.runtime.transfers.size)
            assertEquals(if (expiry) 1 else 0, run.fast!!.calls.size)
        }
    }

    @Test fun survivingStaleOriginStopsInitialPageFallbackBeforeAnyTransfer() = runBlocking {
        val run = Run(fast = Fast { null }, cancellationPollMs = 1)
        File(run.root, "ytdlp-info/job.npe.json").apply {
            mkdirs(); File(this, "blocked").writeText("fixture")
        }
        assertEquals(DownloadError.ExecutionRestricted, assertIs<TransferResult.Failure>(run.download()).error)
        assertTrue(run.runtime.transfers.isEmpty())
    }

    @Test fun rebuiltInfoAndOriginSurviveNewCacheWithinTtlButExpiredResumeRebuilds() = runBlocking {
        var now = 10_000_000L
        val run = Run(clock = { now }, cancellationPollMs = 1)
        run.runtime.action = { _, _ -> run.partial(); throw YtdlpExecutionFailure(DownloadError.Interrupted) }
        assertEquals(DownloadError.Interrupted, assertIs<TransferResult.Failure>(run.download()).error)
        val stored = assertNotNull(run.cache.freshFile("job"))
        val resume = Run(run.cmd, run.root, run.dir, clock = { now }, cancellationPollMs = 1)
        resume.runtime.action = { args, _ ->
            assertEquals(stored, info(args))
            assertTrue(resume.dir.listFiles()!!.isNotEmpty())
            throw YtdlpExecutionFailure(DownloadError.Unsupported)
        }
        // Durable origin: rejection after process restart takes the page path without a new rebuild.
        var attempt = 0
        val rejection = resume.runtime.action
        resume.runtime.action = { args, line ->
            if (++attempt == 1) rejection(args, line)
            else { resume.assertClean(1); assertPage(args); Runtime.output(args) }
        }
        assertIs<TransferResult.Success>(resume.download())
        assertTrue(resume.fast!!.calls.isEmpty())

        val paused = Run(clock = { now }, cancellationPollMs = 1)
        paused.runtime.action = { _, _ -> paused.partial(); throw YtdlpExecutionFailure(DownloadError.Interrupted) }
        assertIs<TransferResult.Failure>(paused.download())
        now += YtdlpInfoCache.TTL_MS
        val expired = Run(paused.cmd, paused.root, paused.dir, clock = { now }, cancellationPollMs = 1)
        expired.fast!!.action = { expired.assertClean(1); json() }
        assertIs<TransferResult.Success>(expired.download())
        assertEquals(1, expired.fast.calls.size)
        assertEquals(setOf("137", "140"), ids(assertNotNull(expired.cache.freshFile("job"))))
    }

    @Test fun cancelDuringRebuildLeavesNoInfoAndDoesNotLaunchTransfer() = runBlocking {
        for (inspected in listOf(false, true)) {
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val run = Run(fast = Fast { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); json() }, cancellationPollMs = 1)
            if (inspected) {
                run.inspected()
                run.runtime.action = { _, line -> run.failAttempt(line) }
            }
            try {
                val task = async(Dispatchers.Default) { run.download() }
                assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
                run.engine.cancel("job")
                assertEquals(DownloadError.Cancelled, assertIs<TransferResult.Failure>(withTimeout(2000) { task.await() }).error)
                assertEquals(listOf("job"), run.fast!!.cancelled)
                assertNull(run.cache.freshFile("job"))
                assertEquals(if (inspected) 1 else 0, run.runtime.transfers.size)
            } finally { release.countDown() }
        }
    }

    @Test fun rebuildDisarmsTheFailedTransfersDeadline() = runBlocking {
        val run = Run(limits = EngineLimits(firstByteMs = 150, npeBudgetMs = 1000), cancellationPollMs = 1)
        run.inspected()
        run.fast!!.action = {
            // Longer than the previous attempt's byte budget, shorter than NPE's own budget.
            assertFalse(CountDownLatch(1).await(350, TimeUnit.MILLISECONDS))
            json()
        }
        run.runtime.action = { args, line ->
            if (run.runtime.transfers.size == 1) run.failAttempt(line)
            run.assertClean(1); assertNotNull(info(args)); Runtime.output(args)
        }
        assertIs<TransferResult.Success>(run.download())
        assertEquals(2, run.runtime.transfers.size)
        assertTrue(run.fast.cancelled.isEmpty())
    }

    @Test fun timeoutAndOccupiedFastSlotUsePageWithFullTransferBudget() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val run = Run(fast = Fast { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); json() },
            limits = EngineLimits(npeBudgetMs = 300, firstByteMs = 200))
        try {
            assertIs<TransferResult.Success>(withTimeout(3000) { run.download() })
            assertEquals(0L, entered.count)
            assertEquals(listOf("job"), run.fast!!.cancelled)
            assertPage(run.runtime.transfers.single())
            assertIs<TransferResult.Success>(withTimeout(2000) {
                run.engine.download(command(job = "second"), temp.newFolder()) {}
            })
            assertEquals(1, run.fast.calls.size)
            assertPage(run.runtime.transfers.last(), "second")
            assertEquals(1L, release.count)
        } finally { release.countDown() }
    }

    @Test fun formatExpiredAfterExpiryDropsTheRecentReadSoTheSameLinkIsReadAgain() = runBlocking {
        val run = Run(cancellationPollMs = 1)
        val inspection = assertIs<InspectResult.Success>(run.engine.inspect(source, "first")).inspection
        val chosen = inspection.formats.first { it.requiresMerge }
        run.cmd = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job", inspection, source, chosen.id, "Fixture")).command
        run.engine.prepareDownload(run.cmd)
        // Before the failure a repaste is served from memory.
        assertIs<InspectResult.Success>(run.engine.inspect(source, "hit"))
        assertEquals(1, run.fast!!.calls.size)
        run.fast.action = { null }
        val page = ObjectMapper().readTree(json()) as com.fasterxml.jackson.databind.node.ObjectNode
        val formats = page.path("formats") as com.fasterxml.jackson.databind.node.ArrayNode
        for (i in formats.size() - 1 downTo 0) if (formats[i].path("format_id").asText() == chosen.sourceFormatId) formats.remove(i)
        run.runtime.page = { page.toString() }
        run.runtime.action = { _, line -> run.failAttempt(line) }
        assertEquals(DownloadError.FormatExpired, assertIs<TransferResult.Failure>(run.download()).error)
        assertEquals(1, run.runtime.transfers.size)
        // The dead read is gone: the same link reads YouTube again.
        val calls = run.fast.calls.size
        run.fast.action = { json() }
        assertIs<InspectResult.Success>(run.engine.inspect(source, "repaste"))
        assertEquals(calls + 1, run.fast.calls.size)
    }

    @Test fun aFinal403OnThePagePathStillDropsTheRecentRead() = runBlocking {
        val run = Run(cancellationPollMs = 1)
        val inspection = assertIs<InspectResult.Success>(run.engine.inspect(source, "first")).inspection
        val chosen = inspection.formats.first { it.requiresMerge }
        run.cmd = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job", inspection, source, chosen.id, "Fixture")).command
        assertIs<InspectResult.Success>(run.engine.inspect(source, "hit"))
        assertEquals(1, run.fast!!.calls.size)
        // No confirmed info file: the page path, where a 403 with no bytes is final.
        run.runtime.action = { _, line -> line("ERROR: [youtube] x: Unable to download webpage: HTTP Error 403: Forbidden"); throw IllegalStateException() }
        assertIs<TransferResult.Failure>(run.download())
        // Final at the first attempt: no restart removed the read on the way.
        assertEquals(1, run.runtime.transfers.size)
        val calls = run.fast!!.calls.size
        assertIs<InspectResult.Success>(run.engine.inspect(source, "repaste"))
        assertEquals(calls + 1, run.fast.calls.size)
    }
}
