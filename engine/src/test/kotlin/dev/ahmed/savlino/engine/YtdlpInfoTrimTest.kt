package dev.ahmed.savlino.engine

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.ahmed.savlino.domain.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class YtdlpInfoTrimTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = LinkRevision(LinkInput.parse("https://example.org/video")!!, 1)
    private val json = """{
        "id":"fixture", "title":"Fixture", "duration":10, "extractor_key":"Youtube",
        "http_headers":{"User-Agent":"fixture"}, "extra":{"keep":[1,2]},
        "formats":[
            {"format_id":"137","ext":"mp4","vcodec":"h264","acodec":"none","http_headers":{"User-Agent":"video"}},
            {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"aac","downloader_options":{"http_chunk_size":10485760}},
            {"format_id":"18","ext":"mp4","vcodec":"h264","acodec":"aac"},
            {"format_id":"136","ext":"mp4","vcodec":"h264","acodec":"none"}
        ]
    }"""

    private fun prepare(row: Format, trim: Boolean = true, metadata: String = json): Pair<YtdlpInfoCache, DownloadCommand> {
        val cache = YtdlpInfoCache(temp.newFolder())
        val inspection = Inspection(source, "version", listOf(row))
        cache.remember(inspection, metadata, trimToChosen = trim)
        val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm(
            "job", inspection, source, row.id, "Fixture")).command
        cache.prepare(command)
        return cache to command
    }

    private fun assertTrimmed(row: Format, expectedIds: List<String>) {
        val (cache, command) = prepare(row)
        val tree = ObjectMapper().readTree(assertNotNull(cache.freshFile(command.jobId))) as ObjectNode
        val original = ObjectMapper().readTree(json) as ObjectNode
        assertEquals(expectedIds, tree.path("formats").map { it.path("format_id").asText() })
        // The entire selected format objects, including headers/options, survive unchanged.
        assertEquals(original.path("formats").filter { it.path("format_id").asText() in expectedIds },
            tree.path("formats").toList())
        tree.remove("formats"); original.remove("formats")
        assertEquals(original, tree)
    }

    @Test fun mergeKeepsOnlyConfirmedVideoAndAudioWithAllRootKeys() {
        assertTrimmed(Format("137+140", "mp4", true, true, sourceFormatId = "137",
            audioFormatId = "140", requiresMerge = true), listOf("137", "140"))
    }
    @Test fun audioOnlyKeepsSingleSource() { assertTrimmed(Format("140", "m4a", false, true), listOf("140")) }
    @Test fun muxedKeepsSingleSource() { assertTrimmed(Format("18", "mp4", true, true), listOf("18")) }
    @Test fun convertedAudioUsesSourceIdInsteadOfSyntheticRowId() {
        assertTrimmed(Format("mp3-fixture", "mp3", false, true, sourceFormatId = "140",
            conversion = AudioConversion.MP3_128), listOf("140"))
    }
    @Test fun untrimmedYtdlpJsonRemainsByteIdentical() {
        val (cache, command) = prepare(Format("18", "mp4", true, true), trim = false)
        assertEquals(json, assertNotNull(cache.freshFile(command.jobId)).readText())
    }
    @Test fun eitherMissingChosenIdWritesNothing() {
        for (row in listOf(Format("missing", "m4a", false, true),
            Format("missing-video", "mp4", true, true, sourceFormatId = "missing", audioFormatId = "140", requiresMerge = true),
            Format("missing-audio", "mp4", true, true, sourceFormatId = "137", audioFormatId = "missing", requiresMerge = true))) {
            val (cache, command) = prepare(row)
            assertNull(cache.freshFile(command.jobId))
        }
    }
    @Test fun malformedOrMissingFormatsWritesNothing() {
        for (metadata in listOf("{", "{}", """{"formats":{}}""")) {
            val (cache, command) = prepare(Format("18", "mp4", true, true), metadata = metadata)
            assertNull(cache.freshFile(command.jobId))
        }
    }
}
