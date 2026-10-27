package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/** H.264 > HEVC > AV1 > VP9 for playback; VP9 and Opus merges use MKV for playback. */
class PlaybackCodecMappingTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = LinkRevision(assertNotNull(LinkInput.parse("https://example.org/123")), 1)
    private fun map(formats: String, extractor: String? = null) = YtdlpMetadataMapper.map(
        """{"title":"Clip",${extractor?.let { "\"extractor_key\":\"$it\"," } ?: ""}"formats":[$formats]}""", source, "v1")
    private fun args(inspection: Inspection, id: String): List<String> {
        val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job-1", inspection, source, id, "Clip")).command
        return YtdlpCommand.download(command, temp.newFolder().canonicalFile)
    }
    private val aac = """{"format_id":"a","ext":"m4a","vcodec":"none","acodec":"mp4a.40.5","abr":64,"filesize":100}"""
    private fun video(id: String, codec: String, height: Int, ext: String = "mp4", size: Long = 1000) =
        """{"format_id":"$id","ext":"$ext","vcodec":"$codec","acodec":"none","height":$height,"tbr":900,"filesize":$size}"""

    @Test fun h264WinsOverVp9AtTheSameHeightAndTheVp9RowIsDropped() {
        // Facebook shape: VP9 and H.264 DASH video both labelled mp4; VP9 has the known (smaller) size.
        val inspection = map(listOf(video("vp9-848", "vp09.00.31.08", 848, size = 10),
            """{"format_id":"avc-848","ext":"mp4","vcodec":"avc1.4d401f","acodec":"none","height":848,"tbr":800}""", aac).joinToString())
        assertTrue(inspection.formats.none { it.id == "vp9-848" })
        val row = inspection.formats.single { it.id == "avc-848" }
        assertEquals("mp4", row.container); assertEquals("a", row.audioFormatId)
        assertEquals("avc-848", inspection.presets.single { it.preset == Preset.BEST }.formatId)
        val argv = args(inspection, "avc-848")
        assertEquals("mp4", argv[argv.indexOf("--merge-output-format") + 1])
        // yt-dlp adds `-movflags +faststart` to every ffmpeg output itself; Nezzel adds no duplicate.
        assertFalse(argv.any { it.contains("movflags") })
    }

    @Test fun vp9OnlyHeightIsKeptAndMergedIntoMkvNeverMp4() {
        val inspection = map(listOf(video("vp9-1080", "vp09.00.40.08", 1080), video("avc-720", "avc1.64001f", 720), aac).joinToString())
        val row = inspection.formats.single { it.id == "vp9-1080" }
        assertEquals("mkv", row.container); assertEquals("a", row.audioFormatId); assertTrue(row.requiresMerge)
        assertEquals("vp09.00.40.08", row.videoCodec)
        assertEquals("mp4", inspection.formats.single { it.id == "avc-720" }.container)
        assertEquals("avc-720", inspection.presets.single { it.preset == Preset.BEST }.formatId, "mp4-safe H.264 keeps BEST")
        val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job-1", inspection, source, "vp9-1080", "Clip")).command
        assertTrue(command.filename.endsWith(".mkv"), command.filename)
        val argv = YtdlpCommand.download(command, temp.newFolder().canonicalFile)
        assertEquals("vp9-1080+a", argv[argv.indexOf("-f") + 1])
        assertEquals("mkv", argv[argv.indexOf("--merge-output-format") + 1])
    }

    @Test fun vp9OnlySourceStillOffersItsRealRowAsMkvPresets() {
        val inspection = map(listOf(video("vp9-480", "vp9", 480), aac).joinToString())
        assertEquals("mkv", inspection.formats.single { it.id == "vp9-480" }.container)
        assertEquals("vp9-480", inspection.presets.single { it.preset == Preset.BEST }.formatId)
    }

    @Test fun withoutH264HevcOutranksAv1AndVp9IsDropped() {
        val inspection = map(listOf(video("vp9", "vp09.00.50.08", 1440, size = 10), video("av1", "av01.0.12M.08", 1440, size = 20),
            video("hevc", "hvc1.1.6.L120.90", 1440, size = 30), aac).joinToString())
        assertEquals(listOf("av1", "hevc"), inspection.formats.filter { it.hasVideo }.map { it.id }.sorted())
        assertEquals("hevc", inspection.presets.single { it.preset == Preset.BEST }.formatId)
        assertTrue(inspection.formats.filter { it.hasVideo }.all { it.container == "mp4" })
    }

    @Test fun h264WithOpusUsesMkvAndOutranksVp9AtTheSameHeight() {
        val inspection = map(listOf(video("avc", "avc1.4d401f", 720), video("vp9", "vp9", 720, ext = "webm"),
            """{"format_id":"o","ext":"webm","vcodec":"none","acodec":"opus","abr":128}""").joinToString())
        val row = inspection.formats.single { it.id == "avc" }
        assertEquals("mkv", row.container); assertEquals("o", row.audioFormatId)
        assertTrue(inspection.formats.none { it.id == "vp9" })
        assertEquals("avc", inspection.presets.single { it.preset == Preset.BEST }.formatId)
    }

    @Test fun vp9WebmWithOpusIsKeptAndMergedIntoMkv() {
        val inspection = map(listOf(video("vp9", "vp9", 720, ext = "webm"),
            """{"format_id":"o","ext":"webm","vcodec":"none","acodec":"opus","abr":128}""").joinToString())
        val row = inspection.formats.single { it.id == "vp9" }
        assertEquals("mkv", row.container); assertEquals("o", row.audioFormatId)
        assertTrue(row.requiresMerge)
        val argv = args(inspection, row.id)
        assertEquals("vp9+o", argv[argv.indexOf("-f") + 1])
        assertEquals("mkv", argv[argv.indexOf("--merge-output-format") + 1])
    }

    @Test fun unknownCodecMp4IsNeverTreatedAsVp9() {
        val inspection = map(listOf("""{"format_id":"u","ext":"mp4","vcodec":"unknown-codec","acodec":"none","height":720}""",
            """{"format_id":"n","ext":"mp4","acodec":"none","vcodec":"avc1","height":360}""", aac).joinToString())
        // An unrecognised codec is not dropped or claimed MP4-compatible; merge conservatively into MKV.
        assertEquals("mkv", inspection.formats.single { it.id == "u" }.container)
        assertEquals(listOf("n", "u"), inspection.formats.filter { it.hasVideo }.map { it.id }.sorted())
    }

    @Test fun presetsAreCompatibilityFirstH264ThenHevcThenAv1() {
        val withH264 = map(listOf(video("avc-720", "avc1.4d401f", 720), video("av1-1440", "av01.0.12M.08", 1440), aac).joinToString())
        assertEquals("avc-720", withH264.presets.single { it.preset == Preset.BEST }.formatId)
        assertEquals("mp4", withH264.formats.single { it.id == "av1-1440" }.container, "AV1 stays in the full list")
        val noH264 = map(listOf(video("av1-720", "av01.0.05M.08", 720), video("av1-1440", "av01.0.12M.08", 1440), aac).joinToString())
        assertEquals("av1-1440", noH264.presets.single { it.preset == Preset.BEST }.formatId)
        assertEquals("av1-720", noH264.presets.single { it.preset == Preset.BALANCED }.formatId)
        val hevcAndAv1 = map(listOf(video("hevc-1080", "hev1.1.6.L120.90", 1080), video("av1-1440", "av01.0.12M.08", 1440), aac).joinToString())
        assertEquals("hevc-1080", hevcAndAv1.presets.single { it.preset == Preset.BEST }.formatId)
        val vp9Only = map(listOf(video("vp9-1080", "vp9", 1080), aac).joinToString())
        assertEquals("vp9-1080", vp9Only.presets.single { it.preset == Preset.BEST }.formatId)
    }

    @Test fun hevcTallerThanEveryH264RowBecomesBestButNeverAtTheSameHeight() {
        val tiktok = map(listOf(video("avc-540", "h264", 540, size = 1600), video("hevc-540", "h265", 540, size = 420),
            video("hevc-720", "h265", 720, size = 540), video("hevc-1080", "h265", 1080, size = 780),
            video("av1-1440", "av01.0.12M.08", 1440), aac).joinToString())
        assertEquals("hevc-1080", tiktok.presets.single { it.preset == Preset.BEST }.formatId)
        assertEquals("hevc-720", tiktok.presets.single { it.preset == Preset.BALANCED }.formatId)
        val sameHeight = map(listOf(video("avc-1080", "avc1.640028", 1080), video("hevc-1080", "hvc1.1.6.L120.90", 1080, size = 10),
            aac).joinToString())
        assertEquals("avc-1080", sameHeight.presets.single { it.preset == Preset.BEST }.formatId)
        assertEquals("avc-1080", sameHeight.presets.single { it.preset == Preset.SMALL }.formatId, "same height stays H.264")
        // H.264 1080 with muxed Opus loses its height's pick to the HEVC + AAC mp4, but it still exists.
        val avcOpus = """{"format_id":"avc-1080","ext":"mp4","vcodec":"avc1.640028","acodec":"opus","height":1080,"tbr":900,"filesize":900}"""
        val hevcMuxed = """{"format_id":"hevc-1080","ext":"mp4","vcodec":"hvc1.1.6.L120.90","acodec":"mp4a.40.2","height":1080,"tbr":900,"filesize":500}"""
        val avcMkv = map(listOf(avcOpus, hevcMuxed,
            """{"format_id":"m","ext":"mp4","vcodec":"avc1.4d401f","acodec":"mp4a.40.2","height":720,"tbr":900,"filesize":900}""").joinToString())
        assertNotEquals("hevc-1080", avcMkv.presets.single { it.preset == Preset.BEST }.formatId, "an H.264 1080 merge exists")
    }

    @Test fun tiktoksHeightlessDownloadRowDoesNotStopTheTallerHevc() {
        // Real TikTok answer (muxed rows): a watermarked H.264 "download" copy with no height next to the sized rows.
        fun muxed(id: String, codec: String, height: Int?, size: Long) = """{"format_id":"$id","ext":"mp4","vcodec":"$codec","acodec":"aac",""" +
            (height?.let { "\"height\":$it," } ?: "") + """"tbr":900,"filesize":$size}"""
        val download = muxed("download", "h264", null, 900)
        val rows = listOf(download, muxed("h264_540p", "h264", 1024, 1800), muxed("bytevc1_540p", "h265", 1024, 700),
            muxed("bytevc1_720p", "h265", 1280, 930)).joinToString()
        val tiktok = map(rows, extractor = "TikTok")
        assertEquals("bytevc1_720p", tiktok.presets.single { it.preset == Preset.BEST }.formatId)
        // With no H.264 height known at all, nothing climbs.
        val unknown = map(listOf(download, muxed("bytevc1_720p", "h265", 1280, 930)).joinToString(), extractor = "TikTok")
        assertNotEquals("bytevc1_720p", unknown.presets.single { it.preset == Preset.BEST }.formatId)
        // Elsewhere an H.264 row of unknown height may be the tallest, so the shorter HEVC stays out.
        assertNotEquals("bytevc1_720p", map(rows).presets.single { it.preset == Preset.BEST }.formatId)
    }
}
