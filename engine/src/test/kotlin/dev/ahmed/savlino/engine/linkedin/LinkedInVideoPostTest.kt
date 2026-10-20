package dev.ahmed.savlino.engine.linkedin

import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

private const val V_ACTIVITY = "7200000000000000001"
private const val V_UGC = "7200000000000000002"
private const val V_POST = "https://www.linkedin.com/posts/placeholder-author_placeholder-topic-ugcPost-$V_UGC-AbCd"
private const val V_CLEAN = "$V_POST/"
private const val V_REDIRECT = "$V_POST/?utm_source=share&rcm=synthetic"
private const val V_CANONICAL = "https://www.linkedin.com/posts/placeholder-author_activity-$V_ACTIVITY-WxYz"
private const val V_FEED = "https://www.linkedin.com/feed/update/urn:li:activity:$V_ACTIVITY"
/** A non-Latin (Arabic) author slug arrives percent-encoded; it must be kept exactly as served. */
private const val V_ARABIC = "https://www.linkedin.com/posts/%D8%A3%D8%AD%D9%85%D8%AF-%d8%b9_ugcPost-$V_UGC-AbCd"
private const val V_STREAM = "https://dms.licdn.com/playlist/vid/v2/SYNTHETIC/mp4-720p/0/1?e=2147483647&v=beta&t=synthetic-sig"
private const val V_COVER = "https://media.licdn.com/dms/image/v2/SYNTHETIC/videocover-high/0/1?e=2147483647&v=beta&t=synthetic-c"

/**
 * Sanitized shape of a real public video post reached through lnkd.in (2026-10-19): the shortlink lands on a
 * `ugcPost-` slug with tracking query params; canonical and the JSON-LD VideoObject name the `activity-` slug;
 * the post container bridges both and owns a `video[data-sources]` and no image list.
 */
private fun videoPostHtml(
    activity: String = "urn:li:activity:$V_ACTIVITY",
    attributed: String? = "urn:li:ugcPost:$V_UGC",
    featured: String? = activity,
    containerAttrs: String = "",
    media: String = "data-test-id=\"feed-native-video-content\"",
    inner: String = "",
    canonical: String? = V_CANONICAL,
    jsonType: String = "VideoObject",
    jsonId: String = V_CANONICAL,
    video: String = "<video muted class=\"share-native-video__node video-js\" data-sources=\"[{&quot;src&quot;:" +
        "&quot;${V_STREAM.replace("&", "&amp;")}&quot;,&quot;type&quot;:&quot;video/mp4&quot;}]\"></video>",
    extra: String = "",
): ByteArray {
    val container = "<article class=\"main-feed-activity-card\" data-activity-urn=\"$activity\"" +
        (featured?.let { " data-featured-activity-urn=\"$it\"" } ?: "") +
        (attributed?.let { " data-attributed-urn=\"$it\"" } ?: "") + " $containerAttrs>"
    return ("<!DOCTYPE html><html lang=\"en\"><head><title>Placeholder post | LinkedIn</title>" +
        (canonical?.let { "<link rel=\"canonical\" href=\"$it\">" } ?: "") +
        "<meta property=\"og:image\" content=\"${V_COVER.replace("&", "&amp;")}\">" +
        "<script type=\"application/ld+json\">{\"@context\":\"http://schema.org\",\"@type\":\"$jsonType\"," +
        "\"@id\":\"$jsonId\",\"contentUrl\":\"$V_STREAM\",\"thumbnailUrl\":\"$V_COVER\",\"duration\":\"PT51S\"," +
        "\"name\":\"Placeholder Author posted on LinkedIn\",\"creator\":{\"@type\":\"Person\",\"name\":\"Placeholder Author\"}}" +
        "</script></head><body><main>$container" +
        "<p data-test-id=\"main-feed-activity-card__commentary\">Placeholder commentary</p>" +
        "<div class=\"share-native-video\" $media>$video</div>$inner" +
        "</article>$extra</main></body></html>").toByteArray()
}

/** A LinkedIn post whose own attachment is a video hands yt-dlp its clean post URL, never a media URL. */
class LinkedInVideoPostTest : PublicImageTestBase() {
    private val parser = LinkedInPostAttachmentParser()
    private fun parse(body: ByteArray = videoPostHtml(), at: String = V_CLEAN) = parser.parse(body, at)
    private fun video(body: ByteArray = videoPostHtml(), at: String = V_CLEAN) =
        assertIs<LinkedInParseResult.Video>(parse(body, at), "expected a video redirect").postUrl
    private fun notVideo(body: ByteArray, at: String = V_CLEAN) =
        assertIsNot<LinkedInParseResult.Video>(parse(body, at)).also { assertIsNot<LinkedInParseResult.Success>(it) }

