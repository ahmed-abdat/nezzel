package dev.ahmed.savlino.engine.x

import dev.ahmed.savlino.engine.mediaimage.DiscoveryCompleteness
import dev.ahmed.savlino.engine.mediaimage.ImageDiscovery
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import dev.ahmed.savlino.engine.mediaimage.ImagePhase
import org.junit.Test
import kotlin.test.*

class XPostParserTest {
    private val parser = XPostParser()
    private fun parse(body: ByteArray, id: String = X_ID) = parser.parse(body, id)
    private fun parsed(body: ByteArray) = assertIs<XParseResult.Success>(parse(body))
    private fun rejected(body: ByteArray, reason: ImageFailure, id: String = X_ID,
        phase: ImagePhase = ImagePhase.PAGE) = assertEquals(XParseResult.Failure(reason, phase), parse(body, id))

    @Test fun mixedPostWithNoUsablePhotosKeepsVideoHandoff() {
        assertNotNull(assertIs<XParseResult.PhotosUnavailableVideo>(parse(xTweet(listOf("""{"type":"photo"}""", xVideo())))).infoJson)
    }

    @Test fun onlyMeasuredBytesCountWhenAllocationHasSpareCapacity() {
        val body = xTweet()
        val allocation = body.copyOf(524288 + 1)
        assertIs<XParseResult.Success>(XPostParser().parse(allocation, X_ID, body.size))
    }

    @Test fun oversizeSyndicationBodyIsPageBudget() {
        rejected(ByteArray(512 * 1024 + 1), ImageFailure.BUDGET)
    }

    @Test fun outsideLinksAndQuotedPostsRequireOpeningThatLinkWithoutBorrowingMedia() {
        val reason = ImageFailure.valueOf("LINKED_ELSEWHERE")
        for (extra in listOf(""","quoted_tweet":{}""", ""","card":{}""",
            ""","entities":{"urls":[{"expanded_url":"https://example.com/synthetic"}]}""")) {
            rejected(xTweet(media = null, extra = extra), reason)
            assertIs<XParseResult.Success>(parse(xTweet(extra = extra)))
        }
        rejected(xTweet(media = null, text = "Synthetic plain text"), ImageFailure.NO_MEDIA)
    }

    @Test fun malformedLaterPhotoKeepsValidFirstAsPartial() {
        val result = parsed(xTweet(listOf(xPhoto(), """{"type":"photo"}""")))
        assertEquals(1, result.candidates.size)
        assertEquals(DiscoveryCompleteness.PARTIAL, result.discovery.completeness)
    }

    @Test fun gifPhotoUsesImageCandidateWhileAnimatedGifKeepsVideoHandoff() {
        assertEquals(listOf("https://pbs.twimg.com/media/SyntheticPhoto1?format=gif&name=orig"),
            parsed(xTweet(listOf(xPhoto("SyntheticPhoto1", "gif")))).candidates)
        assertIs<XParseResult.Video>(parse(xTweet(listOf(xVideo("animated_gif")))))
    }

    @Test fun singlePhotoIsTheOriginalOnPbsWithPostTextAndAuthor() {
        val result = parsed(xTweet())
        assertEquals(listOf("https://pbs.twimg.com/media/SyntheticPhoto1?format=jpg&name=orig"), result.candidates)
        assertEquals(ImageDiscovery(1, DiscoveryCompleteness.COMPLETE), result.discovery)
        assertEquals("Synthetic caption line", result.title)
        assertEquals("Synthetic Author", result.author)
    }

    @Test fun fourPhotosAreAllEnumeratedInPostOrder() {
        val result = parsed(xTweet((1..4).map { xPhoto("SyntheticPhoto$it", if (it == 2) "png" else "jpg") }))
        assertEquals(ImageDiscovery(4, DiscoveryCompleteness.COMPLETE), result.discovery)
        assertEquals(listOf("SyntheticPhoto1?format=jpg", "SyntheticPhoto2?format=png", "SyntheticPhoto3?format=jpg",
            "SyntheticPhoto4?format=jpg").map { "https://pbs.twimg.com/media/$it&name=orig" }, result.candidates)
    }

    @Test fun anOriginalOver2048PxIsReadAsXsLargeCopyLabelledPreview() {
        val result = parsed(xTweet(listOf(xPhoto(width = 4096, height = 3072))))
        assertEquals("https://pbs.twimg.com/media/SyntheticPhoto1?format=jpg&name=large", result.candidates.single())
        assertTrue(result.discovery.previewQuality)
        assertFalse(parsed(xTweet(listOf(xPhoto(width = 2048, height = 2048)))).discovery.previewQuality)
    }

