package dev.ahmed.savlino.engine.youtube

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.engine.YtdlpCommand
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.*
import java.io.IOException
import java.util.Locale
import kotlin.test.*

class NpeInfoJsonTest {
    private val json = ObjectMapper()
    private fun root(info: StreamInfo = NpeFixtures.info()) = json.readTree(assertNotNull(NpeInfoJson.build(info)))
    private fun formats(info: StreamInfo = NpeFixtures.info()) = root(info).path("formats").toList()

    @Test fun requiredKeysHaveYtdlpTypesAndNoWebpageUrl() {
        val root = root()
        for (key in listOf("id", "title", "uploader", "thumbnail", "extractor", "extractor_key", "_type"))
            assertTrue(root.path(key).isTextual, key)
        assertTrue(root.path("duration").isIntegralNumber)
        assertEquals("https://images.test/large.jpg", root.path("thumbnail").asText())
        assertEquals("youtube", root.path("extractor").asText())
        assertEquals("Youtube", root.path("extractor_key").asText())
        assertEquals("video", root.path("_type").asText())
        assertFalse(root.has("webpage_url"))
        for (format in root.path("formats")) {
            for (key in listOf("format_id", "url", "protocol", "ext", "vcodec", "acodec"))
                assertTrue(format.path(key).isTextual, key)
            assertEquals("https", format.path("protocol").asText())
            assertFalse(format.path("has_drm").asBoolean(true))
            assertTrue(format.path("http_headers").path("User-Agent").isTextual)
            assertEquals(10_485_760, format.path("downloader_options").path("http_chunk_size").asInt())
        }
        val video = root.path("formats").first()
        for (key in listOf("width", "height", "fps", "tbr")) assertTrue(video.path(key).isNumber, key)
        assertEquals(800.0, video.path("tbr").asDouble())
        assertEquals(128.0, root.path("formats").last().path("abr").asDouble())
    }

