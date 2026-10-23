package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class YoutubedlEngineTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = LinkRevision(assertNotNull(LinkInput.parse("https://example.org/video")), 1)
    private val row = Format("18", "mp4", true, true, size = Size.Exact(3))
    private fun command(version: String = "2025.11.12") = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm(
        "job-1", Inspection(source, version, listOf(row)), source, row.id, "title")).command
    private class Runtime(override val initialization: EngineInitialization = EngineInitialization()) : YtdlpRuntime {
        var starts = 0
        var failsInitialization = false
        var updates = 0
        var version = "2025.11.12"
        val calls = mutableListOf<List<String>>()
        val destroys = AtomicInteger()
        var action: (List<String>, (String) -> Unit) -> String = { _, _ -> "" }
        override fun initialize() { starts++; if (failsInitialization) throw UnsatisfiedLinkError("missing ABI") }
        override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
            calls += args
            if (args.contains("--version")) return version
            return action(args, line)
        }
        override fun update() { updates++; version = "2026.08.19" }
        override fun destroy(processId: String): Boolean { destroys.incrementAndGet(); return true }
    }
    @Test fun runtimeReceivesExactConversionSourceAndScopedEncodingOptions() = runBlocking {
        for (container in listOf("mp3", "m4a", "webm", "mp4", null)) {
            for ((conversion, scoped) in listOf(
                AudioConversion.MP3_128 to "ExtractAudio+ffmpeg_o1:-c:a libmp3lame -b:a 128k",
                AudioConversion.MP3_320 to "ExtractAudio+ffmpeg_o1:-c:a libmp3lame -b:a 320k -ar 44100")) {
                val dir = temp.newFolder().canonicalFile
                val selected = Format("conversion", "mp3", false, true, conversion = conversion,
                    sourceFormatId = "exact-source", sourceContainer = container)
                val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("converted-job",
                    Inspection(source, "2025.11.12", listOf(selected)), source, selected.id, "title")).command
                val runtime = Runtime().apply { action = { _, _ ->
                    File(dir, "output.mp3").writeText("fixture, no encoding effect claimed")
                    File(dir, "result-path.txt").writeText(File(dir, "output.mp3").path)
                    ""
                } }
                assertIs<TransferResult.Success>(YoutubedlEngine(runtime).download(command, dir) {})
                val args = runtime.calls.single { "--no-simulate" in it }
                assertEquals("exact-source", args[args.indexOf("-f") + 1])
                assertEquals(1, args.count { it == "--postprocessor-args" })
                assertEquals(scoped, args[args.indexOf("--postprocessor-args") + 1])
                assertEquals(File(dir, if (container == "mp3") "output" else "output.%(ext)s").path, args[args.indexOf("-o") + 1])
                assertEquals("converted-job", command.jobId)
            }
        }
    }

    @Test fun transferFailureCarriesTransportOrHttpCauseWithoutRawDiagnostics() = runBlocking {
        for (message in listOf("Connection reset by peer", "The read operation timed out",
            "fragment 3 not found, unable to continue", "Network is unreachable",
            "HTTP Error 429: Too Many Requests", "HTTP Error 503: Service Unavailable")) {
            for (callback in listOf(false, true)) {
                val runtime = Runtime().apply { action = { _, line ->
                    if (callback) { line("ERROR: $message"); throw YtdlpDiagnostics().apply { accept("ERROR: $message") }.failure() }
                    else error("ERROR: $message")
                } }
                val result = assertIs<TransferResult.Failure>(YoutubedlEngine(runtime).download(command(), temp.newFolder()) {})
                assertEquals(if (message.startsWith("HTTP")) DownloadFailureCause.HTTP else DownloadFailureCause.TRANSPORT, result.cause)
                assertEquals(if (message.startsWith("HTTP")) DownloadError.Failed
                    else if (message.startsWith("Network")) DownloadError.Offline else DownloadError.Network, result.error)
            }
        }
    }

    @Test fun fragmentRetryHttpClassSurvivesWatchdogTimeout() = runBlocking {
        for (status in listOf(429, 503)) {
            val stopped = CountDownLatch(1)
            val runtime = object : YtdlpRuntime {
                override val initialization = EngineInitialization()
                override fun initialize() = Unit
                override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
                    if ("--version" in args) return "2025.11.12"
                    line("nezzel:18:1:3:NA")
                    repeat(3) {
                        line("[download] Got error: HTTP Error $status: https://example.org/private. Retrying fragment 2 (1/10)...")
                    }
                    check(stopped.await(5, TimeUnit.SECONDS))
                    error("process stopped")
                }
                override fun destroy(processId: String): Boolean { stopped.countDown(); return true }
                override fun update() = Unit
            }
            try {
                val result = assertIs<TransferResult.Failure>(YoutubedlEngine(runtime,
                    EngineLimits(stallMs = 100, firstByteMs = 3000)).download(command(), temp.newFolder()) {})
                assertEquals(DownloadError.Failed, result.error)
                assertEquals(DownloadFailureCause.HTTP, result.cause)
                assertFalse(result.resumeAllowed)
            } finally { stopped.countDown() }
        }
    }

    @Test fun httpDiagnosticVetoesLaterTransportAndFragmentFailures() = runBlocking {
        for (status in listOf(429, 503)) {
            val runtime = Runtime().apply { action = { _, line ->
                line("ERROR: unable to download fragment: HTTP Error $status")
                line("ERROR: Connection reset by peer")
                error("ERROR: fragment 3 not found, unable to continue")
            } }
            val result = assertIs<TransferResult.Failure>(YoutubedlEngine(runtime).download(command(), temp.newFolder()) {})
            assertEquals(DownloadFailureCause.HTTP, result.cause); assertFalse(result.resumeAllowed)
        }
        assertEquals(DownloadFailureCause.OTHER, YtdlpErrorMapper.cause("WARNING: Connection timed out"))
        assertEquals(DownloadFailureCause.OTHER, YtdlpErrorMapper.cause("ERROR: unknown failure https://example.org/connection-reset/HTTP-Error-503"))
        assertEquals(DownloadFailureCause.HTTP, YtdlpErrorMapper.cause("ERROR: unable to download fragment: HTTP Error 404"))
        assertEquals(DownloadFailureCause.OTHER, YtdlpErrorMapper.cause("ERROR: postprocessing: Connection reset by peer"))
    }

    @Test fun initializationIsLazyAndFailureUsesUnavailableFallbackDuringBackoff() = runBlocking {
        val runtime = Runtime(); val engine = YoutubedlEngine(runtime)
        assertTrue(engine.isAvailable); assertEquals(0, runtime.starts)
        val initialization = EngineInitialization()
        val count = AtomicInteger()
        val results = (1..8).map { async(Dispatchers.Default) { initialization.ensure { count.incrementAndGet(); throw UnsatisfiedLinkError("missing ABI") } } }.awaitAll()
        assertTrue(results.none { it }); assertEquals(1, count.get()); assertFalse(initialization.isAvailable)
        runtime.initialization.ensure { throw IllegalStateException("init failed") }
        assertFalse(engine.isAvailable)
        assertEquals(InspectResult.Failure(DownloadError.EngineUnavailable), engine.inspect(source, "inspect"))
        assertTrue(runtime.calls.isEmpty())
    }
    @Test fun warmUpInitializesOnceAndOnlyRunsLocalVersionWithoutUpdating() = runBlocking {
        val runtime = Runtime(); val engine = YoutubedlEngine(runtime)
        repeat(3) { assertEquals(true, engine.warmUp()) }
        assertEquals(1, runtime.starts)
        assertEquals(listOf(listOf("--ignore-config", "--version")), runtime.calls); assertEquals(0, runtime.updates)
        val failed = Runtime().apply { failsInitialization = true }
        assertEquals(false, YoutubedlEngine(failed).warmUp())
        assertEquals(1, failed.starts); assertFalse(failed.initialization.isAvailable); assertTrue(failed.calls.isEmpty())
        assertEquals(false, UnavailableEngine().warmUp())
    }
    @Test fun inspectUsesMetadataOnlyAndInitializesOnceAcrossCalls() = runBlocking {
        val runtime = Runtime().apply { action = { _, _ -> """{"formats":[{"format_id":"18","ext":"mp4","vcodec":"h264","acodec":"aac"}]}""" } }
        val engine = YoutubedlEngine(runtime)
        repeat(2) {
            engine.clearRecentLinks() // each call extracts
            assertEquals("18", assertIs<InspectResult.Success>(engine.inspect(source, "inspect-$it")).inspection.formats.single { it.id == "18" }.id)
        }
        assertEquals(1, runtime.starts); assertEquals(0, runtime.updates)
        assertEquals(2, runtime.calls.count { it.contains("--dump-json") && it.contains("--skip-download") })
        assertTrue(runtime.calls.none { it.contains("--no-simulate") })
    }
    @Test fun photoExtractorReturnedNothingMapsEmptyInspectionToNoVideo() = runBlocking {
        for (extractor in listOf("telegram:embed", "Bluesky", "instagram", "twitter", "TikTok")) {
            for (output in listOf("", " \n", """{"formats":[]}""",
                """{"_type":"playlist","entries":[]}""", """{"_type":"multi_video","entries":[]}""")) {
                val runtime = Runtime().apply { action = { _, line ->
                    line("WARNING: Extractor $extractor returned nothing")
                    output
                } }
                assertEquals(InspectResult.Failure(DownloadError.NoVideo), YoutubedlEngine(runtime).inspect(source, "photo"),
                    "$extractor / $output")
            }
        }
    }
    /** On the device a class-init or recursion Error escaped catch (Exception) and closed the app. */
    @Test fun anErrorFromTheEngineOrItsCallbackIsAFailedReadNotACrash() = runBlocking {
        for (callback in listOf(false, true)) for (error in listOf<() -> Throwable>(
            { ExceptionInInitializerError(IllegalStateException("init")) }, { NoClassDefFoundError("Missing") }, { StackOverflowError() })) {
            val runtime = Runtime().apply { action = { _, line ->
                if (callback) { line("[download] 1%"); "" } else throw error()
            } }
            val engine = YoutubedlEngine(runtime)
            if (callback) {
                // The progress callback itself fails on the reader thread.
                val failing = Runtime().apply { action = { _, line -> line("x"); "" } }
                val result = YoutubedlEngine(failing).download(command(), temp.newFolder()) { throw error() }
                assertIs<TransferResult.Failure>(result)
            } else assertIs<InspectResult.Failure>(engine.inspect(source, "error"))
        }
    }
    @Test fun warningOnlyEmptyExceptionStillMapsPhotoPostToNoVideo() = runBlocking {
        for (callback in listOf(true, false)) {
            val runtime = Runtime().apply { action = { _, line ->
                val warning = "WARNING: Extractor telegram:embed returned nothing"
                if (callback) line(warning)
                throw IllegalStateException(if (callback) "" else warning)
            } }
            assertEquals(InspectResult.Failure(DownloadError.NoVideo), YoutubedlEngine(runtime).inspect(source, "photo"))
        }
    }
    @Test fun emptyInspectionOfBareSiteRootMapsToNotAPost() = runBlocking {
        for (url in listOf("https://site.example", "https://site.example/", "https://site.example/?_r=1")) {
            val root = LinkRevision(assertNotNull(LinkInput.parse(url)), 1)
            for (output in listOf("", """{"formats":[]}""", """{"_type":"playlist","entries":[]}""")) {
                val runtime = Runtime().apply { action = { _, _ -> output } }
                assertEquals(InspectResult.Failure(DownloadError.NotAPost), YoutubedlEngine(runtime).inspect(root, "root"), url)
            }
            val runtime = Runtime().apply { action = { _, _ -> throw IllegalStateException("") } }
            assertEquals(InspectResult.Failure(DownloadError.NotAPost), YoutubedlEngine(runtime).inspect(root, "root-error"))
        }
    }
    @Test fun emptyInspectionKeepsContentQueriesUnknownExtractorsAndRealErrorsUnchanged() = runBlocking {
        for (url in listOf("https://site.example/?v=sample", "https://site.example/?id=sample", "https://site.example/post")) {
            val post = LinkRevision(assertNotNull(LinkInput.parse(url)), 1)
            for ((output, expected) in listOf("" to DownloadError.Failed, """{"formats":[]}""" to DownloadError.NoFormats)) {
                for (extractor in listOf("generic", "telegramish", "youtube")) {
                    val runtime = Runtime().apply { action = { _, line ->
                        line("WARNING: Extractor $extractor returned nothing"); output
                    } }
                    assertEquals(InspectResult.Failure(expected), YoutubedlEngine(runtime).inspect(post, "unknown"), url)
                }
            }
        }
        val root = LinkRevision(assertNotNull(LinkInput.parse("https://site.example/")), 1)
        for ((error, expected) in listOf("ERROR: Connection timed out" to DownloadError.Network,
            "ERROR: Login required" to DownloadError.LoginRequired, "ERROR: HTTP Error 429" to DownloadError.Failed,
            "ERROR: Unknown failure" to DownloadError.Failed)) {
            val runtime = Runtime().apply { action = { _, line ->
                line("WARNING: Extractor telegram:embed returned nothing")
                throw IllegalStateException(error)
            } }
            assertEquals(InspectResult.Failure(expected), YoutubedlEngine(runtime).inspect(root, "error"), error)
        }
        // A warning must not replace real formats, malformed JSON, or nonempty playlist rejection.
        for ((output, expected) in listOf("{" to DownloadError.Failed,
            """{"_type":"playlist","entries":[{}]}""" to DownloadError.Unsupported)) {
            val runtime = Runtime().apply { action = { _, line ->
                line("WARNING: Extractor telegram:embed returned nothing"); output
            } }
            assertEquals(InspectResult.Failure(expected), YoutubedlEngine(runtime).inspect(root, "invalid"))
        }
        for (output in listOf("", """{"formats":[]}""")) {
            val runtime = Runtime().apply { action = { _, line ->
                line("WARNING: Extractor telegram:embed returned nothing")
                line("ERROR: Unknown failure"); output
            } }
            assertEquals(InspectResult.Failure(if (output.isEmpty()) DownloadError.Failed else DownloadError.NoFormats),
                YoutubedlEngine(runtime).inspect(root, "error-output"))
        }
        val runtime = Runtime().apply { action = { _, line ->
            line("WARNING: Extractor telegram:embed returned nothing")
            """{"formats":[{"format_id":"18","ext":"mp4","vcodec":"h264","acodec":"aac"}]}"""
        } }
        assertIs<InspectResult.Success>(YoutubedlEngine(runtime).inspect(root, "success"))
        Unit
    }
    @Test fun downloadReturnsActualContainedNonemptyOutputAndRealByteEvents() = runBlocking {
        val dir = temp.newFolder()
        val runtime = Runtime().apply { action = { _, line ->
            File(dir, "output.mp4").writeBytes(byteArrayOf(1, 2, 3))
            File(dir, "result-path.txt").writeText(File(dir, "output.mp4").path + "\n")
            line("nezzel:18:3:3:NA"); ""
        } }
        val events = mutableListOf<EngineEvent>()
        val result = YoutubedlEngine(runtime).download(command(), dir, events::add)
        assertEquals(File(dir, "output.mp4").canonicalFile, assertIs<TransferResult.Success>(result).output.file)
        assertEquals<List<EngineEvent>>(listOf(EngineEvent.Progress(3, Size.Exact(3)), EngineEvent.Merging), events)
        assertEquals("18", runtime.calls.last().let { it[it.indexOf("-f") + 1] })
    }
    @Test fun outputEscapeAndStaleEngineVersionCannotSucceed() = runBlocking {
        val dir = temp.newFolder(); val outside = temp.newFile().apply { writeText("outside") }
        val runtime = Runtime().apply { action = { _, _ ->
            File(dir, "output.mp4").writeText("inside")
            File(dir, "result-path.txt").writeText(outside.path); ""
        } }
        val engine = YoutubedlEngine(runtime)
        assertEquals(TransferResult.Failure(DownloadError.FormatExpired), engine.download(command("old"), dir) {})
        assertTrue(runtime.calls.none { it.contains("--no-simulate") })
        assertEquals(TransferResult.Failure(DownloadError.ExecutionRestricted), engine.download(command(), dir) {})
        assertEquals("outside", outside.readText())
    }
    @Test fun exactSingleStreamRejectsTruncatedOutputBeforeReturningItForPublication() = runBlocking {
        for ((expected, actual, succeeds) in listOf(Triple(1000L, 0, false), Triple(1000L, 979, false), Triple(1000L, 980, true),
            Triple(1000L, 1000, true), Triple(1000L, 1100, true), Triple(101L, 98, false),
            Triple(101L, 99, true), Triple(Long.MAX_VALUE, 1000, false))) {
            for (format in listOf(row.copy(size = Size.Exact(expected)),
                Format("audio", "m4a", false, true, size = Size.Exact(expected)))) {
                val dir = temp.newFolder().canonicalFile
                val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job",
                    Inspection(source, "2025.11.12", listOf(format)), source, format.id, "title")).command
                val runtime = Runtime().apply { action = { _, line ->
                    File(dir, "output.${format.container}").writeBytes(ByteArray(actual))
                    File(dir, "result-path.txt").writeText(File(dir, "output.${format.container}").path)
                    // A final progress report must not override the exact inspection size.
                    line("nezzel:${format.id}:$actual:$actual:NA"); ""
                } }
                val result = YoutubedlEngine(runtime).download(command, dir) {}
                if (succeeds) assertEquals(actual.toLong(), assertIs<TransferResult.Success>(result).output.file.length())
                else assertEquals(TransferResult.Failure(DownloadError.MergeFailed), result)
            }
        }
    }
    @Test fun outputSizeGuardSkipsMergesConversionsExtractionEstimatesAndUnknownSizes() = runBlocking {
        val rows = listOf(row.copy(size = Size.Estimated(1000)), row.copy(size = Size.Unknown),
            row.copy(size = Size.Exact(1000), hasAudio = false, audioFormatId = "140", requiresMerge = true),
            Format("converted", "mp3", false, true, size = Size.Exact(1000),
                conversion = AudioConversion.MP3_128, sourceFormatId = "140"),
            Format("extracted", "m4a", false, true, size = Size.Exact(1000), sourceFormatId = "18"))
        for (format in rows) {
            val dir = temp.newFolder().canonicalFile
            val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job",
                Inspection(source, "2025.11.12", listOf(format)), source, format.id, "title")).command
            val runtime = Runtime().apply { action = { _, _ ->
                File(dir, "output.${format.container}").writeBytes(ByteArray(10))
                File(dir, "result-path.txt").writeText(File(dir, "output.${format.container}").path); ""
            } }
            assertEquals(10L, assertIs<TransferResult.Success>(YoutubedlEngine(runtime).download(command, dir) {}).output.file.length())
        }
    }
    @Test fun extraOutputsAndNonliteralExtensionsFailWithoutDeletingAnything() = runBlocking {
        for (names in listOf(listOf("output.mp4", "extra.m4a"), listOf("output.mp4.exe"),
            listOf("output.mp4", "output.f140.m4a"), listOf("output.mp4", "nested"))) {
            val dir = temp.newFolder()
            val runtime = Runtime().apply { action = { _, _ ->
                names.forEach { name -> File(dir, name).writeText("preserve") }
                File(dir, "result-path.txt").writeText(File(dir, names.first()).path)
                ""
            } }
            assertEquals(TransferResult.Failure(DownloadError.ExecutionRestricted), YoutubedlEngine(runtime).download(command(), dir) {})
            names.forEach { assertEquals("preserve", File(dir, it).readText()) }
        }
    }
    @Test fun symlinkedOutputOrManifestReturnsExecutionRestrictedAndPreservesTargets() = runBlocking {
        for (symlinkManifest in listOf(false, true)) {
            val dir = temp.newFolder().canonicalFile
            val outside = temp.newFile().apply { writeText("preserve") }
            val runtime = Runtime().apply { action = { _, _ ->
                val output = File(dir, "output.mp4")
                val manifest = File(dir, "result-path.txt")
                if (symlinkManifest) {
                    output.writeText("media")
                    outside.writeText(output.path)
                    java.nio.file.Files.createSymbolicLink(manifest.toPath(), outside.toPath())
                } else {
                    java.nio.file.Files.createSymbolicLink(output.toPath(), outside.toPath())
                    manifest.writeText(output.path)
                }
                ""
            } }
            assertEquals(TransferResult.Failure(DownloadError.ExecutionRestricted),
                YoutubedlEngine(runtime).download(command(), dir) {})
            assertEquals(if (symlinkManifest) File(dir, "output.mp4").path else "preserve", outside.readText())
        }
    }
    @Test fun cancelBeforeStartPreventsNativeInitializationAndExecution() = runBlocking {
        val runtime = Runtime(); val engine = YoutubedlEngine(runtime)
        engine.cancel("job-1"); engine.cancel("inspect")
        assertEquals(TransferResult.Failure(DownloadError.Cancelled), engine.download(command(), temp.newFolder()) {})
        assertEquals(InspectResult.Failure(DownloadError.Cancelled), engine.inspect(source, "inspect"))
        assertEquals(0, runtime.starts); assertTrue(runtime.calls.isEmpty())
    }
    @Test fun cancelRetriesUntilNativeProcessRegistersAndSuppressesLateProgress() = runBlocking {
        val entered = CountDownLatch(1); val registered = CountDownLatch(1)
        val runtime = object : YtdlpRuntime {
            override val initialization = EngineInitialization()
            val destroys = AtomicInteger()
            override fun initialize() = Unit
            override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
                if (args.contains("--version")) return "2025.11.12"
                entered.countDown()
                check(registered.await(3, TimeUnit.SECONDS))
                line("nezzel:18:2:3:NA")
                throw IllegalStateException("process destroyed")
            }
            override fun destroy(processId: String): Boolean {
                if (destroys.incrementAndGet() < 2) return false
                registered.countDown(); return true
            }
            override fun update() = Unit
        }
        val engine = YoutubedlEngine(runtime)
        val transfer = async(Dispatchers.Default) { engine.download(command(), temp.newFolder()) { fail("late progress") } }
        assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
        engine.cancel("job-1")
        assertEquals(TransferResult.Failure(DownloadError.Cancelled), withTimeout(4000) { transfer.await() })
        assertTrue(runtime.destroys.get() >= 2)
        // The same ID is a fresh attempt once the cancelled operation reaches terminal state.
        assertEquals(InspectResult.Failure(DownloadError.Failed), engine.inspect(source, "job-1"))
    }
    @Test fun coroutineCancellationDestroysNativeProcessAndPropagates() = runBlocking {
        val entered = CountDownLatch(1); val destroyed = CountDownLatch(1)
        val runtime = object : YtdlpRuntime {
            override val initialization = EngineInitialization()
            override fun initialize() = Unit
            override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
                if (args.contains("--version")) return "2025.11.12"
                entered.countDown(); check(destroyed.await(3, TimeUnit.SECONDS)); return ""
            }
            override fun destroy(processId: String): Boolean { destroyed.countDown(); return true }
            override fun update() = Unit
        }
        val task = async(Dispatchers.Default) { YoutubedlEngine(runtime).inspect(source, "inspect") }
        assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
        withTimeout(4000) { task.cancelAndJoin() }
        assertEquals(0, destroyed.count); assertTrue(task.isCancelled)
    }
    @Test fun multipleDocumentsAndPlaylistRootsAreTypedUnsupported() = runBlocking {
        val document = """{"format_id":"18","ext":"mp4","vcodec":"h264","acodec":"aac"}"""
        for (metadata in listOf("$document\n$document", "$document $document",
            """{"_type":"playlist","entries":[]}""", """{"_type":"multi_video","entries":[]}""")) {
            val runtime = Runtime().apply { action = { _, _ -> metadata } }
            assertEquals(InspectResult.Failure(DownloadError.Unsupported), YoutubedlEngine(runtime).inspect(source, "inspect"))
        }
    }
    @Test fun unknownCancellationExpiresAndCannotPoisonLaterReuse() = runBlocking {
        val runtime = Runtime().apply { action = { _, _ -> """{"format_id":"18","ext":"mp4","vcodec":"h264","acodec":"aac"}""" } }
        val engine = YoutubedlEngine(runtime)
        engine.cancel("never-registered")
        delay(750)
        assertIs<InspectResult.Success>(engine.inspect(source, "never-registered"))
        engine.cancel("cancelled-attempt")
        assertEquals(InspectResult.Failure(DownloadError.Cancelled), engine.inspect(source, "cancelled-attempt"))
        assertIs<InspectResult.Success>(engine.inspect(source, "cancelled-attempt"))
        Unit
    }
    @Test fun cancellationAfterExitDoesNotPoisonImmediateIdReuse() = runBlocking {
        val runtime = Runtime().apply { action = { _, _ -> """{"format_id":"18","ext":"mp4","vcodec":"h264","acodec":"aac"}""" } }
        val engine = YoutubedlEngine(runtime)
        assertIs<InspectResult.Success>(engine.inspect(source, "finished"))
        engine.cancel("finished")
        assertIs<InspectResult.Success>(engine.inspect(source, "finished"))
        assertEquals(0, runtime.destroys.get())
    }
    @Test fun cancellationWaitsForEscalationEvenWhenExecuteHasAlreadyUnwound() = runBlocking {
        val entered = CountDownLatch(1); val term = CountDownLatch(1)
        val unwound = CountDownLatch(1); val grace = CountDownLatch(1)
        val runtime = object : YtdlpRuntime {
            override val initialization = EngineInitialization()
            override fun initialize() = Unit
            override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
                if (args.contains("--version")) return "2025.11.12"
                entered.countDown(); check(term.await(3, TimeUnit.SECONDS))
                unwound.countDown(); error("root exited after TERM")
            }
            override fun destroy(processId: String): Boolean {
                term.countDown(); check(grace.await(3, TimeUnit.SECONDS)); return true
            }
            override fun update() = Unit
        }
        val engine = YoutubedlEngine(runtime)
        val task = async(Dispatchers.Default) { engine.inspect(source, "inspect") }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            engine.cancel("inspect")
            assertTrue(withContext(Dispatchers.IO) { unwound.await(3, TimeUnit.SECONDS) })
            delay(75)
            assertFalse(task.isCompleted, "caller must not clean staging during escalation")
        } finally { grace.countDown() }
        assertEquals(InspectResult.Failure(DownloadError.Cancelled), withTimeout(3000) { task.await() })
    }
    @Test fun updateIsExplicitAndReturnsActualOldAndNewVersions() = runBlocking {
        val runtime = Runtime(); val engine = YoutubedlEngine(runtime)
        assertEquals(0, runtime.updates)
        assertEquals(EngineUpdateResult.Success("2025.11.12", "2026.08.19"), engine.updateYtdlp())
        assertEquals(1, runtime.updates); assertEquals(2, runtime.calls.count { it.contains("--version") })
    }
    @Test fun installedVersionCachesActualBinaryAcrossCallsAndSharedInstancesAndInvalidatesOnUpdate() = runBlocking {
        val initialization = EngineInitialization()
        val first = Runtime(initialization); val second = Runtime(initialization)
        val engine = YoutubedlEngine(first); val other = YoutubedlEngine(second)
        assertTrue(engine.isAvailable); assertEquals(0, first.starts); assertTrue(first.calls.isEmpty())
        repeat(3) { assertEquals("2025.11.12", engine.installedVersion()) }
        assertEquals("2025.11.12", other.installedVersion())
        assertEquals(1, first.starts); assertEquals(1, first.calls.size); assertTrue(second.calls.isEmpty())
        assertEquals(EngineUpdateResult.Success("2025.11.12", "2026.08.19"), engine.updateYtdlp())
        assertEquals("2026.08.19", other.installedVersion())
        assertEquals(2, first.calls.size); assertTrue(second.calls.isEmpty())
        val failed = Runtime().apply { this.initialization.ensure { error("missing ABI") } }
        assertNull(YoutubedlEngine(failed).installedVersion()); assertTrue(failed.calls.isEmpty())
        assertNull(UnavailableEngine().installedVersion())
    }
    @Test fun cachedInstalledVersionReturnsWhileNativeDownloadHoldsTheLock() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val runtime = Runtime().apply { action = { _, _ ->
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); ""
        } }
        val engine = YoutubedlEngine(runtime)
        assertEquals("2025.11.12", engine.installedVersion())
        val task = async(Dispatchers.Default) { engine.download(command(), temp.newFolder()) {} }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            assertEquals("2025.11.12", withTimeout(1000) { engine.installedVersion() })
        } finally { release.countDown(); task.await() }
    }
    @Test fun failedUpdateInvalidatesCachedVersionEvenIfBinaryChangedBeforeFailure() = runBlocking {
        val runtime = object : YtdlpRuntime {
            override val initialization = EngineInitialization()
            var version = "old"; var reads = 0
            override fun initialize() = Unit
            override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String { reads++; return version }
            override fun destroy(processId: String) = false
            override fun update() { version = "new"; error("failed after replacement") }
        }
        val engine = YoutubedlEngine(runtime)
        assertEquals("old", engine.installedVersion())
        assertIs<EngineUpdateResult.Failure>(engine.updateYtdlp())
        assertEquals("new", engine.installedVersion()); assertEquals(2, runtime.reads)
    }
    @Test fun exactTransferArgvAndStagesCrossTheRuntimeBoundaryOffline() = runBlocking {
        val rows = listOf(
            Format("136", "mp4", true, false, audioFormatId = "140", requiresMerge = true),
            Format("derived-m4a", "m4a", false, true, sourceFormatId = "18"),
            Format("derived-mp3-128", "mp3", false, true, conversion = AudioConversion.MP3_128, sourceFormatId = "140"),
            Format("derived-mp3-320", "mp3", false, true, conversion = AudioConversion.MP3_320, sourceFormatId = "140"))
        for (format in rows) {
            val dir = temp.newFolder().canonicalFile
            val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job-1",
                Inspection(source, "2025.11.12", listOf(format)), source, format.id, "title")).command
            val events = mutableListOf<EngineEvent>()
            val runtime = Runtime().apply { action = { _, line ->
                File(dir, "output.${format.container}").writeText("media")
                File(dir, "result-path.txt").writeText(File(dir, "output.${format.container}").path)
                line("nezzel:${format.sourceFormatId}:10:NA:NA")
                if (format.requiresMerge) {
                    line("nezzel:140:5:20:NA"); line("[Merger] Merging formats into output.mp4")
                } else line("[ExtractAudio] Destination: output.${format.container}")
                ""
            } }
            assertIs<TransferResult.Success>(YoutubedlEngine(runtime).download(command, dir, events::add))
            val conversion = format.conversion
            val post = when {
                format.requiresMerge -> listOf("--merge-output-format", "mp4")
                conversion == AudioConversion.MP3_128 -> listOf("--extract-audio", "--audio-format", "mp3", "--audio-quality", "128K",
                    "--postprocessor-args", "ExtractAudio+ffmpeg_o1:-c:a libmp3lame -b:a 128k")
                conversion == AudioConversion.MP3_320 -> listOf("--extract-audio", "--audio-format", "mp3", "--audio-quality", "320K",
                    "--postprocessor-args", "ExtractAudio+ffmpeg_o1:-c:a libmp3lame -b:a 320k -ar 44100")
                else -> listOf("--extract-audio", "--audio-format", "m4a")
            }
            val selector = if (format.requiresMerge) "136+140" else format.sourceFormatId
            assertEquals(listOf("--ignore-config", "--no-playlist", "--playlist-items", "1") + YtdlpCommand.NETWORK + listOf(
                "--abort-on-unavailable-fragments", "--retry-sleep", "http:exp=1:8", "--retry-sleep", "fragment:exp=1:16", "-N", "4", "--no-simulate", "--newline",
                "--progress-template", YtdlpCommand.PROGRESS_TEMPLATE, "-f", selector, "-o", File(dir, "output.%(ext)s").path,
                "--print-to-file", "after_move:filepath", File(dir, "result-path.txt").path) + post +
                (if (format.hasVideo) emptyList() else audioTagsWithoutArtist) + listOf("--", source.link.rawUrl), runtime.calls.last())
            assertFalse(selector.contains('/')); assertTrue(selector.split('+').none { it in setOf("best", "b") })
            assertEquals(EngineEvent.Progress(10, Size.Unknown), events.first())
            assertEquals(EngineEvent.Merging, events.last())
        }
    }
}
