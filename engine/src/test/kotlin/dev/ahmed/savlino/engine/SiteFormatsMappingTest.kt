package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class SiteFormatsMappingTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = LinkRevision(assertNotNull(LinkInput.parse("https://example.org/clip")), 1)
    private fun fixture(site: String) = YtdlpMetadataMapper.map(
        javaClass.getResource("/ytdlp/sites/fixture-$site.json")!!.readText(), source, "fixture")
    private fun args(inspection: Inspection, id: String): List<String> {
        val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job", inspection, source, id, "Clip")).command
        return YtdlpCommand.download(command, temp.newFolder())
    }
    private fun video(site: String, ids: List<String>, best: String, merge: String? = null): Inspection {
        val inspection = fixture(site)
        assertEquals(ids, inspection.formats.filter { it.hasVideo }.map { it.id })
        assertEquals(best, inspection.presets.single { it.preset == Preset.BEST }.formatId)
        for (id in ids) {
            val argv = args(inspection, id)
            val row = inspection.formats.single { it.id == id }
            assertEquals(if (id == "mp4") "all[format_id=mp4]" else id + (row.audioFormatId?.let { "+$it" } ?: ""),
                argv[argv.indexOf("-f") + 1])
            if (merge == null) assertFalse("--merge-output-format" in argv)
            else assertEquals(merge, argv[argv.indexOf("--merge-output-format") + 1])
        }
        return inspection
    }

    @Test fun facebookKeepsProgressiveSdHdAlongsideAllDashHeights() {
        val inspection = fixture("facebook")
        assertEquals(setOf("sd", "hd", "1740195447241844v", "1740195463908509v", "1740195453908510v", "1740195457241843v"),
            inspection.formats.filter { it.hasVideo }.map { it.id }.toSet())
        assertEquals("hd", inspection.presets.single { it.preset == Preset.BEST }.formatId)
        for (id in listOf("sd", "hd")) {
            val row = inspection.formats.single { it.id == id }
            assertTrue(row.hasAudio && row.hasVideo); assertFalse(row.requiresMerge)
            assertNull(row.height); assertNull(row.videoCodec); assertNull(row.audioCodec)
            assertEquals(Size.Unknown, row.size)
            assertFalse("--merge-output-format" in args(inspection, id))
        }
        val dash = inspection.formats.single { it.id == "1740195453908510v" }
        assertEquals("1406037693452367a", dash.audioFormatId)
        val argv = args(inspection, dash.id)
        assertEquals("vp9", VideoCodecs.family(dash.videoCodec))
        assertEquals("mkv", argv[argv.indexOf("--merge-output-format") + 1])
    }

    @Test fun xKeepsOnlyReportedHlsRowsWhenAudioIsKnown() {
        val inspection = video("x", listOf("hls-227", "hls-666", "hls-1959"), "hls-1959", "mp4")
        val rows = inspection.formats.filter { it.hasVideo }
        assertEquals(listOf(180, 360, 720), rows.map { it.height })
        assertTrue(rows.none { it.unreportedStreams || it.id.startsWith("http") })
        assertTrue(rows.all { it.audioFormatId == "hls-audio" })
    }

    @Test fun videoOnlyRowsAreSilentOnlyWhenTheWholePostHasNoAudio() {
        val videos = """{"format_id":"v720","ext":"mp4","vcodec":"avc1","acodec":"none","height":720},
            {"format_id":"v480","ext":"mp4","vcodec":"avc1","acodec":"none","height":480}"""
        val cases = listOf(
            "" to true,
            """,{"format_id":"a","ext":"m4a","vcodec":"none","acodec":"aac"}""" to false,
            """,{"format_id":"muxed","ext":"mp4","vcodec":"avc1","acodec":"aac","height":360}""" to false,
            // An unusable audio row still means this is not a silent source.
            """,{"format_id":"drm-a","ext":"m4a","vcodec":"none","acodec":"aac","has_drm":true}""" to false,
            // A video that does not report its audio codec may have sound: unknown, never labelled silent.
            """,{"format_id":"v360","ext":"mp4","vcodec":"avc1","height":360}""" to false,
        )
        for ((extra, silent) in cases) {
            val inspection = YtdlpMetadataMapper.map("""{"formats":[$videos$extra]}""", source, "fixture")
            for (id in listOf("v720", "v480")) {
                val row = inspection.formats.single { it.id == id }
                assertEquals(silent, row.sourceHasNoSound, extra)
                assertFalse(row.hasAudio)
                assertEquals(Size.Unknown, row.size)
                if (silent) {
                    assertNull(row.audioFormatId)
                    val argv = args(inspection, id)
                    assertEquals(id, argv[argv.indexOf("-f") + 1])
                    assertFalse("--merge-output-format" in argv)
                    assertFalse("--extract-audio" in argv)
                } else if (row.audioFormatId == null) {
                    assertEquals(ConfirmResult.Invalid(DownloadError.Unsupported),
                        DownloadCommand.confirm("job", inspection, source, id, "Clip"))
                } else assertTrue(row.requiresMerge)
            }
            if (silent) {
                assertEquals("v720", inspection.presets.single { it.preset == Preset.BEST }.formatId)
                assertTrue(inspection.formats.all { it.hasVideo && it.conversion == null })
            }
        }
    }

    @Test fun pinterestUsesActualHlsAudioAndMergesH264IntoMp4WithoutClaimingAac() {
        val inspection = video("pinterest", listOf("hls-234", "hls-360", "hls-540", "hls-720"), "hls-720", "mp4")
        assertEquals("hls-720", inspection.presets.single { it.preset == Preset.BALANCED }.formatId)
        for (row in inspection.formats.filter { it.hasVideo }) {
            assertEquals("hls-audio", row.audioFormatId); assertFalse(row.hasAudio)
            assertEquals(Size.Unknown, row.size); assertNull(row.audioCodec)
        }
        assertNull(inspection.formats.single { it.id == "hls-audio" }.audioCodec)
    }

    @Test fun twitchKeepsItsOnlyReturned1080pFile() {
        val inspection = video("twitch", listOf("1080"), "1080")
        assertEquals(1080, inspection.formats.single().height)
        assertEquals(Size.Unknown, inspection.formats.single().size)
    }
    @Test fun odyseeRetainsOriginalExactSize() {
        assertEquals(Size.Exact(196947353), video("odysee", listOf("original"), "original").formats.single().size)
    }
    @Test fun kickOffersOneUnknownQualityHlsVideoWithoutInventingFacts() {
        val row = video("kick", listOf("0"), "0").formats.single()
        assertNull(row.height); assertNull(row.videoCodec); assertNull(row.audioCodec)
        assertEquals(Size.Unknown, row.size)
    }
    @Test fun imgurOffersTwitterMp4AndNeverItsGifAsVideo() {
        assertEquals(Size.Unknown, video("imgur", listOf("twitter"), "twitter").formats.single().size)
    }
    @Test fun streamableRetainsH264AndSelectsLiteralMp4Id() {
        val inspection = video("streamable", listOf("mp4-mobile", "mp4"), "mp4")
        assertEquals("mp4", inspection.presets.single { it.preset == Preset.BALANCED }.formatId)
        assertTrue(inspection.formats.all { it.videoCodec == "h264" && it.audioCodec == null })
        assertEquals(Size.Exact(19286494), inspection.formats.single { it.id == "mp4" }.size)
        assertTrue(inspection.formats.none { it.conversion != null })
    }
    @Test fun ninegagRecoversOnlyItsDirectMp4sForSoundAndPrefersReportedHevc() {
        val inspection = fixture("ninegag")
        assertEquals("460sv-h265", inspection.presets.single { it.preset == Preset.BEST }.formatId)
        for (id in listOf("460sv", "460sv-h265")) {
            val row = inspection.formats.single { it.id == id }
            assertTrue(row.hasAudio); assertEquals(Size.Unknown, row.size)
            assertFalse("--merge-output-format" in args(inspection, id))
        }
        assertTrue(inspection.formats.filter { it.container == "webm" }.none { it.hasAudio || it.requiresMerge })
    }
    @Test fun bilibiliPrefersH264WithAacAtEveryHeight() {
        val inspection = video("bilibili", listOf("100022", "30016", "100023", "30032", "100024", "30064", "100026", "30080"), "30080", "mp4")
        assertEquals("30064", inspection.presets.single { it.preset == Preset.BALANCED }.formatId)
        assertTrue(inspection.formats.filter { it.hasVideo }.all { it.audioFormatId == "30280" && it.size is Size.Estimated })
    }
    private fun audio(site: String, id: String, preset: Preset) {
        val inspection = fixture(site)
        assertTrue(inspection.formats.none { it.hasVideo })
        assertEquals(id, inspection.presets.single { it.preset == preset }.formatId)
        assertFalse("--merge-output-format" in args(inspection, id))
    }
    @Test fun mixcloudOffersReturnedAacTrack() = audio("mixcloud", "dash-a1-x3", Preset.AUDIO_M4A)
    @Test fun soundcloudOffersReturnedAacTrack() = audio("soundcloud", "hls_aac_96k", Preset.AUDIO_M4A)
    @Test fun bandcampOffersMp3FromItsActualReturnedTrack() = audio("bandcamp", "nezzel:mp3:192:mp3-128", Preset.AUDIO_MP3)

    @Test fun mergeContainerKeepsVp9AndOpusInMkv() {
        for (codec in listOf("avc1.64001f", "hvc1.1.6.L120", "av01.0.05M.08", "vp09.00.31.08", "vp8", "other")) {
            for (audio in listOf("aac", "mp4a.40.2", "opus", "vorbis")) {
                val inspection = YtdlpMetadataMapper.map("""{"formats":[
                    {"format_id":"v","ext":"mp4","vcodec":"$codec","acodec":"none","height":720},
                    {"format_id":"a","ext":"m4a","vcodec":"none","acodec":"$audio"}]}""", source, "fixture")
                val expected = if (codec in setOf("vp09.00.31.08", "vp8", "other") || audio in setOf("opus", "vorbis")) "mkv" else "mp4"
                val argv = args(inspection, "v")
                assertEquals(expected, argv[argv.indexOf("--merge-output-format") + 1], "$codec + $audio")
            }
        }
    }

    @Test fun soleHlsRecoveryRejectsLiveDrmExplicitSilenceAndAmbiguousRows() {
        val base = javaClass.getResource("/ytdlp/sites/fixture-kick.json")!!.readText()
        for (bad in listOf(base.replace("\"is_live\": null", "\"is_live\": true"),
            base.replace("\"has_drm\": false", "\"has_drm\": true"),
            base.replace("\"format_id\": \"0\"", "\"format_id\": \"0\", \"acodec\": \"none\""),
            base.replace("\"format_id\": \"0\"", "\"format_id\": \"0\", \"vcodec\": \"none\""))) {
            assertTrue(YtdlpMetadataMapper.map(bad, source, "fixture").formats.none { it.hasVideo })
        }
        val node = """{"format_id":"0","ext":"mp4","protocol":"m3u8_native"}"""
        val multiple = YtdlpMetadataMapper.map("""{"formats":[$node,${node.replace("\"0\"", "\"1\"")}]}""", source, "fixture")
        assertTrue(multiple.formats.isEmpty())
    }

    @Test fun unknownHlsAudioOutsidePinterestRemainsMkvAndExplicitAudioNoneIsNeverRecovered() {
        val json = javaClass.getResource("/ytdlp/sites/fixture-pinterest.json")!!.readText()
        val other = YtdlpMetadataMapper.map(json.replace("Pinterest", "Other"), source, "fixture")
        assertTrue(other.formats.filter { it.hasVideo }.all { it.requiresMerge && it.container == "mkv" })
        val silent = YtdlpMetadataMapper.map(json.replace("\"vcodec\": \"none\"", "\"vcodec\": \"none\", \"acodec\": \"none\""), source, "fixture")
        assertTrue(silent.formats.none { it.requiresMerge })
        // Codec-less audio renditions remain possible sound: unknown, not silent, and nothing invented.
        assertTrue(silent.formats.none { it.sourceHasNoSound })
        assertTrue(silent.presets.none { it.preset == Preset.BEST })
    }

    @Test fun aacInMp4WinsTheMergePartnerOverHigherBitrateOpusWithoutInventingM4a() {
        val inspection = YtdlpMetadataMapper.map("""{"formats":[
            {"format_id":"v","ext":"mp4","vcodec":"avc1.64001f","acodec":"none","height":720},
            {"format_id":"aac","ext":"mp4","protocol":"m3u8_native","vcodec":"none","acodec":"mp4a.40.2","abr":128},
            {"format_id":"opus-track","ext":"webm","vcodec":"none","acodec":"opus","abr":160}]}""", source, "fixture")
        assertEquals("aac", inspection.formats.single { it.id == "v" }.audioFormatId)
        val argv = args(inspection, "v")
        assertEquals("v+aac", argv[argv.indexOf("-f") + 1])
        assertEquals("mp4", argv[argv.indexOf("--merge-output-format") + 1])
        assertTrue(inspection.presets.none { it.preset == Preset.AUDIO_M4A })
    }

    @Test fun partialCodecRecoveryIsLimitedToEvidencedProgressiveExtractorsAndNeverExplicitSilence() {
        val json = javaClass.getResource("/ytdlp/sites/fixture-streamable.json")!!.readText()
        val unrelated = YtdlpMetadataMapper.map(json.replace("Streamable", "Other"), source, "fixture")
        assertTrue(unrelated.formats.none { it.hasAudio || it.requiresMerge })
        // A missing acodec is unknown, not silence: nothing is offered and nothing is labelled "No sound".
        assertTrue(unrelated.formats.none { it.sourceHasNoSound })
        assertTrue(unrelated.presets.isEmpty())
        val silent = YtdlpMetadataMapper.map(json.replace("\"acodec\": null", "\"acodec\": \"none\""), source, "fixture")
        assertTrue(silent.formats.none { it.hasAudio || it.requiresMerge })
        assertTrue(silent.formats.all { it.sourceHasNoSound })
        // "mp4" is also a selector word, so the 720p row with that ID is never selectable; 360p is the best real row.
        assertEquals("mp4-mobile", silent.presets.single { it.preset == Preset.BEST }.formatId)
    }
}
