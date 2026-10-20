package dev.ahmed.savlino.engine.linkedin

import dev.ahmed.savlino.engine.mediaimage.*
import org.junit.Test
import kotlin.test.*

class LinkedInParserTest {
    private val parser = LinkedInPostAttachmentParser()
    private fun parsed(body: ByteArray = linkedInHtml(), url: String = POST) =
        assertIs<LinkedInParseResult.Success>(parser.parse(body, url))
    private fun rejected(body: ByteArray, reason: ImageFailure = ImageFailure.NO_MEDIA, url: String = POST) {
        assertEquals(reason, assertIs<LinkedInParseResult.Failure>(parser.parse(body, url)).reason)
    }

    @Test fun onlyMeasuredBytesCountWhenAllocationHasSpareCapacity() {
        val body = linkedInHtml()
        val allocation = body.copyOf(6 * 1024 * 1024 + 1)
        assertIs<LinkedInParseResult.Success>(LinkedInPostAttachmentParser().parse(allocation, POST, body.size))
    }

    @Test fun li18ShapedDiscussionFixtureIsAnEvidencedImage() {
        val body = javaClass.getResourceAsStream("/linkedin/discussion-forum-post.html")!!.use { it.readBytes() }
        assertEquals(listOf("https://media.licdn.com/synthetic-image.jpg"), parsed(body).candidates.map { it.url })
    }

    @Test fun discussionForumPostingUsesTheSameIdentityRules() {
        val body = linkedInHtml().toString(Charsets.UTF_8).replace("SocialMediaPosting", "DiscussionForumPosting")
        assertEquals(listOf(ASSET), parsed(body.toByteArray()).candidates.map { it.url })
        assertEquals(listOf(ASSET), parsed(body.replace("\"url\":\"$POST\"", "\"@id\":\"$POST\"").toByteArray()).candidates.map { it.url })
        rejected(body.replace("\"url\":\"$POST\"", "\"url\":\"$POST\",\"@id\":\"https://www.linkedin.com/posts/other-activity-999\"").toByteArray(), ImageFailure.SOURCE_REFUSED)
        rejected(body.replace("\"url\":\"$POST\"", "\"url\":\"https://www.linkedin.com/posts/other-activity-999\"").toByteArray())
    }

    @Test fun regionalCanonicalNamesTheSamePost() {
        val body = linkedInHtml().toString(Charsets.UTF_8).replace("href=\"$POST\"", "href=\"${POST.replace("www.", "ae.")}\"")
        assertEquals(listOf(ASSET), parsed(body.toByteArray()).candidates.map { it.url })
    }

    @Test fun singleImageRequiresMatchingPostJsonDomAndOpenGraphExcludingAvatar() {
        val result = parsed()
        assertEquals(listOf(ASSET), result.candidates.map { it.url })
        assertEquals(1, result.discovery.discoveredCount)
        assertEquals(DiscoveryCompleteness.UNKNOWN, result.discovery.completeness)
        assertTrue(result.candidates.none { it.url == AVATAR })
    }

    @Test fun signedAssetBytesAndHtmlAttributeEntitiesSurviveAssociation() {
        val asset = "https://media.licdn.com/a%2Fb?sig=Ab%2fCD%2B%3d&x=1+2"
        val html = linkedInHtml(listOf(asset)).toString(Charsets.UTF_8)
            .replace("class=\"feed-images-content\"", "class='feed-images-content'")
        assertEquals(asset, parsed(html.toByteArray()).candidates.single().url)
    }

    @Test fun imageObjectContentUrlIsMediaAndPageUrlCannotReplaceIt() {
        val html = linkedInHtml().toString(Charsets.UTF_8)
            .replace("\"contentUrl\":\"$ASSET\"", "\"url\":\"$POST\",\"contentUrl\":\"$ASSET\"")
        assertEquals(ASSET, parsed(html.toByteArray()).candidates.single().url)
    }

    @Test fun openGraphAloneArbitraryImageObjectAndUnmatchedDomNeverBecomeAttachment() {
        parsed() // Paired positive association.
        val original = linkedInHtml().toString(Charsets.UTF_8)
        for (html in listOf(
            original.replace("SocialMediaPosting", "ImageObject"),
            original.replace("feed-images-content", "author-images"),
            linkedInHtml(domAssets = listOf(SECOND_ASSET)).toString(Charsets.UTF_8),
            original.replace("\"image\":[{\"@type\":\"ImageObject\",\"contentUrl\":\"$ASSET\"}]", "\"image\":[]"))) {
            rejected(html.toByteArray())
        }
    }

