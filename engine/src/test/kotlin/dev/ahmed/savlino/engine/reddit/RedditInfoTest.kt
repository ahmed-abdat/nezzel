package dev.ahmed.savlino.engine.reddit

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Test
import kotlin.test.*

class RedditInfoTest {
    private val video = assertIs<RedditPost.Video>(RedditPostParser.parse(postPage(), postPage().size, POST_ID))
    /** yt-dlp's generic DASH result for a v.redd.it manifest (2026-10-27), values synthetic. */
    private val dash = """{"id":"DASHPlaylist","title":"DASHPlaylist","extractor":"generic","formats":[
        {"format_id":"dash-video-1","height":480,"vcodec":"avc1.4d401f","acodec":"none","protocol":"https"},
        {"format_id":"dash-audio-1","vcodec":"none","acodec":"mp4a.40.2","protocol":"https"}]}"""

    @Test fun thePostNamesTheVideoWhileFormatsStayAsYtDlpReturnedThem() {
        val patched = ObjectMapper().readTree(RedditInfo.patch(dash, video))
        assertEquals(VIDEO_ID, patched["id"].asText())
        assertEquals("A synthetic & quoted \"video\"", patched["title"].asText())
        assertEquals("synthetic_author", patched["uploader"].asText())
        assertEquals(44, patched["duration"].asInt())
        assertTrue(patched["thumbnail"].asText().startsWith("https://external-preview.redd.it/"))
        assertEquals(ObjectMapper().readTree(dash)["formats"], patched["formats"])
        assertEquals(12, ObjectMapper().readTree(RedditInfo.patch("""{"duration":12}""", video))["duration"].asInt())
        assertEquals("[1]", RedditInfo.patch("[1]", video))
    }
}
