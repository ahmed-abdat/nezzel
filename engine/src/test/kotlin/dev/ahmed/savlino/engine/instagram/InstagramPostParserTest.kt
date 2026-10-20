package dev.ahmed.savlino.engine.instagram

import dev.ahmed.savlino.engine.mediaimage.DiscoveryCompleteness
import dev.ahmed.savlino.engine.mediaimage.ImageDiscovery
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import dev.ahmed.savlino.engine.mediaimage.ImagePhase
import org.junit.Test
import kotlin.test.*

class InstagramPostParserTest {
    private val parser = InstagramPostParser()
    private fun parse(page: ByteArray, code: String = IG_CODE) = parser.parse(page, code)

    @Test fun gifCarouselKeepsBothOriginalAndPreviewCandidates() {
        val page = igPage(igPolaris(media = igMedia(8, children = listOf(igPhoto(1), igPhoto(2)))))
            .toString(Charsets.UTF_8).replace(".jpg", ".gif").toByteArray()
        val result = assertIs<InstagramParseResult.Success>(parse(page))
        assertEquals(listOf(igImageUrl(1), igImageUrl(2)).map { it.replace(".jpg", ".gif") }, result.candidates)
        assertEquals(2, result.previewCandidates.size)
        assertTrue(result.previewCandidates.all { it!!.contains(".gif") })
    }

    @Test fun onlyMeasuredBytesCountWhenAllocationHasSpareCapacity() {
        val body = igPage()
        val allocation = body.copyOf(3 * 1024 * 1024 + 1)
        assertIs<InstagramParseResult.Success>(InstagramPostParser().parse(allocation, IG_CODE, body.size))
    }

    @Test fun malformedLaterCarouselItemKeepsValidFirstAsPartial() {
        val result = assertIs<InstagramParseResult.Success>(parse(igPage(igPolaris(media = igMedia(8,
            children = listOf(igPhoto(1), "null", "{}"))))))
        assertEquals(listOf(igImageUrl(1)), result.candidates)
        assertEquals(DiscoveryCompleteness.PARTIAL, result.discovery.completeness)
    }

    @Test fun photoPostIsItsOriginalCandidateWithCaptionAndAuthor() {
        val result = assertIs<InstagramParseResult.Success>(parse(igPage()))
        assertEquals(listOf(igImageUrl(1)), result.candidates)
        assertEquals(listOf(igImageUrl(1, "_s640x640")), result.previewCandidates)
        assertEquals(ImageDiscovery(1, DiscoveryCompleteness.COMPLETE), result.discovery)
        assertEquals("Synthetic caption line", result.title)
        assertEquals("Synthetic Author", result.author)
        assertEquals("InstagramParseResult.Success(redacted)", result.toString())
    }

    @Test fun declaredSizesPickTheLargestCandidateAndAuthorFallsBackToTheUsername() {
        val page = igPage(igPolaris(media = igMedia(candidates = igCandidates(sized = true), fullName = "")))
        val result = assertIs<InstagramParseResult.Success>(parse(page))
        assertEquals(listOf(igImageUrl(1)), result.candidates)
        assertEquals("synthetic_user", result.author)
    }

    @Test fun previewIsTheSmallestDeclaredCandidateAtLeast256Pixels() {
        val candidates = "\"image_versions2\":{\"candidates\":[" +
            listOf(1440, 64, 640, 256).joinToString(",") {
                "{\"url\":" + j(igImageUrl(1, "_s${it}x$it")) + ",\"width\":$it,\"height\":$it}"
            } + "]}"
        val result = assertIs<InstagramParseResult.Success>(parse(igPage(igPolaris(media = igMedia(candidates = candidates)))))
        assertEquals(listOf(igImageUrl(1, "_s1440x1440")), result.candidates)
        assertEquals(listOf(igImageUrl(1, "_s256x256")), result.previewCandidates)
    }

    @Test fun photoCarouselListsEveryPhotoInOrder() {
        val page = igPage(igPolaris(media = igMedia(8, children = (1..5).map { igPhoto(it) })))
        val result = assertIs<InstagramParseResult.Success>(parse(page))
        assertEquals((1..5).map { igImageUrl(it) }, result.candidates)
        assertEquals(ImageDiscovery(5, DiscoveryCompleteness.COMPLETE), result.discovery)
    }

    @Test fun photoFirstMixedCarouselCountsOnlyItsPhotosAsPartial() {
        val page = igPage(igPolaris(media = igMedia(8, children = listOf(igPhoto(1), igVideo(), igPhoto(2)))))
        val result = assertIs<InstagramParseResult.Success>(parse(page))
        assertEquals(listOf(igImageUrl(1), igImageUrl(2)), result.candidates)
        assertEquals(ImageDiscovery(2, DiscoveryCompleteness.PARTIAL, hasVideo = true), result.discovery)
    }

