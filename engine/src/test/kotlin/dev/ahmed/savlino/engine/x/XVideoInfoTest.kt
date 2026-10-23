package dev.ahmed.savlino.engine.x

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.YtdlpMetadataMapper
import org.junit.Test
import kotlin.test.*

/** Syndication JSON → yt-dlp-shaped info: same `http-*` rows as yt-dlp's twitter extractor (synthetic fixture). */
class XVideoInfoTest {
    private val json = ObjectMapper()
    private val fixture = assertNotNull(javaClass.getResource("/x/syndication-video.json")).readText()
    private fun root(text: String = fixture) = json.readTree(text) as ObjectNode
    private fun build(root: JsonNode = root()) = XVideoInfo.build(root, X_ID)
    private fun info(root: JsonNode = root()) = json.readTree(assertNotNull(build(root)))
    private fun ids(info: JsonNode) = info.path("formats").map { it.path("format_id").asText() }
    private val source = LinkRevision(assertNotNull(LinkInput.parse(X_POST)), 1)
    private fun map(info: String) = YtdlpMetadataMapper.map(info, source, "2026.08.19")

    private fun variants(root: ObjectNode) = root.path("mediaDetails")[0].path("video_info").path("variants") as
        com.fasterxml.jackson.databind.node.ArrayNode
    private fun withVariant(variant: String) = root().also { variants(it).add(json.readTree(variant)) }
    private fun variant(bitrate: String, path: String = "1/pu/vid/avc1/1280x720/Extra.mp4") =
        """{$bitrate"content_type":"video/mp4","url":"https://video.twimg.com/ext_tw_video/$path"}"""

    /** What yt-dlp 2026.08.19 returns for this post (device shape): silent hls video, codec-less hls audio, http mp4s. */
    private val ytdlp = """{"id":"$X_ID","title":"Synthetic Author - Synthetic post text line","extractor":"twitter",
        "extractor_key":"Twitter","duration":13.04,"formats":[
        {"format_id":"hls-audio-128000-Audio","ext":"mp4","protocol":"m3u8_native","vcodec":"none","tbr":128,"url":"https://video.twimg.com/a.m3u8"},
        {"format_id":"hls-632","ext":"mp4","protocol":"m3u8_native","vcodec":"avc1.4D401E","acodec":"none","width":320,"height":568,"tbr":632,"url":"https://video.twimg.com/632.m3u8"},
        {"format_id":"http-632","ext":"mp4","protocol":"https","url":"https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/320x568/Synthetic.mp4?tag=14","vcodec":null,"acodec":null,"filesize":null,"tbr":632,"width":320,"height":568},
        {"format_id":"http-950","ext":"mp4","protocol":"https","url":"https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/480x852/Synthetic.mp4?tag=14","vcodec":null,"acodec":null,"filesize":null,"tbr":950,"width":480,"height":852},
        {"format_id":"hls-2176","ext":"mp4","protocol":"m3u8_native","vcodec":"avc1.640020","acodec":"none","width":720,"height":1280,"tbr":2176,"url":"https://video.twimg.com/2176.m3u8"},
        {"format_id":"http-2176","ext":"mp4","protocol":"https","url":"https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/720x1280/Synthetic.mp4?tag=14","vcodec":null,"acodec":null,"filesize":null,"tbr":2176,"width":720,"height":1280}]}"""

