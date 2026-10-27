package dev.ahmed.savlino.engine

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.domain.*
import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class YtdlpMappingTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = LinkRevision(assertNotNull(LinkInput.parse("https://example.org/123")), 1)
    private fun map(name: String) = YtdlpMetadataMapper.map(
        javaClass.getResource("/ytdlp/$name.json")!!.readText(), source, "2025.11.12")
    private fun command(row: Format): DownloadCommand = assertIs<ConfirmResult.Confirmed>(
        DownloadCommand.confirm("job-1", Inspection(source, "2025.11.12", listOf(row)), source, row.id, "عنوان 123")).command

    @Test fun mp3ConversionsScopeEncoderBitrateAndTemplateToActualSourceContainer() {
        for (container in listOf("mp3", "m4a", "webm", "mp4", null)) {
            for ((conversion, quality, scoped) in listOf(
                Triple(AudioConversion.MP3_128, "128K", "ExtractAudio+ffmpeg_o1:-c:a libmp3lame -b:a 128k"),
                Triple(AudioConversion.MP3_192, "192K", "ExtractAudio+ffmpeg_o1:-c:a libmp3lame -b:a 192k"),
                Triple(AudioConversion.MP3_320, "320K", "ExtractAudio+ffmpeg_o1:-c:a libmp3lame -b:a 320k -ar 44100"))) {
                val dir = temp.newFolder().canonicalFile
                val row = Format("converted", "mp3", false, true, audioCodec = "mp3",
                    conversion = conversion, sourceFormatId = "actual-source", sourceContainer = container)
                val args = YtdlpCommand.download(command(row), dir)
                assertEquals(2, args.count { it == "--postprocessor-args" })
                assertEquals(scoped, args[args.indexOf("--postprocessor-args") + 1])
                assertEquals(listOf(scoped, squareCover), args.indices.filter { args[it] == "--postprocessor-args" }.map { args[it + 1] })
                assertEquals("actual-source", args[args.indexOf("-f") + 1])
                assertEquals(quality, args[args.indexOf("--audio-quality") + 1])
                assertEquals(File(dir, if (container == "mp3") "output" else "output.%(ext)s").path,
                    args[args.indexOf("-o") + 1])
                assertEquals(listOf("--", source.link.rawUrl), args.takeLast(2))
                assertEquals(1, args.count { it == "-o" })
                assertEquals(File(dir, "result-path.txt").path, args[args.indexOf("--print-to-file") + 2])
            }
        }
    }
    @Test fun nativeOriginalAudioAndVideoKeepExactDownloadArgv() {
        for (row in listOf(Format("native-mp3", "mp3", false, true, audioCodec = "mp3"),
            Format("native-aac", "m4a", false, true, audioCodec = "aac"),
            Format("native-video", "mp4", true, true, audioCodec = "mp3"))) {
            val dir = temp.newFolder().canonicalFile
            val args = YtdlpCommand.download(command(row), dir)
            assertEquals(listOf("--ignore-config", "--no-playlist", "--playlist-items", "1") + YtdlpCommand.NETWORK + listOf(
                "--abort-on-unavailable-fragments", "--retry-sleep", "http:exp=1:8", "--retry-sleep", "fragment:exp=1:16", "-N", "4", "--no-simulate", "--newline",
                "--progress-template", YtdlpCommand.PROGRESS_TEMPLATE, "-f", row.id,
                "-o", File(dir, "output.%(ext)s").path, "--print-to-file", "after_move:filepath",
                File(dir, "result-path.txt").path) + (if (row.hasVideo) emptyList() else audioTagsWithoutArtist) +
                listOf("--", source.link.rawUrl), args)
            // No encoder args for native rows; audio-tag runs carry only the square-cover convertor args.
            assertEquals(if (row.hasVideo) emptyList() else listOf(squareCover),
                args.indices.filter { args[it] == "--postprocessor-args" }.map { args[it + 1] })
        }
    }

    @Test fun onlyNonYouTubeDownloadsUseConcurrentFragmentsAndNeitherOperationDisablesBundledJs() {
        for (url in listOf("https://example.org/video", "https://www.youtube.com/watch?v=fixture", "https://youtu.be/fixture")) {
            val source = LinkRevision(assertNotNull(LinkInput.parse(url)), 1)
            val row = Format("18", "mp4", true, true)
            val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job",
                Inspection(source, "v1", listOf(row)), source, row.id, "title")).command
            for (info in listOf(null, temp.newFile())) {
                val download = YtdlpCommand.download(command, temp.newFolder(), info)
                if (source.link.host == Host.YOUTUBE) assertFalse("-N" in download)
                else {
                    assertEquals(1, download.count { it == "-N" })
                    assertEquals("4", download[download.indexOf("-N") + 1])
                    assertTrue(download.indexOf("-N") > download.indexOf("--fragment-retries"))
                    assertTrue(download.indexOf("-N") < download.indexOf(if (info == null) "--" else "--load-info-json"))
                }
                for (args in listOf(YtdlpCommand.inspect(source), download)) {
                    assertFalse(args.any { it == "--no-js-runtimes" || it.startsWith("--no-js-runtimes=") })
                    assertFalse(args.any { it == "--remote-components" || it.startsWith("--remote-components=") })
                }
            }
            val inspect = YtdlpCommand.inspect(source)
            assertFalse("-N" in inspect); assertFalse("--retry-sleep" in inspect)
            assertFalse("--abort-on-unavailable-fragments" in inspect)
        }
    }

    @Test fun adaptiveStreamsRetainCodecsSizesAndExactMergePartners() {
        val inspection = map("youtube")
        assertSame(source, inspection.source)
        assertEquals("2025.11.12", inspection.engineVersion)
        val rows = inspection.formats.associateBy { it.id }
        assertFalse(rows.containsKey("sb0")); assertFalse(rows.containsKey("drm")); assertFalse(rows.containsKey("unknown"))
        assertEquals(Size.Exact(1200000), rows.getValue("18").size)
        val video = rows.getValue("136")
        assertEquals("140", video.audioFormatId); assertTrue(video.requiresMerge)
        assertTrue(video.hasVideo); assertFalse(video.hasAudio)
        assertEquals("avc1.4d401f", video.videoCodec); assertNull(video.audioCodec)
        assertEquals(Size.Exact(4000000), video.size)
        assertEquals(Size.Estimated(9000000), rows.getValue("137").size)
        assertEquals(Size.Unknown, rows.getValue("160").size)
        // Playback-compatible codecs (2026-10-19): VP9 248 yields to H.264 137 at the same 1080 height.
        assertFalse(rows.containsKey("248"))
        assertEquals("opus", rows.getValue("251").audioCodec)
        assertEquals(Size.Estimated(1100000), rows.getValue("251").size)
        assertEquals("137", inspection.presets.single { it.preset == Preset.BEST }.formatId)
        assertEquals("136", inspection.presets.single { it.preset == Preset.BALANCED }.formatId)
        assertEquals("18", inspection.presets.single { it.preset == Preset.SMALL }.formatId)
        val mp3 = inspection.formats.single { it.conversion == AudioConversion.MP3_320 }
        assertEquals("140", mp3.sourceFormatId); assertEquals(Size.Unknown, mp3.size)
    }
    @Test fun presetsPreferPlainAudioOverHigherBitrateDrcCounterparts() {
        for (ext in listOf("m4a", "webm")) {
            val codec = if (ext == "m4a") "mp4a.40.2" else "opus"
            val inspection = YtdlpMetadataMapper.map("""{"formats":[
                {"format_id":"136","ext":"$ext","vcodec":"h264","acodec":"none","height":720},
                {"format_id":"140-drc","ext":"$ext","vcodec":"none","acodec":"$codec","abr":160},
                {"format_id":"140","ext":"$ext","vcodec":"none","acodec":"$codec","abr":128}
            ]}""", source, "v1")
            val balanced = inspection.presets.single { it.preset == Preset.BALANCED }
            assertEquals("140", inspection.formats.single { it.id == balanced.formatId }.audioFormatId)
            assertTrue(inspection.formats.any { it.id == "140-drc" }, "Explicit formats stay available")
            assertTrue(inspection.formats.filter { it.conversion != null }.all { it.sourceFormatId == "140" })
            if (ext == "m4a") assertEquals("140", inspection.presets.single { it.preset == Preset.AUDIO_M4A }.formatId)
        }
    }
    @Test fun drcAudioRemainsUsableWhenNoPlainCounterpartWasReturned() {
        val inspection = YtdlpMetadataMapper.map("""{"formats":[
            {"format_id":"136","ext":"mp4","vcodec":"h264","acodec":"none","height":720},
            {"format_id":"140-drc","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":128}
        ]}""", source, "v1")
        assertEquals("140-drc", inspection.formats.single { it.id == "136" }.audioFormatId)
    }
    private fun audioSelection(rows: String, container: String = "mp4") = YtdlpMetadataMapper.map(
        """{"formats":[{"format_id":"video","ext":"$container","vcodec":"h264","acodec":"none","height":720},$rows]}""",
        source, "v1")

    @Test fun originalAudioOutranksHigherBitrateDubForMergeM4aAndMp3() {
        val inspection = audioSelection("""
            {"format_id":"dub","ext":"m4a","vcodec":"none","acodec":"aac","abr":192,"language_preference":-1},
            {"format_id":"original","ext":"m4a","vcodec":"none","acodec":"aac","abr":96,"language_preference":10}
        """)
        assertEquals("original", inspection.formats.single { it.id == "video" }.audioFormatId)
        assertEquals("original", inspection.presets.single { it.preset == Preset.AUDIO_M4A }.formatId)
        assertEquals(listOf("original", "original", "original"), inspection.formats.filter { it.conversion != null }.map { it.sourceFormatId })
        // owner 2026-10-18: auto-dubbed tracks (negative language_preference) hidden; app has no language picker.
        assertEquals(setOf("original"), inspection.formats.filter { !it.hasVideo && it.conversion == null }.map { it.id }.toSet())
    }
    @Test fun everyNonNegativeLanguageGroupRemainsWhileNegativeDubsAreHidden() {
        val inspection = audioSelection("""
            {"format_id":"140-0","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":129.5,"language_preference":-1},
            {"format_id":"251-0","ext":"webm","vcodec":"none","acodec":"opus","abr":140,"language_preference":-1},
            {"format_id":"140-9","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":129.5,"language_preference":-10},
            {"format_id":"139-20","ext":"m4a","vcodec":"none","acodec":"mp4a.40.5","abr":48.8,"language_preference":10},
            {"format_id":"140-20","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":129.4,"language_preference":10},
            {"format_id":"251-20","ext":"webm","vcodec":"none","acodec":"opus","abr":139,"language_preference":10},
            {"format_id":"140-5","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":129.5,"language_preference":5}
        """)
        assertEquals(setOf("139-20", "140-20", "251-20", "140-5"), inspection.formats.filter { !it.hasVideo && it.conversion == null }.map { it.id }.toSet())
        assertEquals("140-20", inspection.formats.single { it.id == "video" }.audioFormatId)
        assertEquals("140-20", inspection.presets.single { it.preset == Preset.AUDIO_M4A }.formatId)
    }
    @Test fun audioWithoutAnyNonNegativeLanguageGroupKeepsEveryRow() {
        val inspection = audioSelection("""
            {"format_id":"dub","ext":"m4a","vcodec":"none","acodec":"aac","abr":192,"language_preference":-1},
            {"format_id":"descriptive","ext":"m4a","vcodec":"none","acodec":"aac","abr":96,"language_preference":-10},
            {"format_id":"unlabeled","ext":"m4a","vcodec":"none","acodec":"aac","abr":64}
        """)
        assertEquals(setOf("dub", "descriptive", "unlabeled"), inspection.formats.filter { !it.hasVideo && it.conversion == null }.map { it.id }.toSet())
    }
    @Test fun tiedMultilingualBitratesSelectPreferredExactIdRegardlessOfRowOrder() {
        val rows = listOf(
            """{"format_id":"140-0","ext":"m4a","vcodec":"none","acodec":"aac","abr":129.9,"language_preference":-1}""",
            """{"format_id":"140-17","ext":"m4a","vcodec":"none","acodec":"aac","abr":129.1,"language_preference":10}""")
        for (ordered in listOf(rows, rows.reversed())) {
            val inspection = audioSelection(ordered.joinToString(","))
            assertEquals("140-17", inspection.formats.single { it.id == "video" }.audioFormatId)
        }
    }
    @Test fun missingLanguagePreferenceRetainsBitrateFallback() {
        val inspection = audioSelection("""
            {"format_id":"low","ext":"m4a","vcodec":"none","acodec":"aac","abr":96},
            {"format_id":"high","ext":"m4a","vcodec":"none","acodec":"aac","abr":192}
        """)
        assertEquals("high", inspection.formats.single { it.id == "video" }.audioFormatId)
        assertEquals("high", inspection.presets.single { it.preset == Preset.AUDIO_M4A }.formatId)
    }
    @Test fun invalidLanguagePreferencesUseUnknownBaselineRatherThanWinning() {
        for (invalid in listOf(null, "null", "true", "\"10\"", "\"NaN\"", "1e999", "-1e999", "[]", "{}")) {
            val preference = invalid?.let { ",\"language_preference\":$it" } ?: ""
            val inspection = audioSelection("""
                {"format_id":"invalid","ext":"m4a","vcodec":"none","acodec":"aac","abr":192$preference},
                {"format_id":"preferred","ext":"m4a","vcodec":"none","acodec":"aac","abr":96,"language_preference":-0.5}
            """)
            assertEquals("preferred", inspection.formats.single { it.id == "video" }.audioFormatId, invalid ?: "missing")
        }
    }
    @Test fun unknownPreferenceBeatsDescriptiveButLosesToDefault() {
        for ((preference, expected) in listOf(-10 to "unknown", 5 to "known")) {
            val inspection = audioSelection("""
                {"format_id":"known","ext":"m4a","vcodec":"none","acodec":"aac","abr":192,"language_preference":$preference},
                {"format_id":"unknown","ext":"m4a","vcodec":"none","acodec":"aac","abr":96}
            """)
            assertEquals(expected, inspection.formats.single { it.id == "video" }.audioFormatId)
        }
    }
    @Test fun fractionalLanguagePreferencesAreNotTruncated() {
        val inspection = audioSelection("""
            {"format_id":"lower","ext":"m4a","vcodec":"none","acodec":"aac","abr":192,"language_preference":5.1},
            {"format_id":"higher","ext":"m4a","vcodec":"none","acodec":"aac","abr":96,"language_preference":5.5}
        """)
        assertEquals("higher", inspection.formats.single { it.id == "video" }.audioFormatId)
    }
    @Test fun aacCompatibilityStillWinsForMp4WhileWebmUsesPreferredOpus() {
        val rows = """
            {"format_id":"default-aac","ext":"m4a","vcodec":"none","acodec":"aac","abr":192,"language_preference":5},
            {"format_id":"original-opus","ext":"webm","vcodec":"none","acodec":"opus","abr":96,"language_preference":10}
        """
        assertEquals("default-aac", audioSelection(rows).formats.single { it.id == "video" }.audioFormatId)
        val webm = audioSelection(rows, "webm").formats.single { it.id == "video" }
        assertEquals("original-opus", webm.audioFormatId)
        assertEquals("mkv", webm.container)
    }
    @Test fun hlsTwinWithOnlyManifestBandwidthYieldsToDirectRowOfSameHeightAndCodecFamily() {
        val inspection = YtdlpMetadataMapper.map("""{"duration":777,"formats":[
            {"format_id":"270","ext":"mp4","vcodec":"avc1.640028","acodec":"none","height":1080,"tbr":2201.059,"protocol":"m3u8_native"},
            {"format_id":"137","ext":"mp4","vcodec":"avc1.640028","acodec":"none","height":1080,"tbr":1018.454,"filesize":98880328,"protocol":"https"},
            {"format_id":"616","ext":"mp4","vcodec":"vp09.00.40.08","acodec":"none","height":1080,"tbr":5000,"protocol":"m3u8_native"},
            {"format_id":"617","ext":"mp4","vcodec":"vp09.00.50.08","acodec":"none","height":1440,"tbr":9000,"protocol":"m3u8_native"},
            {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":129.479,"filesize":12572049,"protocol":"https"}
        ]}""", source, "v1")
        assertEquals("137", inspection.presets.single { it.preset == Preset.BEST }.formatId)
        assertTrue(inspection.formats.none { it.id == "270" })
        // Playback-compatible codecs (2026-10-19): VP9 616 yields to H.264 137 at 1080; VP9-only 1440 HLS stays.
        assertTrue(inspection.formats.none { it.id == "616" })
        assertTrue(inspection.formats.any { it.id == "617" }, "No direct vp9 twin: HLS row stays")
        assertEquals(Size.Exact(111452377), inspection.formats.single { it.id == "137" }.size)
    }
    @Test fun hlsOnlySitesKeepTheirManifestRows() {
        val inspection = YtdlpMetadataMapper.map("""{"duration":60,"formats":[
            {"format_id":"hls-720","ext":"mp4","vcodec":"avc1.64001f","acodec":"none","height":720,"tbr":2000,"protocol":"m3u8_native"},
            {"format_id":"hls-480","ext":"mp4","vcodec":"avc1.64001e","acodec":"none","height":480,"tbr":900,"protocol":"m3u8"},
            {"format_id":"hls-audio","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":128,"protocol":"m3u8_native"}
        ]}""", source, "v1")
        assertEquals(listOf("hls-720", "hls-480"), inspection.formats.filter { it.hasVideo }.map { it.id })
        assertEquals("hls-720", inspection.presets.single { it.preset == Preset.BEST }.formatId)
        assertEquals("hls-audio", inspection.formats.single { it.id == "hls-720" }.audioFormatId)
    }
    @Test fun knownSizeOutranksInflatedBitrateWithinOneHeight() {
        val peak = """{"format_id":"peak","ext":"mp4","vcodec":"avc1.4d401f","acodec":"none","height":720,"tbr":3000}"""
        val approx = """{"format_id":"approx","ext":"mp4","vcodec":"avc1.4d401f","acodec":"none","height":720,"tbr":1000,"filesize_approx":5000}"""
        val exact = """{"format_id":"exact","ext":"mp4","vcodec":"avc1.4d401f","acodec":"none","height":720,"tbr":500,"filesize":6000}"""
        val audio = """{"format_id":"a","ext":"m4a","vcodec":"none","acodec":"aac","abr":128,"filesize":100}"""
        for ((rows, expected) in listOf(listOf(peak, approx) to "approx", listOf(peak, approx, exact) to "exact")) {
            val inspection = YtdlpMetadataMapper.map("""{"formats":[${(rows + audio).joinToString(",")}]}""", source, "v1")
            for (preset in listOf(Preset.BEST, Preset.BALANCED, Preset.SMALL))
                assertEquals(expected, inspection.presets.single { it.preset == preset }.formatId, "$preset $expected")
        }
    }
    @Test fun smallComparesUnknownSizesByRoughEstimateNotLowestHeight() {
        val inspection = YtdlpMetadataMapper.map("""{"duration":100,"formats":[
            {"format_id":"v144","ext":"mp4","vcodec":"h264","acodec":"none","height":144,"tbr":900},
            {"format_id":"v360","ext":"mp4","vcodec":"h264","acodec":"none","height":360,"tbr":200},
            {"format_id":"v720","ext":"mp4","vcodec":"h264","acodec":"none","height":720,"tbr":400},
            {"format_id":"a","ext":"m4a","vcodec":"none","acodec":"aac","abr":128}
        ]}""", source, "v1")
        assertEquals("v360", inspection.presets.single { it.preset == Preset.SMALL }.formatId)
        assertEquals(4_100_000L, inspection.roughSizeBytes["v360"])
        assertTrue(inspection.formats.filter { it.hasVideo }.all { it.size == Size.Unknown }, "Estimate never becomes a size")
    }
    @Test fun recordedHlsAndDubbedYoutubeShapeKeepsDirectOriginalRowsGolden() {
        val text = javaClass.getResource("/ytdlp/recorded-youtube-hls-dubs.json")!!.readText()
        val nodes = ObjectMapper().readTree(text).get("formats").toList()
        fun absent(node: JsonNode) = node.path("acodec").asText("none") == "none"
        val hlsVideo = nodes.filter { it.path("protocol").asText().startsWith("m3u8") && it.path("vcodec").asText("none") != "none" }
            .map { it.get("format_id").asText() }
        val dubbed = nodes.filter { it.path("vcodec").asText() == "none" && !absent(it) && it.path("language_preference").asInt(-1) < 0 }
            .map { it.get("format_id").asText() }
        assertEquals(15, hlsVideo.size); assertEquals(100, dubbed.size)
        val inspection = YtdlpMetadataMapper.map(text, source, "2026.08.19")
        val ids = inspection.formats.map { it.id }.toSet()
        assertTrue(ids.none { it in hlsVideo }); assertTrue(ids.none { it in dubbed })
        assertEquals(setOf("139-20", "249-20", "250-20", "140-20", "251-20"),
            inspection.formats.filter { !it.hasVideo && it.conversion == null }.map { it.id }.toSet())
        // Compatibility-first presets: H.264 137 stays BEST although AV1 401 (2160p) is listed.
        assertTrue("401" in ids)
        assertEquals(listOf(Preset.BEST to "137", Preset.BALANCED to "136", Preset.SMALL to "160", Preset.AUDIO_M4A to "140-20"),
            inspection.presets.filter { it.preset != Preset.AUDIO_MP3 }.map { it.preset to it.formatId })
        val best = inspection.formats.single { it.id == "137" }
        assertEquals("140-20", best.audioFormatId); assertEquals(Size.Exact(111452377), best.size)
        assertEquals(Size.Exact(15349026), inspection.formats.single { it.id == "160" }.size)
    }
    @Test fun webmOnlyPreferredLanguageAlsoFeedsMp3Conversion() {
        val inspection = audioSelection("""
            {"format_id":"dub-opus","ext":"webm","vcodec":"none","acodec":"opus","abr":192,"language_preference":-1},
            {"format_id":"original-opus","ext":"webm","vcodec":"none","acodec":"opus","abr":96,"language_preference":10}
        """, "webm")
        assertEquals("original-opus", inspection.formats.single { it.id == "video" }.audioFormatId)
        assertEquals(listOf("original-opus", "original-opus", "original-opus"), inspection.formats.filter { it.conversion != null }.map { it.sourceFormatId })
    }
    @Test fun muxedPreferredAacFeedsBothM4aAndMp3Extraction() {
        val inspection = YtdlpMetadataMapper.map("""{"formats":[
            {"format_id":"dub-muxed","ext":"mp4","vcodec":"h264","acodec":"aac","height":720,"language_preference":-1},
            {"format_id":"original-muxed","ext":"mp4","vcodec":"h264","acodec":"aac","height":480,"language_preference":10}
        ]}""", source, "v1")
        assertEquals("original-muxed", inspection.formats.single { !it.hasVideo && it.container == "m4a" }.sourceFormatId)
        assertEquals(listOf("original-muxed", "original-muxed", "original-muxed"), inspection.formats.filter { it.conversion != null }.map { it.sourceFormatId })
    }
    @Test fun muxedSourcesWithoutPreferenceKeepFirstRowEvenWhenSecondHasHigherBitrate() {
        for (codec in listOf("aac", "opus")) {
            val inspection = YtdlpMetadataMapper.map("""{"formats":[
                {"format_id":"first","ext":"mp4","vcodec":"h264","acodec":"$codec","height":480,"tbr":100},
                {"format_id":"second","ext":"mp4","vcodec":"h264","acodec":"$codec","height":720,"tbr":999}
            ]}""", source, "v1")
            assertEquals(listOf("first", "first", "first"), inspection.formats.filter { it.conversion != null }.map { it.sourceFormatId })
            if (codec == "aac") assertEquals("first", inspection.formats.single { !it.hasVideo && it.container == "m4a" }.sourceFormatId)
        }
    }
    @Test fun muxedNonAacPreferredLanguageFeedsMp3Fallback() {
        val inspection = YtdlpMetadataMapper.map("""{"formats":[
            {"format_id":"dub","ext":"webm","vcodec":"vp9","acodec":"opus","height":720,"language_preference":-1},
            {"format_id":"original","ext":"webm","vcodec":"vp9","acodec":"opus","height":480,"language_preference":10}
        ]}""", source, "v1")
        assertEquals(listOf("original", "original", "original"), inspection.formats.filter { it.conversion != null }.map { it.sourceFormatId })
    }
    @Test fun sourceHttpFailuresAndGenericDownloadFailuresAreNotConnectivityErrors() {
        val cases = listOf(
            // Recorded stderr: https://github.com/yt-dlp/yt-dlp/issues/13681
            "ERROR: unable to download video data: HTTP Error 403: Forbidden" to DownloadError.FormatExpired,
            // Recorded stderr, source identifier redacted: https://github.com/yt-dlp/yt-dlp/issues/16529
            "ERROR: [SBS] id: Unable to download SMIL file: HTTP Error 410: Gone (caused by <HTTPError 410: Gone>)" to DownloadError.Failed,
            // Recorded stderr: https://github.com/yt-dlp/yt-dlp/issues/17372
            "ERROR: unable to download video data: HTTP Error 404: Not Found" to DownloadError.FormatExpired,
            "ERROR: unable to download video data: HTTP Error 500: Internal Server Error" to DownloadError.Failed,
            "ERROR: unable to download video data: unknown error" to DownloadError.Failed,
            "ERROR: connection configuration is invalid" to DownloadError.Failed,
            "ERROR: Postprocessing: ffmpeg exited with code 1" to DownloadError.MergeFailed,
            "ERROR: unable to download video data: [Errno 104] Connection reset by peer" to DownloadError.Network,
            "ERROR: Unable to download webpage: [Errno 111] Connection refused" to DownloadError.Network,
            "ERROR: Unable to download webpage: The read operation timed out" to DownloadError.Network,
            "ERROR: Unable to download webpage: [Errno -2] Name or service not known" to DownloadError.Network,
            "ERROR: Unknown failure https://example.org/HTTP-Error-403" to DownloadError.Failed)
        for ((stderr, expected) in cases) assertEquals(expected, YtdlpErrorMapper.map(stderr), stderr)
    }
    @Test fun failedHostnameDoesNotProveTheDeviceIsOffline() {
        for (failure in listOf("Name or service not known", "Temporary failure in name resolution",
            "Unable to resolve host", "No address associated with hostname", "nodename nor servname provided",
            "No route to host")) {
            assertEquals(DownloadError.Network, YtdlpErrorMapper.map("ERROR: Unable to download webpage: $failure"), failure)
        }
        assertEquals(DownloadError.Offline, YtdlpErrorMapper.map("ERROR: Network is unreachable"))
    }

    @Test fun concreteNetworkFailuresRemainNetworkErrors() {
        for (failure in listOf("Failed to establish a new connection", "Failed to resolve 'media.example'",
            "getaddrinfo failed", "Broken pipe", "IncompleteRead(1024 bytes read, 2048 more expected)",
            "EOF occurred in violation of protocol", "Connection abort", "Connection aborted",
            "[SSL: UNEXPECTED_EOF_WHILE_READING] EOF while reading", "[SSL: UNEXPECTED_EOF_WHILE_READING]",
            "SSL: EOF detected", "SSLEOFError(8, 'unexpected EOF')")) {
            assertEquals(DownloadError.Network,
                YtdlpErrorMapper.map("ERROR: Unable to download webpage: $failure"), failure)
        }
    }
    @Test fun missingPageIsNotAnExpiredMediaFormatAndUnavailableTakesPrecedence() {
        for (status in listOf(404, 410)) {
            // A missing post page means the post is gone; other metadata files stay generic.
            for (page in listOf("webpage", "JSON metadata")) {
                assertEquals(DownloadError.Unavailable,
                    YtdlpErrorMapper.map("ERROR: Unable to download $page: HTTP Error $status"))
            }
            assertEquals(DownloadError.Failed,
                YtdlpErrorMapper.map("ERROR: Unable to download SMIL file: HTTP Error $status"))
            assertEquals(DownloadError.Failed, YtdlpErrorMapper.map("ERROR: HTTP Error $status"))
            for (media in listOf("video data", "media", "fragment 3", "HLS fragment 2")) {
                assertEquals(DownloadError.FormatExpired,
                    YtdlpErrorMapper.map("ERROR: unable to download $media: HTTP Error $status"))
            }
            for (reason in listOf("Video unavailable", "This video has been removed")) {
                assertEquals(DownloadError.Unavailable, YtdlpErrorMapper.map(
                    "ERROR: $reason; unable to download video data: HTTP Error $status"))
            }
            // Only the final ERROR line decides availability.
            assertEquals(DownloadError.Failed, YtdlpErrorMapper.map(
                "ERROR: unable to download webpage: HTTP Error $status\nERROR: unable to download video data: unknown failure"))
        }
    }
    @Test fun transientServerStatusesKeepTheUserRetryableGenericFailure() {
        for (status in listOf(429) + (500..599)) {
            assertEquals(DownloadError.Failed,
                YtdlpErrorMapper.map("ERROR: unable to download video data: HTTP Error $status"))
        }
    }
    @Test fun singleFileAndAudioOnlyNeedNoMergeAndKeepLatinIdentifiers() {
        val social = map("single-file").formats.single { it.id == "http-480" }
        assertEquals("http-480", social.id); assertEquals(480, social.height)
        assertEquals(Size.Estimated(2345678), social.size); assertFalse(social.requiresMerge)
        val audio = map("audio").formats.single { it.id == "audio-128" }
        assertFalse(audio.hasVideo); assertTrue(audio.hasAudio); assertEquals(Size.Unknown, audio.size)
        assertEquals("aac", audio.audioCodec); assertEquals(128, audio.bitrateKbps)
        assertEquals("audio-128", map("audio").presets.single { it.preset == Preset.AUDIO_M4A }.formatId)
    }
    @Test fun muxedAacSupportsExplicitLosslessM4aAndMp3Extraction() {
        val inspection = map("single-file")
        val audio = inspection.formats.single { !it.hasVideo && it.container == "m4a" }
        assertEquals("http-480", audio.sourceFormatId); assertEquals("aac", audio.audioCodec)
        assertEquals(Size.Unknown, audio.size)
        assertEquals(audio.id, inspection.presets.single { it.preset == Preset.AUDIO_M4A }.formatId)
        val args = YtdlpCommand.download(command(audio), temp.newFolder())
        assertEquals("http-480", args[args.indexOf("-f") + 1])
        assertEquals(listOf("--extract-audio", "--audio-format", "m4a") + audioTagsWithoutArtist,
            args.subList(args.indexOf("--extract-audio"), args.indexOf("--")))
        assertFalse(args.contains("--audio-quality"))
        assertEquals("http-480", inspection.formats.single { it.conversion == AudioConversion.MP3_128 }.sourceFormatId)
    }
    @Test fun webmOnlySiteStillOffersActualPlayableVideoPresets() {
        val inspection = YtdlpMetadataMapper.map("""{"formats":[
            {"format_id":"v720","ext":"webm","vcodec":"vp9","acodec":"none","height":720},
            {"format_id":"a160","ext":"webm","vcodec":"none","acodec":"opus","abr":160}]}""", source, "v1")
        assertEquals("v720", inspection.presets.single { it.preset == Preset.BEST }.formatId)
        assertEquals("v720", inspection.presets.single { it.preset == Preset.BALANCED }.formatId)
        val row = inspection.formats.single { it.id == "v720" }
        assertEquals("a160", row.audioFormatId); assertTrue(row.requiresMerge)
        assertEquals("v720+a160", YtdlpCommand.download(command(row), temp.newFolder()).let { it[it.indexOf("-f") + 1] })
    }
    @Test fun recordedUpstreamMetadataPreservesActualIdsCodecsAndByteProvenance() {
        val youtube = map("recorded-youtube").formats
        assertFalse(youtube.any { it.id == "sb3" })
        assertEquals(Size.Exact(26933341), youtube.single { it.id == "140-0" }.size)
        assertEquals("mp4a.40.2", youtube.single { it.id == "140-0" }.audioCodec)
        assertEquals(Size.Exact(12254603), youtube.single { it.id == "249-0" }.size)
        val video = youtube.single { it.id == "91-0" }
        assertEquals(144, video.height); assertEquals(Size.Unknown, video.size)
        assertEquals("avc1.4D400C", video.videoCodec); assertFalse(video.requiresMerge)
        val social = map("recorded-social-mp4").formats.single { it.hasVideo }
        assertEquals("hls-2176", social.sourceFormatId); assertEquals(720, social.height)
        assertEquals(Size.Estimated(38990299), social.size)
        val audio = map("recorded-audio").formats
        assertEquals(Size.Exact(8504480), audio.single { it.id == "download" }.size)
        assertEquals(Size.Estimated(6805024), audio.single { it.id == "hls_aac_256k" }.size)
        assertEquals(256, audio.single { it.id == "hls_aac_256k" }.bitrateKbps)
        assertTrue(audio.none { it.hasVideo })
    }
    @Test fun missingAudioNeverCreatesAnInventedMergePartner() {
        val inspection = YtdlpMetadataMapper.map("""{"formats":[{"format_id":"v","ext":"mp4","vcodec":"h264","acodec":"none"}]}""", source, "v1")
        assertFalse(inspection.formats.single().requiresMerge); assertTrue(inspection.presets.isEmpty())
        assertIs<ConfirmResult.Invalid>(DownloadCommand.confirm("job", inspection, source, "v", "x"))
    }
    @Test fun exactCommandArgsConfineOutputAndNeverSelectFallbacks() {
        val dir = temp.newFolder().canonicalFile
        val row = Format("136", "mp4", true, false, audioFormatId = "140", requiresMerge = true)
        val args = YtdlpCommand.download(command(row), dir)
        assertEquals(listOf("--ignore-config", "--no-playlist", "--playlist-items", "1") + YtdlpCommand.NETWORK + listOf(
            "--abort-on-unavailable-fragments", "--retry-sleep", "http:exp=1:8", "--retry-sleep", "fragment:exp=1:16", "-N", "4", "--no-simulate", "--newline",
            "--progress-template", YtdlpCommand.PROGRESS_TEMPLATE, "-f", "136+140",
            "-o", File(dir, "output.%(ext)s").path, "--print-to-file", "after_move:filepath",
            File(dir, "result-path.txt").path, "--merge-output-format", "mp4", "--", source.link.rawUrl), args)
        assertEquals(listOf("--ignore-config", "--no-playlist", "--playlist-items", "1", "--socket-timeout", "20", "--retries", "5",
            "--fragment-retries", "10", "--dump-json", "--skip-download", "--", source.link.rawUrl), YtdlpCommand.inspect(source))
        assertEquals("http-480", YtdlpCommand.download(command(map("single-file").formats.single { it.id == "http-480" }), dir).let { it[it.indexOf("-f") + 1] })
    }
    @Test fun audioConversionsAreExplicitAndM4aIsNotTranscoded() {
        val dir = temp.newFolder()
        val audio = map("audio").formats
        for (conversion in AudioConversion.entries) {
            val args = YtdlpCommand.download(command(audio.single { it.conversion == conversion }), dir)
            assertEquals("audio-128", args[args.indexOf("-f") + 1])
            assertEquals(listOf("--extract-audio", "--audio-format", "mp3", "--audio-quality", "${conversion.bitrateKbps}K",
                "--postprocessor-args", "ExtractAudio+ffmpeg_o1:-c:a libmp3lame -b:a ${conversion.bitrateKbps}k" +
                    if (conversion == AudioConversion.MP3_320) " -ar 44100" else "") + audioTagsWithoutArtist,
                args.subList(args.indexOf("--extract-audio"), args.indexOf("--")))
        }
        assertFalse(YtdlpCommand.download(command(audio.first()), dir).contains("--extract-audio"))
    }
    @Test fun selectorSyntaxAndNonemptyStagingAreRejected() {
        val dir = temp.newFolder()
        for (id in listOf("best", "mp4", "m4a", "best.2", "mergeall", "wa", "besta.3", "136/18", "136+140", "-f", "x[height=720]")) {
            assertFailsWith<IllegalArgumentException> {
                YtdlpCommand.download(command(Format(id, "mp4", true, true, videoCodec = "avc1", audioCodec = "aac")), dir)
            }
        }
        File(dir, "existing").writeText("preserve")
        assertFailsWith<IllegalArgumentException> { YtdlpCommand.download(command(Format("18", "mp4", true, true)), dir) }
    }
    @Test fun stagingAcceptsOnlyTheFinishedSourceTheRetryReuses() {
        val mp3 = Format("mp3-192", "mp3", false, true, sourceFormatId = "140", sourceContainer = "m4a",
            conversion = AudioConversion.MP3_192)
        val mp3FromMp3 = mp3.copy(sourceContainer = "mp3")
        val merged = Format("137", "mp4", true, true, audioFormatId = "140", requiresMerge = true)
        assertEquals("output.m4a", YtdlpCommand.finishedSource(mp3))
        assertNull(YtdlpCommand.finishedSource(mp3FromMp3)) // its template has no extension
        assertNull(YtdlpCommand.finishedSource(mp3.copy(sourceContainer = null))) // legacy row: source unknown
        assertEquals("output.mp4", YtdlpCommand.finishedSource(merged))
        for ((format, kept) in listOf(mp3 to "output.m4a", merged to "output.mp4")) {
            val dir = temp.newFolder()
            File(dir, kept).writeText("finished")
            File(dir, "output.f140.m4a.part").writeText("partial")
            assertTrue(YtdlpCommand.download(command(format), dir).isNotEmpty())
            // The conversion target or any other finished name still means a dirty directory.
            for (other in listOf("output.mp3", "output.webm", "output.mkv")) {
                File(dir, other).writeText("stale")
                assertFailsWith<IllegalArgumentException> { YtdlpCommand.download(command(format), dir) }
                File(dir, other).delete()
            }
        }
        val dir = temp.newFolder().also { File(it, "output.mp3").writeText("half") }
        assertFailsWith<IllegalArgumentException> { YtdlpCommand.download(command(mp3FromMp3), dir) }
    }
    @Test fun knownStderrPatternsMapToStableErrorsWithoutExposingMessages() {
        val cases = listOf(
            "ERROR: Unsupported URL: https://example.org/drm" to DownloadError.Unsupported,
            "ERROR: [youtube] id: Sign in to confirm your age" to DownloadError.LoginRequired,
            "ERROR: Login required" to DownloadError.LoginRequired,
            "ERROR: [youtube] id: Private video. Sign in if you've been granted access" to DownloadError.LoginRequired,
            "ERROR: This video is private" to DownloadError.LoginRequired,
            "ERROR: This video is members-only" to DownloadError.LoginRequired,
            "ERROR: Join this channel to get access" to DownloadError.LoginRequired,
            "ERROR: This video is only available for registered users" to DownloadError.LoginRequired,
            "ERROR: Video has been removed" to DownloadError.Unavailable,
            "ERROR: This video is not available in your country" to DownloadError.Unavailable,
            "ERROR: Temporary failure in name resolution" to DownloadError.Network,
            "ERROR: Network is unreachable" to DownloadError.Offline,
            "ERROR: Unable to download webpage: HTTP Error 503" to DownloadError.Failed,
            "ERROR: HTTP Error 429: Too Many Requests" to DownloadError.Failed,
            "ERROR: Sign in to confirm you're not a bot. HTTP Error 429" to DownloadError.LoginRequired,
            "ERROR: Too Many Requests" to DownloadError.Failed,
            "ERROR: Connection timed out" to DownloadError.Network,
            "ERROR: [generic] id: This video is DRM protected" to DownloadError.DrmProtected,
            "ERROR: Requested format is not available" to DownloadError.FormatExpired,
            "ERROR: No space left on device" to DownloadError.InsufficientSpace,
            "ERROR: Postprocessing: Unable to download merged file, ffmpeg exited with code 1" to DownloadError.MergeFailed,
            "[debug] https://example.org/drm/sign in\nERROR: HTTP Error 503" to DownloadError.Failed,
            "WARNING: This video is DRM protected" to DownloadError.Failed,
            "ERROR: Unsupported URL: https://example.org/admittedlydrmish" to DownloadError.Unsupported,
            "ERROR: Unknown failure https://example.org/drm" to DownloadError.Failed,
            "ERROR: Unknown drmish extractor failure" to DownloadError.Failed,
            "unrecognized secret with https://example.org/private" to DownloadError.Failed)
        for ((stderr, expected) in cases) assertEquals(expected, YtdlpErrorMapper.map(stderr), stderr)
    }
    @Test fun deviceObservedFailuresMapToTruthfulCategories() {
        // Site coverage pass 2026-10-19: exact yt-dlp 2026.x ERROR lines.
        val cases = listOf(
            "ERROR: [Instagram] DYhq: There is no video in this post" to DownloadError.NoVideo,
            "ERROR: [Instagram] BoHk: No video formats found!; please report this issue" to DownloadError.NoVideo,
            "ERROR: [twitter] 1577: No video could be found in this tweet" to DownloadError.NoVideo,
            "ERROR: [youtube] BaW_jenozKc: This video is unavailable" to DownloadError.Unavailable,
            "ERROR: [youtube] id: Video unavailable. This content isn't available." to DownloadError.Unavailable,
            "ERROR: [TikTok] 7240: Video not available, status code 10204" to DownloadError.Unavailable,
            "ERROR: [youtube] id: Video unavailable. This video is private" to DownloadError.LoginRequired,
            "ERROR: Unsupported URL: https://www.tiktok.com/?_r=1" to DownloadError.NotAPost,
            "ERROR: Unsupported URL: https://www.tiktok.com/" to DownloadError.NotAPost,
            "ERROR: Unsupported URL: https://www.tiktok.com" to DownloadError.NotAPost,
            "ERROR: Unsupported URL: https://example.org/watch/1" to DownloadError.Unsupported,
            "ERROR: Unsupported URL: https://example.org/?v=1 extra" to DownloadError.Unsupported,
            "ERROR: Requested format is not available" to DownloadError.FormatExpired,
            // Login walls win over availability wording.
            "ERROR: [youtube] id: Video unavailable. Please sign in to watch" to DownloadError.LoginRequired,
            "ERROR: [instagram] id: Video unavailable: you are not logged in" to DownloadError.LoginRequired,
            "ERROR: [youtube] id: Video unavailable. Login required" to DownloadError.LoginRequired,
            "ERROR: [youtube] id: Video unavailable. Sign in to confirm you're not a bot" to DownloadError.LoginRequired,
            // Transient status/network failures stay retryable even with availability wording.
            "ERROR: [youtube] id: Video unavailable; Unable to download webpage: HTTP Error 503" to DownloadError.Failed,
            "ERROR: [youtube] id: Video unavailable: Read timed out" to DownloadError.Network,
            "ERROR: [Instagram] id: No video formats found; Connection reset by peer" to DownloadError.Network,
            "ERROR: Unable to download webpage: HTTP Error 404\nERROR: Connection timed out" to DownloadError.Network,
            "ERROR: [youtube] id: This video is unavailable\nERROR: HTTP Error 429: Too Many Requests" to DownloadError.Failed,
            // Only the final ERROR line names the availability outcome.
            "ERROR: [youtube] id: Video unavailable\nERROR: Unknown failure" to DownloadError.Failed,
            // Photo-only wording only from Instagram/X/TikTok; elsewhere "no formats" stays generic.
            "ERROR: [generic] id: No video formats found!" to DownloadError.Failed,
            "ERROR: [vimeo] id: No video formats found!" to DownloadError.Failed,
            "ERROR: [TikTok] id: No video formats found!" to DownloadError.NoVideo)
        for ((stderr, expected) in cases) assertEquals(expected, YtdlpErrorMapper.map(stderr), stderr)
    }
    @Test fun redirectedTransferDiagnosticsKeepTypedFailuresWithoutRetainingRawOutput() {
        val diagnostics = YtdlpDiagnostics()
        diagnostics.accept("[Merger] Destination: output.mp4")
        diagnostics.accept("ERROR: No space left on device https://example.org/private")
        diagnostics.accept("ERROR: Unrecognized follow-up")
        val failure = diagnostics.failure()
        assertEquals(DownloadError.InsufficientSpace, failure.error)
        assertNull(failure.message)
        assertNull(failure.cause)
    }
    @Test fun pinnedWrapperOptionsStayBeforeEndOfOptionsAndSingleUrl() {
        // 0.18.1 executeImpl adds options, then uses buildCommand; no native/network work here.
        for (args in listOf(YtdlpCommand.inspect(source),
            YtdlpCommand.download(command(Format("18", "mp4", true, true)), temp.newFolder()))) {
            val request = com.yausername.youtubedl_android.YoutubeDLRequest(emptyList()).addCommands(args)
            request.addOption("--no-cache-dir")
            request.addOption("--js-runtimes", "quickjs:/private/qjs")
            request.addOption("--ffmpeg-location", "/private/ffmpeg")
            val argv = request.buildCommand()
            assertEquals(listOf(source.link.rawUrl), argv.drop(argv.indexOf("--") + 1))
            assertEquals(listOf("--no-cache-dir", "--js-runtimes", "quickjs:/private/qjs",
                "--ffmpeg-location", "/private/ffmpeg"), argv.take(5))
            assertEquals("1", argv[argv.indexOf("--playlist-items") + 1])
        }
    }
    @Test fun progressKeepsColonsInStreamIdsAndFloatByteEstimates() {
        val mapper = YtdlpProgressMapper(2)
        assertEquals(EngineEvent.Progress(10, Size.Unknown), mapper.map("nezzel:https:video:10:NA:1234.5"))
        assertEquals(EngineEvent.Progress(30, Size.Estimated(2234)), mapper.map("nezzel:https:audio:20:1000:NA"))
        assertEquals(EngineEvent.Progress(30, Size.Estimated(2234)), mapper.map("nezzel:https:video:9:NA:1234.5"))
        for (invalid in listOf("NaN", "Infinity", "-1.0"))
            assertEquals(EngineEvent.Progress(1, Size.Unknown), YtdlpProgressMapper().map("nezzel:a:1:NA:$invalid"))
    }
    @Test fun byteProgressAccumulatesStreamsAndPreservesUnknownAndEstimatedTotals() {
        val progress = YtdlpProgressMapper(2)
        assertEquals(EngineEvent.Progress(50, Size.Unknown), progress.map("nezzel:136:50:200:NA"))
        assertEquals(EngineEvent.Progress(200, Size.Unknown), progress.map("nezzel:136:200:200:NA"))
        assertEquals(EngineEvent.Progress(225, Size.Exact(300)), progress.map("nezzel:140:25:100:NA"))
        assertEquals(EngineEvent.Merging, progress.map("[Merger] Merging formats into output.mp4"))
        assertEquals(EngineEvent.Merging, progress.map("[ExtractAudio] Destination: output.m4a"))
        assertNull(progress.map("[download] 40.0% of ~1.00MiB"))
        assertEquals(EngineEvent.Progress(10, Size.Unknown), YtdlpProgressMapper().map("nezzel:a:10:NA:NA"))
        assertEquals(EngineEvent.Progress(10, Size.Estimated(99)), YtdlpProgressMapper().map("nezzel:a:10:NA:99"))
    }
}
