package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

internal val audioTagsWithoutArtist = listOf(
    "--embed-metadata", "--embed-thumbnail", "--convert-thumbnails", "jpg",
    "--parse-metadata", ":(?P<meta_comment>)", "--parse-metadata", ":(?P<meta_purl>)",
    "--parse-metadata", ":(?P<meta_description>)", "--parse-metadata", ":(?P<meta_synopsis>)",
    "--parse-metadata", ":(?P<meta_genre>)", "--parse-metadata", ":(?P<meta_date>)",
    "--parse-metadata", ":(?P<meta_artist>)",
    "--replace-in-metadata", "title", "[\\U00010000-\\U0010ffff\\u200b-\\u200d\\ufeff]", "",
    "--replace-in-metadata", "title", "\\s+$", "")

class YtdlpAudioTagsTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = LinkRevision(assertNotNull(LinkInput.parse("https://example.org/audio")), 1)
    private fun command(format: Format) = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm(
        "job-audio", Inspection(source, "v1", listOf(format)), source, format.id, "عنوان 🎵 123")).command
    private val audio = listOf(
        Format("140", "m4a", false, true),
        Format("extracted", "m4a", false, true, sourceFormatId = "18"),
        Format("mp3-128", "mp3", false, true, sourceFormatId = "140", conversion = AudioConversion.MP3_128),
        Format("mp3-320", "mp3", false, true, sourceFormatId = "140", conversion = AudioConversion.MP3_320),
        Format("native-mp3", "mp3", false, true))

    @Test fun supportedAudioEmbedsPrivateTagsWithExactFormatAndUnchangedFilename() {
        val info = temp.newFile().apply { writeText("""{"uploader":"Channel"}""") }
        for (format in audio) {
            val dir = temp.newFolder().canonicalFile
            val args = YtdlpCommand.download(command(format), dir, info)
            assertEquals(audioTagsWithoutArtist, args.subList(args.indexOf("--embed-metadata"), args.indexOf("--load-info-json")))
            assertEquals(format.sourceFormatId, args[args.indexOf("-f") + 1])
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
        override fun update() = Unit
        override fun destroy(processId: String) = true
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
        for (format in audio) {
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
}