    @Test fun plainAndDrcIdsStayExactAndUnique() {
        val ids = formats(NpeFixtures.info(audios = listOf(NpeFixtures.audio(), NpeFixtures.audio(drc = true))))
            .map { it.path("format_id").asText() }
        assertEquals(listOf("137", "140", "140-drc"), ids)
        assertTrue(ids.all(YtdlpCommand::isExactId))
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun multiAudioDrcIdsKeepSerbianAndNumericTracksTMarked() {
        val ids = formats(NpeFixtures.golden()).map { it.path("format_id").asText() }
        assertEquals(listOf("137", "136", "140", "140-drc", "251-ten.4", "251-ten.4-drc", "251-tsr", "251-t2-drc"), ids)
        assertTrue(ids.all(YtdlpCommand::isExactId))
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun sanitisedTrackCollisionsFallBackInsteadOfLosingFormats() {
        assertNull(NpeInfoJson.build(NpeFixtures.info(audios = listOf(
            NpeFixtures.audio(track = "en/4"), NpeFixtures.audio(track = "en?4")))))
    }

    @Test fun trackTypesMapPreferencesAndUnknownTypeStaysAbsent() {
        val tracks = listOf(AudioTrackType.DESCRIPTIVE, AudioTrackType.ORIGINAL,
            AudioTrackType.DUBBED, AudioTrackType.SECONDARY, null)
        val rows = formats(NpeFixtures.info(audios = tracks.mapIndexed { index, type ->
            NpeFixtures.audio(140 + index, "en.$index", type) })).drop(1)
        assertEquals(listOf(-10, 10, -1, -1), rows.take(4).map { it.path("language_preference").asInt() })
        assertFalse(rows.last().has("language_preference"))
        assertTrue(rows.all { it.path("language").asText() == "en" })
        assertEquals("fr-CA", formats(NpeFixtures.info(audios = listOf(
            NpeFixtures.audio(locale = Locale.forLanguageTag("fr-CA"))))).last().path("language").asText())
    }

    @Test fun contentLengthAloneProducesExactSize() {
        for (length in listOf(-1L, 0L, 1234L)) {
            val rows = formats(NpeFixtures.info(videos = listOf(NpeFixtures.video(length = length)),
                audios = listOf(NpeFixtures.audio(length = length))))
            for (row in rows) {
                assertEquals(length > 0, row.has("filesize"))
                if (length > 0) { assertEquals(length, row.path("filesize").asLong()); assertFalse(row.has("filesize_approx")) }
            }
        }
    }

    @Test fun estimatesUseBitrateTimesDurationOnlyWhenBothKnown() {
        assertEquals(1_000_000L, formats().first().path("filesize_approx").asLong())
        assertEquals(160_000L, formats().last().path("filesize_approx").asLong())
        assertEquals(500_000L, formats(NpeFixtures.info(videos = listOf(
            NpeFixtures.video(durationMs = 5000)))).first().path("filesize_approx").asLong())
        for (duration in listOf(-1L, 0L)) assertTrue(formats(NpeFixtures.info(duration = duration))
            .none { it.has("filesize_approx") })
        val unknown = formats(NpeFixtures.info(videos = listOf(NpeFixtures.video(bitrate = 0)),
            audios = listOf(NpeFixtures.audio(bitrate = 0, average = -1))))
        assertTrue(unknown.none { it.has("filesize") || it.has("filesize_approx") })
    }

    @Test fun nonProgressiveAndNonHttpsStreamsAreSkipped() {
        val info = NpeFixtures.info(videos = listOf(NpeFixtures.video()) +
            DeliveryMethod.values().filter { it != DeliveryMethod.PROGRESSIVE_HTTP }.mapIndexed { index, method ->
                NpeFixtures.video(200 + index, method = method) } + NpeFixtures.video(18, url = "http://media.test/video"),
            audios = listOf(NpeFixtures.audio(), NpeFixtures.audio(251, method = DeliveryMethod.DASH),
                NpeFixtures.audio(250, url = "http://media.test/audio")))
        assertEquals(listOf("137", "140"), formats(info).map { it.path("format_id").asText() })
    }

    @Test fun liveAndOtherNonVideoTypesReturnNull() {
        for (type in StreamType.values().filter { it != StreamType.VIDEO_STREAM })
            assertNull(NpeInfoJson.build(NpeFixtures.info(type = type)), type.name)
    }

    @Test fun upcomingReturnsNull() {
        assertNull(NpeInfoJson.build(NpeFixtures.info().apply { setContentAvailability(ContentAvailability.UPCOMING) }))
    }

    // Made-for-kids shape seen live: StreamInfo holds one muxed 360p row and no adaptive or audio rows.
    @Test fun muxedOnlyLowQualityInfoIsAMissWhileAdaptiveInfoStillUsesNpe() {
        assertNull(NpeInfoJson.build(NpeFixtures.info(videos = listOf(NpeFixtures.video(18, 360, only = false)),
            audios = emptyList())))
        assertNull(NpeInfoJson.build(NpeFixtures.info(videos = listOf(NpeFixtures.video(18, 360, only = false)))))
        assertNotNull(NpeInfoJson.build(NpeFixtures.info(videos = listOf(NpeFixtures.video(18, 360, only = false),
            NpeFixtures.video(137, 1080)))))
    }

    @Test fun noProgressiveVideoOnlyReturnsNullEvenWithMuxedVideo() {
        assertNull(NpeInfoJson.build(NpeFixtures.info(videos = listOf(NpeFixtures.video(18, only = false)))))
        assertNull(NpeInfoJson.build(NpeFixtures.info(videos = listOf(NpeFixtures.video(method = DeliveryMethod.DASH)))))
        assertNull(NpeInfoJson.build(NpeFixtures.info(videos = listOf(NpeFixtures.video(url = "http://media.test/video")))))
    }

    @Test fun noProgressiveAudioReturnsNull() {
        assertNull(NpeInfoJson.build(NpeFixtures.info(audios = emptyList())))
        assertNull(NpeInfoJson.build(NpeFixtures.info(audios = listOf(NpeFixtures.audio(method = DeliveryMethod.HLS)))))
    }

    @Test fun partialSuccessWithErrorsReturnsNull() {
        assertNull(NpeInfoJson.build(NpeFixtures.info().apply { addError(IOException("Fixture failure")) }))
        for (message in listOf("Couldn't get audio streams", "Couldn't get video streams", "Couldn't get video only streams"))
            assertNull(NpeInfoJson.build(NpeFixtures.info().apply { addError(ExtractionException(message, IOException())) }))
    }

    @Test fun optionalFieldErrorsKeepTheFastPath() {
        assertNotNull(NpeInfoJson.build(NpeFixtures.info().apply {
            addError(ParsingException("Could not get description")); addError(ExtractionException("Couldn't get DASH manifest"))
        }))
    }

    @Test fun muxedItag18SplitsCodecsAndUnknownCodecsStayAbsent() {
        val rows = formats(NpeFixtures.info(videos = listOf(NpeFixtures.video(),
            NpeFixtures.video(18, 360, "avc1.42001E, mp4a.40.2", only = false),
            NpeFixtures.video(136, codec = null)), audios = listOf(NpeFixtures.audio(codec = null))))
        val byId = rows.associateBy { it.path("format_id").asText() }
        assertEquals("avc1.42001E", byId.getValue("18").path("vcodec").asText())
        assertEquals("mp4a.40.2", byId.getValue("18").path("acodec").asText())
        assertFalse(byId.getValue("136").has("vcodec")); assertEquals("none", byId.getValue("136").path("acodec").asText())
        assertFalse(rows.last().has("acodec")); assertEquals("none", rows.last().path("vcodec").asText())
    }

    @Test fun clientUrlSelectsMatchingNpeUserAgent() {
        for ((client, expected) in listOf("ANDROID" to YoutubeParsingHelper.getAndroidUserAgent(null),
            "IOS" to YoutubeParsingHelper.getIosUserAgent(null), "VISIONOS" to YoutubeParsingHelper.getVisionOsUserAgent(null))) {
            val row = formats(NpeFixtures.info(videos = listOf(NpeFixtures.video(url = NpeFixtures.url(137) + "&c=$client")))).first()
            assertEquals(expected, row.path("http_headers").path("User-Agent").asText())
        }
        assertTrue(formats().first().path("http_headers").path("User-Agent").asText().startsWith("Mozilla/"))
    }

    @Test fun goldenFileMatchesBuilderBytes() {
        assertContentEquals(javaClass.getResourceAsStream("/npe/info-multiaudio-drc.json")!!.use { it.readBytes() },
            assertNotNull(NpeInfoJson.build(NpeFixtures.golden())).toByteArray(Charsets.UTF_8))
    }
}
