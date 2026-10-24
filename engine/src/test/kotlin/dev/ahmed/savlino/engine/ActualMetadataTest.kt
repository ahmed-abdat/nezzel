package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import kotlin.test.*

class ActualMetadataTest {
    private val source = LinkRevision(LinkInput.parse("https://example.org/media")!!, 1)
    private fun map(rows: String, extra: String = "") = YtdlpMetadataMapper.map(
        """{"formats":[$rows]$extra}""", source, "v1")
    // Old ranking picks muxed over adaptive at 720, highest bitrate at 1080, smallest winner at480.
    private val alternatives = """
        {"format_id":"a128","ext":"m4a","vcodec":"none","acodec":"aac","abr":128,"filesize":100},
        {"format_id":"adaptive720","ext":"mp4","vcodec":"h264","acodec":"none","height":720,"tbr":3000,"filesize":200},
        {"format_id":"muxed720","ext":"mp4","vcodec":"h264","acodec":"aac","height":720,"tbr":900,"filesize":900},
        {"format_id":"v1080low","ext":"mp4","vcodec":"h264","acodec":"none","height":1080,"tbr":2000,"filesize":2000},
        {"format_id":"v1080high","ext":"mp4","vcodec":"h264","acodec":"none","height":1080,"tbr":4000,"filesize":4000},
        {"format_id":"v480","ext":"mp4","vcodec":"h264","acodec":"aac","height":480,"tbr":500,"filesize":500}
    """
    @Test fun allActualSameHeightVideoRenditionsRetainExactPartnersAndSizes() {
        val found = map(alternatives)
        assertEquals(listOf("adaptive720", "muxed720", "v1080low", "v1080high", "v480"),
            found.formats.filter { it.hasVideo }.map { it.id })
        val adaptive = found.formats.single { it.id == "adaptive720" }
        assertEquals("a128", adaptive.audioFormatId)
        assertEquals(Size.Exact(300), adaptive.size)
        assertTrue(adaptive.requiresMerge)
        assertNull(found.formats.single { it.id == "muxed720" }.audioFormatId)
    }
    @Test fun sameHeightAlternativesPreservePreChangeGoldenPresetIdsAndPartners() {
        val found = map(alternatives)
        val golden = found.presets.joinToString("\n") { choice ->
            val row = found.formats.single { it.id == choice.formatId }
            "${choice.preset}:${row.id}:${row.audioFormatId ?: "-"}"
        }
        assertEquals("BEST:v1080high:a128\nBALANCED:muxed720:-\nSMALL:v480:-\nAUDIO_M4A:a128:-\nAUDIO_MP3:nezzel:mp3:192:a128:-", golden)
    }
    @Test fun equalBitrateActualAudioAndFirstAcceptedDuplicateIdRemainDistinct() {
        val found = map("""
            {"format_id":"first","ext":"m4a","vcodec":"none","acodec":"aac","abr":128,"filesize":11},
            {"format_id":"second","ext":"m4a","vcodec":"none","acodec":"aac","abr":128,"filesize":22},
            {"format_id":"first","ext":"m4a","vcodec":"none","acodec":"aac","abr":320,"filesize":99}
        """)
        assertEquals(listOf("first", "second"), found.formats.filter { it.conversion == null }.map { it.id })
        assertEquals(Size.Exact(11), found.formats.single { it.id == "first" }.size)
        assertEquals(128, found.formats.single { it.id == "first" }.bitrateKbps)
    }
    @Test fun invalidDrmStoryboardAndSelectorCandidatesNeverEnterFullRows() {
        val found = map("""
            {"format_id":"valid","ext":"mp4","vcodec":"h264","acodec":"aac","height":720},
            {"format_id":"drm","ext":"mp4","vcodec":"h264","acodec":"aac","has_drm":true},
            {"format_id":"storyboard","ext":"mhtml","vcodec":"images","acodec":"none"},
            {"format_id":"missing-codecs","ext":"mp4"},
            {"format_id":"best","ext":"mp4","vcodec":"h264","acodec":"aac"},
            {"format_id":"bad-ext","ext":"../mp4","vcodec":"h264","acodec":"aac"}
        """)
        assertEquals(listOf("valid"), found.formats.filter { it.conversion == null && it.hasVideo }.map { it.id })
        assertTrue(found.formats.none { it.id in setOf("drm", "storyboard", "missing-codecs", "best", "bad-ext") })
    }
    @Test fun conversionRowsCarryActualSourceContainerAndKeepNativeMp3Independent() {
        for ((ext, codec) in listOf("mp3" to "mp3", "m4a" to "aac", "webm" to "opus", "mp4" to "mp3")) {
            val found = map("""{"format_id":"source","ext":"$ext","vcodec":"${if (ext == "mp4") "h264" else "none"}","acodec":"$codec","abr":96}""")
            assertNotNull(found.formats.singleOrNull { it.id == "source" && it.conversion == null })
            val conversions = found.formats.filter { it.conversion != null }
            assertEquals(3, conversions.size)
            assertEquals(setOf(AudioConversion.MP3_128, AudioConversion.MP3_192, AudioConversion.MP3_320), conversions.map { it.conversion }.toSet())
            for (row in conversions) {
                assertEquals(ext, row.sourceContainer); assertEquals("source", row.sourceFormatId)
                assertEquals("mp3", row.audioCodec); assertEquals(Size.Unknown, row.size)
            }
        }
    }
    @Test fun websocketProtocolPartsBlockConversionsOnRootAndSourceButKeepOriginal() {
        for (protocol in listOf("websocket_frag", "https+websocket_frag", "websocket_frag+https")) {
            val row = """{"format_id":"source","ext":"mp3","vcodec":"none","acodec":"mp3"%s}"""
            for (atRoot in listOf(true, false)) {
                val field = ",\"protocol\":\"$protocol\""
                val found = map(row.format(if (atRoot) "" else field), if (atRoot) field else "")
                assertEquals(listOf("source"), found.formats.map { it.id }, "$protocol root=$atRoot")
                assertTrue(found.presets.none { it.preset == Preset.AUDIO_MP3 })
            }
        }
        // Match complete protocol parts; a containing string is not evidence of the downloader.
        val regular = map("""{"format_id":"source","ext":"mp3","vcodec":"none","acodec":"mp3",
            "protocol":"https+not_websocket_frag"}""")
        assertEquals(3, regular.formats.count { it.conversion != null })
    }
    @Test fun liveStatusAloneBlocksConversionsOnRootAndSourceButKeepsOriginal() {
        val row = """{"format_id":"source","ext":"mp3","vcodec":"none","acodec":"mp3"%s}"""
        val live = ",\"live_status\":\"is_live\""
        for (atRoot in listOf(true, false)) {
            val found = map(row.format(if (atRoot) "" else live), if (atRoot) live else "")
            assertEquals(listOf("source"), found.formats.map { it.id })
            assertTrue(found.presets.none { it.preset == Preset.AUDIO_MP3 })
        }
        val regular = map(row.format(""), ",\"live_status\":\"not_live\"")
        assertEquals(3, regular.formats.count { it.conversion != null })
    }
    @Test fun liveDashPeriodsAndWebSocketKeepOriginalButOfferNoMp3Conversions() {
        val base = """{"format_id":"source","ext":"mp3","vcodec":"none","acodec":"mp3"%s}"""
        for (root in listOf(",\"is_live\":true", ",\"is_dash_periods\":true")) {
            val found = map(base.format(""), root)
            assertEquals(listOf("source"), found.formats.map { it.id })
        }
        for (extra in listOf(",\"is_live\":true", ",\"is_dash_periods\":true", ",\"protocol\":\"websocket_frag\"")) {
            val found = map(base.format(extra))
            assertEquals(listOf("source"), found.formats.map { it.id })
        }
    }
}
