package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import kotlin.test.*

class RoughSizeTest {
    private val source = LinkRevision(LinkInput.parse("https://example.org/video")!!, 1)
    private fun map(rows: String, metadata: String = "\"duration\":10.5") =
        YtdlpMetadataMapper.map("""{$metadata,"formats":[$rows]}""", source, "v1")
    private fun video(extra: String = "\"tbr\":800.5", audio: String = "aac") =
        """{"format_id":"v1","ext":"mp4","vcodec":"h264","acodec":"$audio","height":720,$extra}"""
    private fun audio(extra: String = "\"abr\":128.5", codec: String = "aac", ext: String = "m4a") =
        """{"format_id":"a1","ext":"$ext","vcodec":"none","acodec":"$codec",$extra}"""

    @Test fun enforcedMp3ConversionEstimatesUseSelectedRateEvenForAlreadyMp3OrMuxedMp3() {
        for (rows in listOf(audio("\"filesize\":123456", "mp3", "mp3"),
            audio("\"filesize_approx\":123456", "mp3", "mp3"),
            audio("\"abr\":96", "mp3", "mp3"),
            video("\"tbr\":8000,\"filesize\":1000000,\"abr\":96", "mp3"),
            video("\"tbr\":8000", "mp3"))) {
            val found = map(rows)
            val low = found.formats.single { it.conversion == AudioConversion.MP3_128 }
            val mid = found.formats.single { it.conversion == AudioConversion.MP3_192 }
            val high = found.formats.single { it.conversion == AudioConversion.MP3_320 }
            assertEquals(168000L, found.roughSizeBytes[low.id])
            assertEquals(252000L, found.roughSizeBytes[mid.id])
            assertEquals(420000L, found.roughSizeBytes[high.id])
            assertEquals(Size.Unknown, low.size); assertEquals(Size.Unknown, mid.size); assertEquals(Size.Unknown, high.size)
        }
    }
    @Test fun convertedSizeWithoutValidDurationStaysUnknownWhileNativeSizeSurvives() {
        for (duration in listOf("null", "0", "-1", "\"10\"", "1e999")) {
            val found = map(audio("\"filesize\":123456", "mp3", "mp3"), "\"duration\":$duration")
            assertEquals(Size.Exact(123456), found.formats.single { it.id == "a1" }.size)
            val converted = found.formats.filter { it.conversion != null }
            assertEquals(3, converted.size)
            for (row in converted) {
                assertEquals(Size.Unknown, row.size)
                assertFalse(found.roughSizeBytes.containsKey(row.id))
            }
        }
    }

    @Test fun missingSizesUseDecimalReturnedBitrateAndDurationWithoutChangingFormatSize() {
        val result = map(video())
        assertEquals(1_050_656L, result.roughSizeBytes["v1"])
        assertEquals(Size.Unknown, result.formats.single { it.id == "v1" }.size)
    }
    @Test fun exactAndEngineApproximateSizesAlwaysWinOverFallback() {
        for ((field, size) in listOf("filesize" to Size.Exact(42), "filesize_approx" to Size.Estimated(42))) {
            val result = map(video("\"tbr\":800,\"$field\":42"))
            assertEquals(size, result.formats.single { it.id == "v1" }.size)
            assertFalse(result.roughSizeBytes.containsKey("v1"))
        }
    }
    @Test fun mergedEstimateIncludesExactVideoAndRoughSelectedAudio() {
        val result = map(video("\"filesize\":1000000", "none") + "," + audio())
        assertEquals(1_168_656L, result.roughSizeBytes["v1"])
        val row = result.formats.single { it.id == "v1" }
        assertEquals("a1", row.audioFormatId)
        assertEquals(Size.Unknown, row.size)
    }
    @Test fun missingMergePartnerSizeNeverShowsVideoOnlyTotal() {
        val result = map(video(audio = "none") + "," + audio("\"abr\":null"))
        assertFalse(result.roughSizeBytes.containsKey("v1"))
    }
    @Test fun audioUsesWholeStreamTbrBeforeAbr() {
        val result = map(audio("\"abr\":128,\"tbr\":140.5"))
        assertEquals(184_406L, result.roughSizeBytes["a1"])
    }
    @Test fun muxedM4aExtractionUsesAudioBitrateNotVideoBitrate() {
        val result = map(video("\"tbr\":8000,\"abr\":128"))
        val row = result.formats.single { !it.hasVideo && it.container == "m4a" }
        assertEquals(168_000L, result.roughSizeBytes[row.id])
        assertEquals(Size.Unknown, row.size)
        val absent = map(video("\"tbr\":8000"))
        assertFalse(absent.roughSizeBytes.containsKey(row.id))
    }
    @Test fun mp3ReencodeUsesSelectedBitrateForEverySourceCodec() {
        val encoded = map(audio())
        for (row in encoded.formats.filter { it.conversion != null }) {
            assertEquals((10.5 * row.conversion!!.bitrateKbps * 125).toLong(), encoded.roughSizeBytes[row.id])
            assertEquals(Size.Unknown, row.size)
        }
        val copied = map(audio("\"filesize\":123456", "mp3", "mp3"))
        for (row in copied.formats.filter { it.conversion != null })
            assertEquals((10.5 * row.conversion!!.bitrateKbps * 125).toLong(), copied.roughSizeBytes[row.id])
    }
    @Test fun muxedMp3ConversionEstimatesSelectedAudioRateAndNeverUsesVideoSizeOrBitrate() {
        for (metadata in listOf("\"tbr\":800,\"abr\":96", "\"filesize\":1000000,\"abr\":96")) {
            val result = map(video(metadata, "mp3"))
            for (row in result.formats.filter { it.conversion != null })
                assertEquals((10.5 * row.conversion!!.bitrateKbps * 125).toLong(), result.roughSizeBytes[row.id])
        }
        val absent = map(video("\"tbr\":800,\"filesize\":1000000", "mp3"))
        for (row in absent.formats.filter { it.conversion != null })
            assertEquals((10.5 * row.conversion!!.bitrateKbps * 125).toLong(), absent.roughSizeBytes[row.id])
    }
    @Test fun invalidMissingOverflowOrLiveMetadataNeverProducesRoughSize() {
        for (metadata in listOf("\"duration\":null", "\"duration\":0", "\"duration\":-1", "\"duration\":1e300",
            "\"duration\":10,\"is_live\":true", "\"duration\":10,\"live_status\":\"is_live\"",
            "\"duration\":10,\"live_status\":\"is_upcoming\"", "\"duration\":10,\"live_status\":\"post_live\"")) {
            assertTrue(map(video(), metadata).roughSizeBytes.isEmpty(), metadata)
        }
        for (rate in listOf("null", "0", "-1", "1e300", "\"800\"")) {
            val result = map(video("\"tbr\":$rate"))
            assertFalse(result.roughSizeBytes.containsKey("v1"), rate)
        }
    }
    @Test fun mergedSumOverflowDoesNotWrapIntoPlausibleEstimate() {
        val result = map(video("\"filesize\":9223372036854775800", "none") + "," + audio())
        assertFalse(result.roughSizeBytes.containsKey("v1"))
    }
}
