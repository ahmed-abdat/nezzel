package dev.ahmed.savlino.engine.youtube

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.schabi.newpipe.extractor.stream.AudioTrackType
import kotlin.test.*

class NpeInspectTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = LinkRevision(LinkInput.parse("https://www.youtube.com/watch?v=fixture-only")!!, 1)
    private val fallbackJson = """{"format_id":"18","ext":"mp4","vcodec":"h264","acodec":"aac","title":"Fallback fixture"}"""
    private fun fixture() = assertNotNull(NpeInfoJson.build(NpeFixtures.golden()))

    /** [action] returns a JSON string, a [FastResult] or null (= Miss). */
    private class Fast(private val action: () -> Any?) : YoutubeFastSource {
        val calls = CopyOnWriteArrayList<Pair<String, Long>>()
        val urls = CopyOnWriteArrayList<String>()
        val cancellations = CopyOnWriteArrayList<String>()
        override fun infoJson(url: String, operationId: String, timeoutMs: Long): FastResult {
            urls += url; calls += operationId to timeoutMs
            return when (val result = action()) { is FastResult -> result; is String -> FastResult.Json(result); else -> FastResult.Miss }
        }
        override fun cancel(operationId: String) { cancellations += operationId }
    }

    private class Runtime(private val json: String) : YtdlpRuntime {
        override val initialization = EngineInitialization()
        val inspections = CopyOnWriteArrayList<List<String>>()
        val destroyed = CountDownLatch(1)
        var inspectAction: () -> Unit = {}
        override fun initialize() = Unit
        override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
            if ("--version" in args) return "fixture-version"
            check("--dump-json" in args)
            inspections += args
            inspectAction()
            return json
        }
        override fun destroy(processId: String): Boolean { destroyed.countDown(); return true }
        override fun update() = Unit
    }

    @Test fun acceptedFixtureUsesMapperWithoutYtdlpAndCachesOnlyChosenStreams() = runBlocking {
        val json = fixture()
        val fast = Fast { json }
        val runtime = Runtime(fallbackJson)
        val cache = YtdlpInfoCache(temp.newFolder())
        val engine = YoutubedlEngine(runtime, infoCache = cache, fastSource = fast)
        val inspection = assertIs<InspectResult.Success>(engine.inspect(source, "inspect")).inspection
        assertEquals(YtdlpMetadataMapper.map(json, source, "fixture-version").formats, inspection.formats)
        assertEquals("fixture-version", inspection.engineVersion)
        assertTrue(runtime.inspections.isEmpty())
        assertEquals(listOf("inspect" to 10_000L), fast.calls)
        assertEquals(listOf(source.link.rawUrl), fast.urls)
        assertNull(cache.freshFile("job"))
        val chosen = inspection.formats.first { it.requiresMerge }
        val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm(
            "job", inspection, source, chosen.id, "Fixture")).command
        engine.prepareDownload(command)
        val stored = ObjectMapper().readTree(assertNotNull(cache.freshFile("job")))
        assertEquals(setOf(chosen.sourceFormatId, chosen.audioFormatId),
            stored.path("formats").map { it.path("format_id").asText() }.toSet())
        assertTrue(stored.path("formats").size() < ObjectMapper().readTree(json).path("formats").size())
    }

    private suspend fun assertFallback(action: () -> String?) {
        val runtime = Runtime(fallbackJson)
        val fast = Fast(action)
        val actual = assertIs<InspectResult.Success>(YoutubedlEngine(runtime, fastSource = fast)
            .inspect(source, "inspect")).inspection
        val expected = YtdlpMetadataMapper.map(fallbackJson, source, "fixture-version")
        assertEquals(expected.formats, actual.formats)
        assertEquals("Fallback fixture", actual.title)
        assertEquals(listOf(YtdlpCommand.inspect(source)), runtime.inspections)
        assertEquals(1, fast.calls.size)
    }

    @Test fun nullFallsBack() = runBlocking { assertFallback { null } }
    @Test fun exceptionFallsBackWithoutSurfacingPrivateText() = runBlocking {
        assertFallback { throw IllegalStateException("private fixture") }
    }
    @Test fun linkageErrorFallsBack() = runBlocking { assertFallback { throw NoClassDefFoundError("fixture") } }
    @Test fun malformedMetadataFallsBack() = runBlocking { assertFallback { "{" } }
    @Test fun emptyFormatsFallBack() = runBlocking { assertFallback { """{"formats":[]}""" } }
    @Test fun dubOnlyMappedAudioFallsBack() = runBlocking {
        assertFallback { NpeInfoJson.build(NpeFixtures.info(audios = listOf(
            NpeFixtures.audio(type = AudioTrackType.DUBBED)))) }
    }
    @Test fun mappedVideoWithoutAudioFallsBack() = runBlocking {
        val root = ObjectMapper().readTree(fixture())
        val videos = root.path("formats").filter { it.path("acodec").asText() == "none" }
        (root as ObjectNode).set<JsonNode>(
            "formats", ObjectMapper().valueToTree(videos))
        assertFallback { root.toString() }
    }
    @Test fun mappedAudioWithoutVideoFallsBack() = runBlocking {
        assertFallback { NpeInfoJson.build(NpeFixtures.info())?.let { metadata ->
            val root = ObjectMapper().readTree(metadata) as ObjectNode
            val audio = root.path("formats").filter { it.path("vcodec").asText() == "none" }
            root.set<JsonNode>("formats", ObjectMapper().valueToTree(audio))
            root.toString()
        } }
    }
    @Test fun originalDroppedByMapperDoesNotLetSurvivingDubPass() = runBlocking {
        assertFallback { NpeInfoJson.build(NpeFixtures.info(audios = listOf(
            NpeFixtures.audio(track = "original", type = AudioTrackType.ORIGINAL, codec = null),
            NpeFixtures.audio(track = "dub", type = AudioTrackType.DUBBED)))) }
    }

    @Test fun npeTimeoutLeavesTheFullYtdlpBudgetAndDoesNotJoinBlockedWorker() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val json = fixture()
        val fast = Fast { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); json }
        val runtime = Runtime(fallbackJson).apply {
            inspectAction = {
                // NPE used more than one inspection budget; yt-dlp must still get its own full budget.
                assertFalse(destroyed.await(180, TimeUnit.MILLISECONDS))
            }
        }
        val engine = YoutubedlEngine(runtime, EngineLimits(inspectMs = 300, npeBudgetMs = 500), fastSource = fast)
        try {
            val result = withTimeout(3000) { engine.inspect(source, "timeout") }
            assertEquals(0L, entered.count)
            assertIs<InspectResult.Success>(result)
            assertEquals(listOf("timeout"), fast.cancellations)
            assertEquals(listOf("timeout" to 500L), fast.calls)
            assertEquals(1, runtime.inspections.size)
            assertEquals(1L, release.count) // Success precedes the blocked call returning.
        } finally { release.countDown() }
    }

    @Test fun cancellationDuringNpeCancelsFastSourceWithoutFallbackOrJoin() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val json = fixture()
        val fast = Fast { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); json }
        val runtime = Runtime(fallbackJson)
        val engine = engine(runtime, fast)
        try {
            val task = async(Dispatchers.Default) { engine.inspect(source, "cancel") }
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            engine.cancel("cancel")
            assertEquals(InspectResult.Failure(DownloadError.Cancelled), withTimeout(2000) { task.await() })
            assertEquals(listOf("cancel"), fast.cancellations)
            assertTrue(runtime.inspections.isEmpty())
            assertEquals(1L, release.count)
            assertEquals("Fallback fixture", assertIs<InspectResult.Success>(engine.inspect(source, "after-cancel")).inspection.title)
            assertEquals(listOf("cancel"), fast.calls.map { it.first })
        } finally { release.countDown() }
    }

    @Test fun callerCancellationAlsoCancelsFastSourceWithoutJoiningIt() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val fast = Fast { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); null }
        val runtime = Runtime(fallbackJson)
        val engine = engine(runtime, fast)
        try {
            val task = async(Dispatchers.Default) { engine.inspect(source, "parent-cancel") }
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            withTimeout(2000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
            assertEquals(listOf("parent-cancel"), fast.cancellations)
            assertTrue(runtime.inspections.isEmpty())
            assertEquals(1L, release.count)
        } finally { release.countDown() }
    }

    @Test fun timedOutWorkerHoldsSlotUntilItReturnsThenFastSourceCanRunAgain() = runBlocking {
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val json = fixture()
        val fast = Fast {
            try { check(release.await(5, TimeUnit.SECONDS)); json }
            finally { returned.countDown() }
        }
        val runtime = Runtime(fallbackJson)
        val engine = engine(runtime, fast, EngineLimits(npeBudgetMs = 500))
        try {
            assertEquals("Fallback fixture", assertIs<InspectResult.Success>(engine.inspect(source, "first")).inspection.title)
            engine.clearRecentLinks() // each call extracts
            assertEquals("Fallback fixture", assertIs<InspectResult.Success>(engine.inspect(source, "second")).inspection.title)
            assertEquals(listOf("first"), fast.calls.map { it.first })
            assertEquals(2, runtime.inspections.size)
            release.countDown()
            assertTrue(withContext(Dispatchers.IO) { returned.await(3, TimeUnit.SECONDS) })
            // The fake's return precedes the worker's finally by a few instructions. Retry admission
            // until that finally has released the slot, without depending on thread scheduling.
            val third = withTimeout(3000) {
                var result: Inspection
                do {
                    engine.clearRecentLinks()
                    result = assertIs<InspectResult.Success>(engine.inspect(source, "third")).inspection
                    yield()
                } while (fast.calls.size == 1)
                result
            }
            assertEquals(listOf("first", "third"), fast.calls.map { it.first })
            assertEquals("Fixture title", third.title)
        } finally { release.countDown() }
    }

    @Test fun unreachableFailsFastWithoutYtdlp() = runBlocking {
        for ((offline, error) in listOf(true to DownloadError.Offline, false to DownloadError.Network)) {
            val runtime = Runtime(fallbackJson)
            val fast = Fast { FastResult.Unreachable(offline) }
            assertEquals(InspectResult.Failure(error), engine(runtime, fast).inspect(source, "dead"))
            assertTrue(runtime.inspections.isEmpty())
            assertEquals(1, fast.calls.size)
        }
    }

    @Test fun retryAfterUnreachableTakesTheFullYtdlpPathUntilASuccess() = runBlocking {
        val runtime = Runtime(fallbackJson)
        val fast = Fast { FastResult.Unreachable(false) }
        val engine = engine(runtime, fast)
        assertEquals(InspectResult.Failure(DownloadError.Network), engine.inspect(source, "first"))
        assertTrue(runtime.inspections.isEmpty())
        // Same link again (Retry): no NPE budget spent, yt-dlp starts at once. A failed retry keeps the key.
        runtime.inspectAction = { throw RuntimeException("fixture failure") }
        assertIs<InspectResult.Failure>(engine.inspect(source, "retry"))
        runtime.inspectAction = {}
        assertEquals("Fallback fixture", assertIs<InspectResult.Success>(engine.inspect(source, "retry-2")).inspection.title)
        assertEquals(List(2) { YtdlpCommand.inspect(source) }, runtime.inspections)
        assertEquals(listOf("first"), fast.calls.map { it.first })
        // The success is served from memory for 15 min; after that the cleared key fails fast again.
        assertIs<InspectResult.Success>(engine.inspect(source, "hit"))
        now += RecentInspections.MAX_AGE_MS
        assertEquals(InspectResult.Failure(DownloadError.Network), engine.inspect(source, "later"))
        assertEquals(2, runtime.inspections.size)
        assertEquals(listOf("first", "later"), fast.calls.map { it.first })
        val other = LinkRevision(LinkInput.parse("https://www.youtube.com/watch?v=fixture-two")!!, 1)
        assertEquals(InspectResult.Failure(DownloadError.Network), engine.inspect(other, "other"))
    }

    @Test fun lateUnreachableVerdictWithinGraceStillFailsFast() = runBlocking {
        val runtime = Runtime(fallbackJson)
        val fast = Fast { Thread.sleep(450); FastResult.Unreachable(false) }
        val result = withTimeout(3000) { engine(runtime, fast, EngineLimits(npeBudgetMs = 300)).inspect(source, "late") }
        assertEquals(InspectResult.Failure(DownloadError.Network), result)
        assertTrue(runtime.inspections.isEmpty())
    }

    @Test fun nonYoutubeNeverTouchesFastSource() = runBlocking {
        val other = LinkRevision(LinkInput.parse("https://example.org/video")!!, 1)
        val fast = Fast { fail("Non-YouTube fast source") }
        val runtime = Runtime(fallbackJson)
        assertIs<InspectResult.Success>(engine(runtime, fast).inspect(other, "other"))
        assertTrue(fast.calls.isEmpty()); assertTrue(fast.cancellations.isEmpty())
        assertEquals(listOf(YtdlpCommand.inspect(other)), runtime.inspections)
    }

    @Test fun nullFastSourceRetainsTodaysYoutubePath() = runBlocking {
        val runtime = Runtime(fallbackJson)
        assertIs<InspectResult.Success>(YoutubedlEngine(runtime).inspect(source, "plain"))
        assertEquals(listOf(YtdlpCommand.inspect(source)), runtime.inspections)
    }

    @Test fun spentBudgetSkipsFastSourceAndStillRunsYtdlp() = runBlocking {
        val fast = Fast { fail("Spent budget") }
        val runtime = Runtime(fallbackJson)
        assertIs<InspectResult.Success>(engine(runtime, fast, EngineLimits(npeBudgetMs = 0)).inspect(source, "spent"))
        assertTrue(fast.calls.isEmpty())
        assertEquals(listOf(YtdlpCommand.inspect(source)), runtime.inspections)
    }

    private var now = 1_700_000_000_000L
    private fun engine(runtime: Runtime, fast: Fast, limits: EngineLimits = EngineLimits()) =
        YoutubedlEngine(runtime, limits, fastSource = fast, recent = RecentInspections { now })

    @Test fun npeHitSkipsNpeAndStillTrimsToTheChosenStreams() = runBlocking {
        val json = fixture()
        val fast = Fast { json }
        val runtime = Runtime(fallbackJson)
        val cache = YtdlpInfoCache(temp.newFolder()) { now }
        val engine = YoutubedlEngine(runtime, infoCache = cache, fastSource = fast, recent = RecentInspections { now })
        assertIs<InspectResult.Success>(engine.inspect(source, "first"))
        // NPE JSON is not yt-dlp output: an engine update still hits.
        runtime.initialization.installedVersion = "next-version"
        val again = LinkRevision(source.link, 2)
        val inspection = assertIs<InspectResult.Success>(engine.inspect(again, "second")).inspection
        assertEquals(1, fast.calls.size)
        assertTrue(runtime.inspections.isEmpty())
        assertEquals("next-version", inspection.engineVersion)
        val chosen = inspection.formats.first { it.requiresMerge }
        val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job", inspection, again, chosen.id, "Fixture")).command
        engine.prepareDownload(command)
        val file = assertNotNull(cache.freshFile("job"))
        assertTrue(cache.isNpe(file))
        assertEquals(setOf(chosen.sourceFormatId, chosen.audioFormatId),
            ObjectMapper().readTree(file).path("formats").map { it.path("format_id").asText() }.toSet())
    }

    @Test fun joinerWaitsOutASlowNpeOwnerWithoutItsOwnDeadline() = runBlocking {
        val json = fixture()
        val fast = Fast { Thread.sleep(700); json }
        val runtime = Runtime(fallbackJson)
        val engine = engine(runtime, fast, EngineLimits(inspectMs = 300, npeBudgetMs = 3_000))
        val owner = async(Dispatchers.IO) { engine.inspect(source, "owner") }
        delay(100)
        val joiner = async(Dispatchers.IO) { engine.inspect(source, "joiner") }
        assertIs<InspectResult.Success>(withTimeout(5000) { owner.await() })
        assertIs<InspectResult.Success>(withTimeout(5000) { joiner.await() })
        assertEquals(1, fast.calls.size)
        assertTrue(runtime.inspections.isEmpty())
    }
}