    @Test fun progressiveVariantsBecomeYtDlpHttpRowsWithoutHlsCodecOrSize() {
        val info = info()
        assertEquals(listOf("http-632", "http-950", "http-2176"), ids(info))
        val top = info.path("formats")[2]
        assertEquals("https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/720x1280/Synthetic.mp4?tag=14", top.path("url").asText())
        assertEquals(listOf("mp4", "https", "2176", "720", "1280"),
            listOf("ext", "protocol", "tbr", "width", "height").map { top.path(it).asText() })
        for (row in info.path("formats")) for (absent in listOf("vcodec", "acodec", "filesize", "filesize_approx"))
            assertFalse(row.has(absent), absent)
        assertFalse(info.has("webpage_url"), "a failed download never re-extracts silently")
        assertEquals(X_ID, info.path("id").asText())
        assertEquals("Synthetic Author - Synthetic post text line", info.path("title").asText())
        assertEquals("Synthetic Author", info.path("uploader").asText())
        assertEquals(13.04, info.path("duration").asDouble())
        assertEquals("Twitter", info.path("extractor_key").asText())
        assertEquals("https://pbs.twimg.com/ext_tw_video_thumb/1/pu/img/SyntheticPoster.jpg?name=orig", info.path("thumbnail").asText())
        assertEquals(listOf("large", "medium", "small", "thumb", "orig"), info.path("thumbnails").map { it.path("id").asText() })
    }

    @Test fun mappedOptionsEqualTheOnesYtDlpsOwnJsonGives() {
        val ours = map(assertNotNull(build()))
        val theirs = map(ytdlp)
        assertEquals(theirs.formats, ours.formats)
        assertEquals(theirs.presets, ours.presets)
        assertEquals(theirs.roughSizeBytes, ours.roughSizeBytes)
        assertEquals(listOf(1280, 852, 568), ours.formats.sortedByDescending { it.height }.map { it.height })
        assertTrue(ours.formats.all { it.size == Size.Unknown }, "unknown size stays unknown")
        assertEquals("Synthetic Author - Synthetic post text line", ours.title)
    }

    @Test fun animatedGifIsOneSilentHttpRowWithBitrateZero() {
        val gif = root().apply {
            (path("mediaDetails")[0] as ObjectNode).apply {
                put("type", "animated_gif")
                set<JsonNode>("video_info", json.readTree("""{"aspect_ratio":[249,157],"variants":[{"bitrate":0,
                    "content_type":"video/mp4","url":"https://video.twimg.com/tweet_video/Synthetic.mp4"}]}"""))
            }
        }
        val info = info(gif)
        val row = info.path("formats").single()
        assertEquals("http", row.path("format_id").asText())
        assertFalse(row.has("tbr") || row.has("width") || row.has("height"))
        assertFalse(info.has("duration"))
        val mapped = map(info.toString()).formats.single()
        assertTrue(mapped.animatedGif)
        assertEquals("http", mapped.id)
        assertEquals(map("""{"id":"$X_ID","title":"t","extractor_key":"Twitter","formats":[{"format_id":"http","ext":"mp4",
            "protocol":"https","url":"https://video.twimg.com/tweet_video/Synthetic.mp4","vcodec":null,"acodec":null}]}""")
            .formats, map(info.toString()).formats)
    }

    private fun gif(variants: String) = root().apply {
        (path("mediaDetails")[0] as ObjectNode).apply {
            put("type", "animated_gif")
            set<JsonNode>("video_info", json.readTree("""{"variants":$variants}"""))
        }
    }

    @Test fun aGifWithSeveralVariantsDeclinesToYtDlp() {
        // The fixture's own video variants under a GIF type: three rows where yt-dlp gives one.
        assertNull(build(root().apply { (path("mediaDetails")[0] as ObjectNode).put("type", "animated_gif") }))
        assertNull(build(gif("""[{"bitrate":0,"content_type":"video/mp4","url":"https://video.twimg.com/tweet_video/A.mp4"},
            {"bitrate":0,"content_type":"video/mp4","url":"https://video.twimg.com/tweet_video/B.mp4"}]""")))
    }

    @Test fun aGifWithAPositiveBitrateDeclinesToYtDlp() {
        for (rate in listOf(999, 832000))
            assertNull(build(gif("""[{"bitrate":$rate,"content_type":"video/mp4","url":"https://video.twimg.com/tweet_video/A.mp4"}]""")), "$rate")
        assertNotNull(build(gif("""[{"content_type":"video/mp4","url":"https://video.twimg.com/tweet_video/A.mp4"}]""")))
    }

