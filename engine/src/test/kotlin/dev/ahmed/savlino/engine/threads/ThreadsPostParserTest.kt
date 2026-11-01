package dev.ahmed.savlino.engine.threads

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.engine.mediaimage.DiscoveryCompleteness
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import dev.ahmed.savlino.engine.mediaimage.ImagePhase
import org.junit.Test
import kotlin.test.*

class ThreadsPostParserTest {
    private val parser = ThreadsPostParser()
    private val json = ObjectMapper()
    private fun bytes(name: String) = javaClass.getResourceAsStream("/threads/$name.html")!!.use { it.readBytes() }
    private fun parse(name: String, code: String) = parser.parse(bytes(name), code)
    private fun failure(name: String, code: String, reason: ImageFailure, phase: ImagePhase = ImagePhase.PAGE) {
        val failed = assertIs<ThreadsParseResult.Failure>(parse(name, code))
        assertEquals(reason, failed.reason)
        assertEquals(phase, failed.phase)
    }

    @Test fun imagePicksTheWidestCandidateAndTheCaptionFirstLine() {
        val photos = assertIs<ThreadsParseResult.Photos>(parse("image", "SynthPost01"))
        assertEquals(listOf("https://scontent.cdninstagram.com/v/t51/synthetic_full.jpg?oe=6F000000"), photos.candidates)
        assertEquals(listOf("https://scontent.cdninstagram.com/v/t51/synthetic_small.jpg?oe=6F000000"), photos.previewCandidates)
        assertEquals("Synthetic caption", photos.title)
        assertEquals("synthetic_user", photos.author)
        assertNull(photos.videoInfoJson)
        assertEquals(DiscoveryCompleteness.COMPLETE, photos.discovery.completeness)
        assertFalse(photos.discovery.hasVideo)
    }

    @Test fun videoKeepsTheLargestProgressiveFileAndDropsDash() {
        val video = assertIs<ThreadsParseResult.Video>(parse("video", "SynthVid0001"))
        val format = json.readTree(video.infoJson).path("formats").single()
        assertEquals("progressive", format.path("format_id").asText())
        assertEquals("https://instagram.fexample.fna.fbcdn.net/o1/v/t2/f2/synthetic_large.mp4", format.path("url").asText())
        assertEquals(1280, format.path("width").asInt())
        assertEquals(720, format.path("height").asInt())
        assertFalse(format.has("filesize"))
        assertFalse(video.infoJson.contains("MPD"))
        assertFalse(video.infoJson.contains("synthetic_small"))
        assertTrue(json.readTree(video.infoJson).path("thumbnail").asText().endsWith("synthetic_poster.jpg"))
    }

    @Test fun imageCarouselAndMixedCarousel() {
        val album = assertIs<ThreadsParseResult.Photos>(parse("carousel-images", "SynthCarr01"))
        assertEquals(2, album.candidates.size)
        assertTrue(album.candidates[0].contains("slide_a.jpg"))
        assertTrue(album.candidates[1].contains("slide_b.jpg"))
        assertFalse(album.discovery.hasVideo)
        assertNull(album.videoInfoJson)
        val mixed = assertIs<ThreadsParseResult.Photos>(parse("carousel-mixed", "SynthCarr01"))
        assertEquals(2, mixed.candidates.size)
        assertTrue(mixed.discovery.hasVideo)
        val info = requireNotNull(mixed.videoInfoJson)
        assertTrue(info.contains("clip_large.mp4"))
        assertFalse(info.contains("clip_later.mp4"))
    }

    @Test fun missingPrivateMismatchAndBrokenPagesFailClosed() {
        failure("media-null", "SynthPost01", ImageFailure.PRIVATE)
        failure("wrong-code", "SynthPost01", ImageFailure.SOURCE_REFUSED)
        failure("missing-preloader", "SynthPost01", ImageFailure.UNAVAILABLE)
        failure("truncated", "SynthPost01", ImageFailure.UNAVAILABLE)
    }

    @Test fun aReportedSizeAndASilentClipStayLiteral() {
        val sized = assertIs<ThreadsParseResult.Video>(parser.parse(page("""
            {"code":"SynthVid0001","pk":"1386527023068903329077","media_type":2,
             "video_versions":[{"url":"https://scontent.cdninstagram.com/o1/v/t2/sized.mp4","width":1280,"height":720,"filesize":12345}]}
            """.trimIndent()), "SynthVid0001"))
        assertEquals(12345, json.readTree(sized.infoJson).path("formats").single().path("filesize").asLong())
        val silent = assertIs<ThreadsParseResult.Video>(parser.parse(page("""
            {"code":"SynthVid0001","pk":"1386527023068903329077","media_type":2,"has_audio":false,
             "original_width":720,"original_height":900,
             "video_versions":[{"url":"https://scontent.cdninstagram.com/o1/v/t2/silent.mp4"}]}
            """.trimIndent()), "SynthVid0001"))
        val format = json.readTree(silent.infoJson).path("formats").single()
        assertEquals(720, format.path("width").asInt())
        assertEquals(900, format.path("height").asInt())
        assertEquals("h264", format.path("vcodec").asText())
        assertEquals("none", format.path("acodec").asText())
        val onlyVideo = assertIs<ThreadsParseResult.Video>(parser.parse(page("""
            {"code":"SynthCarr01","pk":"21664484714919935285","media_type":8,"carousel_media":[
              {"media_type":2,"video_versions":[{"url":"https://scontent.cdninstagram.com/o1/v/t2/first.mp4","width":1280,"height":720}]},
              {"media_type":2,"video_versions":[{"url":"https://scontent.cdninstagram.com/o1/v/t2/second.mp4","width":640,"height":360}]}
            ]}
            """.trimIndent()), "SynthCarr01"))
        assertTrue(onlyVideo.infoJson.contains("first.mp4"))
        assertFalse(onlyVideo.infoJson.contains("second.mp4"))
    }

    private fun page(media: String) = ("<!DOCTYPE html><script>[\"adp_BarcelonaPostPageTargetQueryRelayPreloader_ab\"," +
        "{\"__bbox\":{\"result\":{\"data\":{\"media\":$media}}}}]</script>").toByteArray()
}
