package dev.ahmed.savlino.engine.download

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class DirectEngineTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = LinkRevision(assertNotNull(LinkInput.parse("https://example.com/post")), 1)
    private val row = Format("progressive", "mp4", true, true, videoCodec = "h264", audioCodec = "aac", size = Size.Exact(6))
    private val json = """{"id":"example","title":"example","formats":[{"format_id":"progressive","ext":"mp4","vcodec":"h264","acodec":"aac","filesize":6,"url":"https://example.com/video.mp4","protocol":"https"}]}"""
    private fun command(id: String = "job") = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm(id,
        Inspection(source, "test", listOf(row)), source, row.id, "example")).command
    private class Runtime(val json: String, val dir: File) : YtdlpRuntime {
        override val initialization = EngineInitialization()
        val calls = mutableListOf<List<String>>()
        var partialAtFallback: String? = null
        var fail = false
        override fun initialize() = Unit
        override fun update() = Unit
        override fun destroy(processId: String) = true
        override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
            calls += args
            if ("--version" in args) return "test"
            if ("--dump-json" in args) return json
            partialAtFallback = dir.resolve(DirectResume.PART).takeIf { it.exists() }?.readText()
            if (fail) { dir.resolve(DirectResume.PART).writeText("ytdlp"); throw IOException("network unavailable") }
            dir.resolve(DirectResume.PART).delete()
            dir.resolve("output.mp4").writeText("abcdef")
            dir.resolve("result-path.txt").writeText(dir.resolve("output.mp4").path)
            return ""
        }
    }
    private fun engine(runtime: Runtime, cache: YtdlpInfoCache, wire: DirectTransport,
        limits: EngineLimits = EngineLimits()) = YoutubedlEngine(runtime, limits, infoCache = cache,
        native = NativeState(), directFactory = { DirectDownloader(transport = { wire }) },
        directSelect = { _, _ -> DirectRequest("https://example.com/video.mp4", emptyMap()) })
    private open class Wire(private val status: Int) : DirectTransport {
        var calls = 0
        override fun execute(url: String, headers: Map<String, String>): DirectResponse {
            calls++
            return object : DirectResponse {
                override val code = status
                override fun header(name: String) = if (name == "Content-Length") "6" else null
                override val body = "abcdef".byteInputStream()
                override fun close() = Unit
            }
        }
        override fun close() = Unit
        override fun cancel() = Unit
    }
    @Test fun nativeSuccessSkipsPythonTransferAndKeepsFinalValidation() = runBlocking {
        val dir = temp.newFolder(); val cache = YtdlpInfoCache(temp.newFolder()); val command = command()
        cache.store(command.jobId, json, npe = false)
        val runtime = Runtime(json, dir); val wire = Wire(200); val engine = engine(runtime, cache, wire)
        assertEquals("native", engine.resumeEngine(command, dir))
        val success = assertIs<TransferResult.Success>(engine.download(command, dir) {})
        assertEquals("abcdef", success.output.file.readText())
        assertTrue(runtime.calls.all { "--version" in it })
        assertEquals(1, wire.calls)
    }
    @Test fun forbiddenReextractsOnceThenOnlyYtdlpAnd503DoesNotReextract() = runBlocking {
        for (status in listOf(403, 503)) {
            val dir = temp.newFolder(); val cache = YtdlpInfoCache(temp.newFolder()); val command = command()
            cache.store(command.jobId, json, npe = false)
            dir.resolve(DirectResume.PART).writeText("abc")
            DirectResume.write(dir, DirectResume.Record("ETag", "\"a\"", 6))
            val runtime = Runtime(json, dir); val wire = Wire(status)
            assertIs<TransferResult.Success>(engine(runtime, cache, wire).download(command, dir) {})
            assertNull(runtime.partialAtFallback)
            assertEquals(if (status == 403) 1 else 0, runtime.calls.count { "--dump-json" in it })
            assertEquals(1, runtime.calls.count { "-o" in it })
            assertEquals(1, wire.calls)
        }
    }
    @Test fun failedFallbackRemainsYtdlpAcrossANewEngineAndLegacyPartialsStayYtdlp() = runBlocking {
        for (legacy in listOf(false, true)) {
            val dir = temp.newFolder(); val cache = YtdlpInfoCache(temp.newFolder()); val command = command()
            cache.store(command.jobId, json, npe = false)
            if (legacy) dir.resolve(DirectResume.PART).writeText("legacy")
            val runtime = Runtime(json, dir).apply { fail = true }; val wire = Wire(503)
            assertIs<TransferResult.Failure>(engine(runtime, cache, wire).download(command, dir) {})
            val next = engine(runtime, cache, Wire(200))
            assertEquals("ytdlp", next.resumeEngine(command, dir))
            assertEquals("ytdlp", dir.resolve(DirectResume.PART).readText())
            assertEquals(if (legacy) 0 else 1, wire.calls)
            if (!legacy) assertEquals(false, DirectResume.read(dir)?.native)
        }
    }
    @Test fun nativePartialIsDiscardedWhenSelectionNowUsesYtdlp() = runBlocking {
        val dir = temp.newFolder(); val cache = YtdlpInfoCache(temp.newFolder()); val command = command()
        cache.store(command.jobId, json, npe = false)
        dir.resolve(DirectResume.PART).writeText("native")
        DirectResume.write(dir, DirectResume.Record("ETag", "\"a\"", 6))
        val runtime = Runtime(json, dir)
        val engine = YoutubedlEngine(runtime, infoCache = cache, native = NativeState(), directSelect = { _, _ -> null })
        assertIs<TransferResult.Success>(engine.download(command, dir) {})
        assertNull(runtime.partialAtFallback)
    }
    @Test fun symlinkStagingCannotWriteAnOwnershipRecordOutsideTheJob() = runBlocking {
        val target = temp.newFolder(); val parent = temp.newFolder(); val dir = File(parent, "job-link")
        java.nio.file.Files.createSymbolicLink(dir.toPath(), target.toPath())
        target.resolve(DirectResume.PART).writeText("native")
        DirectResume.write(target, DirectResume.Record("ETag", "\"a\"", 6))
        val saved = target.resolve(DirectResume.NAME).readText()
        val cache = YtdlpInfoCache(temp.newFolder()); val command = command()
        cache.store(command.jobId, json, npe = false)
        val runtime = Runtime(json, dir)
        val engine = YoutubedlEngine(runtime, infoCache = cache, native = NativeState(), directSelect = { _, _ -> null })
        assertIs<TransferResult.Failure>(engine.download(command, dir) {})
        assertEquals(saved, target.resolve(DirectResume.NAME).readText())
        assertEquals("native", target.resolve(DirectResume.PART).readText())
        assertFalse(runtime.calls.any { "-o" in it })
    }
    @Test fun threeNativeJobsHaveIndependentCancellationAndOneResumesItsOwnPrefix() = runBlocking {
        val cache = YtdlpInfoCache(temp.newFolder())
        val commands = listOf("one", "two", "three").map { command(it).also { cmd -> cache.store(cmd.jobId, json, npe = false) } }
        val directories = commands.associate { it.jobId to temp.newFolder() }
        val entered = CountDownLatch(3)
        val releases = java.util.concurrent.ConcurrentHashMap<String, CountDownLatch>()
        val requests = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String?>>()
        val runtime = Runtime(json, temp.newFolder())
        val engine = YoutubedlEngine(runtime, infoCache = cache, native = NativeState(),
            directSelect = { cmd, _ -> DirectRequest("https://example.com/${cmd.jobId}.mp4", emptyMap()) },
            directFactory = { DirectDownloader(transport = { object : DirectTransport {
                @Volatile var stopped = false
                var release: CountDownLatch? = null
                override fun execute(url: String, headers: Map<String, String>): DirectResponse {
                    val id = url.substringAfterLast('/').substringBefore('.')
                    val resume = headers["Range"] != null
                    requests += id to headers["Range"]
                    val gate = releases.computeIfAbsent(id) { CountDownLatch(1) }; release = gate
                    return object : DirectResponse {
                        override val code = if (resume) 206 else 200
                        override fun header(name: String): String? = when (name) {
                            "ETag" -> "\"a\""
                            "Content-Length" -> if (resume) "3" else "6"
                            "Content-Range" -> if (resume) "bytes 3-5/6" else null
                            else -> null
                        }
                        override val body = if (resume) "def".byteInputStream() else object : InputStream() {
                            var chunk = 0
                            override fun read(): Int = error("bulk read required")
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                if (chunk >= 2) return -1
                                if (chunk == 1) { entered.countDown(); check(gate.await(5, TimeUnit.SECONDS)) }
                                if (stopped) throw IOException("cancelled")
                                (if (chunk++ == 0) "abc" else "def").toByteArray().copyInto(b, off)
                                return 3
                            }
                        }
                        override fun close() = Unit
                    }
                }
                override fun cancel() { stopped = true; release?.countDown() }
                override fun close() = cancel()
            } }) })
        val tasks = commands.associate { cmd -> cmd.jobId to async(Dispatchers.IO) { engine.download(cmd, directories.getValue(cmd.jobId)) {} } }
        try {
            check(entered.await(5, TimeUnit.SECONDS))
            engine.cancel("one")
            assertEquals(DownloadError.Cancelled, assertIs<TransferResult.Failure>(withTimeout(5000) { tasks.getValue("one").await() }).error)
            assertEquals("abc", directories.getValue("one").resolve(DirectResume.PART).readText())
            assertFalse(tasks.getValue("two").isCompleted); assertFalse(tasks.getValue("three").isCompleted)
            assertIs<TransferResult.Success>(engine.download(commands.first(), directories.getValue("one")) {})
            releases.getValue("two").countDown(); releases.getValue("three").countDown()
            assertIs<TransferResult.Success>(withTimeout(5000) { tasks.getValue("two").await() })
            assertIs<TransferResult.Success>(withTimeout(5000) { tasks.getValue("three").await() })
            for (dir in directories.values) assertEquals("abcdef", dir.resolve("output.mp4").readText())
            assertEquals(listOf(null, "bytes=3-"), requests.filter { it.first == "one" }.map { it.second })
            assertEquals(4, requests.size)
        } finally { releases.values.forEach { it.countDown() }; tasks.values.forEach { it.cancelAndJoin() } }
    }
    @Test fun cancellationAndProgressCannotInvertLifecycleAndWriterLocks() = runBlocking {
        val dir = temp.newFolder(); val cache = YtdlpInfoCache(temp.newFolder()); val command = command()
        cache.store(command.jobId, json, npe = false)
        val engine = engine(Runtime(json, dir), cache, Wire(200))
        val entered = CountDownLatch(1); val cancelDone = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val external = Thread { entered.await(); try { engine.cancel(command.jobId) } finally { cancelDone.countDown() } }.apply { isDaemon = true }
        val fromCallback = Thread { try { engine.cancel(command.jobId) } finally { secondDone.countDown() } }.apply { isDaemon = true }
        var once = false
        external.start()
        try {
            val result = engine.download(command, dir) { event ->
                if (event is EngineEvent.Progress && !once) {
                    once = true; entered.countDown()
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (cancelDone.count > 0 && external.state != Thread.State.BLOCKED && System.nanoTime() < until) Thread.yield()
                    fromCallback.start()
                    assertTrue(secondDone.await(2, TimeUnit.SECONDS), "cancel must not hold lifecycle lock while waiting for writer/progress")
                    assertTrue(cancelDone.await(2, TimeUnit.SECONDS), "external cancel must finish without waiting for progress callback")
                }
            }
            assertEquals(DownloadError.Cancelled, assertIs<TransferResult.Failure>(result).error)
        } finally { entered.countDown(); external.join(3000); if (fromCallback.state != Thread.State.NEW) fromCallback.join(3000) }
    }
    @Test fun controlRecordNeverCountsAsReceivedMediaForExpiryRetry() {
        val dir = temp.newFolder()
        DirectResume.write(dir, DirectResume.Record(null, null, null))
        assertFalse(hasPartialBytes(dir))
        dir.resolve(DirectResume.PART).writeText("abc")
        assertTrue(hasPartialBytes(dir))
    }
    @Test fun cancelAndWatchdogCloseTheNativeCallBeforeReturning() = runBlocking {
        for (stall in listOf(false, true)) {
            val dir = temp.newFolder(); val cache = YtdlpInfoCache(temp.newFolder()); val command = command()
            cache.store(command.jobId, json, npe = false)
            val entered = CountDownLatch(1); val released = CountDownLatch(1)
            val wire = object : Wire(200) {
                override fun execute(url: String, headers: Map<String, String>): DirectResponse = object : DirectResponse {
                    override val code = 200
                    override fun header(name: String) = when (name) { "Content-Length" -> "6"; "ETag" -> "\"a\""; else -> null }
                    override val body = object : InputStream() {
                        var first = true
                        override fun read(): Int = error("bulk read required")
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (first) { first = false; b[off] = 'a'.code.toByte(); return 1 }
                            entered.countDown(); check(released.await(5, TimeUnit.SECONDS)); throw IOException("https://example.com/private")
                        }
                    }
                    override fun close() = Unit
                }
                override fun cancel() { released.countDown() }
            }
            val engine = engine(Runtime(json, dir), cache, wire, EngineLimits(stallMs = if (stall) 100 else 5000))
            val task = async(Dispatchers.IO) { engine.download(command, dir) {} }
            try {
                check(entered.await(5, TimeUnit.SECONDS))
                if (!stall) engine.cancel(command.jobId)
                val failure = assertIs<TransferResult.Failure>(withTimeout(5000) { task.await() })
                assertEquals(if (stall) DownloadError.Network else DownloadError.Cancelled, failure.error)
                assertEquals(if (stall) DownloadFailureCause.STALL else DownloadFailureCause.OTHER, failure.cause)
                assertEquals("a", dir.resolve(DirectResume.PART).readText())
                assertFalse(failure.toString().contains("example.com"))
            } finally { released.countDown(); task.cancelAndJoin() }
        }
    }
}
