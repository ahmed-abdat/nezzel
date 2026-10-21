package dev.ahmed.savlino.engine.linkedin

import dev.ahmed.savlino.engine.mediaimage.DiscoveryCompleteness
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import org.junit.Test
import kotlin.test.*

private const val ACTIVITY = "7200000000000000001"
private const val UGC = "7200000000000000002"
private const val LANDING = "https://www.linkedin.com/posts/placeholder-org_placeholder-topic-ugcPost-$UGC-AbCd/"
private const val CANONICAL = "https://www.linkedin.com/posts/placeholder-org_placeholder-topic-activity-$ACTIVITY-WxYz"
private fun asset(n: Int) = "https://media.licdn.com/dms/image/v2/SYNTHETIC$n/feedshare-shrink_800/B4D/0/$n?e=2147483647&v=beta&t=sig$n"

/**
 * Sanitized shape of the owner's public 6-photo post (2026-10-21, lnkd.in → ugcPost slug): the grid shows five tiles,
 * the fifth with LinkedIn's "+1" overlay; the sixth photo exists only in the post's JSON-LD.
 */
private fun gridHtml(photos: Int = 6, shown: Int = 5, overlay: String? = "+1", overlayAt: Int = shown - 1): ByteArray {
    fun attr(v: String) = v.replace("&", "&amp;")
    val images = (1..photos).joinToString(",") { "{\"url\":\"${asset(it)}\",\"@type\":\"ImageObject\"}" }
    val tiles = (1..shown).joinToString("") { n ->
        val cover = if (overlay != null && n - 1 == overlayAt)
            "<div class=\"absolute inset-0\" data-test-id=\"feed-images-content__overlay\"> $overlay </div>" else ""
        "<li class=\"col-span-2\" data-test-id=\"feed-images-content__list-item\" data-position=\"${n - 1}\" style>" +
            "<img class=\"lazy-load\" data-delayed-url=\"${attr(asset(n))}\" data-ghost-url=\"https://static.licdn.com/aero-v1/sc/h/ghost\" " +
            "alt=\"No alternative text description for this image\">$cover<!----></li>"
    }
    return ("<!DOCTYPE html><html><head><link rel=\"canonical\" href=\"$CANONICAL\">" +
        "<meta property=\"og:image\" content=\"${attr(asset(1))}\">" +
        "<script type=\"application/ld+json\">{\"@context\":\"http://schema.org\",\"@type\":\"SocialMediaPosting\"," +
        "\"@id\":\"$CANONICAL\",\"headline\":\"Placeholder\",\"author\":{\"name\":\"Placeholder Org\",\"@type\":\"Organization\"}," +
        "\"image\":[$images]}</script></head><body><article data-activity-urn=\"urn:li:activity:$ACTIVITY\" " +
        "data-featured-activity-urn=\"urn:li:activity:$ACTIVITY\" data-attributed-urn=\"urn:li:ugcPost:$UGC\">" +
        "<ul class=\"grid feed-images-content list-none\" data-test-id=\"feed-images-content\">$tiles</ul></article></body></html>").toByteArray()
}

class LinkedInOverflowGridTest {
    private fun parse(html: ByteArray) = LinkedInPostAttachmentParser().parse(html, LANDING)

    @Test fun plusOneOverlayAddsTheHiddenPhotoFromTheSamePost() {
        val result = assertIs<LinkedInParseResult.Success>(parse(gridHtml()))
        assertEquals((1..6).map(::asset), result.candidates.map { it.url })
        assertEquals(DiscoveryCompleteness.COMPLETE, result.discovery.completeness)
    }

    @Test fun overlayCountThatDisagreesWithTheJsonLdAddsNothing() {
        val result = assertIs<LinkedInParseResult.Success>(parse(gridHtml(overlay = "+2")))
        assertEquals((1..5).map(::asset), result.candidates.map { it.url })
        assertEquals(DiscoveryCompleteness.PARTIAL, result.discovery.completeness)
    }

    @Test fun overlayOnAnyTileButTheLastOrWithOtherTextStaysAmbiguous() {
        assertEquals(LinkedInParseResult.Failure(ImageFailure.NO_MEDIA), parse(gridHtml(overlayAt = 2)))
        assertEquals(LinkedInParseResult.Failure(ImageFailure.NO_MEDIA), parse(gridHtml(overlay = "more")))
    }
}
