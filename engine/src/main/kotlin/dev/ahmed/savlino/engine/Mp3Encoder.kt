package dev.ahmed.savlino.engine

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.domain.DownloadError
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What one MP3 row turns into: the downloaded [source], an optional square [cover] and `key=value` tags. */
internal data class Mp3Job(val directory: File, val source: File, val cover: File?, val metadata: List<String>,
    val bitrateKbps: Int, val resample: Boolean)

/** NoSpace, cancel (Cancelled) and stall (ConvertFailed) end the encode; only a failed check falls back. */
internal class Mp3Failure(val error: DownloadError) : Exception()

/** The source's container, input rate and duration, read from ffmpeg's banner on stderr. */
internal data class Mp3Input(val movFamily: Boolean, val rate: Int, val durationMicros: Long?) {
    companion object {
        private val input = Regex("Input #0, (.+?), from ")
        private val duration = Regex("Duration: (\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})")
        private val audio = Regex("Stream #0:\\d+.*?: Audio: .*?, (\\d+) Hz")

        fun parse(text: String): Mp3Input? {
            val demuxer = input.find(text)?.groupValues?.get(1) ?: return null
            val rate = audio.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val micros = duration.find(text)?.groupValues?.let { (_, h, m, s, cs) ->
                ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1_000_000 + cs.toLong() * 10_000
            }
            return Mp3Input(demuxer.split(',').first() == "mov", rate, micros)
        }
    }
}

/** yt-dlp's FFmpegMetadataPP.add() for the keys an MP3 row keeps; the rest are blanked as today's args do. */
internal object Mp3Tags {
    // album <- (album, series) as in yt-dlp ffmpeg.py add('album', ('album', 'series')), true of
    // the bundled 2026.10.26 and of the weekly STABLE update.
    private val fields = listOf(
        "title" to listOf("track", "title"), "track" to listOf("track_number"),
        "artist" to listOf("artist", "artists", "creator", "creators", "uploader", "uploader_id"),
        "composer" to listOf("composer", "composers"), "album" to listOf("album", "series"),
        "album_artist" to listOf("album_artist", "album_artists"), "disc" to listOf("disc_number"),
        "show" to listOf("series"), "season_number" to listOf("season_number"),
        "episode_id" to listOf("episode", "episode_id"), "episode_sort" to listOf("episode_number"))
    private val blanks = listOf("comment", "purl", "description", "synopsis", "genre", "date")

    /** `key=value` pairs. Without a real [artist] (the info JSON's own), yt-dlp's uploader fallback is blanked. */
    fun metadata(json: String?, realArtist: Boolean): List<String> {
        val info = try { json?.let { ObjectMapper().readTree(it) }?.takeIf(JsonNode::isObject) } catch (_: Exception) { null }
        val blank = blanks + if (realArtist) emptyList() else listOf("artist")
        val values = LinkedHashMap<String, String>()
        if (info != null) for ((key, sources) in fields) {
            // The first non-null source decides; an empty one adds nothing, as in add().
            val node = sources.firstNotNullOfOrNull { info.get(it)?.takeUnless(JsonNode::isNull) } ?: continue
            val value = (if (node.isArray) node.joinToString(", ") { it.asText() } else node.asText()).replace("\u0000", "")
            if (value.isNotEmpty()) values[key] = value
        }
        blank.forEach { values[it] = "" }
        return values.map { (key, value) -> "$key=$value" }
    }
}

/**
 * Encodes a downloaded source to `output.mp3`. When the probe proves every seam lines up ([Mp3ChunkPlan]) the source
 * is cut into chunks that encode in parallel and the kept frames are streamed into one tagging mux; anything else
 * runs one LAME pass. Tags and cover are written in that same last step, so there is no separate tagging run.
 * Every intermediate is an `output.temp.*` partial, so a process death leaves staging a retry accepts.
 */
