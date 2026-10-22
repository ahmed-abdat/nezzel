package dev.ahmed.savlino.engine.facebook

import dev.ahmed.savlino.engine.mediaimage.DiscoveryCompleteness
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import org.junit.Test
import kotlin.test.*

internal fun facebookFixture(name: String): String =
    assertNotNull(FacebookPageParserTest::class.java.getResource("/facebook/$name"), "fixture $name").readText()
internal fun cdn(n: Int, ext: String = "jpg") = "https://scontent.example.fbcdn.net/$n.$ext"
internal const val FB_STORY = "https://www.facebook.com/story.php?story_fbid=900000000000001&id=42"
internal const val FB_SHARE = "https://www.facebook.com/share/p/1SyntheticAb/"
internal const val FB_PHOTO = "https://www.facebook.com/photo/?fbid=900000000000001"

class FacebookPageParserTest {
    private val parser = FacebookPageParser()
    private fun identity(url: String) = assertNotNull(FacebookPostIdentity.parse(url))
    private fun parse(html: String, page: String = FB_STORY, requested: String? = null) =
        parser.parse(html.toByteArray(), identity(page), requested?.let(::identity))
    private fun parsed(html: String, page: String = FB_STORY, requested: String? = null) =
        assertIs<FacebookParseResult.Success>(parse(html, page, requested))
    private fun rejected(html: String, reason: ImageFailure, page: String = FB_STORY, requested: String? = null) =
        assertEquals(reason, assertIs<FacebookParseResult.Failure>(parse(html, page, requested)).reason)

    @Test fun mixedAlbumKeepsPhotosAndItsOneVideoForPostAndPlainShareLinks() {
        for (request in listOf<String?>(null, FB_SHARE, "https://www.facebook.com/share/Synthetic/")) {
            val result = parsed(facebookFixture("mixed-post.html"), "https://www.facebook.com/synthetic/posts/900000000000001", request)
            assertEquals(listOf("900000000000002", "900000000000004", "900000000000005", "900000000000006"),
                result.candidates.map { it.photoId })
            assertEquals(4, result.discovery.discoveredCount)
            assertEquals(11, result.discovery.claimedCount)
            assertEquals(DiscoveryCompleteness.PARTIAL, result.discovery.completeness)
            assertTrue(result.discovery.hasVideo)
            assertEquals("900000000000003", result.videoId)
        }
    }

    @Test fun videoSharesOfAMixedAlbumRedirectToItsOneBoundVideo() {
        for (kind in listOf("v", "r")) {
            val result = parse(facebookFixture("mixed-post.html"), FB_STORY, "https://www.facebook.com/share/$kind/Synthetic/")
            assertEquals("900000000000003", assertIs<FacebookParseResult.Video>(result).videoId)
        }
    }

    @Test fun albumVideoIdsAreValidatedAndOnlyOneDistinctIdIsExposed() {
        val page = facebookFixture("mixed-post.html")
        for (bad in listOf("", "invalid", "1".repeat(26))) {
            val result = parsed(page.replace("900000000000003", bad))
            assertFalse(result.discovery.hasVideo)
            assertNull(result.videoId)
        }
        val nodes = "\"count\":11,\"nodes\":["
        val video = "{\"media\":{\"__typename\":\"Video\",\"id\":\"900000000000007\"}},"
        assertTrue(page.contains(nodes))
        val several = page.replace(nodes, nodes + video)
        val result = parsed(several)
        assertTrue(result.discovery.hasVideo)
        assertNull(result.videoId)
        assertIs<FacebookParseResult.Success>(parse(several, FB_STORY, "https://www.facebook.com/share/v/Synthetic/"))
        val duplicate = page.replace(nodes, nodes + video.replace("900000000000007", "900000000000003"))
        assertEquals("900000000000003", parsed(duplicate).videoId)
    }

    @Test fun albumWithOnlyAVideoStillNeedsAVideoShareToRedirect() {
        val page = facebookFixture("mixed-post.html").replace("\"__typename\":\"Photo\"", "\"__typename\":\"GenericAttachmentMedia\"")
        rejected(page, ImageFailure.NO_MEDIA)
        assertEquals("900000000000003", assertIs<FacebookParseResult.Video>(parse(page, FB_STORY,
            "https://www.facebook.com/share/v/Synthetic/")).videoId)
    }

