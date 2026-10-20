package dev.ahmed.savlino.engine.linkedin

import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

private const val P_ACTIVITY = "7300000000000000001"
private const val P_UGC = "7300000000000000002"
private const val P_LANDING = "https://www.linkedin.com/posts/placeholder-author_placeholder-topic-ugcPost-$P_UGC-AbCd"
private const val P_CANONICAL = "https://www.linkedin.com/posts/placeholder-author_placeholder-topic-activity-$P_ACTIVITY-WxYz"

/**
 * Sanitized shape of a real public video post page (lnkd.in → `ugcPost-` landing, 2026-10-19, ~3.8 MB): og:type
 * video.other, canonical/og:url and the one JSON-LD VideoObject name the `activity-` slug with the same author/title
 * text, a video-js player and no post-marked containers, no `video[data-sources]`, no image list. [padding] stands in
 * for the page's large inline scripts/styles.
 */
private fun playerPostHtml(
    ogType: String? = "video.other",
    canonical: String? = P_CANONICAL,
    ogUrl: String? = P_CANONICAL,
    jsonLd: String = videoObject(P_CANONICAL),
    extra: String = "",
    padding: Int = 0,
): ByteArray = ("<!DOCTYPE html><html lang=\"en\"><head><title>Placeholder | LinkedIn</title>" +
    (ogType?.let { "<meta property=\"og:type\" content=\"$it\">" } ?: "") +
    (ogUrl?.let { "<meta property=\"og:url\" content=\"$it\">" } ?: "") +
    "<meta property=\"og:image\" content=\"https://static.licdn.com/aero-v1/sc/h/placeholder\">" +
    (canonical?.let { "<link rel=\"canonical\" href=\"$it\">" } ?: "") +
    "<script type=\"application/ld+json\">$jsonLd</script>" +
    "<style>" + "a{}".repeat(padding / 3) + "</style></head><body><main>" +
    "<div data-vjs-player class=\"video-js vjs-fluid\" style=\"\"><div class=\"vjs-poster\"></div></div>" +
    "<p>Placeholder commentary</p>$extra</main></body></html>").toByteArray()

private fun videoObject(id: String, type: String = "VideoObject") =
    "{\"@context\":\"https://schema.org\",\"@type\":\"$type\",\"@id\":\"$id\",\"headline\":\"Placeholder\"," +
        "\"thumbnailUrl\":\"https://static.licdn.com/placeholder\",\"duration\":\"PT1M56S\"," +
        "\"creator\":{\"@type\":\"Person\",\"name\":\"Placeholder Author\"}," +
        "\"comment\":[{\"@type\":\"Comment\",\"text\":\"Placeholder\",\"author\":{\"@type\":\"Person\",\"name\":\"Someone\"}}]}"

/** LinkedIn's video-js post layout is read as a video post; anything less keeps the image path's verdict. */
class LinkedInPlayerVideoTest : PublicImageTestBase() {
    private val parser = LinkedInPostAttachmentParser()
    private fun parse(body: ByteArray = playerPostHtml(), at: String = "$P_LANDING/") = parser.parse(body, at)

    @Test fun playerLayoutVideoPostRedirectsToTheCleanCanonicalPost() {
        assertEquals("$P_CANONICAL/", assertIs<LinkedInParseResult.Video>(parse()).postUrl)
        // Landing already on the activity slug, or on its feed/update form.
        assertEquals("$P_CANONICAL/", assertIs<LinkedInParseResult.Video>(parse(at = "$P_CANONICAL/")).postUrl)
        assertEquals("$P_CANONICAL/", assertIs<LinkedInParseResult.Video>(
            parse(at = "https://www.linkedin.com/feed/update/urn:li:activity:$P_ACTIVITY/")).postUrl)
        // A graph wrapper and a missing og:url are fine.
        assertIs<LinkedInParseResult.Video>(parse(playerPostHtml(ogUrl = null,
            jsonLd = "{\"@context\":\"https://schema.org\",\"@graph\":[${videoObject(P_CANONICAL)}]}")))
    }

    @Test fun aRealSizedPageUnderTheCapIsParsed() {
        val body = playerPostHtml(padding = 4 * 1024 * 1024)
        assertTrue(body.size > 4 * 1024 * 1024)
        assertIs<LinkedInParseResult.Video>(parse(body))
    }

