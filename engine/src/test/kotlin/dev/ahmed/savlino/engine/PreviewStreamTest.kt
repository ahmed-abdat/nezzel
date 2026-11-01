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
                "rejects HLS and DASH manifests",
                formats(
                    """{"url":"https://media.example/video.m3u8","protocol":"m3u8_native","ext":"mp4","height":360,"vcodec":"avc1","acodec":"aac"}""",
                    """{"url":"https://media.example/video.mpd","protocol":"http_dash_segments","ext":"mp4","height":360,"vcodec":"avc1","acodec":"aac"}""",
                ),
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
                "preserves request headers except cookies",
                formats(
                    """{"url":"https://media.example/headers.mp4","ext":"mp4","height":360,"vcodec":"avc1","acodec":"aac","http_headers":{"User-Agent":"Preview fixture","Referer":"https://media.example/watch","Cookie":"fixture=value"}}""",
                ),
                url = "https://media.example/headers.mp4",
                headers = mapOf("User-Agent" to "Preview fixture", "Referer" to "https://media.example/watch"),
            ),
            Case(
                "rejects playlists even with a playable root file",
                """{"_type":"playlist","url":"https://media.example/playlist.mp4","ext":"mp4","vcodec":"avc1","acodec":"aac"}""",
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