    @Test fun singleImageWithDifferentOrMissingOpenGraphIsNotSelected() {
        parsed()
        rejected(linkedInHtml(og = AVATAR))
        rejected(linkedInHtml(og = null))
    }

    @Test fun authorImageCannotBeSelectedEvenWhenAllThreeSurfacesAgree() {
        parsed()
        rejected(linkedInHtml(listOf(AVATAR), author = AVATAR))
    }

    @Test fun scriptCommentTemplateAndRecommendationImagesCannotImpersonatePostDom() {
        val imageMarkup = "<ul class='feed-images-content'><li><img data-delayed-url='$ASSET'></li></ul>"
        for (decoy in listOf("<!--$imageMarkup-->", "<script type='text/plain'>$imageMarkup</script>",
            "<template>$imageMarkup</template>", "<aside class='recommendations'>$imageMarkup</aside>",
            "<div class='advertisement'>$imageMarkup</div>", "<div class='video-player'>$imageMarkup</div>",
            "<div class='document-cover'>$imageMarkup</div>")) {
            rejected(linkedInHtml(domAssets = emptyList(), extra = decoy))
        }
    }

    @Test fun recommendationAndAdDecoysInsideThePostNeedExplicitAttachmentShapeAndMakeMixedListsAmbiguous() {
        // JSON-LD evidences both assets, so these decoys cannot rely on URL evidence alone to be excluded.
        val base = linkedInHtml(listOf(ASSET, SECOND_ASSET), listOf(ASSET)).toString(Charsets.UTF_8)
        assertEquals(listOf(ASSET), parsed(base.toByteArray()).candidates.map { it.url }) // Paired positive control.
        val suggested = "https://media.licdn.com/suggested-post?sig=synthetic"
        for (decoy in listOf(
            "<li data-test-id='related-content'><img data-delayed-url='$SECOND_ASSET'></li>",
            "<li aria-label='Promoted'><img data-delayed-url='$SECOND_ASSET'></li>",
            "<li><img data-delayed-url='$SECOND_ASSET' data-tracking-control-name='sponsored-update'></li>",
            "<li><a href='https://www.linkedin.com/posts/other-activity-999'><img data-delayed-url='$SECOND_ASSET'></a></li>",
            "<li><div><img data-delayed-url='$SECOND_ASSET'></div></li>",
            "<li><img src='$SECOND_ASSET'></li>",
            "<li><img data-delayed-url='$SECOND_ASSET' src='$suggested'></li>",
            "<li class='update-components-mini-update-v2'><img data-delayed-url='$suggested'></li>",
            "<div class='more-from-author'><img data-delayed-url='$SECOND_ASSET'></div>")) {
            rejected(base.replaceFirst("</ul>", "$decoy</ul>").toByteArray())
        }
        val secondList = "<section aria-label='More from this author'><ul class='feed-images-content'>" +
            "<li><img data-delayed-url='$SECOND_ASSET'></li></ul></section>"
        rejected(base.replace("</ul></article>", "</ul>$secondList</article>").toByteArray())
    }

    @Test fun conflictingCanonicalAndJsonPostOrUnownedDomFailClosed() {
        parsed()
        rejected(linkedInHtml(canonical = "https://www.linkedin.com/posts/other-activity-999"), ImageFailure.SOURCE_REFUSED)
        rejected(linkedInHtml(jsonPost = "https://www.linkedin.com/posts/other-activity-999"), ImageFailure.NO_MEDIA)
        val foreignDom = linkedInHtml().toString(Charsets.UTF_8).replace("data-activity-urn=\"urn:li:activity:123\"",
            "data-activity-urn=\"urn:li:activity:999\"").replace("data-post-url=\"$POST\"", "data-post-url=\"https://www.linkedin.com/posts/other-activity-999\"")
        rejected(foreignDom.toByteArray())
    }

    @Test fun matchingPostIsChosenAmongJsonLdGraphsWithoutGlobalImageHarvesting() {
        val unrelated = "<script type='application/ld+json'>{\"@graph\":[{\"@type\":\"SocialMediaPosting\",\"url\":\"https://www.linkedin.com/posts/other-activity-999\",\"image\":\"$AVATAR\"}]}</script>"
        val html = linkedInHtml(extra = unrelated)
        assertEquals(listOf(ASSET), parsed(html).candidates.map { it.url })
    }