    @Test fun duplicateAlbumRootsThatDisagreeOnlyOnTheVideoBindNothing() {
        val page = facebookFixture("mixed-post.html")
        val conflict = page.replace("900000000000003", "900000000000007")
        val both = page.replace("</body>", conflict.substringAfter("<body>").substringBefore("</body>") + "</body>")
        rejected(both, ImageFailure.NO_MEDIA)
        rejected(both, ImageFailure.NO_MEDIA, FB_STORY, "https://www.facebook.com/share/v/Synthetic/")
    }

    @Test fun boundTextAndLinkSharePostsHaveNoMediaDespiteUnboundPhotos() {
        for (name in listOf("group-text-post.html", "link-share-post.html"))
            rejected(facebookFixture(name), ImageFailure.NO_MEDIA)
    }

    /** A Photo with a perfectly valid CDN URL placed where only decoys live (comments, save info, feeds). */
    private fun decoy(id: String, n: Int) =
        "{\"__typename\":\"Photo\",\"id\":\"$id\",\"viewer_image\":{\"uri\":\"${cdn(n)}\",\"width\":4000,\"height\":4000}}"

    @Test fun onlyMeasuredBytesCountWhenAllocationHasSpareCapacity() {
        val body = facebookFixture("photo-post.html").toByteArray()
        val allocation = body.copyOf(10 * 1024 * 1024 + 1)
        assertIs<FacebookParseResult.Success>(FacebookPageParser().parse(allocation, FacebookPostIdentity.parse(FB_STORY)!!, null, body.size))
    }

    @Test fun singlePhotoPostFromShareRedirectPicksTheStoryAttachmentPhoto() {
        val result = parsed(facebookFixture("photo-post.html"), FB_STORY, FB_SHARE)
        assertEquals(listOf(cdn(1)), result.candidates.map { it.url })
        assertEquals("900000000000002", result.candidates.single().photoId)
        assertEquals(1, result.discovery.discoveredCount)
        assertEquals(DiscoveryCompleteness.COMPLETE, result.discovery.completeness)
        assertTrue(result.discovery.previewQuality, "page declares a larger viewer_image than the only URL it offers")
    }

    @Test fun zeroRatedWebFacebookCdnHostIsTheSamePhoto() {
        val zeroRated = "https://z-p3-scontent.example.fbcdn.net/1.jpg"
        val html = facebookFixture("photo-post.html").replace(cdn(1), zeroRated)
        assertTrue(html.contains(zeroRated))
        val web = "https://web.facebook.com/story.php?story_fbid=900000000000001&id=42"
        assertEquals(listOf(zeroRated), parsed(html, web, FB_SHARE).candidates.map { it.url })
    }

    @Test fun nodeRootAndNodeV2RootAreBothStoryRoots() {
        val original = facebookFixture("photo-post.html")
        assertTrue(original.contains("\"node_v2\":{\"__typename\":\"Story\""))
        val legacy = original.replace("\"node_v2\":{\"__typename\":\"Story\"", "\"node\":{\"__typename\":\"Story\"")
        assertEquals(listOf(cdn(1)), parsed(legacy).candidates.map { it.url })
    }

    @Test fun decoysInCommentsSaveInfoAndFeedsAreNeverPicked() {
        val html = facebookFixture("photo-post.html")
            .replace("\"savable\":{\"__typename\":\"Photo\",\"id\":\"900000000000002\"}", "\"savable\":${decoy("900000000000002", 91)}")
            .replace("\"parent_post_story\":{\"attachments\":[{\"media\":{\"__typename\":\"Photo\",\"__isNode\":\"Photo\",\"id\":\"900000000000002\"}}]",
                "\"parent_post_story\":{\"attachments\":[{\"media\":${decoy("900000000000002", 92)}}]")
            .replace("\"data\":{\"node_v2\":", "\"data\":{\"viewer\":{\"feed\":{\"edges\":[{\"node\":{\"attachments\":[{\"styles\":{\"attachment\":{\"media\":${decoy("900000000000099", 93)}}}}]}}]}},\"node_v2\":")
        assertTrue(html.contains(cdn(91)) && html.contains(cdn(92)) && html.contains(cdn(93)), "decoys injected")
        val result = parsed(html)
        assertEquals(listOf(cdn(1)), result.candidates.map { it.url })
    }

