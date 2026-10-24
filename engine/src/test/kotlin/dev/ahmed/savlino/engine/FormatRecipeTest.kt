package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/** Snappy-gaps 8b: a job that starts without its info JSON downloads the confirmed row or fails, never another file. */
class FormatRecipeTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = LinkRevision(LinkInput.parse("https://example.org/watch")!!, 1)
    private fun row(id: String = "18", ext: String = "mp4", vcodec: String = "avc1.42001E", height: Int = 360,
        size: String = "\"filesize\":1000", extra: String = """"protocol":"https","fps":30,"format_note":"360p","tbr":512""") =
        """{"format_id":"$id","ext":"$ext","vcodec":"$vcodec","acodec":"mp4a.40.2","width":640,"height":$height,$size,$extra,"url":"https://example.org/$id"}"""
    private fun page(vararg rows: String) = """{"id":"clip","title":"Clip","formats":[${rows.joinToString(",")}]}"""

    private class Runtime(var page: String) : YtdlpRuntime {
        override val initialization = EngineInitialization()
        val transfers = mutableListOf<List<String>>()
        var action: (List<String>, (String) -> Unit) -> Unit = { args, _ -> output(args) }
        @Volatile var read: ((String) -> Unit) -> String = { page }
        val destroyed = java.util.concurrent.CountDownLatch(1)
        override fun initialize() = Unit
        override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
            if ("--version" in args) return "version"
            if ("--dump-json" in args) return read(line)
            transfers += args; action(args, line); return ""
        }
        override fun destroy(processId: String): Boolean { destroyed.countDown(); return true }
        override fun update() = Unit
        companion object {
            fun output(args: List<String>) {
                val output = File(args[args.indexOf("-o") + 1].replace("%(ext)s", "mp4"))
                output.writeText("m".repeat(1000)) // The exact size the fixtures report.
                File(output.parentFile, "result-path.txt").writeText(output.path)
            }
        }
    }

    /** Inspect [inspected], confirm "18", let its info JSON pass the TTL, then download against [fresh]. */
    private fun staleDownload(inspected: String, fresh: String, limits: EngineLimits = EngineLimits(),
        read: ((Runtime) -> ((String) -> Unit) -> String)? = null): Pair<TransferResult, Runtime> = runBlocking {
        var now = 10_000_000L
        val runtime = Runtime(inspected)
        val engine = YoutubedlEngine(runtime, limits, infoCache = YtdlpInfoCache(temp.newFolder()) { now })
        val inspection = assertIs<InspectResult.Success>(engine.inspect(source, "inspect")).inspection
        val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job", inspection, source, "18", "t")).command
        engine.prepareDownload(command)
        now += YtdlpInfoCache.TTL_MS
        runtime.page = fresh
        read?.let { runtime.read = it(runtime) }
        engine.download(command, temp.newFolder()) {} to runtime
    }
    private fun loaded(args: List<String>) = File(args[args.indexOf("--load-info-json") + 1]).readText()

    @Test fun sameRowAfterTheTtlDownloadsFromTheCheckedPageJson() {
        val (result, runtime) = staleDownload(page(row()), page(row(), row("22", height = 720)))
        assertIs<TransferResult.Success>(result, result.toString())
        val args = runtime.transfers.single()
        assertFalse(source.link.rawUrl in args)
        assertEquals("18", args[args.indexOf("-f") + 1])
        assertEquals(listOf("18"), Regex("\"format_id\":\"(\\w+)\"").findAll(loaded(args)).map { it.groupValues[1] }.toList())
    }

    @Test fun missingRowFailsAsFormatExpiredWithoutATransfer() {
        val (result, runtime) = staleDownload(page(row()), page(row("22", height = 720)))
        assertEquals(DownloadError.FormatExpired, assertIs<TransferResult.Failure>(result).error)
        assertTrue(runtime.transfers.isEmpty())
    }

    @Test fun droppedConnectionDuringThePageReadWaitsForTheNetwork() {
        val reads = listOf<(Runtime) -> ((String) -> Unit) -> String>(
            { _ -> { line -> line("ERROR: connection reset"); "" } },
            { _ -> { line -> line("ERROR: [youtube] clip: Connection reset by peer"); error("ERROR: Connection reset by peer") } },
            { _ -> { _ -> error("ERROR: [youtube] clip: Connection reset by peer") } })
        for ((index, read) in reads.withIndex()) {
            val (result, runtime) = staleDownload(page(row()), page(row()), read = read)
            val failure = assertIs<TransferResult.Failure>(result, "read $index")
            assertEquals(DownloadError.Network, failure.error, "read $index")
            assertEquals(DownloadFailureCause.TRANSPORT, failure.cause, "read $index")
            assertTrue(runtime.transfers.isEmpty())
        }
    }

    @Test fun pageReadTimeoutWaitsForTheNetwork() {
        val (result, runtime) = staleDownload(page(row()), page(row()), EngineLimits(inspectMs = 300)) { runtime ->
            { _ -> check(runtime.destroyed.await(5, java.util.concurrent.TimeUnit.SECONDS)); error("process destroyed") }
        }
        val failure = assertIs<TransferResult.Failure>(result)
        assertEquals(DownloadError.Network, failure.error)
        assertEquals(DownloadFailureCause.TRANSPORT, failure.cause)
        assertTrue(runtime.transfers.isEmpty())
    }

    @Test fun sameIdWithAnotherHeightCodecContainerOrExactSizeFailsWithoutATransfer() {
        for (changed in listOf(row(height = 720), row(vcodec = "vp09.00.10.08"), row(ext = "webm"), row(size = "\"filesize\":2000"))) {
            val (result, runtime) = staleDownload(page(row()), page(changed))
            assertEquals(DownloadError.FormatExpired, assertIs<TransferResult.Failure>(result).error, changed)
            assertTrue(runtime.transfers.isEmpty(), changed)
        }
    }

    @Test fun anotherEstimateProtocolFpsOrNoteStillDownloads() {
        val estimated = "\"filesize_approx\":1000"
        for ((inspected, fresh) in listOf(row(size = estimated) to row(size = "\"filesize_approx\":1500"),
            row() to row(extra = """"protocol":"m3u8_native","fps":29.97,"format_note":"medium","tbr":498"""),
            row(vcodec = "avc1.42001E") to row(vcodec = "avc1.4D401F"))) {
            val (result, runtime) = staleDownload(page(inspected), page(fresh))
            assertIs<TransferResult.Success>(result, fresh)
            assertTrue("--load-info-json" in runtime.transfers.single())
        }
    }

    @Test fun expiredUrlRetryReadsThePageAgainAndStopsOnAChangedRow() = runBlocking {
        val runtime = Runtime(page(row()))
        val engine = YoutubedlEngine(runtime, infoCache = YtdlpInfoCache(temp.newFolder()))
        val inspection = assertIs<InspectResult.Success>(engine.inspect(source, "inspect")).inspection
        val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job", inspection, source, "18", "t")).command
        runtime.action = { args, line ->
            File(File(args[args.indexOf("-o") + 1]).parentFile, "output.mp4.part").writeText("old bytes")
            runtime.page = page(row(height = 720))
            line("ERROR: unable to download video data: HTTP Error 403: Forbidden"); error("")
        }
        assertEquals(DownloadError.FormatExpired, assertIs<TransferResult.Failure>(engine.download(command, temp.newFolder()) {}).error)
        assertEquals(1, runtime.transfers.size)
    }

    @Test fun recipeComparesOnlyWhatTheUserPicked() {
        val video = Format("137", "mp4", true, false, videoCodec = "avc1.640028", width = 1920, height = 1080, size = Size.Exact(900))
        val audio = Format("140", "m4a", false, true, audioCodec = "mp4a.40.2", size = Size.Exact(100))
        val merged = video.copy(hasAudio = true, audioFormatId = "140", requiresMerge = true, size = Size.Exact(1000))
        val streams = mapOf("137" to video, "140" to audio)
        assertTrue(FormatRecipe.same(merged, streams))
        assertTrue(FormatRecipe.same(merged.copy(size = Size.Estimated(5)), streams))
        assertTrue(FormatRecipe.same(merged.copy(videoCodec = null, height = null), streams))
        assertFalse(FormatRecipe.same(merged, streams - "140"))
        assertFalse(FormatRecipe.same(merged, streams + ("140" to audio.copy(audioCodec = "opus"))))
        assertFalse(FormatRecipe.same(merged, streams + ("137" to video.copy(height = 720))))
        assertFalse(FormatRecipe.same(merged, streams + ("140" to audio.copy(size = Size.Exact(101)))))
        assertTrue(FormatRecipe.same(merged, streams + ("140" to audio.copy(size = Size.Estimated(101)))))
        val mp3 = Format("nezzel:mp3:128:140", "mp3", false, true, audioCodec = "mp3", bitrateKbps = 128,
            conversion = AudioConversion.MP3_128, sourceFormatId = "140", sourceContainer = "m4a")
        assertTrue(FormatRecipe.same(mp3, streams))
        assertFalse(FormatRecipe.same(mp3, streams + ("140" to audio.copy(container = "webm"))))
        assertFalse(FormatRecipe.same(mp3, mapOf("140" to video.copy(id = "140"))))
        val extracted = Format("nezzel:m4a:18", "m4a", false, true, audioCodec = "mp4a.40.2", sourceFormatId = "18")
        assertTrue(FormatRecipe.same(extracted, mapOf("18" to Format("18", "mp4", true, true, audioCodec = "mp4a.40.2"))))
        assertFalse(FormatRecipe.same(extracted, mapOf("18" to Format("18", "mp4", true, false))))
    }
}