    @Test fun actualTwoAssetsUseDomOrderAndExactDedupRatherThanJsonArrayPosition() {
        val result = parsed(linkedInHtml(listOf(ASSET, SECOND_ASSET, ASSET), listOf(SECOND_ASSET, ASSET, SECOND_ASSET), og = null))
        assertEquals(listOf(SECOND_ASSET, ASSET), result.candidates.map { it.url })
        assertEquals(2, result.discovery.discoveredCount)
        assertEquals(DiscoveryCompleteness.UNKNOWN, result.discovery.completeness)
    }

    @Test fun claimedHiddenItemsArePartialAndNeverInvented() {
        val result = parsed(linkedInHtml(listOf(ASSET, SECOND_ASSET), listOf(ASSET), claimed = 5))
        assertEquals(listOf(ASSET), result.candidates.map { it.url })
        assertEquals(DiscoveryCompleteness.PARTIAL, result.discovery.completeness)
        assertEquals(1, result.discovery.discoveredCount)
        assertEquals(5, result.discovery.claimedCount)
    }

    @Test fun completeDiscoveryRequiresCorroboratedExplicitBoundedCount() {
        val result = parsed(linkedInHtml(listOf(ASSET, SECOND_ASSET), claimed = 2))
        assertEquals(DiscoveryCompleteness.COMPLETE, result.discovery.completeness)
        assertEquals(2, result.candidates.size)
    }

    @Test fun publicActivityIdentityMatchesCanonicalPostWithoutInventedRewrite() {
        val result = parsed(linkedInHtml(), ACTIVITY)
        assertEquals(ASSET, result.candidates.single().url)
        assertNotNull(LinkedInPostIdentity.parse("https://lnkd.in/p/synthetic"))
        assertNotNull(LinkedInPostIdentity.parse(POST))
        assertNotNull(LinkedInPostIdentity.parse(ACTIVITY))
        for (url in listOf("https://www.linkedin.com/login",
            "https://www.linkedin.com/feed/update/urn:li:activity:abc", "https://www.linkedin.com.evil.test/posts/fixture"))
            assertNull(LinkedInPostIdentity.parse(url))
    }

    @Test fun candidateOriginUserinfoPortAndBaseTagCannotExpandAssetPolicy() {
        parsed()
        for (asset in listOf("http://media.licdn.com/asset", "https://evil.test/asset", "https://media.licdn.com.evil.test/asset",
            "https://user@media.licdn.com/asset", "https://media.licdn.com:444/asset", "//media.licdn.com/asset", "/relative-asset")) {
            rejected(linkedInHtml(listOf(asset), extra = "<base href='https://media.licdn.com/'>"))
        }
    }

    @Test fun malformedHtmlCanRecoverQuotesButCannotPromoteBrokenJsonOrDuplicateConflicts() {
        val html = linkedInHtml().toString(Charsets.UTF_8).replace("</li>", "").replace("</head>", "")
        assertEquals(ASSET, parsed(html.toByteArray()).candidates.single().url)
        rejected(linkedInHtml().toString(Charsets.UTF_8).replace("\"@type\":\"SocialMediaPosting\"", "\"@type\":oops").toByteArray())
        rejected(linkedInHtml().toString(Charsets.UTF_8).replace("\"url\":\"$POST\"", "\"url\":\"$POST\",\"url\":\"https://www.linkedin.com/posts/other\"").toByteArray(), ImageFailure.SOURCE_REFUSED)
    }

    @Test fun htmlDepthJsonDepthAndCandidateCountsAreBoundedBeforeAssociation() {
        assertEquals(ASSET, parsed().candidates.single().url)
        rejected(ByteArray(6 * 1024 * 1024 + 1) { 32 }, ImageFailure.BUDGET)
        rejected(("<div>".repeat(2048) + linkedInHtml().toString(Charsets.UTF_8) + "</div>".repeat(2048)).toByteArray(), ImageFailure.BUDGET)
        rejected(linkedInHtml(List(65) { "https://media.licdn.com/asset-$it" }), ImageFailure.BUDGET)
        val nested = linkedInHtml().toString(Charsets.UTF_8).replace("\"headline\":\"Synthetic image post\"",
            "\"extra\":" + "[".repeat(100) + "0" + "]".repeat(100))
        rejected(nested.toByteArray(), ImageFailure.BUDGET)
    }

    @Test fun videoAndDocumentOnlyPostsDoNotTurnPostersOrCoversIntoImages() {
        parsed()
        for (kind in listOf("VideoObject", "DigitalDocument")) rejected(linkedInHtml().toString(Charsets.UTF_8)
            .replace("\"image\":[{\"@type\":\"ImageObject\",\"contentUrl\":\"$ASSET\"}]",
                "\"sharedContent\":{\"@type\":\"$kind\",\"thumbnailUrl\":\"$ASSET\"}").toByteArray())
    }
}
