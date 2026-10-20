package dev.ahmed.savlino.engine.linkedin

import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.*

private const val SHARE_ACTIVITY = "7100000000000000001"
private const val SHARE_ID = "7100000000000000002"
private const val SHARE_POST = "https://www.linkedin.com/posts/placeholder-author_placeholder-topic-share-$SHARE_ID-AbCd/"
private const val SHARE_REDIRECT = "$SHARE_POST?highlightedUpdateUrn=urn%3Ali%3Aactivity%3A$SHARE_ACTIVITY"
private const val SHARE_CANONICAL = "https://www.linkedin.com/posts/placeholder-author_placeholder-topic-activity-$SHARE_ACTIVITY-WxYz"
private const val SHARE_ASSET =
    "https://media.licdn.com/dms/image/v2/SYNTHETIC/feedshare-shrink_800/B4Dsynthetic/0/1?e=2147483647&v=beta&t=synthetic-sig"
private const val SHARE_AVATAR =
    "https://media.licdn.com/dms/image/v2/SYNTHETIC/profile-displayphoto-shrink_100_100/0/1?e=2147483647&v=beta&t=synthetic-a"
private const val COMMENT_AVATAR =
    "https://media.licdn.com/dms/image/v2/SYNTHETIC/profile-displayphoto-shrink_100_100/0/2?e=2147483647&v=beta&t=synthetic-c"
private const val GHOST = "https://static.licdn.com/aero-v1/sc/h/synthetic-ghost"

/**
 * Sanitized shape of a real public single-image post reached through lnkd.in (2026-10-19 device bug):
 * the shortlink lands on a `share-` slug while canonical/JSON-LD name the `activity-` slug, JSON-LD
 * uses "@id" (no "url"), and the attachment li/img carry LinkedIn's marker attributes.
 */
