package dev.ahmed.savlino.engine.facebook

import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

/** Sub-unit B: a Facebook post whose own attachment is a Video hands yt-dlp `watch/?v={id}`, never a media URL. */
class FacebookVideoRedirectTest : PublicImageTestBase() {
    private val parser = FacebookPageParser()
    private val post = "https://www.facebook.com/somepage/posts/900000000000001"
    private val pfbid = "https://www.facebook.com/permalink.php?story_fbid=pfbid0Synthetic&id=42"
    private val decoyStory = "https://www.facebook.com/story.php?story_fbid=900000000000091&id=42"
    private val storyTarget = "/story.php?story_fbid=900000000000001&id=42"
    private val html = mapOf("Content-Type" to "text/html; charset=utf-8")

    private fun identity(url: String) = assertNotNull(FacebookPostIdentity.parse(url))
    private fun parse(page: String, at: String = FB_STORY, requested: String? = null) =
        parser.parse(page.toByteArray(), identity(at), requested?.let(::identity))
    private fun video(page: String, at: String = FB_STORY, requested: String? = null) =
        assertIs<FacebookParseResult.Video>(parse(page, at, requested), "expected a video redirect").videoId
    private fun noMedia(page: String, at: String = FB_STORY, requested: String? = null) =
        assertEquals(ImageFailure.NO_MEDIA, assertIs<FacebookParseResult.Failure>(parse(page, at, requested)).reason)

    @Test fun recordedVideoPostResolvesToItsOwnAttachmentVideo() {
        val page = facebookFixture("video-story.html")
        assertTrue(page.contains("video.other") && page.contains(cdn(3)), "og thumbnail present")
        assertEquals("900000000000002", video(page))
        assertEquals("900000000000002", video(page, post))
        assertEquals("900000000000002", video(page, FB_STORY, FB_SHARE), "share → story.php keeps the page's own post id")
        assertEquals("900000000000002", video(page, pfbid), "pfbid: the sole story is this post")
        assertFalse(parse(page).toString().contains("900000000000002"), "result renders no id")
    }

    @Test fun decoyStoriesReelFeedsAndRelatedVideosAreIgnored() {
        val page = facebookFixture("video-decoys-post.html")
        assertTrue(page.contains("900000000000092") && page.contains("900000000000094"), "decoys injected")
        assertEquals("900000000000002", video(page))
        assertEquals("900000000000092", video(page, decoyStory), "each story binds only its own attachment")
        noMedia(page, pfbid) // two complete stories and no post id: nothing is bound, the app falls back as before
        noMedia(page, "https://www.facebook.com/story.php?story_fbid=900000000000093&id=42") // feed story is not a root
        noMedia(facebookFixture("video-post.html")) // related-video feed units are not this post
        noMedia(facebookFixture("reel-page.html"), "https://www.facebook.com/reel/900000000000001")
    }

    @Test fun onlyOneNumericVideoOnAPostRequestResolves() {
        val page = facebookFixture("video-story.html")
        noMedia(page.replace("900000000000002", "abc"))
        noMedia(page.replace("900000000000002", "1".repeat(26)))
        val two = page.replace("\"attachments\":[{\"media\":{\"__typename\":\"Video\",\"id\":\"900000000000002\",\"__isNode\":\"Video\"},",
            "\"attachments\":[{\"styles\":{\"attachment\":{\"media\":{\"__typename\":\"Video\",\"id\":\"900000000000005\"}}}},{\"media\":{\"__typename\":\"Video\",\"id\":\"900000000000002\",\"__isNode\":\"Video\"},")
        assertTrue(two.contains("900000000000005"))
        noMedia(two)
        noMedia(page, FB_PHOTO) // a photo request never becomes a video
    }