    private val pfbid = "https://www.facebook.com/permalink.php?story_fbid=pfbid0Synthetic&id=42"
    private val secondStory = Regex("<script[^>]*>[^<]*adp_placeholder_decoy_1[^<]*</script>")

    @Test fun unboundDecoysOnTheAllowedCdnAreNeverPicked() {
        val html = facebookFixture("decoys-post.html")
        for (n in listOf(81, 82, 83, 84, 85)) assertTrue(html.contains(cdn(n)), "decoy $n present")
        assertEquals(listOf(cdn(1)), parsed(html, FB_STORY).candidates.map { it.url }, "known post id binds its own story")
        assertEquals(listOf(cdn(1)), parsed(html, FB_STORY, FB_SHARE).candidates.map { it.url })
        rejected(html, ImageFailure.NO_MEDIA, pfbid) // unknown post id + two candidate stories: ambiguous
        val single = html.replace(secondStory, "")
        assertFalse(single.contains(cdn(81)))
        assertEquals(listOf(cdn(1)), parsed(single, pfbid).candidates.map { it.url }, "unknown id, exactly one story")
        // Neither the unbound currMedia nor the profile Photo node is a photo page's media.
        rejected(html, ImageFailure.NO_MEDIA, "https://www.facebook.com/photo/?fbid=900000000000090")
        rejected(html, ImageFailure.NO_MEDIA, FB_STORY, "https://www.facebook.com/photo/?fbid=900000000000081")
    }

    @Test fun storyWithoutPostIdIsNotTheRequestedPost() {
        val html = facebookFixture("photo-post.html").replace("\"post_id\":\"900000000000001\",", "")
        assertFalse(html.contains("\"post_id\""))
        rejected(html, ImageFailure.NO_MEDIA, FB_STORY)
        assertEquals(listOf(cdn(1)), parsed(html, pfbid).candidates.map { it.url })
    }

    @Test fun photoPageTakesOnlyTheCurrMediaMatchingTheRequestedFbid() {
        val extra = "<script type=\"application/json\" data-sjs>{\"require\":[[\"RelayPrefetchedStreamCache\",\"next\",[],[\"q\"," +
            "{\"__bbox\":{\"result\":{\"data\":{\"currMedia\":${decoy("900000000000099", 86)}}}}}]]]}</script>"
        val html = facebookFixture("photo-page.html").replace("</body>", "$extra</body>")
        assertEquals(listOf(cdn(1)), parsed(html, FB_PHOTO).candidates.map { it.url })
        assertEquals(listOf(cdn(86)), parsed(html, "https://www.facebook.com/photo/?fbid=900000000000099").candidates.map { it.url })
    }

    @Test fun albumKeepsPageNodesInOrderPrefersViewerImageAndReportsTrueTotal() {
        val result = parsed(facebookFixture("album-post.html"), FB_STORY, FB_SHARE)
        assertEquals(listOf(cdn(2), cdn(4), cdn(6), cdn(8), cdn(10)), result.candidates.map { it.url })
        assertEquals(listOf(cdn(1), cdn(3), cdn(5), cdn(7), cdn(9)), result.candidates.map { it.previewUrl })
        assertEquals(5, result.discovery.discoveredCount)
        assertEquals(18, result.discovery.claimedCount)
        assertEquals(DiscoveryCompleteness.PARTIAL, result.discovery.completeness)
        assertFalse(result.discovery.previewQuality)
    }

    @Test fun albumPreviewFallsBackToOriginalWhenSmallerRenditionIsMissingOrOffCdn() {
        for (replacement in listOf("https://external.example.fbcdn.net/1.jpg", "")) {
            val page = facebookFixture("album-post.html").replace(cdn(1), replacement)
            val first = parsed(page).candidates.first()
            assertEquals(cdn(2), first.url)
            assertEquals(cdn(2), first.previewUrl)
        }
    }