    @Test fun anythingShortOfThePageSayingVideoForThisPostIsNotAVideo() {
        val other = "https://www.linkedin.com/posts/someone-else_other-topic-activity-7300000000000000009-QqQq"
        val notVideo = listOf(
            playerPostHtml(ogType = null),
            playerPostHtml(ogType = "article"),
            playerPostHtml(extra = "<meta property=\"og:type\" content=\"article\">"),
            playerPostHtml(canonical = null),
            playerPostHtml(canonical = other, ogUrl = other, jsonLd = videoObject(other)),
            playerPostHtml(ogUrl = other),
            playerPostHtml(jsonLd = videoObject(other)),
            playerPostHtml(jsonLd = videoObject(P_CANONICAL, "SocialMediaPosting")),
            playerPostHtml(jsonLd = "[${videoObject(P_CANONICAL)},${videoObject(P_CANONICAL)}]"),
            playerPostHtml(jsonLd = "[${videoObject(P_CANONICAL)},${videoObject(P_CANONICAL, "SocialMediaPosting")}]"),
            playerPostHtml(jsonLd = "{\"@type\":oops}"),
            playerPostHtml(extra = "<ul class=\"feed-images-content\"><li><img data-delayed-url=\"https://media.licdn.com/x\"></li></ul>"),
        )
        for ((index, body) in notVideo.withIndex())
            assertIsNot<LinkedInParseResult.Video>(parse(body), "case $index")
        // A landing that names a different post by the same ID kind, or another author/title text, is not bound.
        for (landing in listOf("https://www.linkedin.com/posts/placeholder-author_placeholder-topic-activity-7300000000000000003-AbCd/",
            "https://www.linkedin.com/posts/other-author_other-topic-ugcPost-$P_UGC-AbCd/",
            "https://www.linkedin.com/posts/ugcPost-$P_UGC-AbCd/",
            "https://www.linkedin.com/feed/update/urn:li:activity:7300000000000000003/"))
            assertIsNot<LinkedInParseResult.Video>(parse(at = landing), landing)
    }

    @Test fun shortlinkLandingOnThePlayerPageInspectsAsAVideoRedirect() {
        fixture(respond = { request, socket -> when {
            request.target.startsWith("/p/") -> socket.reply(301, headers = mapOf("Location" to "$P_LANDING/?utm_source=share&rcm=synthetic"))
            else -> socket.reply(body = playerPostHtml(padding = 3 * 1024 * 1024 + 512 * 1024), headers = mapOf("Content-Type" to "text/html"))
        } }) { f ->
            val result = runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource("https://lnkd.in/p/synthetic", 4), IMAGE_IDENTITY), ImageSourceType.LINKEDIN) }
            val redirect = assertIs<MediaInspectResult.VideoRedirect>(result)
            assertEquals("$P_CANONICAL/", redirect.source.link.rawUrl)
            assertEquals(4L, redirect.source.revision)
            assertEquals(2, f.server.requests.size, "no asset is fetched for a video post")
        }
    }

    @Test fun preVerdictPageFailuresOfferTheCleanLandingAndOthersDoNot() {
        // Oversize after the shortlink hop: the landing (query stripped) is offered.
        fixture(respond = { request, socket -> when {
            request.target.startsWith("/p/") -> socket.reply(301, headers = mapOf("Location" to "$P_LANDING/?utm_source=share"))
            else -> socket.reply(declared = 6L * 1024 * 1024 + 1, headers = mapOf("Content-Type" to "text/html"))
        } }) { f ->
            val failure = failed(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource("https://lnkd.in/p/synthetic"), IMAGE_IDENTITY), ImageSourceType.LINKEDIN) },
                ImageFailure.BUDGET, ImagePhase.PAGE)
            assertEquals("$P_LANDING/", failure.fallback?.link?.rawUrl)
        }
        // Unrecognised layout (no attachment evidence at all): still unknown media, the landing is offered.
        fixture(respond = { _, socket -> socket.reply(body = "<html><body>Placeholder</body></html>".toByteArray(), headers = mapOf("Content-Type" to "text/html")) }) { f ->
            val failure = failed(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource("$P_LANDING?trk=x"), IMAGE_IDENTITY), ImageSourceType.LINKEDIN) },
                ImageFailure.NO_MEDIA, ImagePhase.PAGE)
            assertEquals("$P_LANDING/", failure.fallback?.link?.rawUrl)
        }
        // The shortlink itself failed: no post was reached, nothing is offered.
        fixture(respond = { _, socket -> socket.reply(500) }) { f ->
            val failure = failed(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource("https://lnkd.in/p/synthetic"), IMAGE_IDENTITY), ImageSourceType.LINKEDIN) },
                ImageFailure.HTTP, ImagePhase.PAGE)
            assertNull(failure.fallback)
        }
        // An image post whose image failed keeps its image error.
        fixture(respond = { request, socket ->
            if (request.target.startsWith("/posts/")) socket.reply(body = linkedInHtml(), headers = mapOf("Content-Type" to "text/html"))
            else socket.reply(403)
        }) { f ->
            val failure = failed(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource(POST), IMAGE_IDENTITY), ImageSourceType.LINKEDIN) },
                ImageFailure.HTTP, ImagePhase.IMAGE)
            assertNull(failure.fallback)
        }
    }
}