    private val firstAttachment = "\"attachments\":[{\"media\":{\"__typename\":\"Video\",\"id\":\"900000000000002\",\"__isNode\":\"Video\"},"
    private fun withLeadingAttachment(page: String, attachment: String) =
        page.replace(firstAttachment, "\"attachments\":[$attachment,${firstAttachment.removePrefix("\"attachments\":[")}")
            .also { assertNotEquals(page, it, "attachment injected") }

    @Test fun everyAttachmentCountsAndAnyInvalidOrOtherOneMeansNoVideo() {
        val page = facebookFixture("video-story.html")
        // Video 111 next to Video "abc": not "exactly one video".
        noMedia(withLeadingAttachment(page, "{\"styles\":{\"attachment\":{\"media\":{\"__typename\":\"Video\",\"id\":\"abc\"}}}}")
            .replace("900000000000002", "111"))
        // A video next to a non-media (link) attachment.
        noMedia(withLeadingAttachment(page, "{\"styles\":{\"attachment\":{\"media\":{\"__typename\":\"GenericAttachmentMedia\"}}}}"))
        // One attachment whose two Video nodes disagree.
        noMedia(page.replace("\"media\":{\"__typename\":\"Video\",\"id\":\"900000000000002\",\"__isNode\":\"Video\"},\"styles\"",
            "\"media\":{\"__typename\":\"Video\",\"id\":\"900000000000006\",\"__isNode\":\"Video\"},\"styles\"")
            .also { assertTrue(it.contains("900000000000006")) })
    }

    @Test fun duplicateRootsCollapseOnlyWithIdenticalMedia() {
        val conflict = facebookFixture("video-conflict-post.html")
        assertTrue(conflict.indexOf("900000000000095") < conflict.indexOf("900000000000002"), "decoy root comes first")
        noMedia(conflict)
        noMedia(conflict, post)
        val page = facebookFixture("video-story.html")
        val again = page.replace("</body>", page.substringAfter("<body>").substringBefore("</body>") + "</body>")
        assertEquals("900000000000002", video(again), "an identical repeat still collapses")
        val photo = facebookFixture("photo-post.html")
        val photoAgain = photo.replace("</body>", photo.substringAfter("<body>").substringBefore("</body>") + "</body>")
        assertIs<FacebookParseResult.Success>(parse(photoAgain))
    }

    @Test fun photoPostsAreUnchangedAndAPhotoAttachmentNextToAVideoStillWins() {
        assertIs<FacebookParseResult.Success>(parse(facebookFixture("photo-post.html")))
        // Two roots for the same post_id, one with a photo and one with a video: copies disagree, nothing binds.
        val conflicting = facebookFixture("photo-post.html").replace("</body>", facebookFixture("video-story.html")
            .substringAfter("<body>").substringBefore("</body>") + "</body>")
        noMedia(conflicting)
        // One story whose attachments are a photo and a video: the photo is the image, never a video redirect.
        val video = "{\"styles\":{\"attachment\":{\"media\":{\"__typename\":\"Video\",\"id\":\"900000000000007\"}}}}"
        val photo = facebookFixture("photo-post.html")
        val mixed = photo.replace("\"post_id\":\"900000000000001\",\"attachments\":[{", "\"post_id\":\"900000000000001\",\"attachments\":[$video,{")
        assertTrue(mixed.contains("900000000000007"), "video attachment injected")
        assertEquals(listOf(cdn(1)), assertIs<FacebookParseResult.Success>(parse(mixed)).candidates.map { it.url })
    }

    private fun site(page: String = "video-story.html", share: String = "https://www.facebook.com$storyTarget&rdid=synthetic",
        storyMovedTo: String? = null): (RecordedMediaRequest, SSLSocket) -> Unit = { request, socket ->
        when {
            storyMovedTo != null && request.target == storyTarget -> socket.reply(302, headers = mapOf("Location" to storyMovedTo))
            request.headers["host"] == "scontent.example.fbcdn.net" ->
                socket.reply(body = raster(), headers = mapOf("Content-Type" to "image/png"))
            request.target.startsWith("/share/") -> socket.reply(302, headers = mapOf("Location" to share))
            request.target == storyTarget || request.target.startsWith("/permalink.php") || request.target.startsWith("/reel/") || request.target.contains("/videos/") ->
                socket.reply(body = facebookFixture(page).toByteArray(), headers = html)
            else -> socket.reply(404)
        }
    }
    private fun PublicImageFixture.inspect(url: String, revision: Long = 7) = runBlocking {
        inspector.inspect(inspector.newOperation(imageSource(url, revision), IMAGE_IDENTITY), ImageSourceType.FACEBOOK)
    }
    private fun redirect(result: MediaInspectResult) = assertIs<MediaInspectResult.VideoRedirect>(result).source

    @Test fun videoPostInspectionReturnsTheWatchUrlWithoutAnyMediaRequest() {
        fixture(respond = site()) { f ->
            val source = redirect(f.inspect(FB_SHARE))
            assertEquals("https://www.facebook.com/watch/?v=900000000000002", source.link.rawUrl)
            assertEquals(7, source.revision, "the user's revision carries over")
            assertEquals(listOf("/share/p/1SyntheticAb/", storyTarget), f.server.requests.map { it.target })
            assertFalse(f.inspect(FB_STORY).toString().contains("watch"), "result renders no URL")
        }
    }

    @Test fun postLinkLandingOnAReelOrVideoPageHandsOverThatVideoId() {
        fixture(respond = site("reel-page.html", "https://www.facebook.com/reel/900000000000077")) { f ->
            assertEquals("https://www.facebook.com/watch/?v=900000000000077", redirect(f.inspect(FB_SHARE)).link.rawUrl)
        }
        fixture(respond = site("video-post.html", "https://www.facebook.com/somepage/videos/900000000000078/")) { f ->
            assertEquals("https://www.facebook.com/watch/?v=900000000000078", redirect(f.inspect(FB_SHARE)).link.rawUrl)
        }
    }

    @Test fun anIdBearingLinkRedirectedToAVideoPageNeverTakesTheUrlsId() {
        val moved = "https://www.facebook.com/somepage/videos/900000000000456/"
        // The landing page binds this post's own story: its attachment wins over the URL's id.
        fixture(respond = site("video-story.html", storyMovedTo = moved)) { f ->
            assertEquals("https://www.facebook.com/watch/?v=900000000000002", redirect(f.inspect(FB_STORY)).link.rawUrl)
            assertEquals(listOf(storyTarget, "/somepage/videos/900000000000456/"), f.server.requests.map { it.target })
        }
        // No bound story on the landing page (related-video decoys only): no redirect at all.
        fixture(respond = site("video-post.html", storyMovedTo = moved)) { f ->
            failed(f.inspect(FB_STORY), ImageFailure.NO_MEDIA, ImagePhase.PAGE)
        }
        fixture(respond = site("reel-page.html", storyMovedTo = "https://www.facebook.com/reel/900000000000789")) { f ->
            failed(f.inspect(FB_STORY), ImageFailure.NO_MEDIA, ImagePhase.PAGE)
        }
        // A shortlink carries no id of its own: its landing reel names the video.
        fixture(respond = site("reel-page.html", "https://www.facebook.com/reel/900000000000789")) { f ->
            assertEquals("https://www.facebook.com/watch/?v=900000000000789", redirect(f.inspect(FB_SHARE)).link.rawUrl)
        }
    }

    @Test fun photoPostInspectionIsUnchanged() {
        fixture(respond = site("photo-post.html")) { f ->
            assertIs<MediaInspectResult.Success>(f.inspect(FB_STORY)).close()
        }
        fixture(respond = site("video-decoys-post.html")) { f ->
            failed(f.inspect(pfbid), ImageFailure.NO_MEDIA, ImagePhase.PAGE)
        }
    }
}