    @Test fun requestedPhotoIdSelectsThatNodeOrNothing() {
        val album = facebookFixture("album-post.html")
        val chosen = parsed(album, FB_STORY, "https://www.facebook.com/photo/?fbid=900000000000004")
        assertEquals(listOf(cdn(6)), chosen.candidates.map { it.url })
        assertEquals(DiscoveryCompleteness.COMPLETE, chosen.discovery.completeness)
        rejected(album, ImageFailure.NO_MEDIA, FB_STORY, "https://www.facebook.com/photo/?fbid=900000000000077")
    }

    @Test fun storyForAnotherPostIsNotThisPost() {
        parsed(facebookFixture("photo-post.html"), FB_STORY)
        rejected(facebookFixture("photo-post.html"), ImageFailure.NO_MEDIA,
            "https://www.facebook.com/story.php?story_fbid=123&id=42")
    }

    @Test fun photoPagePicksCurrMediaNotCommentAttachmentsOrNeighbours() {
        val html = facebookFixture("photo-page.html")
        assertTrue(html.contains(cdn(2)) && html.contains(cdn(3)), "comment link-preview decoys present")
        val result = parsed(html, FB_PHOTO)
        assertEquals(listOf(cdn(1)), result.candidates.map { it.url })
        assertEquals(DiscoveryCompleteness.COMPLETE, result.discovery.completeness)
        rejected(html, ImageFailure.NO_MEDIA, "https://www.facebook.com/photo/?fbid=900000000000002")
    }

    @Test fun reelAndVideoPagesOfferNoImageEvenWithThumbnailsAndDecoyReels() {
        val reel = facebookFixture("reel-page.html")
        assertTrue(reel.contains("lasso_blue_feed") && reel.contains(cdn(5)), "decoy reels with thumbnails present")
        rejected(reel, ImageFailure.NO_MEDIA, "https://www.facebook.com/reel/900000000000001")
        rejected(facebookFixture("video-post.html"), ImageFailure.NO_MEDIA, "https://www.facebook.com/somepage/videos/900000000000002/")
        // og:image of a video page is a thumbnail: relay data exists, so no fallback.
        rejected(facebookFixture("video-post.html"), ImageFailure.NO_MEDIA, FB_STORY)
    }

    @Test fun unavailableAndLoginWallAreTypedPrivateNotParseErrors() {
        rejected(facebookFixture("unavailable.html"), ImageFailure.PRIVATE)
        rejected(facebookFixture("login-page.html"), ImageFailure.PRIVATE)
        rejected(facebookFixture("http400-shell.html"), ImageFailure.NO_MEDIA)
    }

    @Test fun loginFormNextToRealDataIsNotFailure() {
        val html = facebookFixture("photo-post.html").replace("<div id=\"mount_0_0_placeholder\"></div>",
            "<div id=\"mount_0_0_placeholder\"></div><form id=\"login_form\" action=\"#\"></form>")
        assertEquals(listOf(cdn(1)), parsed(html).candidates.map { it.url })
    }

    @Test fun offCdnOrPlainHttpUrlsAreNotCandidates() {
        val original = facebookFixture("photo-post.html")
        for (replacement in listOf("https://external.example.fbcdn.net/1.jpg", "http://scontent.example.fbcdn.net/1.jpg",
                "https://scontent.example.fbcdn.net.evil.example/1.jpg", "https://www.facebook.com/1.jpg")) {
            rejected(original.replace(cdn(1), replacement), ImageFailure.NO_MEDIA)
        }
    }

    @Test fun loginWallWinsOverAnOgImageFallback() {
        val wall = facebookFixture("login-page.html").replace("</head>", "<meta property=\"og:image\" content=\"${cdn(7)}\" /></head>")
        rejected(wall, ImageFailure.PRIVATE)
    }

