package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

// Square album art: crop to min(width,height); the comma is escaped for ffmpeg's filter parser (one backslash in the value).
internal const val squareCover = "ThumbnailsConvertor+ffmpeg_o:-c:v mjpeg -qmin 1 -qscale:v 1 -vf \"crop=min(iw\\,ih):min(iw\\,ih)\""

internal val audioTagsWithoutArtist = listOf(
    "--embed-metadata", "--embed-thumbnail", "--convert-thumbnails", "jpg",
    "--postprocessor-args", squareCover,
    "--parse-metadata", ":(?P<meta_comment>)", "--parse-metadata", ":(?P<meta_purl>)",
    "--parse-metadata", ":(?P<meta_description>)", "--parse-metadata", ":(?P<meta_synopsis>)",
    "--parse-metadata", ":(?P<meta_genre>)", "--parse-metadata", ":(?P<meta_date>)",
    "--parse-metadata", ":(?P<meta_artist>)",
    "--replace-in-metadata", "title", "[\\U00010000-\\U0010ffff\\u200b-\\u200d\\ufeff]|&(?:amp;)?#(?:[0-9]+|[xX][0-9a-fA-F]+);", "",
    "--replace-in-metadata", "title", "\\s+$", "")

/** MP3 rows: yt-dlp writes the square cover and the tag fields; Mp3Encoder applies them. */
internal fun mp3SourceArgs(dir: File) = listOf(
    "-o", "thumbnail:" + File(dir, "output.temp.%(ext)s").path, "--write-thumbnail", "--convert-thumbnails", "jpg",
    "--postprocessor-args", squareCover, "--print-to-file",
    "after_move:%(.{title,track,track_number,artist,artists,creator,creators,uploader,uploader_id,composer,composers," +
        "album,album_artist,album_artists,disc_number,series,season_number,episode,episode_id,episode_number})j",
    File(dir, "output.temp.tags").path) + audioTagsWithoutArtist.takeLast(8)

class YtdlpAudioTagsTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = LinkRevision(assertNotNull(LinkInput.parse("https://example.org/audio")), 1)
    private fun command(format: Format) = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm(
        "job-audio", Inspection(source, "v1", listOf(format)), source, format.id, "عنوان 🎵 123")).command
    private val audio = listOf(
        Format("140", "m4a", false, true),
        Format("extracted", "m4a", false, true, sourceFormatId = "18"),
        Format("mp3-128", "mp3", false, true, sourceFormatId = "140", conversion = AudioConversion.MP3_128),
        Format("mp3-192", "mp3", false, true, sourceFormatId = "140", conversion = AudioConversion.MP3_192),
        Format("mp3-320", "mp3", false, true, sourceFormatId = "140", conversion = AudioConversion.MP3_320),
        Format("native-mp3", "mp3", false, true))

    @Test fun supportedAudioEmbedsPrivateTagsWithExactFormatAndUnchangedFilename() {
        val info = temp.newFile().apply { writeText("""{"uploader":"Channel"}""") }
        for (format in audio) {
            val dir = temp.newFolder().canonicalFile
            val args = YtdlpCommand.download(command(format), dir, info)
            if (format.conversion != null) {
                assertEquals(mp3SourceArgs(dir), args.subList(args.indexOf("--write-thumbnail") - 2, args.indexOf("--load-info-json")))
                assertTrue(args.none { it == "--extract-audio" || it == "--embed-metadata" || "libmp3lame" in it })
                assertEquals(File(dir, "output.%(ext)s").path, args[args.indexOf("-o") + 1])
                continue
            }
            assertEquals(audioTagsWithoutArtist, args.subList(args.indexOf("--embed-metadata"), args.indexOf("--load-info-json")))
            assertEquals(format.sourceFormatId, args[args.indexOf("-f") + 1])
            assertEquals(squareCover, args[args.indexOf("--convert-thumbnails") + 3])
            assertEquals("""ThumbnailsConvertor+ffmpeg_o:-c:v mjpeg -qmin 1 -qscale:v 1 -vf "crop=min(iw\,ih):min(iw\,ih)"""", YtdlpCommand.SQUARE_COVER)
            assertEquals(YtdlpCommand.SQUARE_COVER, squareCover)
            assertEquals(File(dir, "output.%(ext)s").path, args[args.indexOf("-o") + 1])
            assertFalse(args.any { source.link.rawUrl in it })
            assertFalse(args.any { "عنوان" in it || "Channel" in it })
        }
    }

    @Test fun videoAndUnsupportedAudioNeverAddTaggingArguments() {
        for (format in listOf(Format("video", "mp4", true, true), Format("video-m4a", "m4a", true, true),
            Format("webm", "webm", false, true, sourceFormatId = "251"), Format("opus-audio", "opus", false, true))) {
            val args = YtdlpCommand.download(command(format), temp.newFolder())
            assertTrue(args.none { it in setOf("--embed-metadata", "--embed-thumbnail", "--convert-thumbnails",
                "--parse-metadata", "--replace-in-metadata") })
        }
    }

    @Test fun onlyNonblankTextualArtistInLoadedInfoAvoidsArtistBlanking() {
        for ((json, hasArtist) in listOf(
            """{"artist":"فنان","uploader":"Channel"}""" to true,
            """{"artist":"  Artist  "}""" to true,
            """{"uploader":"Channel","creator":"Creator"}""" to false,
            """{"artist":"  "}""" to false, """{"artist":null}""" to false,
            """{"artist":123}""" to false, "invalid" to false, null to false)) {
            val info = json?.let { temp.newFile().apply { writeText(it) } }
            val args = YtdlpCommand.download(command(audio.first()), temp.newFolder(), info)
            assertEquals(!hasArtist, ":(?P<meta_artist>)" in args)
            // URL fallback remains positional; no tag flag can receive a URL.
            assertFalse((if (info == null) args.dropLast(2) else args).any { source.link.rawUrl in it })
        }
    }

    private class Runtime : YtdlpRuntime {
        override val initialization = EngineInitialization()
        val calls = mutableListOf<List<String>>()
        var action: (List<String>, (String) -> Unit) -> String = { _, _ -> "" }
        override fun initialize() = Unit
        override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
            if ("--version" in args) return "v1"
            calls += args
            return action(args, line)
        }
        var ffmpeg: (List<String>) -> Process? = { null }
        override fun startFfmpeg(args: List<String>, stdin: Boolean, stderr: File) = ffmpeg(args)
        override fun constrained() = true
        override fun update() = Unit
        override fun destroy(processId: String) = true
    }
    private class Exited : Process() {
        override fun getOutputStream() = java.io.ByteArrayOutputStream()
        override fun getInputStream() = java.io.ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = java.io.ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
        override fun exitValue() = 0
        override fun isAlive() = false
        override fun destroy() = Unit
    }

    @Test fun artistDecisionUsesTheSameConfirmedInfoJsonLoadedByTheDownload() = runBlocking {
        for (hasArtist in listOf(true, false)) {
            val format = audio.first()
            val inspection = Inspection(source, "v1", listOf(format))
            val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm(
                "cached-audio", inspection, source, format.id, "title")).command
            val json = if (hasArtist) """{"artist":"Artist","uploader":"Channel"}""" else """{"uploader":"Channel"}"""
            val cache = YtdlpInfoCache(temp.newFolder()).apply { remember(inspection, json) }
            val dir = temp.newFolder().canonicalFile
            val runtime = Runtime().apply { action = { args, _ ->
                assertEquals(json, File(args[args.indexOf("--load-info-json") + 1]).readText())
                assertEquals(!hasArtist, ":(?P<meta_artist>)" in args)
                assertFalse(args.any { source.link.rawUrl in it })
                File(dir, "output.m4a").writeText("audio")
                File(dir, "result-path.txt").writeText(File(dir, "output.m4a").path)
                ""
            } }
            assertIs<TransferResult.Success>(YoutubedlEngine(runtime, infoCache = cache).download(command, dir) {})
        }
    }

    @Test fun exactAudioCompletenessUsesBytesBeforeTaggingShrinksMetadata() = runBlocking {
        for (container in listOf("mp3", "m4a")) {
            for (taggingFails in listOf(false, true)) {
                val dir = temp.newFolder().canonicalFile
                val output = File(dir, "output.$container")
                val format = Format("native-audio", container, false, true, size = Size.Exact(1000))
                val runtime = Runtime().apply { action = { args, line ->
                    if ("--embed-metadata" in args) {
                        // Metadata parsing before transfer must not snapshot a missing file.
                        line("[MetadataParser] Parsed title")
                        output.writeBytes(ByteArray(1000))
                        line("nezzel:native-audio:1000:1000:NA")
                        line("[Metadata] Adding metadata")
                        output.writeBytes(ByteArray(950))
                        if (taggingFails) error("ERROR: Postprocessing: cover failed")
                    }
                    File(dir, "result-path.txt").writeText(output.path)
                    ""
                } }
                val result = YoutubedlEngine(runtime).download(command(format), dir) {}
                assertEquals(950L, assertIs<TransferResult.Success>(result).output.file.length())
                assertEquals(if (taggingFails) 2 else 1, runtime.calls.size)
            }
        }
    }

    @Test fun delayedTaggingCallbackUsesMeasuredTransferBytesRatherThanAlreadyRewrittenFile() = runBlocking {
        val dir = temp.newFolder().canonicalFile
        val output = File(dir, "output.mp3")
        val runtime = Runtime().apply { action = { _, line ->
            output.writeBytes(ByteArray(1000))
            line("nezzel:native-audio:1000:1000:NA")
            // Native processing may outrun the wrapper's stdout reader.
            output.writeBytes(ByteArray(950))
            line("[Metadata] Adding metadata")
            File(dir, "result-path.txt").writeText(output.path)
            ""
        } }
        val format = Format("native-audio", "mp3", false, true, size = Size.Exact(1000))
        val result = YoutubedlEngine(runtime).download(command(format), dir) {}
        assertEquals(950L, assertIs<TransferResult.Success>(result).output.file.length())
    }

    @Test fun truncatedAudioStillFailsWhenTaggingExpandsIt() = runBlocking {
        val dir = temp.newFolder().canonicalFile
        val output = File(dir, "output.mp3")
        val runtime = Runtime().apply { action = { _, line ->
            output.writeBytes(ByteArray(950))
            line("nezzel:native-audio:950:950:NA")
            line("[Metadata] Adding metadata")
            output.writeBytes(ByteArray(1100))
            File(dir, "result-path.txt").writeText(output.path)
            ""
        } }
        val format = Format("native-audio", "mp3", false, true, size = Size.Exact(1000))
        assertEquals(TransferResult.Failure(DownloadError.MergeFailed),
            YoutubedlEngine(runtime).download(command(format), dir) {})
    }

    @Test fun taggingFailureRetriesOnceOnSameAudioAndPublishesWithoutArtworkSidecars() = runBlocking {
        // MP3 rows tag in Mp3Encoder's mux, which has its own untagged retry.
        for (format in audio.filter { it.conversion == null }) {
            val dir = temp.newFolder().canonicalFile
            val output = File(dir, "output.${format.container}")
            val runtime = Runtime().apply { action = { args, line ->
                if ("--embed-thumbnail" in args) {
                    output.writeText("already downloaded audio")
                    File(dir, "output.jpg").writeText("cover")
                    File(dir, "output.temp.${format.container}").writeText("incomplete tag rewrite")
                    File(dir, "result-path.txt").writeText("stale\nstale")
                    line("[EmbedThumbnail] Adding thumbnail")
                    error("ERROR: Postprocessing: cover failed")
                }
                assertEquals("already downloaded audio", output.readText())
                assertFalse(File(dir, "output.jpg").exists())
                assertFalse(File(dir, "result-path.txt").exists())
                File(dir, "result-path.txt").writeText(output.path)
                ""
            } }
            val events = mutableListOf<EngineEvent>()
            val result = YoutubedlEngine(runtime).download(command(format), dir, events::add)
            assertEquals(output, assertIs<TransferResult.Success>(result).output.file)
            assertEquals(2, runtime.calls.size)
            val first = runtime.calls.first()
            assertEquals(first.take(first.indexOf("--embed-metadata")) + first.takeLast(2), runtime.calls.last())
            assertEquals(setOf(output.name, "result-path.txt"), dir.list()!!.toSet())
            assertFalse(events.any { it is EngineEvent.Restarting })
        }
    }

    @Test fun aRetryAfterADeathBeforeTheMuxReusesTheSourceAndEndsWithOnlyTheMp3() = runBlocking {
        for (sourceContainer in listOf("m4a", "mp3")) {
            val format = Format("mp3-192", "mp3", false, true, sourceFormatId = "140", sourceContainer = sourceContainer,
                conversion = AudioConversion.MP3_192)
            val dir = temp.newFolder().canonicalFile
            val staged = File(dir, if (sourceContainer == "mp3") "output" else "output.m4a").apply { writeText("downloaded audio") }
            // What a process death mid-encode leaves: the kept source, cover and tags, a chunk and a half-written mux.
            File(dir, "output.temp.jpg").writeText("cover")
            File(dir, "output.temp.tags").writeText("""{"title":"Old","uploader":"Channel"}""" + "\n")
            File(dir, "output.temp.c03").writeText("chunk")
            File(dir, "output.temp.enc").writeText("half")
            val runtime = Runtime().apply { action = { args, _ ->
                assertTrue("--write-thumbnail" in args && "--extract-audio" !in args)
                assertEquals("downloaded audio", staged.readText()) // kept: yt-dlp reports it as already downloaded
                File(dir, "result-path.txt").appendText(staged.path + "\n")
                // --print-to-file appends this run's tags after the dead run's line.
                File(dir, "output.temp.tags").appendText("""{"title":"Song","uploader":"Channel"}""" + "\n")
                ""
            } }
            val ffmpeg = mutableListOf<List<String>>()
            runtime.ffmpeg = { args -> ffmpeg += args; File(args.last().removePrefix("file:")).writeText("mp3"); Exited() }
            // The inspection's info JSON keeps the staged source; without it the engine restarts from nothing.
            val inspection = Inspection(source, "v1", listOf(format))
            val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm(
                "job-audio", inspection, source, format.id, "title")).command
            val cache = YtdlpInfoCache(temp.newFolder()).apply { remember(inspection, """{"uploader":"Channel"}""") }
            val result = assertIs<TransferResult.Success>(YoutubedlEngine(runtime, infoCache = cache).download(command, dir) {})
            assertEquals(File(dir, "output.mp3"), result.output.file)
            assertEquals("mp3", result.output.file.readText())
            assertEquals(setOf("output.mp3", "result-path.txt"), dir.list()!!.toSet())
            assertEquals(1, runtime.calls.size)
            val single = ffmpeg.single()
            assertEquals("file:${staged.path}", single[single.indexOf("-i") + 1])
            assertTrue("file:${File(dir, "output.temp.jpg").path}" in single)
            assertEquals(listOf("title=Song", "artist=", "comment=", "purl=", "description=", "synopsis=", "genre=", "date="),
                single.indices.filter { single[it] == "-metadata" }.map { single[it + 1] })
        }
    }

    @Test fun secondPostprocessingFailureRemainsMergeFailed() = runBlocking {
        val runtime = Runtime().apply { action = { _, _ -> error("ERROR: Postprocessing: failed") } }
        assertEquals(TransferResult.Failure(DownloadError.MergeFailed),
            YoutubedlEngine(runtime).download(command(audio.first()), temp.newFolder()) {})
        assertEquals(2, runtime.calls.size)
    }

    @Test fun cancellationWinsOverTaggingFallback() = runBlocking {
        val runtime = Runtime()
        val engine = YoutubedlEngine(runtime)
        runtime.action = { _, _ -> engine.cancel("job-audio"); error("ERROR: Postprocessing: failed") }
        assertEquals(TransferResult.Failure(DownloadError.Cancelled), engine.download(command(audio.first()), temp.newFolder()) {})
        assertEquals(1, runtime.calls.size)
    }

    @Test fun missingThumbnailIsSuccessAndTransportOrVideoFailuresNeverRetryTagging() = runBlocking {
        val dir = temp.newFolder().canonicalFile
        val runtime = Runtime().apply { action = { _, line ->
            line("[EmbedThumbnail] There aren't any thumbnails to embed")
            File(dir, "output.m4a").writeText("audio")
            File(dir, "result-path.txt").writeText(File(dir, "output.m4a").path)
            ""
        } }
        assertIs<TransferResult.Success>(YoutubedlEngine(runtime).download(command(audio.first()), dir) {})
        assertEquals(1, runtime.calls.size)
        for ((format, message, expected) in listOf(
            Triple(audio.first(), "ERROR: Connection reset by peer", DownloadError.Network),
            Triple(Format("18", "mp4", true, true), "ERROR: Postprocessing: failed", DownloadError.MergeFailed))) {
            val failed = Runtime().apply { action = { _, _ -> error(message) } }
            assertEquals(expected, assertIs<TransferResult.Failure>(
                YoutubedlEngine(failed).download(command(format), temp.newFolder()) {}).error)
            assertEquals(1, failed.calls.size)
        }
    }

    @Test fun keptSourceConvertsAgainWithoutRunningYtdlp() = runBlocking {
        val format = Format("mp3-192", "mp3", false, true, sourceFormatId = "140", sourceContainer = "m4a",
            conversion = AudioConversion.MP3_192)
        val dir = temp.newFolder().canonicalFile
        val staged = File(dir, "output.m4a").apply { writeText("downloaded audio") }
        File(dir, "output.temp.tags").writeText("""{"title":"Song","artist":"Singer","uploader":"Channel"}""" + "\n")
        val runtime = Runtime().apply { action = { _, _ -> fail("A kept-source retry must not run yt-dlp") } }
        val ffmpeg = mutableListOf<List<String>>()
        runtime.ffmpeg = { args -> ffmpeg += args; File(args.last().removePrefix("file:")).writeText("mp3"); Exited() }
        val result = assertIs<TransferResult.Success>(YoutubedlEngine(runtime).convertKept(command(format), dir) {})
        assertEquals(File(dir, "output.mp3"), result.output.file)
        assertTrue(runtime.calls.isEmpty())
        val single = ffmpeg.single()
        assertEquals("file:${staged.path}", single[single.indexOf("-i") + 1])
        // Retry dropped the info JSON: the printed tags still say the artist is real.
        assertTrue("artist=Singer" in single)
        // No kept source (or not an MP3 row): the caller downloads in full.
        assertNull(YoutubedlEngine(runtime).convertKept(command(format), temp.newFolder()) {})
        assertNull(YoutubedlEngine(runtime).convertKept(command(audio.first()), dir) {})
    }

    @Test fun finishedMp3RetryValidatesAndReturnsWithoutYtdlpOrEncoding() = runBlocking {
        val format = Format("mp3-192", "mp3", false, true, sourceFormatId = "140", sourceContainer = "m4a",
            conversion = AudioConversion.MP3_192)
        val dir = temp.newFolder().canonicalFile
        val output = File(dir, "output.mp3").apply { writeText("finished mp3") }
        File(dir, "result-path.txt").writeText(output.path + "\n")
        File(dir, "output.m4a.part-Frag184.part").writeText("leftover fragment")
        val runtime = Runtime().apply {
            action = { _, _ -> fail("Finished output must not transfer") }
            ffmpeg = { fail("Finished output must not encode") }
        }
        val result = assertIs<TransferResult.Success>(YoutubedlEngine(runtime).convertKept(command(format), dir) {})
        assertEquals(output, result.output.file)
        assertEquals("finished mp3", output.readText())
        assertEquals(setOf("output.mp3", "result-path.txt"), dir.list()!!.toSet())
        assertTrue(runtime.calls.isEmpty())
    }

    @Test fun finishedMp3WithoutManifestProofIsNeverReturnedByConvertKept() = runBlocking {
        val format = Format("mp3-192", "mp3", false, true, sourceFormatId = "140", sourceContainer = "m4a",
            conversion = AudioConversion.MP3_192)
        for (proof in listOf("missing", "empty", "wrong", "multiple", "symlink")) {
            val dir = temp.newFolder().canonicalFile
            val output = File(dir, "output.mp3").apply { writeText(if (proof == "empty") "" else "unconfirmed") }
            val manifest = File(dir, "result-path.txt")
            when (proof) {
                "missing" -> Unit
                "wrong" -> manifest.writeText(File(dir, "output.m4a").path)
                "multiple" -> manifest.writeText(output.path + "\n" + output.path)
                "symlink" -> java.nio.file.Files.createSymbolicLink(manifest.toPath(),
                    temp.newFile().apply { writeText(output.path) }.toPath())
                else -> manifest.writeText(output.path)
            }
            val runtime = Runtime().apply { ffmpeg = { fail("Unconfirmed target must not encode") } }
            assertNull(YoutubedlEngine(runtime).convertKept(command(format), dir) {}, proof)
            assertTrue(runtime.calls.isEmpty())
        }
    }

    @Test fun longLiveMp3DropsOnlyKnownTransferDebrisAfterEncode() = runBlocking {
        val format = Format("mp3-192", "mp3", false, true, sourceFormatId = "140", sourceContainer = "m4a",
            conversion = AudioConversion.MP3_192)
        val dir = temp.newFolder().canonicalFile
        val debris = listOf("output.m4a.part", "output.m4a.ytdl", "output.m4a.part-Frag184.part")
        val runtime = Runtime().apply {
            action = { _, _ ->
                File(dir, "output.m4a").writeText("downloaded audio")
                File(dir, "result-path.txt").writeText(File(dir, "output.m4a").path)
                debris.forEach { File(dir, it).writeText("fragment") }
                ""
            }
            ffmpeg = { args -> File(args.last().removePrefix("file:")).writeText("finished mp3"); Exited() }
        }
        val result = assertIs<TransferResult.Success>(YoutubedlEngine(runtime).download(command(format), dir) {})
        assertEquals("finished mp3", result.output.file.readText())
        assertEquals(setOf("output.mp3", "result-path.txt"), dir.list()!!.toSet())
        assertEquals(1, runtime.calls.size)
    }

    @Test fun failureWritingManifestAfterEncodeKeepsTheFinishedMp3() = runBlocking {
        val format = Format("mp3-192", "mp3", false, true, sourceFormatId = "140", sourceContainer = "m4a",
            conversion = AudioConversion.MP3_192)
        val dir = temp.newFolder().canonicalFile
        val runtime = Runtime().apply {
            action = { _, _ ->
                File(dir, "output.m4a").writeText("downloaded audio")
                File(dir, "result-path.txt").writeText(File(dir, "output.m4a").path)
                ""
            }
            ffmpeg = { args ->
                File(args.last().removePrefix("file:")).writeText("finished mp3")
                // Rename succeeds, but finish cannot replace the manifest with a regular file.
                File(dir, "result-path.txt").delete()
                File(dir, "result-path.txt").mkdir()
                Exited()
            }
        }
        val result = assertIs<TransferResult.Failure>(YoutubedlEngine(runtime).download(command(format), dir) {})
        assertEquals(DownloadError.PostTransferFailed, result.error)
        assertEquals(ExecutionFailureReason.STORAGE_STAGING, result.reason)
        assertEquals("finished mp3", File(dir, "output.mp3").readText())
        assertEquals("downloaded audio", File(dir, "output.m4a").readText())
    }

    @Test fun keptSourceThatStillFailsToConvertReportsConvertFailed() = runBlocking {
        val format = Format("mp3-128", "mp3", false, true, sourceFormatId = "140", sourceContainer = "m4a",
            conversion = AudioConversion.MP3_128)
        val dir = temp.newFolder().canonicalFile
        File(dir, "output.m4a").writeText("downloaded audio")
        val runtime = Runtime().apply { ffmpeg = { Failed() } }
        assertEquals(DownloadError.ConvertFailed, assertIs<TransferResult.Failure>(
            YoutubedlEngine(runtime).convertKept(command(format), dir) {}).error)
        assertTrue(File(dir, "output.m4a").isFile)
    }

    @Test fun ytdlpPostprocessingFailureOnAnMp3RowIsAConversionNotAMerge() {
        val video = Format("137", "mp4", true, true, audioFormatId = "140", requiresMerge = true)
        assertEquals(DownloadError.MergeFailed, YtdlpCommand.postProcessingError(video, DownloadError.MergeFailed))
        assertEquals(DownloadError.MergeFailed, YtdlpCommand.postProcessingError(audio.first(), DownloadError.MergeFailed))
        for (format in audio) assertEquals(if (format.conversion != null) DownloadError.ConvertFailed else DownloadError.MergeFailed,
            YtdlpCommand.postProcessingError(format, DownloadError.MergeFailed), format.id)
        assertEquals(DownloadError.Network, YtdlpCommand.postProcessingError(audio[2], DownloadError.Network))
    }

    private class Failed : Process() {
        override fun getOutputStream() = java.io.ByteArrayOutputStream()
        override fun getInputStream() = java.io.ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = java.io.ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 1
        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
        override fun exitValue() = 1
        override fun isAlive() = false
        override fun destroy() = Unit
    }
}
