package dev.ahmed.savlino.engine

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.*

@RunWith(Parameterized::class)
class PreviewStreamTest(private val case: Case) {
    data class Case(
        val name: String,
        val json: String,
        val url: String? = null,
        val audioUrl: String? = null,
        val hasVideo: Boolean = true,
        val headers: Map<String, String> = emptyMap(),
        val checkRedaction: Boolean = false,
        val hls: Boolean = false,
    ) {
        override fun toString() = name
    }

    @Test fun picksPlayablePreview() {
        val stream = PreviewStream.pick(case.json)
        if (case.url == null) {
            assertNull(stream, case.name)
            return
        }
        val actual = assertNotNull(stream, case.name)
        assertEquals(case.url, actual.url, case.name)
        assertEquals(case.audioUrl, actual.audioUrl, case.name)
        assertEquals(case.hasVideo, actual.hasVideo, case.name)
        assertEquals(case.headers, actual.headers, case.name)
        assertEquals(case.hls, actual.hls, case.name)
        if (case.checkRedaction) {
            val description = actual.toString()
            assertFalse(actual.url in description, case.name)
            assertFalse(assertNotNull(actual.audioUrl) in description, case.name)
            assertFalse("https://" in description, case.name)
        }
    }

    companion object {
        private fun formats(vararg entries: String) = """{"formats":[${entries.joinToString(",")}]}"""

        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Case>> = listOf(
            Case(
                "prefers small muxed H264 over HD and VP9",
                formats(
                    """{"url":"https://media.example/hd.mp4","protocol":"https","ext":"mp4","height":720,"vcodec":"avc1","acodec":"aac"}""",
                    """{"url":"https://media.example/vp9.webm","protocol":"https","ext":"webm","height":360,"vcodec":"vp9","acodec":"opus"}""",
                    """{"url":"https://media.example/small.mp4","protocol":"https","ext":"mp4","height":360,"vcodec":"avc1","acodec":"aac"}""",
                ),
                url = "https://media.example/small.mp4",
            ),
            Case(
                "pairs 360p video with low bitrate M4A",
                formats(
                    """{"url":"https://media.example/240.mp4","ext":"mp4","height":240,"vcodec":"avc1","acodec":"none"}""",
                    """{"url":"https://media.example/1080.mp4","ext":"mp4","height":1080,"vcodec":"avc1","acodec":"none"}""",
                    """{"url":"https://media.example/360.mp4","ext":"mp4","height":360,"vcodec":"avc1","acodec":"none"}""",
                    """{"url":"https://media.example/128.m4a","ext":"m4a","abr":128,"vcodec":"none","acodec":"aac"}""",
                    """{"url":"https://media.example/opus.webm","ext":"webm","abr":32,"vcodec":"none","acodec":"opus"}""",
                    """{"url":"https://media.example/48.m4a","ext":"m4a","abr":48,"vcodec":"none","acodec":"aac"}""",
                ),
                url = "https://media.example/360.mp4",
                audioUrl = "https://media.example/48.m4a",
            ),
            Case(
                "rejects DASH manifests and plain-HTTP playlists",
                formats(
                    """{"url":"https://media.example/video.mpd","protocol":"http_dash_segments","ext":"mp4","height":360,"vcodec":"avc1","acodec":"aac"}""",
                    """{"url":"http://media.example/video.m3u8","protocol":"m3u8_native","ext":"mp4","height":360,"vcodec":"avc1","acodec":"aac"}""",
                ),
            ),
            Case(
                "plays the HLS variant nearest 360p when there is no plain file",
                formats(
                    """{"url":"https://media.example/288.m3u8","protocol":"m3u8_native","ext":"mp4","height":288,"vcodec":"avc1","acodec":"mp4a"}""",
                    """{"url":"https://media.example/480.m3u8","protocol":"m3u8_native","ext":"mp4","height":480,"vcodec":"avc1","acodec":"mp4a"}""",
                    """{"url":"https://media.example/1080.m3u8","protocol":"m3u8_native","ext":"mp4","height":1080,"vcodec":"avc1","acodec":"mp4a"}""",
                ),
                url = "https://media.example/288.m3u8",
                hls = true,
            ),
            Case(
                "prefers a small HLS variant over a big plain file",
                formats(
                    """{"url":"https://media.example/640.m3u8","protocol":"m3u8_native","ext":"mp4","height":640,"vcodec":"avc1","acodec":"mp4a"}""",
                    """{"url":"https://media.example/1280.m3u8","protocol":"m3u8_native","ext":"mp4","height":1280,"vcodec":"avc1","acodec":"mp4a"}""",
                    """{"url":"https://media.example/blob.mp4","protocol":"https","ext":"mp4","height":1920,"vcodec":null,"acodec":null}""",
                ),
                url = "https://media.example/640.m3u8",
                hls = true,
            ),
            Case(
                "plays an HLS sound-only playlist",
                formats(
                    """{"url":"https://media.example/aac.m3u8","protocol":"m3u8_native","ext":"m4a","vcodec":"none","acodec":"mp4a","abr":96}""",
                ),
                url = "https://media.example/aac.m3u8",
                hasVideo = false,
                hls = true,
            ),
            Case(
                "rejects DRM streams",
                formats(
                    """{"url":"https://media.example/drm.mp4","has_drm":true,"ext":"mp4","height":360,"vcodec":"avc1","acodec":"aac"}""",
                ),
            ),
            Case(
                "rejects insecure HTTP streams",
                formats(
                    """{"url":"http://media.example/video.mp4","protocol":"http","ext":"mp4","height":360,"vcodec":"avc1","acodec":"aac"}""",
                ),
            ),
            Case(
                "plays an audio only source",
                formats(
                    """{"url":"https://media.example/audio.mp3","ext":"mp3","vcodec":"none","acodec":"mp3","abr":128}""",
                ),
                url = "https://media.example/audio.mp3",
                hasVideo = false,
            ),
            Case(
                "uses a direct root file without formats",
                """{"url":"https://media.example/direct.mp4","ext":"mp4","vcodec":"avc1","acodec":"aac"}""",
                url = "https://media.example/direct.mp4",
            ),
            Case(
                "keeps only safe request headers",
                formats(
                    """{"url":"https://media.example/headers.mp4","ext":"mp4","height":360,"vcodec":"avc1","acodec":"aac","http_headers":{"User-Agent":"Preview fixture","Referer":"https://media.example/watch","Cookie":"fixture=value","Authorization":"Bearer fixture"}}""",
                ),
                url = "https://media.example/headers.mp4",
                headers = mapOf("User-Agent" to "Preview fixture", "Referer" to "https://media.example/watch"),
            ),
            Case(
                "rejects playlists even with a playable root file",
                """{"_type":"playlist","url":"https://media.example/playlist.mp4","ext":"mp4","vcodec":"avc1","acodec":"aac"}""",
            ),
            Case(
                "plays the smaller unlabelled file when the site gives no codecs or height",
                formats(
                    """{"format_id":"hd","url":"https://media.example/hd.mp4","ext":"mp4"}""",
                    """{"format_id":"sd","url":"https://media.example/sd.mp4","ext":"mp4"}""",
                    """{"format_id":"thumb","url":"https://media.example/poster.jpg","ext":"jpg"}""",
                ),
                url = "https://media.example/sd.mp4",
            ),
            Case(
                "prefers a sized video and audio pair over an unlabelled file",
                formats(
                    """{"format_id":"sd","url":"https://media.example/sd.mp4","ext":"mp4"}""",
                    """{"url":"https://media.example/360.mp4","ext":"mp4","height":360,"vcodec":"avc1","acodec":"none"}""",
                    """{"url":"https://media.example/48.m4a","ext":"m4a","abr":48,"vcodec":"none","acodec":"aac"}""",
                ),
                url = "https://media.example/360.mp4",
                audioUrl = "https://media.example/48.m4a",
            ),
            Case(
                "plays plain files whose codecs are null, nearest to 360p",
                formats(
                    """{"url":"https://media.example/a.m3u8","protocol":"m3u8_native","ext":"mp4","vcodec":"none","acodec":null}""",
                    """{"url":"https://media.example/270.mp4","protocol":"https","ext":"mp4","vcodec":null,"acodec":null,"height":270}""",
                    """{"url":"https://media.example/360.mp4","protocol":"https","ext":"mp4","vcodec":null,"acodec":null,"height":360}""",
                    """{"url":"https://media.example/720.mp4","protocol":"https","ext":"mp4","vcodec":null,"acodec":null,"height":720}""",
                ),
                url = "https://media.example/360.mp4",
            ),
            Case(
                "plays the smallest big file when no small one has sound",
                formats(
                    """{"url":"https://media.example/video.mpd","protocol":"http_dash_segments","ext":"mp4","vcodec":"avc1","acodec":"mp4a","height":424}""",
                    """{"url":"https://media.example/1138.mp4","protocol":"https","ext":"mp4","vcodec":null,"acodec":null,"height":1138}""",
                    """{"url":"https://media.example/1920.mp4","protocol":"https","ext":"mp4","vcodec":null,"acodec":null,"height":1920}""",
                ),
                url = "https://media.example/1138.mp4",
            ),
            Case(
                "plays silent video when the site gives no sound at all",
                formats(
                    """{"url":"https://media.example/nosize.mp4","ext":"mp4","vcodec":null,"acodec":"none"}""",
                    """{"url":"https://media.example/840.mp4","ext":"mp4","vcodec":"avc1","acodec":"none","height":840}""",
                    """{"url":"https://media.example/1280.mp4","ext":"mp4","vcodec":"avc1","acodec":"none","height":1280}""",
                ),
                url = "https://media.example/840.mp4",
            ),
            Case(
                "never plays silent video when the sound is only in a playlist",
                formats(
                    """{"url":"https://media.example/audio.m3u8","protocol":"m3u8_native","ext":"mp4","vcodec":"none","acodec":"mp4a"}""",
                    """{"url":"https://media.example/360.mp4","ext":"mp4","vcodec":"avc1","acodec":"none","height":360}""",
                ),
            ),
            Case("rejects malformed JSON", "{\"formats\":["),
            Case(
                "redacts both stream URLs from toString",
                formats(
                    """{"url":"https://media.example/private-video.mp4","ext":"mp4","height":360,"vcodec":"avc1","acodec":"none"}""",
                    """{"url":"https://media.example/private-audio.m4a","ext":"m4a","abr":48,"vcodec":"none","acodec":"aac"}""",
                ),
                url = "https://media.example/private-video.mp4",
                audioUrl = "https://media.example/private-audio.m4a",
                checkRedaction = true,
            ),
        ).map { arrayOf(it) }
    }
}
