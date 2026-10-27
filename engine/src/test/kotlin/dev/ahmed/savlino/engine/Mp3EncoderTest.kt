package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.DownloadError
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Mp3EncoderTest {
    @get:Rule val temp = TemporaryFolder()

    /** Exits at once, blocks until killed, or (pipe) ends when its stdin closes. [onExit] runs when its code is read. */
    private class Fake(private val exit: Int = 0, blocks: Boolean = false, private val onClose: ((ByteArray) -> Unit)? = null,
        private val onExit: () -> Unit = {}) : Process() {
        val killed = CountDownLatch(1)
        private val ended = CountDownLatch(if (blocks || onClose != null) 1 else 0)
        private val stdin = object : ByteArrayOutputStream() {
            override fun close() { onClose?.invoke(toByteArray()); ended.countDown() }
        }
        override fun getOutputStream() = stdin
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(timeout: Long, unit: TimeUnit) = ended.await(timeout, unit)
        override fun waitFor(): Int { ended.await(); return code() }
        override fun exitValue() = code().also { onExit() }
        private fun code() = if (killed.count == 0L) 137 else exit
        override fun isAlive() = ended.count != 0L
        override fun destroy() { killed.countDown(); ended.countDown() }
        override fun destroyForcibly(): Process { destroy(); return this }
        fun finish() = ended.countDown()
    }

    private val rate = 44100
    private val kbps = 128
    private val total = 130L * rate // 130 s: eight chunks
    private val banner = "Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'file:x':\n  Duration: 00:02:10.00, start: 0.000000\n" +
        "  Stream #0:0[0x1](und): Audio: aac (LC) (mp4a / 0x6134706D), 44100 Hz, stereo, fltp, 127 kb/s\n"
    private val calls: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
    private fun arg(args: List<String>, name: String) = args[args.indexOf(name) + 1]
    private fun out(args: List<String>) = File(args.last().removePrefix("file:"))
    private fun chunks() = calls.filter { "-reservoir" in it }
    private fun singles() = calls.filter { "libmp3lame" in it && "-reservoir" !in it }
    private fun muxes() = calls.filter { "pipe:0" in it }

    /** A CBR frame carrying its single-pass index, so the mux order can be read back. */
    private fun frame(index: Int): ByteArray {
        val bytes = ByteArray(144_000 * kbps / rate)
        val h = (0x7FF shl 21) or (3 shl 19) or (1 shl 17) or (1 shl 16) or (9 shl 12)
        for (i in 0..3) bytes[i] = (h ushr (24 - 8 * i)).toByte()
        ByteBuffer.wrap(bytes, 10, 4).putInt(index)
        return bytes
    }
    private val frameSize = 144_000 * kbps / rate
    /** The copy mux's Info frame: all four Xing fields, delay/padding zero, counting [frames]. */
    private fun info(frames: Int) = frame(0).also { bytes ->
        "Info".toByteArray().copyInto(bytes, 36)
        ByteBuffer.wrap(bytes, 40, 8).putInt(0xF).putInt(frames)
    }
    private fun muxWrite(args: List<String>, extra: Int = 0): (ByteArray) -> Unit =
        { out(args).writeBytes(info(it.size / frameSize + extra) + it) }
    /** Audio frame indices after the Info frame. */
    private fun indices(file: File): List<Int> {
        val bytes = file.readBytes()
        assertEquals("Info", String(bytes, 36, 4))
        return (1 until bytes.size / frameSize).map { ByteBuffer.wrap(bytes, it * frameSize + 10, 4).int }
    }

    /** Writes what a chunk run would: stats proving its cut, and frames from+j for j in 0..span/1152+1. */
    private fun encodeChunk(args: List<String>, statsShift: Long = 0) {
        val trim = Regex("atrim=start_pts=(\\d+)(?::end_pts=(\\d+))?$").find(arg(args, "-af"))!!
        val start = trim.groupValues[1].toLong()
        val span = trim.groupValues[2].takeIf { it.isNotEmpty() }?.let { it.toLong() - start } ?: (total - start)
        File(arg(args, "-stats_enc_pre")).writeText(buildString {
            var sn = 0L
            while (sn < span) { val samp = minOf(1152L, span - sn); append("${start + sn + statsShift} $sn $samp\n"); sn += samp }
        })
        val from = (start / 1152).toInt()
        out(args).outputStream().use { o -> repeat((span / 1152 + 2).toInt()) { o.write(frame(from + it)) } }
    }

    private fun probe(args: List<String>, stderr: File, text: String = banner) {
        stderr.writeText(text)
        File(arg(args, "-stats_enc_pre")).writeText("0 0 1024\n1024 1024 1024\n2048 2048 1024\n")
    }

    private fun staging(): Pair<File, Mp3Job> {
        val dir = temp.newFolder()
        val source = dir.resolve("output.m4a").apply { writeText("source") }
        val cover = dir.resolve(Mp3Encoder.COVER).apply { writeText("jpg") }
        dir.resolve(Mp3Encoder.TAGS).writeText("{}")
        dir.resolve("result-path.txt").writeText(source.path + "\n")
        return dir to Mp3Job(dir, source, cover, listOf("title=Song", "artist="), kbps, resample = false)
    }

    /** The default fake: probe, chunks and mux succeed; one pass writes "single". */
    private fun starter(chunk: (List<String>) -> Process? = { encodeChunk(it); Fake() },
        mux: (List<String>) -> Process = { args -> Fake(onClose = muxWrite(args)) },
        probeText: String = banner): (List<String>, Boolean, File) -> Process? = { args, stdin, stderr ->
        calls += args
        when {
            "pcm_s16le" in args -> { probe(args, stderr, probeText); Fake() }
            "-reservoir" in args -> chunk(args)
            "pipe:0" in args -> { assertTrue(stdin); mux(args) }
            else -> { out(args).writeText("single"); Fake() }
        }
    }

    @Test fun chunksKeepEverySinglePassFrameOnceInOrderAndOnlyTheResultRemains() {
        val (dir, job) = staging()
        val result = Mp3Encoder(starter()).encode(job, "job")
        assertEquals(File(dir, "output.mp3"), result)
        val frames = indices(result)
        assertEquals((0 until frames.size).toList(), frames)
        assertTrue(frames.size * 1152L >= total)
        assertEquals(setOf("output.mp3", "result-path.txt"), dir.list()!!.toSet())
        assertEquals(result.path + "\n", dir.resolve("result-path.txt").readText())
        assertEquals(Mp3ChunkPlan.CHUNKS, chunks().size)
        assertTrue(singles().isEmpty())
        val chunk = chunks().first { "-ss" in it }
        for (flag in listOf("-noaccurate_seek", "-copyts", "-start_at_zero"))
            assertTrue(chunk.indexOf(flag) < chunk.indexOf("-i"), flag)
        assertEquals(listOf("-reservoir", "0", "-write_xing", "0", "-id3v2_version", "0"),
            chunk.subList(chunk.indexOf("-reservoir"), chunk.indexOf("-reservoir") + 6))
        assertEquals(listOf("-hide_banner", "-y", "-f", "mp3", "-i", "pipe:0", "-i", "file:${job.cover!!.path}",
            "-i", "file:${job.source.path}", "-map", "0:a", "-map", "1:v", "-map_metadata", "2", "-c", "copy",
            "-id3v2_version", "3", "-write_id3v1", "1", "-metadata", "title=Song", "-metadata", "artist=",
            "-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)",
            "-f", "mp3", "file:${dir.resolve("output.temp.enc").path}"), muxes().single())
    }

    @Test fun theFirstFailedChunkCheckStopsEveryOtherEncoderAtOnceAndOnePassRuns() {
        val (dir, job) = staging()
        val others = Collections.synchronizedList(mutableListOf<Fake>())
        val othersStarted = CountDownLatch(Mp3ChunkPlan.WORKERS - 1)
        val started = System.nanoTime()
        val encoder = Mp3Encoder(starter(chunk = { args ->
            if ("-ss" !in args) Fake(blocks = true).also { first ->
                // Chunk 0 lands one sample late once the others run.
                thread { if (othersStarted.await(5, TimeUnit.SECONDS)) { encodeChunk(args, statsShift = 1); first.finish() } }
            } else Fake(blocks = true).also { others += it; othersStarted.countDown() }
        }), heartbeatMs = 60_000, stallMs = 600_000)
        val result = encoder.encode(job, "job")
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10))
        assertEquals("single", result.readText())
        assertEquals(1, singles().size)
        assertTrue(muxes().isEmpty())
        assertTrue(others.isNotEmpty() && others.all { it.killed.count == 0L })
        assertTrue(chunks().size <= Mp3ChunkPlan.WORKERS, "no chunk starts after the failure")
        assertEquals(setOf("output.mp3", "result-path.txt"), dir.list()!!.toSet())
        // The single pass carries the same tags and cover from the source.
        val single = singles().single()
        assertEquals("0", arg(single, "-map_metadata"))
        assertEquals("128k", arg(single, "-b:a"))
        assertTrue("-reservoir" !in single && "-ar" !in single)
        assertEquals(listOf("title=Song", "artist="), single.indices.filter { single[it] == "-metadata" }.map { single[it + 1] })
    }

    @Test fun ineligibleSourcesAndConstrainedPhonesRunOnePass() {
        val webm = "Input #0, matroska,webm, from 'file:x':\n  Duration: 00:02:10.00\n  Stream #0:0: Audio: opus, 48000 Hz\n"
        assertEquals("single", Mp3Encoder(starter(probeText = webm)).encode(staging().second, "a").readText())
        assertTrue(chunks().isEmpty())
        calls.clear()
        assertEquals("single", Mp3Encoder(starter(), constrained = { true }).encode(staging().second, "b").readText())
        assertEquals(1, calls.size, "constrained: no probe")
        calls.clear()
        val short = banner.replace("00:02:10.00", "00:01:59.00")
        assertEquals("single", Mp3Encoder(starter(probeText = short)).encode(staging().second, "c").readText())
        assertTrue(chunks().isEmpty())
    }

    @Test fun aFullDiskEndsTheEncodeWithoutFallingBack() {
        val (dir, job) = staging()
        val encoder = Mp3Encoder(starter(chunk = { args ->
            File(dir, "output.temp.e" + out(args).name.takeLast(2)).writeText("Error: No space left on device\n")
            Fake(exit = 1)
        }))
        assertEquals(DownloadError.InsufficientSpace, assertFailsWith<Mp3Failure> { encoder.encode(job, "job") }.error)
        assertTrue(singles().isEmpty())
        assertTrue(job.source.isFile, "the source stays for a retry")
    }

    @Test fun cancelStopsEveryEncoderAndNeverFallsBack() {
        val (dir, job) = staging()
        val blocked = Collections.synchronizedList(mutableListOf<Fake>())
        val running = CountDownLatch(Mp3ChunkPlan.WORKERS)
        val encoder = Mp3Encoder(starter(chunk = { Fake(blocks = true).also { blocked += it; running.countDown() } }))
        var failure: Throwable? = null
        val worker = thread { failure = runCatching { encoder.encode(job, "job") }.exceptionOrNull() }
        assertTrue(running.await(5, TimeUnit.SECONDS))
        assertTrue(encoder.cancel("job"))
        worker.join(10_000)
        assertFalse(worker.isAlive)
        assertEquals(DownloadError.Cancelled, (failure as Mp3Failure).error)
        assertTrue(blocked.all { it.killed.count == 0L })
        assertTrue(singles().isEmpty())
        assertEquals(setOf("output.m4a", Mp3Encoder.COVER, Mp3Encoder.TAGS, "result-path.txt"), dir.list()!!.toSet())
        assertFalse(encoder.cancel("job"))
    }

    @Test fun chunksThatStopGrowingAreAStallNotAFallback() {
        val (_, job) = staging()
        val encoder = Mp3Encoder(starter(chunk = { Fake(blocks = true) }), heartbeatMs = 10, stallMs = 100)
        assertEquals(DownloadError.MergeFailed, assertFailsWith<Mp3Failure> { encoder.encode(job, "job") }.error)
        assertTrue(singles().isEmpty())
    }

    @Test fun aFailedTaggedMuxRetriesWithoutCoverOrTags() {
        val (_, job) = staging()
        var attempt = 0
        val result = Mp3Encoder(starter(mux = { args ->
            if (attempt++ == 0) Fake(exit = 1, onClose = {}) else Fake(onClose = muxWrite(args))
        })).encode(job, "job")
        assertEquals(2, muxes().size)
        val retry = muxes().last()
        assertTrue("-metadata" !in retry && "-map_metadata" !in retry && "file:${job.cover!!.path}" !in retry)
        assertEquals((0 until indices(result).size).toList(), indices(result))
        assertTrue(singles().isEmpty())
    }

    @Test fun leftoversOfADeadProcessAreClearedAndTheRetryFinishesOnce() {
        val (dir, job) = staging()
        dir.resolve("output.temp.c03").writeText("stale")
        dir.resolve("output.temp.s03").writeText("stale")
        dir.resolve("output.temp.enc").writeText("stale")
        val result = Mp3Encoder(starter()).encode(job, "job")
        assertEquals((0 until indices(result).size).toList(), indices(result))
        assertEquals(setOf("output.mp3", "result-path.txt"), dir.list()!!.toSet())
    }

    @Test fun anInfoFrameThatDoesNotCountTheKeptFramesRunsOnePassWithoutTheChunksBesideIt() {
        val (dir, job) = staging()
        var beside: Set<String>? = null
        val base = starter(mux = { args -> Fake(onClose = muxWrite(args, extra = -1)) })
        val result = Mp3Encoder({ args, stdin, err ->
            if ("libmp3lame" in args && "-reservoir" !in args) beside = dir.list()!!.toSet()
            base(args, stdin, err)
        }).encode(job, "job")
        assertEquals("single", result.readText())
        assertEquals(setOf("output.m4a", Mp3Encoder.COVER, Mp3Encoder.TAGS, "result-path.txt"), beside)
    }

    @Test fun aCancelAfterTheLastChildEndedIsNotPublished() {
        val (dir, job) = staging()
        lateinit var encoder: Mp3Encoder
        var once = true
        encoder = Mp3Encoder(starter(mux = { args ->
            Fake(onClose = muxWrite(args), onExit = { if (once) { once = false; encoder.cancel("job") } })
        }))
        assertEquals(DownloadError.Cancelled, assertFailsWith<Mp3Failure> { encoder.encode(job, "job") }.error)
        assertFalse(dir.resolve("output.mp3").exists())
        assertTrue(job.source.isFile)
    }

    @Test fun aProbeThatWritesNothingFallsBackToOnePass() {
        val base = starter()
        val encoder = Mp3Encoder({ args, stdin, err -> if ("pcm_s16le" in args) { calls += args; Fake(blocks = true) } else base(args, stdin, err) },
            heartbeatMs = 10, stallMs = 100)
        assertEquals("single", encoder.encode(staging().second, "job").readText())
        assertTrue(chunks().isEmpty())
    }

    @Test fun waitingForAnotherJobsEncodersIsNotAStall() {
        val (_, job) = staging()
        Mp3Encoder.permits.acquire(Mp3ChunkPlan.WORKERS)
        val encoder = Mp3Encoder(starter(), heartbeatMs = 10, stallMs = 100)
        var failure: Throwable? = null
        var result: File? = null
        val worker = thread { runCatching { encoder.encode(job, "job") }.onSuccess { result = it }.onFailure { failure = it } }
        try { Thread.sleep(500) } finally { Mp3Encoder.permits.release(Mp3ChunkPlan.WORKERS) }
        worker.join(10_000)
        assertNull(failure)
        assertEquals(Mp3ChunkPlan.CHUNKS, chunks().size)
        assertEquals((0 until indices(result!!).size).toList(), indices(result!!))
    }

    @Test fun aFailedRenameKeepsTheSourceForTheRetry() {
        val (dir, job) = staging()
        dir.resolve("output.mp3").mkdir()
        dir.resolve("output.mp3/x").writeText("x")
        assertEquals(DownloadError.ExecutionRestricted, assertFailsWith<Mp3Failure> { Mp3Encoder(starter()).encode(job, "job") }.error)
        assertTrue(job.source.isFile && job.cover!!.isFile && dir.resolve(Mp3Encoder.TAGS).isFile)
    }

    @Test fun bannerGivesContainerRateAndDuration() {
        assertEquals(Mp3Input(true, 44100, 130_000_000), Mp3Input.parse(banner))
        val muxed = "Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'file:x':\n  Duration: 01:05:12.34, start: 0.000000\n" +
            "  Stream #0:0[0x1](und): Video: h264 (High), yuv420p, 1080x1920, 30 fps\n" +
            "  Stream #0:1[0x2](und): Audio: aac (LC) (mp4a / 0x6134706D), 48000 Hz, stereo\n"
        assertEquals(Mp3Input(true, 48000, 3_912_340_000), Mp3Input.parse(muxed))
        assertNull(Mp3Input.parse("Input #0, mov,mp4,m4a, from 'x':\n  Duration: N/A\n  Stream #0:0: Video: h264\n"))
        assertEquals(Mp3Input(true, 44100, null), Mp3Input.parse(banner.replace("00:02:10.00", "N/A")))
    }

    @Test fun tagsFollowYtdlpsFirstNonNullRuleAndBlankWhatTodayBlanks() {
        val json = """{"title":"Video","track":"Song","artists":["A","B"],"album":null,"series":"Show",""" +
            """"track_number":3,"composer":"","composers":["C"],"episode":"E\u0000x","uploader":"Up"}"""
        assertEquals(listOf("title=Song", "track=3", "artist=A, B", "album=Show", "show=Show", "episode_id=Ex",
            "comment=", "purl=", "description=", "synopsis=", "genre=", "date="), Mp3Tags.metadata(json, realArtist = true))
        // A blanked uploader fallback keeps add()'s position, as the meta_artist update does in yt-dlp.
        assertEquals(listOf("title=Video", "artist=", "comment=", "purl=", "description=", "synopsis=", "genre=", "date="),
            Mp3Tags.metadata("""{"title":"Video","uploader":"Up"}""", realArtist = false))
        assertEquals(listOf("comment=", "purl=", "description=", "synopsis=", "genre=", "date=", "artist="),
            Mp3Tags.metadata(null, realArtist = false))
    }
}