    @Test fun videoPostResolvesToItsCleanPostUrl() {
        assertEquals(V_CLEAN, video())
        assertEquals(V_CLEAN, video(at = V_POST), "trailing slash added, nothing else")
        assertEquals("$V_CANONICAL/", video(at = V_CANONICAL), "an activity-slug landing keeps its own slug")
        assertEquals("$V_CANONICAL/", video(at = V_FEED), "a feed/update landing takes the page's canonical post slug")
        assertEquals("$V_FEED/", video(videoPostHtml(canonical = null), V_FEED), "no canonical: the activity form")
        assertFalse(parse().toString().contains("linkedin"), "result renders no URL")
    }

    @Test fun anEncodedArabicSlugIsKeptExactlyAsServed() {
        assertEquals("$V_ARABIC/", video(at = V_ARABIC))
        assertEquals("$V_ARABIC/", video(at = "$V_ARABIC/"))
        // Escaped separators, dots, percent signs and controls never form a post slug.
        for (bad in listOf("%2F", "%2f", "%2E%2E", "%3F", "%23", "%25", "%5C", "%0A", "%7F", "%G1", "%-1", "%"))
            assertNull(LinkedInPostIdentity.parse("https://www.linkedin.com/posts/a${bad}b_ugcPost-$V_UGC-AbCd"), bad)
    }

    @Test fun videoEvidenceMustBeThePostsOwn() {
        notVideo(videoPostHtml(jsonType = "SocialMediaPosting"))
        notVideo(videoPostHtml(jsonId = "https://www.linkedin.com/posts/placeholder-activity-7200000000000000999-QrSt"))
        notVideo(videoPostHtml(video = "<video muted class=\"share-native-video__node\"></video>"))
        notVideo(videoPostHtml(video = "<template><video data-sources=\"[]\"></video></template>"))
        notVideo(videoPostHtml(attributed = "urn:li:ugcPost:7200000000000000999"))
        notVideo(videoPostHtml(activity = "urn:li:activity:7200000000000000999"), V_CANONICAL)
        notVideo(videoPostHtml(canonical = "https://www.linkedin.com/posts/placeholder-activity-7200000000000000999-QrSt"))
        // A second container claiming the same post is ambiguous.
        notVideo(videoPostHtml(extra = "<article data-activity-urn=\"urn:li:activity:$V_ACTIVITY\" " +
            "data-attributed-urn=\"urn:li:ugcPost:$V_UGC\"></article>"))
        // A related post's video elsewhere on the page is not this post's.
        notVideo(videoPostHtml(video = "", extra = "<article data-activity-urn=\"urn:li:activity:7200000000000000999\">" +
            "<video data-sources=\"[]\"></video></article>"))
    }

    /** share/ugcPost A must not bind to activity B's video unless the page is A's own featured post. */
    @Test fun aReshareNeverLendsItsVideoToTheRequestedPost() {
        val other = "7200000000000000777"
        val nestedOriginal = "<article data-activity-urn=\"urn:li:activity:$other\" data-attributed-urn=\"urn:li:ugcPost:$V_UGC\">" +
            "<div data-test-id=\"feed-native-video-content\"><video data-sources=\"[]\"></video></div></article>"
        // B (the resharer) wraps A's card: two post containers, never a video.
        notVideo(videoPostHtml(attributed = "urn:li:share:7200000000000000888", video = "", inner = nestedOriginal))
        notVideo(videoPostHtml(inner = nestedOriginal))
        // A flat A→B claim without the page's featured card naming B, or with it naming another activity.
        notVideo(videoPostHtml(featured = null))
        notVideo(videoPostHtml(featured = "urn:li:activity:$other"))
        // An activity request never bridges through data-attributed-urn.
        notVideo(videoPostHtml(activity = "urn:li:activity:$other"), V_CANONICAL)
    }