private fun sharePostHtml(
    attributed: String? = "urn:li:share:$SHARE_ID",
    activity: String = "urn:li:activity:$SHARE_ACTIVITY",
    featured: String? = activity,
    jsonId: String = SHARE_CANONICAL,
    jsonUrl: String? = null,
    item: String = "data-test-id=\"feed-images-content__list-item\" data-position=\"0\" style",
    ghost: String = GHOST,
    extra: String = "",
    headline: String? = "Placeholder headline for the post",
    articleBody: String? = "Placeholder body first line\nPlaceholder second line",
    authorName: String = "Placeholder Author",
): ByteArray {
    fun attr(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;")
    fun json(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace(Regex("[\\u0000-\\u001f]")) { "\\u%04x".format(it.value[0].code) } + "\""
    val container = "<article class=\"main-feed-activity-card\" data-activity-urn=\"$activity\"" +
        (featured?.let { " data-featured-activity-urn=\"$it\"" } ?: "") +
        (attributed?.let { " data-attributed-urn=\"$it\"" } ?: "") + ">"
    return ("<!DOCTYPE html><html lang=\"en\"><head><title>Placeholder post | LinkedIn</title>" +
        "<link rel=\"canonical\" href=\"${attr(SHARE_CANONICAL)}\">" +
        "<meta property=\"og:url\" content=\"${attr(SHARE_CANONICAL)}\">" +
        "<meta property=\"og:image\" content=\"${attr(SHARE_ASSET)}\">" +
        "<meta name=\"twitter:image\" content=\"${attr(SHARE_ASSET)}\">" +
        "<script type=\"application/ld+json\">{\"@context\":\"http://schema.org\",\"@type\":\"SocialMediaPosting\"," +
        "\"@id\":${json(jsonId)}," + (jsonUrl?.let { "\"url\":${json(it)}," } ?: "") +
        "\"datePublished\":\"2026-01-01T00:00:00.000Z\"," +
        (headline?.let { "\"headline\":${json(it)}," } ?: "") + (articleBody?.let { "\"articleBody\":${json(it)}," } ?: "") +
        "\"author\":{\"name\":${json(authorName)},\"url\":\"https://www.linkedin.com/in/placeholder\"," +
        "\"image\":{\"url\":${json(SHARE_AVATAR)},\"@type\":\"ImageObject\"},\"@type\":\"Person\"}," +
        "\"image\":{\"url\":${json(SHARE_ASSET)},\"@type\":\"ImageObject\"}," +
        "\"comment\":[{\"@type\":\"Comment\",\"text\":\"Placeholder comment\",\"author\":{\"name\":\"Placeholder\"," +
        "\"image\":{\"url\":${json(COMMENT_AVATAR)},\"@type\":\"ImageObject\"},\"@type\":\"Person\"}}]}</script></head>" +
        "<body><main>$container<div class=\"flex\"><div class=\"base-main-feed-card__entity-lockup\">" +
        "<div class=\"flex items-center font-sans mb-1\" data-test-id=\"main-feed-activity-card__entity-lockup\">" +
        "<a href=\"https://www.linkedin.com/in/placeholder\" class=\"relative\">" +
        "<img class=\"hue-web-entity__image lazy-load\" data-delayed-url=\"${attr(SHARE_AVATAR)}\" " +
        "data-ghost-classes=\"bg-color-entity-ghost-background\" data-ghost-url=\"$GHOST\" alt=\"Placeholder Author\"></a>" +
        "</div></div></div><div class=\"attributed-text-segment-list__container\">" +
        "<p class=\"attributed-text-segment-list__content\" dir=\"ltr\" data-test-id=\"main-feed-activity-card__commentary\">" +
        "Placeholder commentary</p></div>" +
        "<ul class=\"grid grid-cols-2 feed-images-content list-none pl-0\" data-test-id=\"feed-images-content\">" +
        "<li class=\"col-span-2 row-span-2\" $item>" +
        "<img class=\"lazy-load object-cover\" data-delayed-url=\"${attr(SHARE_ASSET)}\" data-ghost-url=\"${attr(ghost)}\" " +
        "alt=\"No alternative text description for this image\"><!----><!----></li></ul>" +
        "<code id=\"placeholder-code\" style=\"display: none\"><!--\"Placeholder\"--></code>" +
        "<section class=\"comment\"><div><a href=\"https://www.linkedin.com/in/placeholder-c\">" +
        "<div role=\"img\" aria-label=\"Placeholder\" class=\"comment__avatar\" data-delayed-url=\"${attr(COMMENT_AVATAR)}\" " +
        "data-ghost-url=\"$GHOST\"></div></a></div></section></article>$extra</main></body></html>").toByteArray()
}

class LinkedInSharePostTest : PublicImageTestBase() {
    private val parser = LinkedInPostAttachmentParser()
    private fun failure(body: ByteArray, url: String = SHARE_POST) =
        assertIs<LinkedInParseResult.Failure>(parser.parse(body, url)).reason

    @Test fun sharePostPageBridgesToItsActivityAndYieldsOnlyTheAttachment() {
        val result = assertIs<LinkedInParseResult.Success>(parser.parse(sharePostHtml(), SHARE_POST))
        assertEquals(listOf(SHARE_ASSET), result.candidates.map { it.url })
        assertEquals(1, result.discovery.discoveredCount)
    }

    @Test fun titleAndAuthorComeFromThePostJsonLdFirstLineWithoutLinksOrControls() {
        fun parsed(html: ByteArray) = assertIs<LinkedInParseResult.Success>(parser.parse(html, SHARE_POST))
        parsed(sharePostHtml()).let {
            assertEquals("Placeholder headline for the post", it.title); assertEquals("Placeholder Author", it.author)
        }
        assertEquals("Placeholder body first line", parsed(sharePostHtml(headline = null)).title)
        assertEquals("Read this", parsed(sharePostHtml(headline = "\n  Read this https://lnkd.in/abc www.example.org/x \u0007\nmore")).title)
        val long = parsed(sharePostHtml(headline = "word ".repeat(60))).title!!
        assertTrue(long.codePointCount(0, long.length) <= 120)
        // Nothing proven, nothing invented.
        parsed(sharePostHtml(headline = null, articleBody = null, authorName = "https://example.org/only-a-link")).let {
            assertNull(it.title); assertNull(it.author)
        }
    }

    @Test fun withoutTheContainerBridgeTheShareSlugStillFailsAtCanonicalAsOnTheDevice() {
        // The device failure: share-slug identity vs activity canonical -> Conflict (formerly UNSUPPORTED, shown as "Image type not supported").
        assertEquals(ImageFailure.SOURCE_REFUSED, failure(sharePostHtml(attributed = null)))
        assertEquals(ImageFailure.SOURCE_REFUSED, failure(sharePostHtml(attributed = "urn:li:share:7100000000000000999")))
        assertEquals(ImageFailure.SOURCE_REFUSED, failure(sharePostHtml(attributed = "urn:li:ugcPost:$SHARE_ID")))
    }

    @Test fun ambiguousOrInconsistentBridgeEvidenceIsAConflict() {
        assertEquals(ImageFailure.SOURCE_REFUSED, failure(sharePostHtml(featured = "urn:li:activity:7100000000000000999")))
        assertEquals(ImageFailure.SOURCE_REFUSED, failure(sharePostHtml(activity = "urn:li:share:$SHARE_ID")))
        val second = "<article data-activity-urn=\"urn:li:activity:7100000000000000999\" " +
            "data-attributed-urn=\"urn:li:share:$SHARE_ID\"></article>"
        assertEquals(ImageFailure.SOURCE_REFUSED, failure(sharePostHtml(extra = second)))
        // A hidden template claiming the share is not page evidence.
        val hidden = "<template><article data-activity-urn=\"urn:li:activity:7100000000000000999\" " +
            "data-attributed-urn=\"urn:li:share:$SHARE_ID\"></article></template>"
        assertIs<LinkedInParseResult.Success>(parser.parse(sharePostHtml(extra = hidden), SHARE_POST))
    }

    /** share A must not bind to activity B through any claim but the list-owning post container. */
    @Test fun detachedOrDuplicateBridgeClaimsAreRefusedEvenForTheSameActivity() {
        val claim = "data-activity-urn=\"urn:li:activity:$SHARE_ACTIVITY\" data-attributed-urn=\"urn:li:share:$SHARE_ID\""
        // Page, list and JSON-LD are activity B; only a detached decoy claims "share A is B".
        assertEquals(ImageFailure.SOURCE_REFUSED, failure(sharePostHtml(attributed = null, extra = "<div $claim></div>")))
        // A decoy nested inside the post that owns no list is detached too.
        val nested = sharePostHtml(attributed = null).toString(Charsets.UTF_8)
        assertEquals(1, Regex("<code id=").findAll(nested).count())
        assertEquals(ImageFailure.SOURCE_REFUSED, failure(nested.replace("<code id=", "<div $claim></div><code id=").toByteArray()))
        // The genuine container plus a duplicate claim naming the same activity is still ambiguous.
        assertEquals(ImageFailure.SOURCE_REFUSED, failure(sharePostHtml(extra = "<div $claim></div>")))
        // A claiming container that owns its own list beside the real post's list is not the sole owner.
        val wrapper = "<section $claim><ul class=\"feed-images-content\"><li><img data-delayed-url=\"$SHARE_ASSET\"></li></ul></section>"
        assertEquals(ImageFailure.NO_MEDIA, failure(sharePostHtml(attributed = null, extra = wrapper)))
    }

    @Test fun decoyBridgeStopsTheShortlinkBeforeAnyImageRequest() {
        val decoy = sharePostHtml(attributed = null,
            extra = "<div data-activity-urn=\"urn:li:activity:$SHARE_ACTIVITY\" data-attributed-urn=\"urn:li:share:$SHARE_ID\"></div>")
        fixture(respond = { request, socket -> when {
            request.target.startsWith("/p/") -> socket.reply(301, headers = mapOf("Location" to SHARE_REDIRECT))
            request.target.startsWith("/posts/") -> socket.reply(body = decoy, headers = mapOf("Content-Type" to "text/html"))
            else -> socket.reply(body = raster(format = "jpeg"), headers = mapOf("Content-Type" to "image/jpeg"))
        } }) { f ->
            failed(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource("https://lnkd.in/p/synthetic"), IMAGE_IDENTITY),
                ImageSourceType.LINKEDIN) }, ImageFailure.SOURCE_REFUSED, ImagePhase.PAGE)
            assertEquals(2, f.server.requests.size, "shortlink + page only; zero image requests")
        }
    }

    @Test fun jsonLdIdNamesThePostAndMustAgreeWithUrl() {
        assertIs<LinkedInParseResult.Success>(parser.parse(sharePostHtml(jsonUrl = SHARE_CANONICAL), SHARE_POST))
        // url and @id are compared as post identities: activity slug and feed/update URN of one activity agree.
        assertIs<LinkedInParseResult.Success>(parser.parse(sharePostHtml(
            jsonUrl = "https://www.linkedin.com/feed/update/urn:li:activity:$SHARE_ACTIVITY/"), SHARE_POST))
        assertEquals(ImageFailure.SOURCE_REFUSED, failure(sharePostHtml(
            jsonUrl = "https://www.linkedin.com/feed/update/urn:li:activity:7100000000000000999")))
        assertEquals(ImageFailure.SOURCE_REFUSED, failure(sharePostHtml(jsonUrl = "https://www.linkedin.com/posts/placeholder-activity-7100000000000000999")))
        assertEquals(ImageFailure.NO_MEDIA, failure(sharePostHtml(jsonId = "https://www.linkedin.com/posts/placeholder-activity-7100000000000000999")))
    }

    @Test fun markerAttributesAreAcceptedOnlyWithTheirExactPageValues() {
        for (item in listOf(
            "data-test-id=\"related-content\" data-position=\"0\" style",
            "data-test-id=\"feed-images-content__list-item\" data-position=\"1\" style",
            "data-test-id=\"feed-images-content__list-item\" data-position=\"0\" style=\"display:none\"",
            "data-test-id=\"feed-images-content__list-item\" data-position=\"0\" data-tracking-control-name=\"x\"",
        )) assertEquals(ImageFailure.NO_MEDIA, failure(sharePostHtml(item = item)), item)
        for (ghost in listOf("https://media.licdn.com/ghost", "https://static.licdn.com.evil.example/ghost", "http://static.licdn.com/g"))
            assertEquals(ImageFailure.NO_MEDIA, failure(sharePostHtml(ghost = ghost)), ghost)
    }

    @Test fun shareIdentityNeverMatchesAnActivityOrAnotherShareWithoutPageEvidence() {
        val share = assertNotNull(LinkedInPostIdentity.parse(SHARE_POST))
        val canonical = assertNotNull(LinkedInPostIdentity.parse(SHARE_CANONICAL))
        val feed = assertNotNull(LinkedInPostIdentity.parse("https://www.linkedin.com/feed/update/urn:li:activity:$SHARE_ACTIVITY"))
        assertFalse(share.sameAs(canonical)); assertFalse(share.sameAs(feed))
        assertFalse(share.sameAs(assertNotNull(LinkedInPostIdentity.parse("https://www.linkedin.com/posts/placeholder-share-7100000000000000999-AbCd"))))
        assertTrue(canonical.sameAs(feed), "vanity slug text is not identity; the activity id is")
        assertTrue(share.withBridgedActivity(SHARE_ACTIVITY).sameAs(canonical))
        // The transport still refuses share -> activity and activity A -> B redirects.
        fun hop(from: String, to: String) = MediaHttpPolicy.allowsLinkedInPageHop(
            assertNotNull(MediaHttpPolicy.parse(from)), assertNotNull(MediaHttpPolicy.parse(to)))
        assertFalse(hop(SHARE_POST, SHARE_CANONICAL))
        assertFalse(hop(SHARE_CANONICAL, "https://www.linkedin.com/feed/update/urn:li:activity:7100000000000000999"))
        assertTrue(hop("https://lnkd.in/p/synthetic", SHARE_REDIRECT))
        assertTrue(hop(SHARE_CANONICAL, "https://www.linkedin.com/feed/update/urn:li:activity:$SHARE_ACTIVITY"))
    }

    @Test fun shortlinkToSharePostInspectsTheOneAttachmentAndSaves() {
        val jpeg = raster(format = "jpeg")
        fixture(respond = { request, socket -> when {
            request.target.startsWith("/p/") -> socket.reply(301, headers = mapOf("Location" to SHARE_REDIRECT))
            request.target.startsWith("/posts/") ->
                socket.reply(body = sharePostHtml(), headers = mapOf("Content-Type" to "text/html; charset=utf-8"))
            else -> socket.reply(body = jpeg, headers = mapOf("Content-Type" to "image/jpeg"))
        } }) { f ->
            val operation = f.inspector.newOperation(imageSource("https://lnkd.in/p/synthetic"), IMAGE_IDENTITY)
            inspected(runBlocking { f.inspector.inspect(operation, ImageSourceType.LINKEDIN) }).use { result ->
                assertEquals(1, result.inspection.items.size)
                assertIs<ImageSaveAvailability.Available>(result.saveAvailability)
                assertEquals("Placeholder headline for the post", result.inspection.items.single().metadata.title)
                assertEquals("Placeholder Author", result.inspection.items.single().metadata.uploader)
                assertEquals(3, f.server.requests.size)
                assertTrue(f.server.requests.last().target.startsWith("/dms/image/v2/SYNTHETIC/feedshare-shrink_800/"))
                assertEquals(SHARE_POST, f.server.requests.last().headers["referer"])
                val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
                val sink = ByteArrayOutputStream()
                assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertContentEquals(jpeg, sink.toByteArray())
                assertEquals(4, f.server.requests.size)
                assertEquals(f.server.requests[2].target, f.server.requests[3].target)
            }
        }
    }
    @Test fun shortlinkGifWithLyingJpegHeaderOffersAndSavesOriginalGif() {
        val jpeg = animatedGif()
        fixture(respond = { request, socket -> when {
            request.target.startsWith("/p/") -> socket.reply(301, headers = mapOf("Location" to SHARE_REDIRECT))
            request.target.startsWith("/posts/") ->
                socket.reply(body = sharePostHtml(), headers = mapOf("Content-Type" to "text/html; charset=utf-8"))
            else -> socket.reply(body = jpeg, headers = mapOf("Content-Type" to "image/jpeg"))
        } }) { f ->
            val operation = f.inspector.newOperation(imageSource("https://lnkd.in/p/synthetic"), IMAGE_IDENTITY)
            inspected(runBlocking { f.inspector.inspect(operation, ImageSourceType.LINKEDIN) }).use { result ->
                assertEquals(1, result.inspection.items.size)
                assertEquals("gif", firstRendition(result).image.format.extension)
                assertEquals("image/gif", firstRendition(result).image.format.mimeType)
                assertTrue(f.server.requests.last().headers["accept"].orEmpty().contains("image/gif"))
                assertIs<ImageSaveAvailability.Available>(result.saveAvailability)
                assertEquals("Placeholder headline for the post", result.inspection.items.single().metadata.title)
                assertEquals("Placeholder Author", result.inspection.items.single().metadata.uploader)
                assertEquals(3, f.server.requests.size)
                assertTrue(f.server.requests.last().target.startsWith("/dms/image/v2/SYNTHETIC/feedshare-shrink_800/"))
                assertEquals(SHARE_POST, f.server.requests.last().headers["referer"])
                val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
                val sink = ByteArrayOutputStream()
                val saved = assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertEquals("gif", saved.image.format.extension)
                assertEquals("image/gif", saved.image.format.mimeType)
                assertContentEquals(jpeg, sink.toByteArray())
                assertEquals(4, f.server.requests.size)
                assertEquals(f.server.requests[2].target, f.server.requests[3].target)
            }
        }
    }
}