    @Test fun idsUseYtDlpsIntegerDivisionAndBitrateFallback() {
        fun extra(bitrate: String) = ids(info(withVariant(variant(bitrate)))).last()
        assertEquals("http", extra(""""bitrate":999,"""))
        assertEquals("http-1", extra(""""bitrate":1500,"""))
        assertEquals("http", extra(""))
        assertEquals("http-3000", extra(""""bitrate":0,"bit_rate":3000000,"""))
        for (bad in listOf(""""bitrate":"2176000",""", """"bitrate":-1000,""", """"bitrate":1.5e6,"""))
            assertNull(build(withVariant(variant(bad))), bad)
    }

    @Test fun anyUnknownShapeDeclinesToYtDlp() {
        val cases = listOf(
            withVariant(variant(""""bitrate":632000,""", "1/pu/vid/avc1/320x568/Duplicate.mp4")), // duplicate http-632
            withVariant("""{"bitrate":1,"content_type":"video/webm","url":"https://video.twimg.com/ext_tw_video/a.mp4"}"""),
            withVariant("""{"bitrate":1000,"content_type":"video/mp4","url":"https://example.invalid/vid/a.mp4"}"""),
            withVariant("""{"bitrate":1000,"content_type":"video/mp4","url":"http://video.twimg.com/ext_tw_video/a.mp4"}"""),
            withVariant("""{"bitrate":1000,"content_type":"video/mp4","url":"https://video.twimg.com:8443/ext_tw_video/a.mp4"}"""),
            withVariant("""{"bitrate":1000,"content_type":"video/mp4"}"""),
            root().apply { variants(this).removeAll { "m3u8" !in it.path("url").asText() } }, // hls only
            root().apply { put("card", "{}") },
            root().apply { set<JsonNode>("quoted_tweet", json.readTree("""{"id_str":"1"}""")) },
            root().apply { set<JsonNode>("retweeted_status", json.readTree("""{"id_str":"1"}""")) },
            root().apply { put("__typename", "TweetTombstone") },
            root().apply { put("id_str", "1790000000000000002") },
            root().apply { (path("mediaDetails")[0] as ObjectNode).put("type", "audio_space") },
            root().apply { remove("mediaDetails") })
        for ((index, case) in cases.withIndex()) assertNull(build(case), "case $index")
    }

    @Test fun severalOwnVideosKeepYtDlpsFirstPlaylistItem() {
        val two = root().apply {
            val second = (path("mediaDetails")[0].deepCopy<ObjectNode>())
            (second.path("video_info").path("variants") as com.fasterxml.jackson.databind.node.ArrayNode).removeAll()
            (path("mediaDetails") as com.fasterxml.jackson.databind.node.ArrayNode).add(second)
        }
        val info = info(two)
        assertEquals(listOf("http-632", "http-950", "http-2176"), ids(info))
        assertEquals("Synthetic Author - Synthetic post text line #1", info.path("title").asText())
    }

    @Test fun titleStripsLinksAndCutsAt72CodePointsLikeYtDlp() {
        val long = "😀".repeat(80)
        val info = info(root().apply { put("text", "$long https://t.co/Synthetic") })
        assertEquals("Synthetic Author - " + "😀".repeat(69) + "...", info.path("title").asText())
        assertEquals("Synthetic post text line", info(root().apply { remove("user") }).path("title").asText())
    }

    @Test fun parserCarriesTheInfoForVideoPostsOnly() {
        val parsed = assertIs<XParseResult.Video>(XPostParser().parse(fixture.toByteArray(), X_ID))
        assertEquals(build(), parsed.infoJson)
        assertNull(assertIs<XParseResult.Video>(XPostParser().parse(xTweet(listOf(xVideo()), extra =
            ""","quoted_tweet":{"id_str":"1"}"""), X_ID)).infoJson)
    }
}