    @Test fun hiddenCommentOrStrayVideosAreNotThePostsVideo() {
        notVideo(videoPostHtml(media = "data-test-id=\"feed-native-video-content\" hidden"))
        notVideo(videoPostHtml(media = "data-test-id=\"feed-native-video-content\" aria-hidden=\"true\""))
        notVideo(videoPostHtml(media = "data-test-id=\"feed-native-video-content\" style=\"display: none\""))
        notVideo(videoPostHtml(containerAttrs = "style=\"visibility:hidden\""))
        notVideo(videoPostHtml(media = "data-test-id=\"comment-video\""))
        notVideo(videoPostHtml(video = "<div><video data-sources=\"[]\"></video></div>"))
        val commentVideo = "<section class=\"comment\"><div data-test-id=\"feed-native-video-content\">" +
            "<video data-sources=\"[]\"></video></div></section>"
        notVideo(videoPostHtml(inner = commentVideo))
        notVideo(videoPostHtml(video = "", inner = commentVideo))
    }

    @Test fun anImageListAnywhereInThePostDefersToTheImagePath() {
        val nested = "<div data-post-url=\"$V_CANONICAL\"><ul class=\"feed-images-content\"><li>" +
            "<img data-delayed-url=\"$V_COVER\"></li></ul></div>"
        assertIsNot<LinkedInParseResult.Video>(parse(videoPostHtml(inner = nested)))
        assertIsNot<LinkedInParseResult.Video>(parse(videoPostHtml(inner = "<ul class=\"feed-images-content\"></ul>")))
    }

    @Test fun imagePostsAreUnchangedEvenNextToAVideoElement() {
        val image = linkedInHtml()
        assertIs<LinkedInParseResult.Success>(parser.parse(image, POST))
        // The post's own image list wins over a video element and a VideoObject naming the same post.
        val withVideo = image.toString(Charsets.UTF_8).replace("</ul>", "</ul><video data-sources=\"[]\"></video>")
            .replace("</head>", "<script type=\"application/ld+json\">{\"@type\":\"VideoObject\",\"@id\":\"$POST\"}</script></head>")
            .toByteArray()
        assertEquals(listOf(ASSET), assertIs<LinkedInParseResult.Success>(parser.parse(withVideo, POST)).candidates.map { it.url })
    }

    @Test fun aPostWithNeitherImageNorVideoKeepsItsFormerResult() {
        assertEquals(ImageFailure.NO_MEDIA, assertIs<LinkedInParseResult.Failure>(
            parse(videoPostHtml(video = "", attributed = null), V_CANONICAL)).reason)
    }

    private fun site(page: ByteArray = videoPostHtml(), location: String = V_REDIRECT): (RecordedMediaRequest, javax.net.ssl.SSLSocket) -> Unit =
        { request, socket -> when {
            request.target.startsWith("/p/") -> socket.reply(301, headers = mapOf("Location" to location))
            request.target.startsWith("/posts/") || request.target.startsWith("/feed/") ->
                socket.reply(body = page, headers = mapOf("Content-Type" to "text/html; charset=utf-8"))
            else -> socket.reply(404)
        } }
    private fun PublicImageFixture.inspect(url: String, revision: Long = 7) = runBlocking {
        inspector.inspect(inspector.newOperation(imageSource(url, revision), IMAGE_IDENTITY), ImageSourceType.LINKEDIN)
    }

    @Test fun shortlinkToAVideoPostReturnsTheCleanPostUrlWithoutAnyMediaRequest() {
        fixture(respond = site()) { f ->
            val result = f.inspect("https://lnkd.in/p/synthetic")
            val source = assertIs<MediaInspectResult.VideoRedirect>(result).source
            assertEquals(V_CLEAN, source.link.rawUrl, "query stripped, https www.linkedin.com /posts/<slug>/")
            assertEquals(7, source.revision, "the user's revision carries over")
            assertEquals(2, f.server.requests.size, "shortlink + page only; no media request")
            assertFalse(result.toString().contains("linkedin"))
        }
    }

    @Test fun shortlinkToAnEncodedArabicSlugKeepsItEncoded() {
        fixture(respond = site(location = "$V_ARABIC?utm_source=share")) { f ->
            val source = assertIs<MediaInspectResult.VideoRedirect>(f.inspect("https://lnkd.in/p/synthetic")).source
            assertEquals("$V_ARABIC/", source.link.rawUrl)
        }
    }

    @Test fun shortlinkToAPostWithoutMediaIsStillNoMedia() {
        fixture(respond = site(videoPostHtml(video = "", attributed = null), "$V_CANONICAL?rcm=synthetic")) { f ->
            failed(f.inspect("https://lnkd.in/p/synthetic"), ImageFailure.NO_MEDIA, ImagePhase.PAGE)
        }
    }
}
