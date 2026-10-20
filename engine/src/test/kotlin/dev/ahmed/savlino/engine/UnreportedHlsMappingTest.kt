package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/** Sanitized Pinterest HLS roles: video renditions and a separate audio playlist, not muxed files. */
class UnreportedHlsMappingTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = LinkRevision(assertNotNull(LinkInput.parse("https://www.pinterest.com/pin/1/")), 1)
    private fun map(rows: String) = YtdlpMetadataMapper.map(
        """{"extractor":"Pinterest","title":"Sample pin","formats":[$rows]}""", source, "fixture")
    private fun video(id: String, height: Int, codec: String = "unknown") =
        """{"format_id":"$id","ext":"mp4","protocol":"m3u8_native","vcodec":"$codec","acodec":"none",
            "height":$height,"url":"https://media.example/$id.m3u8"}"""
    private fun audio(id: String = "audio", codec: String = "unknown", bitrate: Int = 128) =
        """{"format_id":"$id","ext":"mp4","protocol":"m3u8_native","vcodec":"none","acodec":"$codec",
            "tbr":$bitrate,"url":"https://media.example/$id.m3u8"}"""

    @Test fun unknownCodecHlsVideoRowsMergeTheBestActualAudioWithoutInventingCodecsOrSizes() {
        val inspection = map(listOf(video("v240", 240), video("v720", 720), audio("a64", bitrate = 64), audio("a128")).joinToString())
        val videos = inspection.formats.filter { it.hasVideo }
        assertEquals(listOf("v240", "v720"), videos.map { it.id })
        for (row in videos) {
            assertFalse(row.hasAudio, "the video playlist itself is silent")
            assertTrue(row.requiresMerge)
            assertEquals("a128", row.audioFormatId)
            assertEquals("mkv", row.container, "unknown audio is not asserted MP4-compatible")
            assertNull(row.videoCodec); assertNull(row.audioCodec)
            assertEquals(Size.Unknown, row.size)
            val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job", inspection, source, row.id, "Pin")).command
            val args = YtdlpCommand.download(command, temp.newFolder().canonicalFile)
            assertEquals("${row.id}+a128", args[args.indexOf("-f") + 1])
            assertEquals("mkv", args[args.indexOf("--merge-output-format") + 1])
        }
        assertEquals("v720", inspection.presets.single { it.preset == Preset.BEST }.formatId)
        val track = inspection.formats.single { it.id == "a128" }
        assertFalse(track.hasVideo); assertTrue(track.hasAudio); assertNull(track.audioCodec)
    }

    @Test fun knownH264StillWinsPresetsWithCodecLessAudioOverHigherHevcAv1AndUnknownVideo() {
        val inspection = map(listOf(video("h264", 360, "avc1.4d401e"), video("hevc", 720, "hvc1"),
            video("av1", 1080, "av01"), video("unknown", 1440), audio()).joinToString())
        assertEquals("h264", inspection.presets.single { it.preset == Preset.BEST }.formatId)
        assertTrue(inspection.formats.filter { it.hasVideo }.all { it.audioFormatId == "audio" && it.requiresMerge })
    }

    @Test fun absentAudioCodecAndVideoCodecUseOnlyReportedHlsStreamRoles() {
        val inspection = map(video("v", 720).replace("\"vcodec\":\"unknown\",", "") + "," +
            audio().replace("\"acodec\":\"unknown\",", ""))
        assertEquals("audio", inspection.formats.single { it.hasVideo }.audioFormatId)
        assertEquals("v", inspection.presets.single { it.preset == Preset.BEST }.formatId)
        val unknown = map(video("v", 720).replace("\"acodec\":\"none\"", "\"acodec\":\"unknown\"") + "," + audio())
        assertFalse(unknown.formats.single { it.hasVideo }.hasAudio)
        assertTrue(unknown.formats.single { it.hasVideo }.requiresMerge)
    }

    @Test fun silentOrDrmOrAmbiguousTracksNeverBecomeVideoWithSound() {
        for (rows in listOf(video("v", 720), video("v", 720, "avc1") + "," + audio(codec = "none"),
            video("v", 720) + "," + audio().replace("\"tbr\"", "\"has_drm\":true,\"tbr\""),
            video("v", 720) + "," + audio().replace("\"vcodec\":\"none\",", ""))) {
            val inspection = map(rows)
            assertTrue(inspection.presets.none { it.preset in setOf(Preset.BEST, Preset.BALANCED, Preset.SMALL) }, rows)
            assertTrue(inspection.formats.filter { it.hasVideo }.none { it.hasAudio || it.requiresMerge }, rows)
        }
    }

    @Test fun opusUsesMkvAndDrmVideoIsNotRecovered() {
        val compatible = map(video("v", 720, "avc1") + "," + audio(codec = "opus"))
        assertEquals("v", compatible.presets.single { it.preset == Preset.BEST }.formatId)
        assertEquals("mkv", compatible.formats.single { it.hasVideo }.container)
        val drm = map(video("v", 720).replace("\"height\"", "\"has_drm\":true,\"height\"") + "," + audio())
        assertTrue(drm.formats.none { it.hasVideo })
    }
}
