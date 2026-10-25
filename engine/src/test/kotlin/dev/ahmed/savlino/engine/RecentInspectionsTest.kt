package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class RecentInspectionsTest {
    @get:Rule val temp = TemporaryFolder()
    private val link = LinkInput.parse("https://example.org/watch?v=private-token")!!
    private val source = LinkRevision(link, 1)
    private var now = 1_700_000_000_000L
    private var elapsed = 5_000L
    /** Wall and monotonic time move together unless a test rolls the wall clock. */
    private fun advance(ms: Long) { now += ms; elapsed += ms }
    private fun recent() = RecentInspections({ now }, { elapsed })
    private fun json(url: String = "https://cdn.example.org/clip?token=private") =
        """{"id":"clip","format_id":"18","ext":"mp4","vcodec":"h264","acodec":"aac","url":"$url"}"""

    private class Runtime(var json: String) : YtdlpRuntime {
        override val initialization = EngineInitialization()
        val inspections = CopyOnWriteArrayList<List<String>>()
        val transfers = CopyOnWriteArrayList<List<String>>()
        val killed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        @Volatile var inspect: (String) -> Unit = {}
        @Volatile var version: () -> Unit = {}
        override fun initialize() = Unit
        override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
            if ("--version" in args) { version(); return "version" }
            if ("--dump-json" in args) { inspections += args; inspect(processId); return json }
            transfers += args
            val output = File(args[args.indexOf("-o") + 1].replace("%(ext)s", "mp4"))
            output.writeText("media")
            File(output.parentFile, "result-path.txt").writeText(output.path)
            return ""
        }
        override fun destroy(processId: String): Boolean { killed += processId; return true }
        override fun update() = Unit
    }

    private fun engine(runtime: Runtime, cache: YtdlpInfoCache? = null, limits: EngineLimits = EngineLimits()) =
        YoutubedlEngine(runtime, limits, cache, recent = recent())

    private fun confirm(inspection: Inspection, at: LinkRevision = source, id: String = "job") =
        assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm(id, inspection, at, "18", "title")).command

    @Test fun hitRemapsWithoutArgvAndKeepsTheExtractionAge() = runBlocking {
        val runtime = Runtime(json())
        val cache = YtdlpInfoCache(temp.newFolder()) { now }
        val engine = engine(runtime, cache)
        val first = assertIs<InspectResult.Success>(engine.inspect(source, "first")).inspection
        val extractedAt = now
        advance(10 * 60_000)
        val again = LinkRevision(link, 2)
        val second = assertIs<InspectResult.Success>(engine.inspect(again, "second")).inspection
        assertEquals(1, runtime.inspections.size)
        assertNotEquals(first.inspectionId, second.inspectionId)
        assertEquals(first.formats, second.formats)
        val command = confirm(second, again)
        engine.prepareDownload(command)
        assertEquals(extractedAt, assertNotNull(cache.freshFile("job")).lastModified())
        assertIs<TransferResult.Success>(engine.download(command, temp.newFolder()) {})
        assertTrue("--load-info-json" in runtime.transfers.single())
    }

    @Test fun freshPlaylistInspectionBypassesTheRecentResultAndKeepsTheRealFailure() = runBlocking {
        val runtime = Runtime(json())
        val engine = engine(runtime)
        assertIs<InspectResult.Success>(engine.inspect(source, "initial"))
        runtime.json = json().replace("\"18\"", "\"22\"")
        // Ordinary inspection still reuses its recent result.
        assertTrue("18" in assertIs<InspectResult.Success>(engine.inspect(source, "ordinary")).inspection.formats.map { it.id })
        val fresh = assertIs<InspectResult.Success>(engine.inspectFresh(source, "fresh"))
        assertTrue("22" in fresh.inspection.formats.map { it.id })
        runtime.inspect = { throw YtdlpExecutionFailure(DownloadError.Offline) }
        val failed = assertIs<InspectResult.Failure>(engine.inspectFresh(source, "fresh-offline"))
        assertEquals(DownloadError.Offline, failed.error)
    }

    @Test fun fifteenMinutesAndAVersionChangeMiss() = runBlocking {
        val runtime = Runtime(json())
        val engine = engine(runtime)
        assertIs<InspectResult.Success>(engine.inspect(source, "a"))
        advance(RecentInspections.MAX_AGE_MS - 1)
        assertIs<InspectResult.Success>(engine.inspect(source, "b"))
        assertEquals(1, runtime.inspections.size)
        advance(1)
        assertIs<InspectResult.Success>(engine.inspect(source, "c"))
        assertEquals(2, runtime.inspections.size)
        runtime.initialization.installedVersion = "next"
        val updated = YoutubedlEngine(runtime, recent = recent().also {
            it.put(link, json(), RecentInspections.Provenance.YTDLP, "version", null)
        })
        assertEquals("next", assertIs<InspectResult.Success>(updated.inspect(source, "d")).inspection.engineVersion)
        assertEquals(3, runtime.inspections.size)
    }

    @Test fun signedExpiryEndsTheHitAndTheDownloadFile() = runBlocking {
        val seconds = now / 1000
        // Expires in 12 min: cached until 7 min (5 min margin), never served past it.
        val runtime = Runtime(json("https://cdn.example.org/clip?expire=${seconds + 12 * 60}&sig=x"))
        val cache = YtdlpInfoCache(temp.newFolder()) { now }
        val engine = engine(runtime, cache)
        assertIs<InspectResult.Success>(engine.inspect(source, "a"))
        advance(7 * 60_000 - 1)
        val late = assertIs<InspectResult.Success>(engine.inspect(source, "b")).inspection
        assertEquals(1, runtime.inspections.size)
        advance(1)
        assertIs<InspectResult.Success>(engine.inspect(source, "c"))
        assertEquals(2, runtime.inspections.size)
        // The job file of the hit inherits the signed expiry, not a fresh hour.
        engine.prepareDownload(confirm(late))
        assertNull(cache.freshFile("job"))
    }

    @Test fun wallClockRollbackStillMissesAtTheMonotonicExpiry() = runBlocking {
        val runtime = Runtime(json())
        val engine = engine(runtime)
        assertIs<InspectResult.Success>(engine.inspect(source, "a"))
        now -= 60 * 60_000
        elapsed += RecentInspections.MAX_AGE_MS
        assertIs<InspectResult.Success>(engine.inspect(source, "b"))
        assertEquals(2, runtime.inspections.size)
        // Signed expiry in 12 min: the remaining 7 min are fixed at insertion, a rollback cannot stretch them.
        runtime.json = json("https://cdn.example.org/clip?expire=${now / 1000 + 12 * 60}&sig=x")
        engine.clearRecentLinks()
        assertIs<InspectResult.Success>(engine.inspect(source, "c"))
        now -= 60 * 60_000
        elapsed += 7 * 60_000 - 1
        assertIs<InspectResult.Success>(engine.inspect(source, "d"))
        assertEquals(3, runtime.inspections.size)
        elapsed += 1
        assertIs<InspectResult.Success>(engine.inspect(source, "e"))
        assertEquals(4, runtime.inspections.size)
    }

    @Test fun nearlyExpiredResultIsNeitherCachedNorWrittenForDownload() = runBlocking {
        val runtime = Runtime(json("https://cdn.example.org/clip?x-expires=${now / 1000 + 3 * 60}"))
        val cache = YtdlpInfoCache(temp.newFolder()) { now }
        val engine = engine(runtime, cache)
        val inspection = assertIs<InspectResult.Success>(engine.inspect(source, "a")).inspection
        assertIs<InspectResult.Success>(engine.inspect(source, "b"))
        assertEquals(2, runtime.inspections.size)
        engine.prepareDownload(confirm(inspection))
        assertNull(cache.freshFile("job"))
        assertIs<TransferResult.Success>(engine.download(confirm(inspection), temp.newFolder()) {})
        // The download reads the page again and uses that JSON now, but never keeps it for a later resume.
        assertEquals(3, runtime.inspections.size)
        assertTrue("--load-info-json" in runtime.transfers.single())
        assertNull(cache.freshFile("job"))
    }

    @Test fun expiryKeysAreReadFromEveryUrl() {
        val s = now / 1000
        fun until(json: String) = RecentInspections.signedUntil(json)
        assertNull(until(json()))
        assertEquals((s + 600) * 1000 - RecentInspections.MARGIN_MS, until(json("https://a.example/v?a=1&expire=${s + 600}")))
        assertEquals((s + 900) * 1000 - RecentInspections.MARGIN_MS,
            until(json("https://a.example/v?oe=${(s + 900).toString(16).uppercase()}&_nc=1")))
        assertEquals((s + 300) * 1000 - RecentInspections.MARGIN_MS, until("""{"url":"https://a.example/m?expire=${s + 3600}",
            "formats":[{"url":"https://a.example/f?x-expires=${s + 1200}"}],
            "requested_formats":[{"url":"https://a.example/r?expire=${s + 300}#frag"}]}"""))
        assertNull(until(json("https://a.example/v?expire=soon&oe=zz")))
        assertNull(until(json("https://a.example/v/expire/${s + 60}/clip")))
    }

    @Test fun failuresAreNotCachedAndClearForgetsEverything() = runBlocking {
        val runtime = Runtime(json())
        runtime.inspect = { throw RuntimeException("fixture failure") }
        val engine = engine(runtime)
        assertIs<InspectResult.Failure>(engine.inspect(source, "a"))
        runtime.inspect = {}
        assertIs<InspectResult.Success>(engine.inspect(source, "b"))
        assertIs<InspectResult.Success>(engine.inspect(source, "c"))
        assertEquals(2, runtime.inspections.size)
        engine.clearRecentLinks()
        assertIs<InspectResult.Success>(engine.inspect(source, "d"))
        assertEquals(3, runtime.inspections.size)
    }

    @Test fun clearDuringAnExtractionKeepsItsResultOut() = runBlocking {
        val runtime = Runtime(json())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        runtime.inspect = { if (runtime.inspections.size == 1) { entered.countDown(); release.await(5, TimeUnit.SECONDS) } }
        val engine = engine(runtime)
        val owner = async(Dispatchers.IO) { engine.inspect(source, "owner") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        engine.clearRecentLinks()
        release.countDown()
        // The open sheet still gets its options; only the cache forgets them.
        assertIs<InspectResult.Success>(withTimeout(5000) { owner.await() })
        assertIs<InspectResult.Success>(engine.inspect(source, "next"))
        assertEquals(2, runtime.inspections.size)
        assertIs<InspectResult.Success>(engine.inspect(source, "after"))
        assertEquals(2, runtime.inspections.size)
    }

    @Test fun clearBeforeTheExtractionStartsStillKeepsItsResultOut() = runBlocking {
        val runtime = Runtime(json())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        // The version probe runs after inspect began and before the owner registers.
        runtime.version = { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        val engine = engine(runtime)
        val owner = async(Dispatchers.IO) { engine.inspect(source, "owner") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        engine.clearRecentLinks()
        release.countDown()
        assertIs<InspectResult.Success>(withTimeout(5000) { owner.await() })
        assertIs<InspectResult.Success>(engine.inspect(source, "next"))
        assertEquals(2, runtime.inspections.size)
    }

    @Test fun boundedByCountAndSizeWithHashedKeys() {
        val recent = recent()
        val links = (0..8).map { LinkInput.parse("https://example.org/watch?v=clip-$it")!! }
        links.forEach { recent.put(it, json(), RecentInspections.Provenance.YTDLP, "v", null) }
        assertNull(recent.get(links[0], "v"))
        assertNotNull(recent.get(links[1], "v"))
        assertNotNull(recent.get(links[8], "v"))
        val big = "x".repeat(RecentInspections.MAX_CHARS + 1)
        recent.put(links[1], big, RecentInspections.Provenance.YTDLP, "v", null)
        assertNull(recent.get(links[1], "v"))
        // Two 5M entries cannot both stay.
        val half = "y".repeat(5_000_000)
        recent.put(links[2], half, RecentInspections.Provenance.YTDLP, "v", null)
        recent.put(links[3], half, RecentInspections.Provenance.YTDLP, "v", null)
        assertNull(recent.get(links[2], "v"))
        assertNotNull(recent.get(links[3], "v"))
        val key = RecentInspections.key(link)
        assertEquals(64, key.length)
        assertFalse("private-token" in key)
        recent.put(link, json(), RecentInspections.Provenance.YTDLP, "v", null)
        assertFalse("private" in recent.toString())
        assertFalse("private" in assertNotNull(recent.get(link, "v")).toString())
        recent.clear()
        assertNull(recent.get(link, "v"))
    }

    @Test fun prefetchedReaderJsonServesInspectAcrossVersionsEvenWhenYtdlpFails() = runBlocking {
        val runtime = Runtime(json())
        runtime.inspect = { throw RuntimeException("fixture failure") }
        val engine = engine(runtime)
        engine.prefetched(link, json())
        engine.prefetched(link, "not json") // dropped silently, the first entry stays
        val inspection = assertIs<InspectResult.Success>(engine.inspect(source, "a")).inspection
        assertEquals(setOf("18"), inspection.formats.map { it.sourceFormatId }.toSet())
        runtime.initialization.installedVersion = "next"
        assertIs<InspectResult.Success>(engine.inspect(source, "b"))
        assertTrue(runtime.inspections.isEmpty())
    }

    @Test fun concurrentInspectsOfOneLinkRunOneArgv() = runBlocking {
        val runtime = Runtime(json())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        runtime.inspect = { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        val engine = engine(runtime)
        val first = async(Dispatchers.IO) { engine.inspect(source, "first") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) { engine.inspect(LinkRevision(link, 2), "second") }
        delay(200)
        assertFalse(second.isCompleted)
        release.countDown()
        val a = assertIs<InspectResult.Success>(withTimeout(5000) { first.await() }).inspection
        val b = assertIs<InspectResult.Success>(withTimeout(5000) { second.await() }).inspection
        assertEquals(1, runtime.inspections.size)
        assertNotEquals(a.inspectionId, b.inspectionId)
    }

    @Test fun cancelledOwnerHandsOverAndACancelledJoinerLeavesAtOnce() = runBlocking {
        val runtime = Runtime(json())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        runtime.inspect = { id ->
            if (runtime.inspections.size == 1) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            if (id in runtime.killed) throw RuntimeException("killed")
        }
        val engine = engine(runtime)
        val owner = async(Dispatchers.IO) { engine.inspect(source, "owner") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val quitter = async(Dispatchers.IO) { engine.inspect(source, "quitter") }
        val joiner = async(Dispatchers.IO) { engine.inspect(source, "joiner") }
        delay(150)
        engine.cancel("quitter")
        assertEquals(InspectResult.Failure(DownloadError.Cancelled), withTimeout(2000) { quitter.await() })
        assertFalse(owner.isCompleted)
        engine.cancel("owner")
        release.countDown()
        assertEquals(InspectResult.Failure(DownloadError.Cancelled), withTimeout(5000) { owner.await() })
        assertIs<InspectResult.Success>(withTimeout(5000) { joiner.await() })
        assertEquals(2, runtime.inspections.size)
    }

    @Test fun failedOwnerLetsTheJoinerRunItsOwnAttempt() = runBlocking {
        val runtime = Runtime(json())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        runtime.inspect = {
            if (runtime.inspections.size == 1) {
                entered.countDown(); release.await(5, TimeUnit.SECONDS); throw RuntimeException("fixture failure")
            }
        }
        val engine = engine(runtime)
        val owner = async(Dispatchers.IO) { engine.inspect(source, "owner") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val joiner = async(Dispatchers.IO) { engine.inspect(source, "joiner") }
        delay(150)
        release.countDown()
        assertIs<InspectResult.Failure>(withTimeout(5000) { owner.await() })
        assertIs<InspectResult.Success>(withTimeout(5000) { joiner.await() })
        assertEquals(2, runtime.inspections.size)
    }
}