    @Test fun ogImageIsAPreviewQualityFallbackOnlyForThePostItselfWithoutRelayData() {
        val ogOnly = "<!doctype html><html><head><meta property=\"og:image\" content=\"${cdn(7)}\"></head><body></body></html>"
        val result = parsed(ogOnly, FB_STORY)
        assertEquals(listOf(cdn(7)), result.candidates.map { it.url })
        assertTrue(result.discovery.previewQuality)
        assertEquals(DiscoveryCompleteness.UNKNOWN, result.discovery.completeness)
        rejected(ogOnly, ImageFailure.NO_MEDIA, "https://www.facebook.com/reel/900000000000001")
        rejected(ogOnly.replace(cdn(7), "https://external.example.fbcdn.net/7.jpg"), ImageFailure.NO_MEDIA)
        val twoOg = ogOnly.replace("</head>", "<meta property=\"og:image\" content=\"${cdn(8)}\"></head>")
        rejected(twoOg, ImageFailure.NO_MEDIA)
        // Relay data present (a text-only story): og:image is never used.
        val withRelay = facebookFixture("photo-post.html").replace(cdn(1), "https://external.example.fbcdn.net/1.jpg")
        assertTrue(withRelay.contains("og:image"))
        rejected(withRelay, ImageFailure.NO_MEDIA)
    }

    @Test fun videoOgTypeNeverFallsBackToTheThumbnail() {
        val video = facebookFixture("video-og-post.html")
        rejected(video, ImageFailure.NO_MEDIA)
        rejected(video, ImageFailure.NO_MEDIA, FB_STORY, FB_SHARE)
        rejected(video.replace("video.other", " VIDEO.movie "), ImageFailure.NO_MEDIA)
        // Any other og:type keeps the labelled preview fallback.
        val article = parsed(video.replace("video.other", "article"))
        assertEquals(listOf(cdn(1)), article.candidates.map { it.url })
        assertTrue(article.discovery.previewQuality)
    }

    @Test fun relayVerifiedPhotoWinsEvenWhenOgTypeSaysVideo() {
        // The owner's real public photo post declares og:type video.other next to relay Photo data.
        val photo = facebookFixture("photo-post.html").replace("</head>", "<meta property=\"og:type\" content=\"video.other\" /></head>")
        assertTrue(photo.contains("video.other"))
        val result = parsed(photo, FB_STORY, FB_SHARE)
        assertEquals(listOf(cdn(1)), result.candidates.map { it.url })
    }

    @Test fun oversizedOrHostileStructuresFailAsBudget() {
        rejected(" ".repeat(10 * 1024 * 1024 + 1), ImageFailure.BUDGET)
        val atCap = facebookFixture("photo-post.html").let { it + " ".repeat(10 * 1024 * 1024 - it.toByteArray().size) }
        assertEquals(listOf(cdn(1)), parsed(atCap).candidates.map { it.url })
        val deep = "[".repeat(5000) + "]".repeat(5000)
        val deepScript = "<html><body><script type=\"application/json\" data-sjs>{\"require\":[\"RelayPrefetchedStreamCache\",$deep]}</script></body></html>"
        rejected(deepScript, ImageFailure.BUDGET)
    }

    @Test fun parsedResultsNeverRenderUrls() {
        val result = parsed(facebookFixture("photo-post.html"))
        for (value in listOf(result, result.candidates.single())) assertFalse(value.toString().contains("fbcdn"))
    }

    /** The recorded fixtures carry no text (sanitized); these add the recorded key paths with placeholder values. */
    @Test fun singlePhotoPostTitleAndAuthorComeOnlyFromTheBoundStory() {
        val story = "\"node_v2\":{\"__typename\":\"Story\",\"id\":\"placeholder-id-1\","
        val original = facebookFixture("photo-post.html")
        assertTrue(original.contains(story))
        parsed(original, FB_STORY, FB_SHARE).let { assertNull(it.title); assertNull(it.author) }
        val html = original.replace(story, story + "\"message\":{\"text\":\"Placeholder caption \\u0001https://example.org/x\\nsecond\"}," +
            "\"actors\":[{\"__typename\":\"User\",\"name\":\"Placeholder Page\"}],")
        val result = parsed(html, FB_STORY, FB_SHARE)
        assertEquals("Placeholder caption", result.title)
        assertEquals("Placeholder Page", result.author)
        // Text only under comet_sections.content.story is the same story and counts too.
        val nested = "\"content\":{\"__typename\":\"CometFeedStoryDefaultContentStrategy\",\"story\":{"
        assertTrue(original.contains(nested))
        val content = original.replace(nested, nested + "\"message\":{\"text\":\"Nested caption\"},\"actors\":[{\"name\":\"Nested Page\"}],")
        parsed(content, FB_STORY, FB_SHARE).let { assertEquals("Nested caption", it.title); assertEquals("Nested Page", it.author) }
    }

