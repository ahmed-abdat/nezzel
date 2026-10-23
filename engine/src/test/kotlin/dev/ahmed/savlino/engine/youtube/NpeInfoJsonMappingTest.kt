package dev.ahmed.savlino.engine.youtube

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.YtdlpMetadataMapper
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import kotlin.test.*

class NpeInfoJsonMappingTest {
    private val json = ObjectMapper()
    private val source = LinkRevision(assertNotNull(LinkInput.parse("https://example.org/fixture")), 1)
    private fun map(info: StreamInfo) = assertNotNull(NpeInfoJson.build(info)).let {
        it to YtdlpMetadataMapper.map(it, source, "fixture-version") }

    @Test fun originalAudioWinsMergeSelectionAndDubsAreHidden() {
        val (text, inspection) = map(NpeFixtures.info(audios = listOf(
            NpeFixtures.audio(track = "fr.1", type = AudioTrackType.DUBBED, bitrate = 256_000),
            NpeFixtures.audio(track = "en.4", type = AudioTrackType.ORIGINAL),
            NpeFixtures.audio(track = "en.4", type = AudioTrackType.ORIGINAL, drc = true))))
        assertEquals("140-ten.4", inspection.formats.first { it.hasVideo }.audioFormatId)
        assertFalse(inspection.formats.any { it.sourceFormatId == "140-tfr.1" })
        assertTrue(NpeInfoJson.accepts(text, inspection))
    }

    @Test fun mirroredHttpsItagsKeepRecordedHeightsAndAudioChoices() {
        val recorded = json.readTree(javaClass.getResource("/ytdlp/recorded-youtube-hls-dubs.json")!!.readText()) as ObjectNode
        val direct = recorded.path("formats").filter { it.path("protocol").asText() == "https" }
        val videos = direct.filter { it.path("vcodec").asText() != "none" }.map { row ->
            NpeFixtures.video(row.path("format_id").asInt(), row.path("height").asInt(), row.path("vcodec").asText(),
                format = if (row.path("ext").asText() == "webm") MediaFormat.WEBM else MediaFormat.MPEG_4) }
        val audios = direct.filter { it.path("vcodec").asText() == "none" }.map { row ->
            val id = row.path("format_id").asText().split('-')
            NpeFixtures.audio(id[0].toInt(), "fixture.${id[1]}",
                if (row.path("language_preference").asInt() >= 0) AudioTrackType.ORIGINAL else AudioTrackType.DUBBED,
                codec = row.path("acodec").asText(), average = row.path("abr").asDouble().toInt(),
                bitrate = (row.path("abr").asDouble() * 1000).toInt(),
                format = if (row.path("ext").asText() == "webm") MediaFormat.WEBMA_OPUS else MediaFormat.M4A) }
        val (_, actual) = map(NpeFixtures.info(videos, audios))
        recorded.set<com.fasterxml.jackson.databind.JsonNode>("formats", json.valueToTree(direct))
        val expected = YtdlpMetadataMapper.map(recorded.toString(), source, "fixture-version")
        fun heights(inspection: Inspection) = inspection.formats.filter { it.hasVideo }.mapNotNull { it.height }.toSet()
        fun audioChoices(inspection: Inspection) = inspection.formats.filter { !it.hasVideo && it.hasAudio }
            .map { Triple(it.container, it.audioCodec, it.conversion) }.toSet()
        assertEquals(setOf(144, 240, 360, 480, 720, 1080, 1440, 2160), heights(actual))
        assertEquals(heights(expected), heights(actual))
        assertEquals(audioChoices(expected), audioChoices(actual))
        assertEquals(expected.formats.count { !it.hasVideo }, actual.formats.count { !it.hasVideo })
    }

    @Test fun acceptsUntypedAudio() {
        val (text, inspection) = map(NpeFixtures.info())
        assertTrue(NpeInfoJson.accepts(text, inspection))
    }

    @Test fun partialSuccessWithErrorsCannotBeMapped() {
        assertNull(NpeInfoJson.build(NpeFixtures.info().apply { addError(IOException("Fixture error")) }))
    }

    @Test fun dubOnlyIsRejected() {
        val (text, inspection) = map(NpeFixtures.info(audios = listOf(NpeFixtures.audio(type = AudioTrackType.DUBBED))))
        assertFalse(NpeInfoJson.accepts(text, inspection))
    }

    @Test fun originalDroppedByBuilderDoesNotRescueSurvivingDub() {
        val (text, inspection) = map(NpeFixtures.info(audios = listOf(
            NpeFixtures.audio(140, type = AudioTrackType.ORIGINAL, url = "http://media.test/original"),
            NpeFixtures.audio(251, type = AudioTrackType.DUBBED, codec = "opus", format = MediaFormat.WEBMA_OPUS))))
        assertFalse(NpeInfoJson.accepts(text, inspection))
    }

    @Test fun originalDroppedByMapperDoesNotRescueSurvivingDub() {
        val (text, inspection) = map(NpeFixtures.info(audios = listOf(
            NpeFixtures.audio(140, type = AudioTrackType.ORIGINAL, codec = null),
            NpeFixtures.audio(251, type = AudioTrackType.DUBBED, codec = "opus", format = MediaFormat.WEBMA_OPUS))))
        assertFalse(NpeInfoJson.accepts(text, inspection))
    }

    @Test fun videoOnlyAndAudioOnlyInspectionsAreRejected() {
        val (text, inspection) = map(NpeFixtures.info())
        assertFalse(NpeInfoJson.accepts(text, Inspection(source, "fixture-version", inspection.formats.filter { it.hasVideo })))
        assertFalse(NpeInfoJson.accepts(text, Inspection(source, "fixture-version", inspection.formats.filterNot { it.hasVideo })))
    }

    @Test fun absentSurvivingJsonIdCannotRescueDub() {
        val (text, inspection) = map(NpeFixtures.info(audios = listOf(NpeFixtures.audio(type = AudioTrackType.DUBBED))))
        val rows = inspection.formats + Format("not-in-json", "m4a", false, true, audioCodec = "aac")
        assertFalse(NpeInfoJson.accepts(text, Inspection(source, "fixture-version", rows)))
    }
}