internal class Mp3Encoder(
    private val start: (args: List<String>, stdin: Boolean, stderr: File) -> Process?,
    private val constrained: () -> Boolean = { false },
    private val heartbeatMs: Long = 5_000,
    private val stallMs: Long = 60_000,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private class Stopped : Exception()
    private class Group { val children = mutableListOf<Process>(); var stopped = false }
    private class Run {
        var cancelled = false
        val groups = mutableListOf<Group>()
    }
    private val runs = ConcurrentHashMap<String, Run>()

    fun cancel(processId: String): Boolean {
        val run = runs[processId] ?: return false
        synchronized(run) {
            run.cancelled = true
            run.groups.forEach { group -> group.children.forEach { it.destroyForcibly() } }
        }
        return true
    }

    fun encode(job: Mp3Job, processId: String): File {
        val run = Run()
        check(runs.putIfAbsent(processId, run) == null)
        val dir = job.directory
        try {
            clean(dir)
            val enc = File(dir, "output.temp.enc")
            val plan = if (constrained()) null else plan(job, run)
            val chunks = plan?.let { chunks(job, it, run) }
            // Without a patched Info frame gapless players would keep LAME's lead-in and tail: run one pass instead.
            val muxed = plan != null && chunks != null &&
                (mux(job, plan, chunks, run, tagged = true) || mux(job, plan, chunks, run, tagged = false)) &&
                Mp3Frames.patchLameTag(enc, plan.samples(chunks.last().samples), chunks.sumOf { kept(plan, it) })
            if (!muxed) {
                // The chunks and a half-written mux would otherwise sit beside the one pass's output.
                clean(dir)
                if (!single(job, run, tagged = true) && !single(job, run, tagged = false)) throw Mp3Failure(DownloadError.ConvertFailed)
            }
            // A cancel after the last child ended: nothing else would see it before the file is published.
            if (synchronized(run) { run.cancelled }) throw Mp3Failure(DownloadError.Cancelled)
            return finish(job, enc)
        } finally {
            // Best effort: the next run's clean() also removes them, and a failure here must not hide the real one.
            try { dir.listFiles().orEmpty().filter { SCRATCH.matches(it.name) }.forEach { it.delete() } }
            finally { runs.remove(processId, run) }
        }
    }

    // --- probe -----------------------------------------------------------------------------------------------------

    private fun plan(job: Mp3Job, run: Run): Mp3ChunkPlan? {
        val err = File(job.directory, "output.temp.err")
        val stats = File(job.directory, "output.temp.probe")
        val args = listOf("-nostdin", "-hide_banner", "-y", "-copyts", "-start_at_zero", "-i", "file:${job.source.path}",
            "-t", "2", "-vn") + (if (job.resample) listOf("-af", "aresample=$RESAMPLED") else emptyList()) +
            listOf("-c:a", "pcm_s16le") + stats(stats) + listOf("-f", "null", "-")
        // A two-second decode that writes no stats for stallMs is stuck: fall back to one pass, which has its own check.
        val ran = try { runToEnd(run, group(run), args, err, growth = stats) }
        catch (stall: Mp3Failure) { if (stall.error == DownloadError.ConvertFailed) false else throw stall }
        if (!ran) return null
        val input = Mp3Input.parse(err.readText()) ?: return null
        val probe = stats.useLines { Mp3Probe.parse(it) } ?: return null
        val rate = if (job.resample) RESAMPLED else input.rate
        return Mp3ChunkPlan.of(Mp3Source(input.movFamily, input.rate, rate, probe, input.durationMicros, constrained = false))
    }

    // --- chunks ----------------------------------------------------------------------------------------------------

    private class Encoded(val chunk: Mp3Chunk, val file: File, val offsets: LongArray, val samples: Long)

    /** Null: a chunk failed its checks, and the rest were stopped at once; the caller runs one pass instead. */
    private fun chunks(job: Mp3Job, plan: Mp3ChunkPlan, run: Run): List<Encoded>? {
        val group = group(run)
        val results = arrayOfNulls<Encoded>(plan.chunks.size)
        val failed = AtomicBoolean()
        val holding = AtomicInteger()
        var error: Mp3Failure? = null
        val latch = CountDownLatch(plan.chunks.size)
        val pool = Executors.newFixedThreadPool(Mp3ChunkPlan.WORKERS)
        try {
            for (chunk in plan.chunks) pool.execute {
                try {
                    permits.acquire()
                    holding.incrementAndGet()
                    try { results[chunk.index] = encodeChunk(job, plan, chunk, run, group) }
                    finally { holding.decrementAndGet(); permits.release() }
                    if (results[chunk.index] == null && !failed.getAndSet(true)) stop(run, group)
                } catch (failure: Mp3Failure) {
                    synchronized(results) { if (error == null) error = failure }
                    stop(run, group)
                } catch (_: Stopped) {
                } catch (_: InterruptedException) {
                } catch (_: Exception) {
                    // An unreadable chunk file counts as a failed check: stop the rest now, never wait for the stall.
                    if (!failed.getAndSet(true)) stop(run, group)
                } finally { latch.countDown() }
            }
            var bytes = -1L
            var grew = nanoTime()
            while (!latch.await(heartbeatMs, TimeUnit.MILLISECONDS)) {
                if (synchronized(run) { run.cancelled }) break
                // Waiting for another job's encoders is not a stall: the clock runs only while this job encodes.
                if (holding.get() == 0) { grew = nanoTime(); continue }
                val now = plan.chunks.sumOf { chunkFile(job, it).length() }
                if (now > bytes) { bytes = now; grew = nanoTime() }
                else if ((nanoTime() - grew) / 1_000_000 >= stallMs) {
                    synchronized(results) { if (error == null) error = Mp3Failure(DownloadError.ConvertFailed) }
                    stop(run, group)
                    break
                }
            }
        } finally {
            pool.shutdownNow()
            // Every worker reaps its own child: once they all ended no encoder can still write staging.
            joinPool(pool)
        }
        if (synchronized(run) { run.cancelled }) throw Mp3Failure(DownloadError.Cancelled)
        synchronized(results) { error }?.let { throw it }
        if (failed.get()) return null
        return results.map { it ?: return null }
    }

    private fun encodeChunk(job: Mp3Job, plan: Mp3ChunkPlan, chunk: Mp3Chunk, run: Run, group: Group): Encoded? {
        val name = chunk.index.toString().padStart(2, '0')
        val out = chunkFile(job, chunk)
        val stats = File(job.directory, "output.temp.s$name")
        val seek = plan.seekMicros(chunk).takeIf { it > 0 }
            ?.let { listOf("-ss", "${it / 1_000_000}.${(it % 1_000_000).toString().padStart(6, '0')}") }.orEmpty()
        val filter = buildString {
            plan.inputTrimStart(chunk)?.let { append("atrim=start_pts=$it,") }
            append("aresample=${plan.sampleRate},atrim=start_pts=${plan.startPts(chunk)}")
            plan.endPts(chunk)?.let { append(":end_pts=$it") }
        }
        val args = listOf("-nostdin", "-hide_banner", "-y") + seek + listOf("-noaccurate_seek", "-copyts", "-start_at_zero",
            "-i", "file:${job.source.path}", "-vn", "-af", filter, "-c:a", "libmp3lame", "-b:a", "${job.bitrateKbps}k",
            "-reservoir", "0", "-write_xing", "0", "-id3v2_version", "0") + stats(stats) +
            listOf("-f", "mp3", "file:${out.path}")
        if (!runToEnd(run, group, args, File(job.directory, "output.temp.e$name"), growth = null)) return null
        val samples = stats.useLines { plan.chunkSamples(chunk, it) } ?: return null
        val offsets = out.inputStream().buffered().use { Mp3Frames.offsets(it, job.bitrateKbps, plan.sampleRate) } ?: return null
        val frames = offsets.size - 1
        val (_, keepEnd) = plan.keep(chunk)
        if (frames < plan.minFrames(chunk) || (keepEnd ?: 0) > frames) return null
        return Encoded(chunk, out, offsets, samples)
    }

    private fun kept(plan: Mp3ChunkPlan, encoded: Encoded): Long {
        val (from, to) = plan.keep(encoded.chunk)
        return ((to ?: (encoded.offsets.size - 1)) - from).toLong()
    }

    private fun chunkFile(job: Mp3Job, chunk: Mp3Chunk) =
        File(job.directory, "output.temp.c${chunk.index.toString().padStart(2, '0')}")

    // --- mux and single pass ---------------------------------------------------------------------------------------

    /** Streams the kept frames into a `-c copy` mux that adds today's tags and cover. False: run it another way. */
    private fun mux(job: Mp3Job, plan: Mp3ChunkPlan, chunks: List<Encoded>, run: Run, tagged: Boolean): Boolean {
        val enc = File(job.directory, "output.temp.enc")
        val cover = job.cover.takeIf { tagged }
        val args = buildList {
            addAll(listOf("-hide_banner", "-y", "-f", "mp3", "-i", "pipe:0"))
            cover?.let { addAll(listOf("-i", "file:${it.path}")) }
            // The source joins as a metadata-only input: its global tags reach the file as ExtractAudio's did.
            if (tagged) addAll(listOf("-i", "file:${job.source.path}"))
            addAll(listOf("-map", "0:a"))
            if (cover != null) addAll(listOf("-map", "1:v"))
            if (tagged) addAll(listOf("-map_metadata", if (cover != null) "2" else "1"))
            addAll(listOf("-c", "copy") + tagArgs(job, cover, tagged) + listOf("-f", "mp3", "file:${enc.path}"))
        }
        val group = group(run)
        val child = startIn(run, group, args, stdin = true, err = File(job.directory, "output.temp.err"))
        val writeFailed = AtomicBoolean()
        val writer = Thread {
            try {
                child.outputStream.use { pipe ->
                    val buffer = ByteArray(64 * 1024)
                    for (encoded in chunks) RandomAccessFile(encoded.file, "r").use { file ->
                        val (from, to) = plan.keep(encoded.chunk)
                        file.seek(encoded.offsets[from])
                        var left = encoded.offsets[to ?: (encoded.offsets.size - 1)] - encoded.offsets[from]
                        while (left > 0) {
                            val read = file.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                            if (read < 0) throw IOException("short chunk")
                            pipe.write(buffer, 0, read)
                            left -= read
                        }
                    }
                }
            } catch (_: Exception) { writeFailed.set(true) }
        }
        writer.start()
        val ok = try { await(run, group, child, File(job.directory, "output.temp.err"), enc) }
        finally { joinThread(writer) }
        return ok && !writeFailed.get()
    }

    /** One LAME pass with the reservoir on, as ExtractAudio ran it, plus the same tags and cover. */
    private fun single(job: Mp3Job, run: Run, tagged: Boolean): Boolean {
        val enc = File(job.directory, "output.temp.enc")
        val cover = job.cover.takeIf { tagged }
        val args = buildList {
            addAll(listOf("-nostdin", "-hide_banner", "-y", "-i", "file:${job.source.path}"))
            cover?.let { addAll(listOf("-i", "file:${it.path}")) }
            addAll(listOf("-map", "0:a"))
            if (cover != null) addAll(listOf("-map", "1:v"))
            addAll(listOf("-map_metadata", if (tagged) "0" else "-1", "-c:a", "libmp3lame", "-b:a", "${job.bitrateKbps}k"))
            if (job.resample) addAll(listOf("-ar", "$RESAMPLED"))
            if (cover != null) addAll(listOf("-c:v", "copy"))
            addAll(tagArgs(job, cover, tagged) + listOf("-f", "mp3", "file:${enc.path}"))
        }
        return runToEnd(run, group(run), args, File(job.directory, "output.temp.err"), growth = enc)
    }

    private fun tagArgs(job: Mp3Job, cover: File?, tagged: Boolean): List<String> = buildList {
        addAll(listOf("-id3v2_version", "3", "-write_id3v1", "1"))
        if (tagged) job.metadata.forEach { addAll(listOf("-metadata", it)) }
        if (cover != null) addAll(listOf("-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)"))
    }

    /** The result takes the final name first, so a failed rename still leaves the source for a retry; then the rest goes. */
    private fun finish(job: Mp3Job, enc: File): File {
        val dir = job.directory
        val output = File(dir, "output.mp3")
        if (!enc.renameTo(output)) throw Mp3Failure(DownloadError.ExecutionRestricted)
        // This is a completion marker, not a crash-durability guarantee: neither the output nor manifest is
        // fsynced here. Adoption still rejects missing/empty output; power-loss durability is not promised.
        File(dir, "result-path.txt").writeText(output.path + "\n")
        // A thumbnail the convertor could not turn into jpg is left under its own extension.
        val thumbnails = dir.listFiles().orEmpty().filter { THUMBNAIL.matches(it.name) }
        (listOfNotNull(job.source, job.cover, File(dir, TAGS)) + thumbnails).forEach {
            if (it.exists() && !it.delete()) throw Mp3Failure(DownloadError.ExecutionRestricted)
        }
        return output
    }

    // --- processes -------------------------------------------------------------------------------------------------

    private fun group(run: Run) = Group().also { synchronized(run) { run.groups += it } }

    private fun stop(run: Run, group: Group) = synchronized(run) {
        group.stopped = true
        group.children.forEach { it.destroyForcibly() }
    }

    /** Starts and registers under the lock cancel sweeps, so no child can start after a cancel and outlive it. */
    private fun startIn(run: Run, group: Group, args: List<String>, stdin: Boolean, err: File): Process = synchronized(run) {
        if (run.cancelled) throw Mp3Failure(DownloadError.Cancelled)
        if (group.stopped) throw Stopped()
        val child = start(args, stdin, err) ?: throw Mp3Failure(DownloadError.EngineUnavailable)
        group.children += child
        child
    }

    private fun runToEnd(run: Run, group: Group, args: List<String>, err: File, growth: File?): Boolean =
        await(run, group, startIn(run, group, args, stdin = false, err = err), err, growth)

    /**
     * True on exit 0. Cancel, a full disk and (with [growth]) no new output for [stallMs] end the encode instead.
     * The child is always reaped before this returns.
     */
    private fun await(run: Run, group: Group, child: Process, err: File, growth: File?): Boolean {
        try {
            var size = -1L
            var grew = nanoTime()
            while (!child.waitFor(heartbeatMs, TimeUnit.MILLISECONDS)) {
                if (growth == null) continue
                val now = growth.length()
                if (now > size) { size = now; grew = nanoTime() }
                else if ((nanoTime() - grew) / 1_000_000 >= stallMs) {
                    stop(run, group)
                    reap(child)
                    throw Mp3Failure(DownloadError.ConvertFailed)
                }
            }
        } finally { reap(child) }
        if (synchronized(run) { run.cancelled }) throw Mp3Failure(DownloadError.Cancelled)
        if (synchronized(run) { group.stopped }) throw Stopped()
        if (child.exitValue() == 0) return true
        if (err.isFile && err.readText().contains("No space left on device")) throw Mp3Failure(DownloadError.InsufficientSpace)
        return false
    }

    private fun reap(child: Process) {
        if (child.isAlive) child.destroyForcibly()
        var interrupted = false
        while (true) { try { child.waitFor(); break } catch (_: InterruptedException) { interrupted = true } }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun joinThread(thread: Thread) {
        var interrupted = false
        while (true) { try { thread.join(); break } catch (_: InterruptedException) { interrupted = true } }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun joinPool(pool: java.util.concurrent.ExecutorService) {
        var interrupted = false
        while (true) {
            try { if (pool.awaitTermination(1, TimeUnit.MINUTES)) break } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun stats(file: File) = listOf("-stats_enc_pre", file.path, "-stats_enc_pre_fmt", "{pts} {sn} {samp}")

    /** Leftovers of an earlier run (process death) and this run's scratch files; never the source or the result. */
    private fun clean(dir: File) {
        dir.listFiles().orEmpty().filter { SCRATCH.matches(it.name) }.forEach {
            if (it.exists() && !it.delete()) throw Mp3Failure(DownloadError.ExecutionRestricted)
        }
    }

    companion object {
        const val TAGS = "output.temp.tags"
        const val COVER = "output.temp.jpg"
        private const val RESAMPLED = 44100
        private val THUMBNAIL = Regex("output\\.temp\\.(?:jpe?g|png|webp|avif|gif)")
        private val SCRATCH = Regex("output\\.temp\\.(?:[cse]\\d{2}|enc|probe|err)")
        /** Encoders across every job in the process: two MP3 rows never run 2×WORKERS at once. */
        // Fair: a second job's chunks queue behind the first's instead of being overtaken indefinitely.
        internal val permits = Semaphore(Mp3ChunkPlan.WORKERS, true)
    }
}
