package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/** X/Twitter: yt-dlp 2026.08.19 returns one direct `http` mp4 with every codec/size/dimension field null. */
class UnreportedDirectMp4MappingTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = LinkRevision(assertNotNull(LinkInput.parse("https://x.com/someone/status/1")), 1)
    private fun map(formats: String, extra: String = "") = YtdlpMetadataMapper.map(
        """{"id":"1","title":"Post","extractor":"twitter"$extra,"formats":[$formats]}""", source, "2026.08.19")
    private val xRow = """{"format_id":"http","ext":"mp4","protocol":"https","url":"https://video.example/v.mp4",
        "vcodec":null,"acodec":null,"width":null,"height":null,"filesize":null,"tbr":null}"""

    @Test fun loneUnknownCodecDirectMp4BecomesOneVideoRowWithUnknownSizeAndNoConversions() {
        val inspection = map(xRow)
        val row = inspection.formats.single()
        assertEquals("http", row.id)
        assertEquals("mp4", row.container)
        assertTrue(row.hasVideo && row.unreportedStreams)
        assertNull(row.height); assertNull(row.videoCodec); assertNull(row.audioCodec)
        assertEquals(Size.Unknown, row.size)
        assertTrue(inspection.roughSizeBytes.isEmpty(), "no bitrate: no estimate")
        assertTrue(inspection.formats.none { it.conversion != null }, "no MP3 from a stream with no audio facts")
        assertTrue(inspection.presets.isNotEmpty())
        assertTrue(inspection.presets.all { it.formatId == "http" && it.preset != Preset.BALANCED })
    }

    @Test fun realBitrateAndDurationGiveOnlyTheLabelledEstimate() {
        val inspection = map(xRow.replace("\"tbr\":null", "\"tbr\":800"), extra = ",\"duration\":10")
        assertEquals(Size.Unknown, inspection.formats.single().size)
        assertEquals(1_000_000L, inspection.roughSizeBytes["http"])
    }

    @Test fun downloadingTheRowSelectsItsExactFormatId() {
        val row = map(xRow).formats.single()
        val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job-1",
            Inspection(source, "2026.08.19", listOf(row)), source, row.id, "Post")).command
        val args = YtdlpCommand.download(command, temp.newFolder().canonicalFile)
        assertEquals("http", args[args.indexOf("-f") + 1])
        assertFalse("--merge-output-format" in args); assertFalse("--extract-audio" in args)
    }

    @Test fun fallbackNeverAppliesToExplicitSilentHlsDashExplicitNoneNonMp4OrDrm() {
        for (bad in listOf(xRow.replace("\"https\"", "\"m3u8_native\"").replace("\"acodec\":null", "\"acodec\":\"none\""),
            xRow.replace("\"https\"", "\"http_dash_segments\""),
            xRow.replace("\"vcodec\":null", "\"vcodec\":\"none\""),
            xRow.replace("\"ext\":\"mp4\"", "\"ext\":\"webm\""),
            xRow.replace("\"url\"", "\"has_drm\":true,\"url\""),
            xRow.replace("\"protocol\":\"https\",", ""),
            xRow.replace("v.mp4", "master.m3u8"), xRow.replace("v.mp4", "manifest.MPD"),
            xRow.replace("https://video.example", "ftp://video.example"),
            xRow.replace("\"url\":\"https://video.example/v.mp4\",", ""))) {
            assertTrue(map(bad).formats.isEmpty(), bad)
        }
    }

    @Test fun twoCodecLessDirectMp4RowsAreAmbiguousAndGiveNoFormats() {
        assertTrue(map("$xRow,${xRow.replace("\"http\"", "\"http-2\"")}").formats.isEmpty())
    }

    /** LinkedIn (2026-10-19 real shape): renditions `0`/`1` with absent codecs/height, differing only in tbr. */
    private fun linkedIn(id: String, tbr: String) = """{"format_id":"$id","ext":"mp4","protocol":"https",
        "url":"https://dms.example/$id/v.mp4","tbr":$tbr,"video_ext":"mp4","audio_ext":"none"}"""

    @Test fun severalCodecLessRowsOfferOnlyTheStrictlyHighestBitrate() {
        val row = map("${linkedIn("0", "559.496")},${linkedIn("1", "701.339")}").formats.single()
        assertEquals("1", row.id)
        assertNull(row.height); assertEquals(Size.Unknown, row.size)
    }

    /** X video, device yt-dlp 2026.08.19 shape: HLS avc1 video-only, HLS audio without acodec, codec-less http mp4s. */
    private val xVideo = listOf(
        """{"format_id":"hls-audio-128000-Audio","ext":"mp4","protocol":"m3u8_native","vcodec":"none","tbr":128,"url":"https://video.example/a.m3u8"}""",
        """{"format_id":"http-256","ext":"mp4","protocol":"https","width":320,"height":180,"tbr":256,"url":"https://video.example/256/v.mp4"}""",
        """{"format_id":"hls-227","ext":"mp4","protocol":"m3u8_native","vcodec":"avc1.4D400D","acodec":"none","width":320,"height":180,"tbr":227.6,"url":"https://video.example/227.m3u8"}""",
        """{"format_id":"http-832","ext":"mp4","protocol":"https","width":640,"height":360,"tbr":832,"url":"https://video.example/832/v.mp4"}""",
        """{"format_id":"hls-666","ext":"mp4","protocol":"m3u8_native","vcodec":"avc1.4D401E","acodec":"none","width":640,"height":360,"tbr":666.5,"url":"https://video.example/666.m3u8"}""",
        """{"format_id":"http-2176","ext":"mp4","protocol":"https","width":1280,"height":720,"tbr":2176,"url":"https://video.example/2176/v.mp4"}""",
        """{"format_id":"hls-1959","ext":"mp4","protocol":"m3u8_native","vcodec":"avc1.64001F","acodec":"none","width":1280,"height":720,"tbr":1959.2,"url":"https://video.example/1959.m3u8"}""")

    @Test fun xVideoWithSilentHlsRowsOffersEachCodecLessDirectMp4ByItsHeight() {
        val inspection = map(xVideo.joinToString(","))
        assertEquals(listOf("http-2176", "http-832", "http-256"), inspection.formats.sortedByDescending { it.height }.map { it.id })
        assertEquals(listOf(720, 360, 180), inspection.formats.sortedByDescending { it.height }.map { it.height })
        assertTrue(inspection.formats.none { it.conversion != null || it.requiresMerge })
        assertEquals("http-2176", inspection.presets.single { it.preset == Preset.BEST }.formatId)
    }

    @Test fun codecLessDirectMp4sStayIgnoredWhenAMappedRowAlreadyPlaysWithSound() {
        val audio = """{"format_id":"hls-audio","ext":"mp4","protocol":"m3u8_native","vcodec":"none","acodec":"mp4a.40.2","tbr":128,"url":"https://video.example/a.m3u8"}"""
        val ids = map((xVideo.drop(1) + audio).joinToString(",")).formats.map { it.id }
        assertTrue(ids.none { it.startsWith("http-") }, ids.toString())
    }

    @Test fun codecLessDirectMp4sWithRepeatedHeightsFallBackToTheHighestBitrateOnly() {
        val rows = listOf(linkedIn("0", "559.4").replace("\"tbr\"", "\"height\":720,\"tbr\""),
            linkedIn("1", "701.3").replace("\"tbr\"", "\"height\":720,\"tbr\""))
        assertEquals("1", map(rows.joinToString(",")).formats.single().id)
    }

    @Test fun severalCodecLessRowsWithAMissingOrTiedBitrateStayAmbiguous() {
        assertTrue(map("${linkedIn("0", "null")},${linkedIn("1", "701.339")}").formats.isEmpty())
        assertTrue(map("${linkedIn("0", "701.339")},${linkedIn("1", "701.339")}").formats.isEmpty())
    }

    @Test fun realShapeWithAbsentCodecFieldsStillMaps() {
        val real = """{"format_id":"http","ext":"mp4","protocol":"https","url":"https://video.example/v.mp4","tbr":null}"""
        assertEquals("http", map(real).formats.single().id)
    }

    @Test fun normalListWithCodecRowsStillDropsUnknownCodecJunk() {
        val normal = """{"format_id":"720","ext":"mp4","protocol":"https","vcodec":"avc1.4d401f","acodec":"mp4a.40.2","height":720,"width":1280}"""
        val ids = map("$xRow,$normal").formats.map { it.id }
        assertFalse("http" in ids)
        assertTrue("720" in ids)
        assertTrue(map(normal).formats.none { it.unreportedStreams })
    }

    @Test fun xTweetVideoPathIsLabelledAnimatedGifWithNoAudioRows() {
        val gif = map(xRow.replace("https://video.example/v.mp4", "https://video.twimg.com/tweet_video/AbC123.mp4"))
        val row = gif.formats.single()
        assertTrue(row.animatedGif && row.unreportedStreams)
        assertTrue(gif.formats.none { !it.hasVideo }, "no M4A/MP3 rows for a GIF")
        assertTrue(gif.presets.none { it.preset == Preset.AUDIO_M4A || it.preset == Preset.AUDIO_MP3 })
    }

    @Test fun animatedGifNeedsTheExactXGifPathSignal() {
        for (url in listOf("https://video.example/v.mp4", "https://video.twimg.com/ext_tw_video/1/pu/vid/720x1280/a.mp4",
            "https://video.twimg.com/amplify_video/1/vid/a.mp4", "http://video.twimg.com/tweet_video/a.mp4",
            "https://video.example/tweet_video/a.mp4")) {
            assertFalse(map(xRow.replace("https://video.example/v.mp4", url)).formats.single().animatedGif, url)
        }
        val coded = """{"format_id":"720","ext":"mp4","protocol":"https","url":"https://video.twimg.com/ext_tw_video/a.mp4",
            "vcodec":"avc1.4d401f","acodec":"mp4a.40.2","height":720}"""
        assertFalse(map(coded).formats.single { it.hasVideo }.animatedGif)
    }

    @Test fun gifWithSeveralVideoOnlyVariantsAndAnAudioRowKeepsOnlySilentGifVideoRows() {
        fun gif(id: String, height: Int) = """{"format_id":"$id","ext":"mp4","protocol":"https",
            "url":"https://video.twimg.com/tweet_video/AbC$id.mp4","vcodec":"avc1.4d401e","acodec":"none","height":$height,"tbr":300}"""
        val audio = """{"format_id":"hls-audio","ext":"m4a","protocol":"https","url":"https://video.twimg.com/a.m4a",
            "vcodec":"none","acodec":"mp4a.40.2","abr":64}"""
        val inspection = map("${gif("g320", 320)},${gif("g480", 480)},$audio")
        assertEquals(listOf("g320", "g480"), inspection.formats.map { it.id }.sorted())
        assertTrue(inspection.formats.all { it.hasVideo && it.animatedGif && !it.requiresMerge && it.audioFormatId == null })
        assertTrue(inspection.formats.none { it.conversion != null || !it.hasVideo }, "no M4A/MP3 for a GIF")
        assertEquals("g480", inspection.presets.single { it.preset == Preset.BEST }.formatId)
        assertTrue(inspection.presets.none { it.preset == Preset.AUDIO_M4A || it.preset == Preset.AUDIO_MP3 })
        val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job-1", inspection, source, "g480", "Post")).command
        val args = YtdlpCommand.download(command, temp.newFolder().canonicalFile)
        assertEquals("g480", args[args.indexOf("-f") + 1])
        assertFalse("--merge-output-format" in args); assertFalse("--extract-audio" in args)
    }

    @Test fun ordinaryVideoOnlyRowWithoutPartnerIsStillRejected() {
        val row = Format("v", "mp4", true, false, videoCodec = "avc1", height = 480)
        assertEquals(ConfirmResult.Invalid(DownloadError.Unsupported),
            DownloadCommand.confirm("job-1", Inspection(source, "v1", listOf(row)), source, "v", "Post"))
    }
}