    @Test fun videoOnlyHandsBackButVideoFirstMixedCarouselKeepsItsPhotos() {
        assertEquals(InstagramParseResult.Video, parse(igPage(igPolaris(media = igMedia(2)))))
        val mixed = assertIs<InstagramParseResult.Success>(
            parse(igPage(igPolaris(media = igMedia(8, children = listOf(igVideo(), igPhoto(1)))))))
        assertEquals(listOf(igImageUrl(1)), mixed.candidates)
    }

    @Test fun moreThanTwentyPhotosReturnsOnlyTheFirstTwenty() {
        val result = assertIs<InstagramParseResult.Success>(parse(
            igPage(igPolaris(media = igMedia(8, children = (1..23).map { igPhoto(it) })))))
        assertEquals((1..20).map { igImageUrl(it) }, result.candidates)
        assertEquals(3, result.discovery.cappedCount)
        assertEquals(DiscoveryCompleteness.PARTIAL, result.discovery.completeness)
    }

    @Test fun mixedPostWithNoUsablePhotosKeepsVideoHandoff() {
        assertEquals(InstagramParseResult.PhotosUnavailableVideo, parse(igPage(igPolaris(media = igMedia(8,
            children = listOf(igPhoto(1, host = "evil.example"), igVideo()))))))
    }

    @Test fun gatedOrLoggedOutHiddenMediaIsNotPublic() {
        assertEquals(InstagramParseResult.Failure(ImageFailure.PRIVATE), parse(igPage(igPolaris(media = null))))
        assertEquals(InstagramParseResult.Failure(ImageFailure.PRIVATE),
            parse(igPage(igPolaris(gating = """{"gating_type":"sensitivity"}"""))))
    }

    @Test fun missingChangedOrMismatchedDataIsRefused() {
        val refused = InstagramParseResult.Failure(ImageFailure.SOURCE_REFUSED)
        assertEquals(refused, parse(igShell()))
        assertEquals(refused, parse("<html>changed</html>".toByteArray()))
        assertEquals(refused, parse(igPage(), code = "SynthMissing"))
        assertEquals(refused, parse(igPage(igPolaris(media = igMedia(code = "SynthOther2")))))
        assertEquals(refused, parse(igPage(igPolaris(media = igMedia(5)))))
        assertEquals(refused, parse(igPage(igPolaris(media = igMedia(8, children = emptyList())))))
        assertEquals(refused, parse(igPage(igPolaris(media = igMedia(8)))))
        assertEquals(refused, parse(igPage(igPolaris(media = igMedia(8, children = (1..101).map { igPhoto(it % 9 + 1) })))))
        // Two copies of the requested post that disagree bind nothing.
        assertEquals(refused, parse(igPage(igPolaris(), igPolaris(media = igMedia(n = 2)))))
        // A duplicate key makes the block malformed, so no data is read from it.
        assertEquals(refused, parse(igPage(igPolaris().replace("\"pk\":\"1\",", "\"pk\":\"1\",\"pk\":\"2\","))))
    }

    @Test fun identicalRepeatedCopiesAreAccepted() {
        assertIs<InstagramParseResult.Success>(parse(igPage(igPolaris(), igPolaris())))
    }

    @Test fun anyCandidateOffTheInstagramCdnRefusesTheItem() {
        // Already classified as a photo: an image failure, never a pre-verdict (yt-dlp fallback) one.
        val imageRefused = InstagramParseResult.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE)
        for (host in listOf("scontent.xx.fbcdn.net", "evil.example", "static.cdninstagram.com")) {
            val page = igPage(igPolaris(media = igMedia(candidates = igCandidates(host = host))))
            assertEquals(imageRefused, parse(page), host)
        }
        val mixed = igPage(igPolaris(media = igMedia(8, children = listOf(igPhoto(1), igPhoto(2, host = "evil.example")))))
        val partial = assertIs<InstagramParseResult.Success>(parse(mixed))
        assertEquals(listOf(igImageUrl(1)), partial.candidates)
        assertEquals(DiscoveryCompleteness.PARTIAL, partial.discovery.completeness)
    }

    @Test fun oversizeAndDeepPagesAreBudgetFailures() {
        assertEquals(InstagramParseResult.Failure(ImageFailure.BUDGET), parse(ByteArray(3 * 1024 * 1024 + 1) { ' '.code.toByte() }))
        val deep = "[".repeat(400) + "]".repeat(400)
        val page = igPage(igPolaris().replace("\"extensions\":{\"is_final\":true}", "\"extensions\":$deep"))
        assertEquals(InstagramParseResult.Failure(ImageFailure.BUDGET), parse(page))
    }
}
