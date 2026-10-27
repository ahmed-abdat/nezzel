package dev.ahmed.savlino.engine.tiktok

import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import org.junit.Test
import kotlin.test.*

internal fun tikTokFixture(): String = TikTokPostParserTest::class.java.getResourceAsStream("/tiktok/photo-post.json")!!
    .bufferedReader().use { it.readText() }
internal fun tikTokPage(json: String = tikTokFixture()): ByteArray =
    "<html><script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\" type=\"application/json\">$json</script></html>".toByteArray()

class TikTokPostParserTest {
    @Test fun onlyKnownPrivateStatusesArePrivate() {
        for ((status, reason) in listOf(10216 to ImageFailure.PRIVATE, 10222 to ImageFailure.PRIVATE,
            10204 to ImageFailure.NO_MEDIA, 99999 to ImageFailure.NO_MEDIA)) {
            val json = """{"__DEFAULT_SCOPE__":{"webapp.video-detail":{"statusCode":$status}}}"""
            assertEquals(reason, assertIs<TikTokParseResult.Failure>(TikTokPostParser().parse(tikTokPage(json), "900000001")).reason)
        }
    }
    @Test fun nullImagePostWithoutVideoHasNoMedia() {
        val json = """{"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{"id":"900000001","imagePost":null}}}}}"""
        assertEquals(ImageFailure.NO_MEDIA,
            assertIs<TikTokParseResult.Failure>(TikTokPostParser().parse(tikTokPage(json), "900000001")).reason)
    }

    @Test fun readsOnlyBoundPostImagesAndKeepsTheFirstTwoMirrorsInOrder() {
        val parsed = assertIs<TikTokParseResult.Success>(TikTokPostParser().parse(tikTokPage(), "900000001"))
        assertEquals(listOf(0, 1), parsed.photos.map { it.position })
        assertEquals("https://p16-sign.example.tiktokcdn.com/photos/synthetic1~tplv-test.jpeg?signature=fake", parsed.photos[0].urls[0])
        assertEquals("https://p19-sign.example.tiktokcdn-us.com/photos/synthetic1~tplv-test.jpeg?signature=fake", parsed.photos[0].urls[1])
        assertEquals(12, parsed.sound?.durationSeconds)
        assertNull(parsed.title)
        assertFalse(parsed.toString().contains("signature"))
    }
    @Test fun duplicateUrlsKeepTheFirstOriginalPositionAndMirrors() {
        val json = tikTokFixture().replace("synthetic2~", "synthetic1~")
        val result = assertIs<TikTokParseResult.Success>(TikTokPostParser().parse(tikTokPage(json), "900000001"))
        assertEquals(listOf(0), result.photos.map { it.position })
        assertEquals(2, result.photos.single().urls.size)
        assertEquals(0, result.discovery.unavailableCount)
    }
    @Test fun wrongPostAndDuplicateJsonKeysAreRefused() {
        for (json in listOf(tikTokFixture().replace("900000001", "900000002"),
            tikTokFixture().replace("\"id\":\"900000001\"", "\"id\":\"900000001\",\"id\":\"900000002\"")))
            assertEquals(ImageFailure.SOURCE_REFUSED,
                assertIs<TikTokParseResult.Failure>(TikTokPostParser().parse(tikTokPage(json), "900000001")).reason)
    }
    @Test fun unsafeFirstMirrorDoesNotPromoteAnUnrelatedCandidateOrRenumberFollowingPhotos() {
        val json = tikTokFixture().replace("https://p16-sign.example.tiktokcdn.com/photos/synthetic1~tplv-test.jpeg?signature=fake", "https://evil.example/one.jpg")
            .replace("https://p19-sign.example.tiktokcdn-us.com/photos/synthetic1~tplv-test.jpeg?signature=fake", "https://evil.example/two.jpg")
        val parsed = assertIs<TikTokParseResult.Success>(TikTokPostParser().parse(tikTokPage(json), "900000001"))
        assertEquals(listOf(1), parsed.photos.map { it.position })
        assertEquals(1, parsed.discovery.unavailableCount)
    }
    @Test fun videoWithoutImagePostStaysAVideoAndUnsupportedImageUrlsAreNotGuessed() {
        val video = """{"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{"id":"900000001","video":{}}}}}}"""
        assertEquals(TikTokParseResult.Video, TikTokPostParser().parse(tikTokPage(video), "900000001"))
        val unsupported = tikTokFixture().replace(".jpeg?", ".unknown?")
        assertIs<TikTokParseResult.Failure>(TikTokPostParser().parse(tikTokPage(unsupported), "900000001"))
        assertEquals(ImageFailure.SOURCE_REFUSED, assertIs<TikTokParseResult.Failure>(
            TikTokPostParser().parse(tikTokPage(tikTokFixture() + "{}"), "900000001")).reason)
    }
    @Test fun photoAndVideoIdentitiesHaveTheSameQueryFreeFetchUrl() {
        val photo = assertNotNull(TikTokPostIdentity.parse("https://www.tiktok.com/@synthetic/photo/900000001?tracking=fake"))
        assertTrue(photo.isPhoto)
        assertEquals("https://www.tiktok.com/@synthetic/video/900000001", photo.videoUrl)
        assertFalse(assertNotNull(TikTokPostIdentity.parse(photo.videoUrl)).isPhoto)
        assertNull(TikTokPostIdentity.parse("https://www.tiktok.com.evil.example/@synthetic/photo/900000001"))
    }

    @Test fun postTextDecodesEmojiEscapedTwice() {
        assertEquals("a \uD83D\uDE2E & b &#1114112;",
            dev.ahmed.savlino.engine.mediaimage.PostText.tiktokUnescaped("a &amp;#128558; &amp; b &amp;#1114112;"))
    }
}
