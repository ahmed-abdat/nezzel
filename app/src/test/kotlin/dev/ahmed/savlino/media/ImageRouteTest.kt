package dev.ahmed.savlino.media

import dev.ahmed.savlino.domain.LinkInput
import dev.ahmed.savlino.engine.MediaSourceRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageRouteTest {
    private fun route(url: String) = ImageRoute.of(requireNotNull(LinkInput.parse(url)) { "fixture must be a valid link: $url" })

    @Test fun telegramAndBlueskyOnlyRoutePublicPostShapes() {
        for (url in listOf("https://t.me/synthetic_channel/123", "https://telegram.me/synthetic_channel/123?single"))
            assertEquals("TELEGRAM", route(url).name)
        for (url in listOf("https://bsky.app/profile/synthetic.test/post/3synthetic001",
            "https://bsky.app/profile/did:plc:abcdefghijklmnopqrstuvwx/post/3synthetic001"))
            assertEquals("BLUESKY", route(url).name)
        for (url in listOf("https://t.me/synthetic_channel", "https://t.me/c/123/456", "https://t.me/+SyntheticInvite",
            "https://t.me/share/url?url=https%3A%2F%2Fexample.com", "https://t.me/synthetic_channel/not-a-post",
            "https://bsky.app/profile/synthetic.test", "https://bsky.app/profile/synthetic.test/post/3synthetic001/likes",
            "https://bsky.app.evil.com/profile/synthetic.test/post/3synthetic001"))
            assertEquals(url, MediaSourceRoute.LEGACY, route(url))
    }

    @Test fun narrowExtensionlessCdnAndPrefixedInstagramShapes() {
        for (url in listOf("https://pbs.twimg.com/media/Synthetic?format=jpg&name=orig", "https://example.com/photo.jpg#x"))
            assertEquals(MediaSourceRoute.DIRECT_IMAGE, ImageRoute.of(dev.ahmed.savlino.domain.LinkInput.parse(url)!!))
        for (url in listOf("https://pbs.twimg.com/media/Synthetic?format=mp4", "https://example.com/media/token?id=1"))
            assertEquals(MediaSourceRoute.LEGACY, ImageRoute.of(dev.ahmed.savlino.domain.LinkInput.parse(url)!!))
        assertEquals(MediaSourceRoute.INSTAGRAM, ImageRoute.of(dev.ahmed.savlino.domain.LinkInput.parse("https://instagr.am/synthetic/p/SynthPost01/")!!))
        assertEquals(MediaSourceRoute.LEGACY, ImageRoute.of(dev.ahmed.savlino.domain.LinkInput.parse("https://instagr.am/synthetic/reel/SynthPost01/")!!))
    }

    @Test fun linkedInPostShapesGoToTheLinkedInImageInspector() {
        assertEquals(MediaSourceRoute.LINKEDIN, route("https://www.linkedin.com/posts/someone_title-activity-7380000000000000000-AbCd"))
        assertEquals(MediaSourceRoute.LINKEDIN, route("https://www.linkedin.com/feed/update/urn:li:activity:7380000000000000000/"))
        assertEquals(MediaSourceRoute.LINKEDIN, route("https://lnkd.in/p/eXaMpLe"))
    }

    @Test fun facebookPostAndPhotoShapesGoToTheFacebookImageInspector() {
        assertEquals(MediaSourceRoute.FACEBOOK, route("https://www.facebook.com/photo/?fbid=123&set=a.1"))
        assertEquals(MediaSourceRoute.FACEBOOK, route("https://www.facebook.com/nasa/posts/1234567890"))
        assertEquals(MediaSourceRoute.FACEBOOK, route("https://m.facebook.com/story.php?story_fbid=123&id=456"))
        assertEquals(MediaSourceRoute.FACEBOOK, route("https://www.facebook.com/nasa/photos/a.1/987654321/"))
        assertEquals(MediaSourceRoute.FACEBOOK, route("https://www.facebook.com/share/p/AbC123xyz/"))
        // Plain share links name no kind: the page fetch finds a photo, or redirects a landing video to yt-dlp.
        assertEquals(MediaSourceRoute.FACEBOOK, route("https://www.facebook.com/share/1AbC23xyZ/"))
        assertEquals(MediaSourceRoute.FACEBOOK, route("https://m.facebook.com/share/19AbCdEfG?mibextid=abc"))
    }

    @Test fun facebookVideosAndReelsStayOnTheLegacyEngine() {
        assertEquals(MediaSourceRoute.LEGACY, route("https://www.facebook.com/reel/1234567890"))
        assertEquals(MediaSourceRoute.LEGACY, route("https://www.facebook.com/watch/?v=1234567890"))
        assertEquals(MediaSourceRoute.LEGACY, route("https://www.facebook.com/nasa/videos/1234567890/"))
        assertEquals(MediaSourceRoute.LEGACY, route("https://fb.watch/AbC123/"))
        assertEquals(MediaSourceRoute.FACEBOOK, route("https://www.facebook.com/share/v/AbC123xyz/"))
        assertEquals(MediaSourceRoute.FACEBOOK, route("https://www.facebook.com/share/r/AbC123xyz/"))
        assertEquals(MediaSourceRoute.LEGACY, route("https://www.facebook.com/nasa"))
    }

    @Test fun facebookEventsAreUnsupportedBeforeAnyInspection() {
        for (host in listOf("facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com", "mbasic.facebook.com"))
            for (path in listOf("events/", "events/900000000000001/", "events/900000000000001/photo.jpg"))
                org.junit.Assert.assertTrue(ImageRoute.isUnsupported(LinkInput.parse("https://$host/$path")!!))
        for (url in listOf("https://example.com/events/900000000000001/",
            "https://www.facebook.com.evil.example/events/900000000000001/",
            "https://www.facebook.com/synthetic/posts/900000000000001"))
            org.junit.Assert.assertFalse(ImageRoute.isUnsupported(LinkInput.parse(url)!!))
    }

    @Test fun gifImagesRouteDirectWhileTweetVideoStaysVideo() {
        for (url in listOf("https://example.com/photo.GIF", "https://pbs.twimg.com/media/Synthetic?format=gif&name=orig"))
            assertEquals(MediaSourceRoute.DIRECT_IMAGE, route(url))
        assertEquals(MediaSourceRoute.LEGACY, route("https://video.twimg.com/tweet_video/Synthetic.mp4"))
    }

    @Test fun stillImageExtensionsGoDirectInAnyCase() {
        assertEquals(MediaSourceRoute.DIRECT_IMAGE, route("https://example.com/a/photo.JPG"))
        assertEquals(MediaSourceRoute.DIRECT_IMAGE, route("https://example.com/a/photo.jpeg?w=1080"))
        assertEquals(MediaSourceRoute.DIRECT_IMAGE, route("https://cdn.example.org/x.png"))
        assertEquals(MediaSourceRoute.DIRECT_IMAGE, route("https://scontent.xx.fbcdn.net/v/t39/123_n.webp?stp=dst"))
    }

    @Test fun xPostShapesGoToTheXReaderFirst() {
        for (url in listOf("https://x.com/someone/status/1790000000000000001", "https://twitter.com/someone/status/1790000000000000001?s=20",
            "https://mobile.twitter.com/someone/status/1790000000000000001/photo/2", "https://x.com/i/status/1790000000000000001"))
            assertEquals(url, MediaSourceRoute.X, route(url))
    }

    @Test fun otherXPagesStayOnTheLegacyEngine() {
        for (url in listOf("https://x.com/someone", "https://x.com/i/spaces/1AbCdEf", "https://x.com/someone/status/1/likes",
            "https://t.co/AbCdEf", "https://video.twimg.com/ext_tw_video/1/pu/vid/1280x720/a.mp4"))
            assertEquals(url, MediaSourceRoute.LEGACY, route(url))
        assertEquals(MediaSourceRoute.DIRECT_IMAGE, route("https://pbs.twimg.com/media/Synthetic.jpg"))
    }

    @Test fun instagramPostsGoToTheInstagramReaderAndReelsStayOnTheLegacyEngine() {
        for (url in listOf("https://www.instagram.com/p/SynthPost01/", "https://instagram.com/p/SynthPost01?igsh=Synthetic",
            "https://m.instagram.com/p/SynthPost01/"))
            assertEquals(url, MediaSourceRoute.INSTAGRAM, route(url))
        for (url in listOf("https://www.instagram.com/reel/SynthPost01/", "https://www.instagram.com/reels/SynthPost01/",
            "https://www.instagram.com/tv/SynthPost01/", "https://www.instagram.com/synthetic_user/",
            "https://www.instagram.com/stories/synthetic_user/1/"))
            assertEquals(url, MediaSourceRoute.LEGACY, route(url))
    }

    @Test fun everythingElseStaysOnTheLegacyEngine() {
        assertEquals(MediaSourceRoute.LEGACY, route("https://youtube.com/watch?v=one"))
        assertEquals(MediaSourceRoute.LEGACY, route("https://www.linkedin.com/in/someone/"))
        assertEquals(MediaSourceRoute.LEGACY, route("https://example.com/clip.mp4"))
        assertEquals(MediaSourceRoute.LEGACY, route("https://example.com/photo.jpg.html"))
        assertEquals(MediaSourceRoute.LEGACY, route("https://example.com/page?file=photo.jpg"))
    }
}