    @Test fun avifPhotosKeepTheirReturnedEncoding() {
        val result = parsed(xTweet(listOf(xPhoto().replace("SyntheticPhoto1.jpg", "SyntheticPhoto1.avif"))))
        assertEquals(listOf("https://pbs.twimg.com/media/SyntheticPhoto1?format=avif&name=orig"), result.candidates)
    }

    @Test fun anyVideoOrGifInThePostHandsOffToYtDlp() {
        assertNotNull(assertIs<XParseResult.Video>(parse(xTweet(listOf(xVideo())))).infoJson)
        assertIs<XParseResult.Video>(parse(xTweet(listOf(xVideo("animated_gif")))))
        // Mixed photo + video: yt-dlp reads the video, as it did before this reader.
        assertEquals(1, parsed(xTweet(listOf(xPhoto(), xVideo()))).candidates.size)
        assertEquals(1, parsed(xTweet(listOf(xVideo(), xPhoto()))).candidates.size)
    }

    @Test fun textOrLinkCardPostHasNoMediaAndQuotedOrCardImagesAreNotBorrowed() {
        assertEquals("LINKED_ELSEWHERE", (parse(xTextWithCard()) as XParseResult.Failure).reason.name)
        rejected(xTweet(media = null, text = "Synthetic plain text"), ImageFailure.NO_MEDIA)
        rejected(xTweet(media = emptyList(), text = "Synthetic plain text"), ImageFailure.NO_MEDIA)
        rejected(xTweet(listOf("""{"type":"unknown_kind"}"""), text = "Synthetic plain text"), ImageFailure.NO_MEDIA)
    }

    @Test fun tombstoneIsNotPublic() = rejected(xTombstone(), ImageFailure.PRIVATE)
    /** Any other typename (TweetUnavailable, …) is a shape the reader does not know: SOURCE_REFUSED, so yt-dlp reads it. */
    @Test fun unknownTypenamesAreRefusedForTheYtDlpFallback() {
        for (typename in listOf("TweetUnavailable", "TweetWithVisibilityResults", ""))
            rejected(xTweet(listOf(xVideo()), typename = typename), ImageFailure.SOURCE_REFUSED)
    }

    @Test fun malformedOrForeignResponsesAreRefusedNeverGuessed() {
        rejected("not json".toByteArray(), ImageFailure.SOURCE_REFUSED)
        rejected("[]".toByteArray(), ImageFailure.SOURCE_REFUSED)
        rejected("{\"__typename\":\"Tweet\",\"id_str\":\"$X_ID\",\"id_str\":\"$X_ID\"}".toByteArray(), ImageFailure.SOURCE_REFUSED)
        rejected(xTweet(typename = "SomethingElse"), ImageFailure.SOURCE_REFUSED)
        rejected(xTweet(), ImageFailure.SOURCE_REFUSED, id = "1790000000000000002")
        rejected(xTweet(id = "1790000000000000002"), ImageFailure.SOURCE_REFUSED)
        rejected("{\"__typename\":\"Tweet\",\"id_str\":\"$X_ID\",\"mediaDetails\":{\"type\":\"photo\"}}".toByteArray(),
            ImageFailure.SOURCE_REFUSED)
        rejected(xTweet(List(17) { xPhoto() }), ImageFailure.SOURCE_REFUSED)
        for (bad in listOf("http://pbs.twimg.com/media/A.jpg", "https://pbs.twimg.com.evil.example/media/A.jpg",
            "https://pbs.twimg.com/profile_images/A.jpg", "https://pbs.twimg.com/media/A.heic",
            "https://pbs.twimg.com/media/A.jpg?name=orig", "https://pbs.twimg.com/media/../A.jpg", "https://example.com/media/A.jpg"))
            // Already a photo-only post: an image failure, never a pre-verdict (yt-dlp fallback) one.
            rejected(xTweet(listOf(xPhoto().replace("https://pbs.twimg.com/media/SyntheticPhoto1.jpg", bad))),
                ImageFailure.SOURCE_REFUSED, phase = ImagePhase.IMAGE)
    }

    @Test fun resultsNeverRenderUrlsOrText() {
        assertEquals("XParseResult.Success(redacted)", parsed(xTweet()).toString())
    }
}
