package dev.ahmed.savlino.engine.download

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.EngineEvent
import java.io.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class DirectDownloaderTest {
    @get:Rule val temp = TemporaryFolder()
    private val row = DirectRequest("https://example.com/video.mp4", mapOf("User-Agent" to "test"))
    private class Reply(val status: Int = 200, val fields: Map<String, String> = mapOf("Content-Length" to "6"),
        val input: InputStream = "abcdef".byteInputStream()) : DirectResponse {
        override val code get() = status
        override fun header(name: String) = fields.entries.firstOrNull { it.key.equals(name, true) }?.value
        override val body get() = input
        override fun close() = input.close()
    }
    private class Wire(vararg replies: Reply) : DirectTransport {
        val requests = mutableListOf<Map<String, String>>()
        val replies = ArrayDeque(replies.toList())
        var cancelled = false
        override fun execute(url: String, headers: Map<String, String>): DirectResponse {
            requests += headers
            return replies.removeFirst()
        }
        override fun cancel() { cancelled = true }
        override fun close() = Unit
    }
    private fun download(dir: File, wire: Wire, cancelled: () -> Boolean = { false },
        events: MutableList<EngineEvent> = mutableListOf(), expired: () -> Boolean = { false }) =
        DirectDownloader(transport = { wire }).download(row, dir, cancelled, expired, events::add)

    @Test fun identicalBytesAndMeasuredTotal() {
        val dir = temp.newFolder(); val events = mutableListOf<EngineEvent>()
        assertEquals(DirectResult.Complete, download(dir, Wire(Reply(fields = mapOf("Content-Length" to "6", "ETag" to "\"a\""))), events = events))
        assertEquals("abcdef", dir.resolve("output.mp4").readText())
        assertEquals(EngineEvent.Progress(6, Size.Exact(6)), events.filterIsInstance<EngineEvent.Progress>().last())
        assertFalse(dir.resolve("native-resume").exists())
    }
    @Test fun droppedConnectionKeepsPrefixAndResumesAtExactOffset() {
        val dir = temp.newFolder()
        val drop = object : InputStream() { var reads = 0
            override fun read(): Int = if (reads++ < 3) 'a'.code else throw IOException("https://example.com/private")
        }
        val failure = assertIs<DirectResult.Failed>(download(dir, Wire(Reply(fields = mapOf("ETag" to "\"a\"", "Content-Length" to "6"), input = drop))))
        assertEquals(DownloadFailureCause.TRANSPORT, failure.cause)
        assertEquals("aaa", dir.resolve("output.mp4.part").readText())
        val wire = Wire(Reply(206, mapOf("ETag" to "\"a\"", "Content-Range" to "bytes 3-5/6", "Content-Length" to "3"), "def".byteInputStream()))
        assertEquals(DirectResult.Complete, download(dir, wire))
        assertEquals("aaadef", dir.resolve("output.mp4").readText())
        assertEquals("bytes=3-", wire.requests.single()["Range"])
        assertEquals("\"a\"", wire.requests.single()["If-Range"])
        assertFalse(failure.toString().contains("example.com"))
    }
    private fun partial(dir: File) {
        dir.resolve("output.mp4.part").writeText("abc")
        DirectResume.write(dir, DirectResume.Record("ETag", "\"a\"", 6, 3))
    }
    @Test fun rangeIgnoredWith200RestartsAndValidatorChangeNeverAppends() {
        for (status in listOf(200, 206)) {
            val dir = temp.newFolder(); partial(dir); val events = mutableListOf<EngineEvent>()
            val wire = Wire(Reply(status, mapOf("ETag" to "\"b\"", "Content-Length" to "6", "Content-Range" to "bytes 3-5/6")))
            val result = download(dir, wire, events = events)
            if (status == 200) {
                assertEquals(DirectResult.Complete, result)
                assertEquals("abcdef", dir.resolve("output.mp4").readText())
                assertTrue(events.any { it is EngineEvent.Restarting })
            } else {
                assertEquals(DirectResult.Fallback, result)
                assertFalse(dir.resolve("output.mp4.part").exists())
            }
        }
    }
    @Test fun interruptedRestartNeverPairsOldPrefixWithNewValidator() {
        val dir = temp.newFolder(); partial(dir)
        val wire = Wire(Reply(200, mapOf("ETag" to "\"b\"", "Content-Length" to "6")))
        val downloader = DirectDownloader({ wire }, { directory, record ->
            DirectResume.write(directory, record)
            throw IOException("simulated interruption")
        })
        assertIs<DirectResult.Failed>(downloader.download(row, dir, { false }, { false }, progress = {}))
        assertEquals(0L, dir.resolve("output.mp4.part").length())
        assertEquals("\"b\"", DirectResume.read(dir)?.validator)
    }
    @Test fun weakEtagUsesLastModifiedAndUnvalidatedPrefixRestarts() {
        val date = "Mon, 01 Jan 2001 00:00:00 GMT"
        for (validated in listOf(true, false)) {
            val dir = temp.newFolder()
            val headers = if (validated) mapOf("Last-Modified" to date, "ETag" to "W/\"a\"", "Content-Length" to "6")
                else mapOf("ETag" to "W/\"a\"", "Content-Length" to "6")
            assertIs<DirectResult.Failed>(download(dir, Wire(Reply(fields = headers, input = "abc".byteInputStream()))))
            val reply = if (validated) Reply(206, mapOf("Last-Modified" to date, "Content-Range" to "bytes 3-5/6"), "def".byteInputStream()) else Reply()
            val wire = Wire(reply); val events = mutableListOf<EngineEvent>()
            assertEquals(DirectResult.Complete, download(dir, wire, events = events))
            assertEquals("abcdef", dir.resolve("output.mp4").readText())
            assertEquals(if (validated) date else null, wire.requests.single()["If-Range"])
            assertEquals(if (validated) "bytes=3-" else null, wire.requests.single()["Range"])
            if (!validated) assertTrue(events.any { it is EngineEvent.Restarting })
        }
    }
    @Test fun malformedRangesAndEncodingDiscardBeforeFallback() {
        for (headers in listOf(mapOf("Content-Range" to "bytes 2-5/6"), mapOf("Content-Range" to "bytes 3-5/6", "Content-Encoding" to "gzip"))) {
            val dir = temp.newFolder(); partial(dir)
            assertEquals(DirectResult.Fallback, download(dir, Wire(Reply(206, headers))))
            assertFalse(dir.resolve("output.mp4.part").exists())
        }
    }
    @Test fun httpErrorsDoNotRetryAndForbiddenRequestsRefresh() {
        for ((status, expected) in listOf(403 to DirectResult.Expired, 503 to DirectResult.Fallback, 416 to DirectResult.Fallback)) {
            val dir = temp.newFolder(); partial(dir); val wire = Wire(Reply(status))
            assertEquals(expected, download(dir, wire))
            assertEquals(1, wire.requests.size)
            assertFalse(dir.resolve("output.mp4.part").exists())
        }
    }
    @Test fun unknownLengthFallsBackWithoutPublishingAnUnverifiablePrefix() {
        val dir = temp.newFolder(); val events = mutableListOf<EngineEvent>()
        assertEquals(DirectResult.Fallback, download(dir, Wire(Reply(fields = emptyMap())), events = events))
        assertFalse(dir.resolve("output.mp4").exists())
        assertFalse(dir.resolve(DirectResume.PART).exists())
        assertTrue(events.filterIsInstance<EngineEvent.Progress>().all { it.total == Size.Unknown })
    }
    @Test fun policyFailureDiscardsPrefixInsteadOfRetryingNativeAsNetwork() {
        val dir = temp.newFolder(); partial(dir)
        val wire = object : DirectTransport {
            override fun execute(url: String, headers: Map<String, String>): DirectResponse = throw DirectPolicyRejected()
            override fun cancel() = Unit
            override fun close() = Unit
        }
        assertEquals(DirectResult.Fallback, DirectDownloader({ wire }).download(row, dir, { false }, { false }, {}))
        assertFalse(dir.resolve(DirectResume.PART).exists())
        assertFalse(dir.resolve(DirectResume.NAME).exists())
    }
    @Test fun resumeTruncatesUnsyncedTailAndCompleteCheckpointNeedsNoGet() {
        val dir = temp.newFolder(); dir.resolve(DirectResume.PART).writeText("abcBAD")
        DirectResume.write(dir, DirectResume.Record("ETag", "\"a\"", 6, 3))
        val wire = Wire(Reply(206, mapOf("ETag" to "\"a\"", "Content-Range" to "bytes 3-5/6"), "def".byteInputStream()))
        assertEquals(DirectResult.Complete, download(dir, wire))
        assertEquals("bytes=3-", wire.requests.single()["Range"])
        assertEquals("abcdef", dir.resolve("output.mp4").readText())
        val full = temp.newFolder(); full.resolve(DirectResume.PART).writeText("abcdef")
        DirectResume.write(full, DirectResume.Record("ETag", "\"a\"", 6, 6))
        val unused = Wire()
        assertEquals(DirectResult.Complete, download(full, unused))
        assertTrue(unused.requests.isEmpty())
        assertEquals("abcdef", full.resolve("output.mp4").readText())
    }
    @Test fun checkpointsBoundCrashLossAndPreserveGracefulDropPrefix() {
        val dir = temp.newFolder(); val size = 8 * 1024 * 1024
        val seen = mutableListOf<Long?>()
        val wire = Wire(Reply(fields = mapOf("Content-Length" to "${size + 6}", "ETag" to "\"a\""),
            input = ByteArray(size + 3) { 'a'.code.toByte() }.inputStream()))
        val downloader = DirectDownloader({ wire }, { directory, record -> seen += record.syncedBytes; DirectResume.write(directory, record) })
        assertIs<DirectResult.Failed>(downloader.download(row, dir, { false }, { false }, {}))
        assertTrue(seen.contains(size.toLong()))
        assertEquals((size + 3).toLong(), DirectResume.read(dir)?.syncedBytes)
        assertEquals((size + 3).toLong(), dir.resolve(DirectResume.PART).length())
    }
    @Test fun filesystemErrorsMapToSpaceOnlyForEnospc() {
        for ((reason, expected) in listOf("Permission denied" to DownloadError.Failed, "ENOSPC" to DownloadError.InsufficientSpace)) {
            val wire = Wire(Reply(fields = mapOf("Content-Length" to "6")))
            val downloader = DirectDownloader({ wire }, { _, _ -> throw java.nio.file.FileSystemException("example", null, reason) })
            assertEquals(expected, assertIs<DirectResult.Failed>(downloader.download(row, temp.newFolder(), { false }, { false }, {})).error)
        }
    }
    @Test fun shortBodyRemainsPartial() {
        val short = temp.newFolder()
        assertEquals(DownloadFailureCause.TRANSPORT, assertIs<DirectResult.Failed>(download(short,
            Wire(Reply(fields = mapOf("Content-Length" to "9", "ETag" to "\"a\""))))).cause)
        assertEquals("abcdef", short.resolve("output.mp4.part").readText())
    }
    @Test fun cancellationDuringReadWritesNoReturnedBytesAndStallIsTyped() {
        var cancelled = false
        val input = object : ByteArrayInputStream("abcdef".toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { cancelled = true; return super.read(b, off, len) }
        }
        val dir = temp.newFolder()
        assertEquals(DownloadError.Cancelled, assertIs<DirectResult.Failed>(download(dir, Wire(Reply(input = input)), { cancelled })).error)
        assertEquals(0, dir.resolve("output.mp4.part").length())
        assertEquals(DownloadFailureCause.STALL, assertIs<DirectResult.Failed>(download(temp.newFolder(), Wire(Reply()), expired = { true })).cause)
    }
}