    @Test fun photoPageTitleComesFromTheMatchedPhotosOwnStory() {
        val own = "\"creation_story\":{\"id\":\"placeholder-id-2\","
        assertTrue(facebookFixture("photo-page.html").contains(own))
        val html = facebookFixture("photo-page.html").replace(own, own +
            "\"message\":{\"text\":\"Photo caption\"},\"actors\":[{\"name\":\"Photo Owner\"}],")
        val result = parsed(html, FB_PHOTO)
        assertEquals(listOf(cdn(1)), result.candidates.map { it.url })
        assertEquals("Photo caption", result.title); assertEquals("Photo Owner", result.author)
    }

    private fun relayBlock(data: String) = "<script type=\"application/json\" data-sjs>{\"require\":[[\"ScheduledServerJS\",\"handle\",null," +
        "[{\"__bbox\":{\"require\":[[\"RelayPrefetchedStreamCache\",\"next\",[],[\"adp_extra\",{\"__bbox\":{\"complete\":true," +
        "\"result\":{\"data\":$data}}}]]]}}]]]}</script>"

    /** a photo link takes text only from its unique current media, never an unrelated story. */
    @Test fun photoPageNeverTakesTextFromAnUnrelatedStoryOrADuplicateCurrentMedia() {
        val unrelated = relayBlock("{\"node_v2\":{\"__typename\":\"Story\",\"post_id\":\"900000000000777\"," +
            "\"message\":{\"text\":\"Unrelated story\"},\"actors\":[{\"name\":\"Unrelated Page\"}]}}")
        parsed(facebookFixture("photo-page.html").replace("</body>", "$unrelated</body>"), FB_PHOTO).let {
            assertEquals(listOf(cdn(1)), it.candidates.map { c -> c.url })
            assertNull(it.title); assertNull(it.author)
        }
        val own = "\"creation_story\":{\"id\":\"placeholder-id-2\","
        val captioned = facebookFixture("photo-page.html").replace(own, own +
            "\"message\":{\"text\":\"Photo caption\"},\"actors\":[{\"name\":\"Photo Owner\"}],")
        val duplicate = relayBlock("{\"comment\":{\"__bbox\":{\"result\":{\"data\":{\"currMedia\":{\"__typename\":\"Photo\"," +
            "\"id\":\"900000000000001\",\"creation_story\":{\"message\":{\"text\":\"Comment caption\"}," +
            "\"actors\":[{\"name\":\"Commenter\"}]}}}}}}}")
        parsed(captioned.replace("</body>", "$duplicate</body>"), FB_PHOTO).let {
            assertEquals(listOf(cdn(1)), it.candidates.map { c -> c.url })
            assertNull(it.title); assertNull(it.author)
        }
    }

    @Test fun postLinkTextIgnoresCommentsAndOtherStories() {
        val story = "\"node_v2\":{\"__typename\":\"Story\",\"id\":\"placeholder-id-1\","
        val html = facebookFixture("photo-post.html").replace(story, story +
            "\"comments\":{\"nodes\":[{\"message\":{\"text\":\"Comment text\"},\"actors\":[{\"name\":\"Commenter\"}]}]},")
            .replace("</body>", relayBlock("{\"node_v2\":{\"__typename\":\"Story\",\"post_id\":\"900000000000777\"," +
                "\"message\":{\"text\":\"Other story\"},\"actors\":[{\"name\":\"Other Page\"}]}}") + "</body>")
        parsed(html, FB_STORY, FB_SHARE).let {
            assertEquals(listOf(cdn(1)), it.candidates.map { c -> c.url })
            assertNull(it.title); assertNull(it.author)
        }
    }
}
