package dev.ahmed.savlino.engine.facebook

import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy
import org.junit.Test
import kotlin.test.*

class FacebookPostIdentityTest {
    private fun accepted(url: String) = assertNotNull(FacebookPostIdentity.parse(url), "accepted shape")

    @Test fun everyAcceptedShapeNormalisesToWwwWithOnlyTheIdentifyingQuery() {
        val cases = listOf(
            Triple("https://www.facebook.com/share/p/1AbC-_9/", FacebookLinkKind.SHARE, "https://www.facebook.com/share/p/1AbC-_9/"),
            Triple("https://m.facebook.com/share/v/Xyz12?mibextid=abc", FacebookLinkKind.SHARE, "https://www.facebook.com/share/v/Xyz12"),
            Triple("https://web.facebook.com/share/r/Q1/", FacebookLinkKind.SHARE, "https://www.facebook.com/share/r/Q1/"),
            Triple("https://www.facebook.com/share/1AbC-_9/", FacebookLinkKind.SHARE, "https://www.facebook.com/share/1AbC-_9/"),
            Triple("https://m.facebook.com/share/19Xyz?mibextid=abc", FacebookLinkKind.SHARE, "https://www.facebook.com/share/19Xyz"),
            Triple("https://facebook.com/story.php?story_fbid=123456789&id=42&rdid=x", FacebookLinkKind.STORY,
                "https://www.facebook.com/story.php?story_fbid=123456789&id=42"),
            Triple("https://m.facebook.com/permalink.php?id=42&story_fbid=pfbid0AbCd", FacebookLinkKind.PERMALINK,
                "https://www.facebook.com/permalink.php?story_fbid=pfbid0AbCd&id=42"),
            Triple("https://www.facebook.com/somepage/posts/987654321/", FacebookLinkKind.POST, "https://www.facebook.com/somepage/posts/987654321/"),
            Triple("https://www.facebook.com/some.page/posts/pfbid02xYz?__cft__=t", FacebookLinkKind.POST, "https://www.facebook.com/some.page/posts/pfbid02xYz"),
            Triple("https://www.facebook.com/somepage/posts/%D8%B9%D8%A7%D8%AC%D9%84/987654321/", FacebookLinkKind.POST,
                "https://www.facebook.com/somepage/posts/%D8%B9%D8%A7%D8%AC%D9%84/987654321/"),
            Triple("https://web.facebook.com/photo/?fbid=555&set=a.1", FacebookLinkKind.PHOTO, "https://www.facebook.com/photo/?fbid=555"),
            Triple("https://www.facebook.com/photo.php?fbid=555", FacebookLinkKind.PHOTO, "https://www.facebook.com/photo/?fbid=555"),
            Triple("https://www.facebook.com/reel/1195289147628387", FacebookLinkKind.REEL, "https://www.facebook.com/reel/1195289147628387"),
            Triple("https://m.facebook.com/watch/?v=777", FacebookLinkKind.WATCH, "https://www.facebook.com/watch/?v=777"),
            Triple("https://www.facebook.com/cnn/videos/10155529876156509/", FacebookLinkKind.VIDEO, "https://www.facebook.com/cnn/videos/10155529876156509/"),
            Triple("https://fb.watch/aBc_12-/", FacebookLinkKind.SHORTLINK, "https://fb.watch/aBc_12-/"))
        for ((input, kind, normalised) in cases) {
            val identity = accepted(input)
            assertEquals(kind, identity.kind, "kind")
            assertEquals(normalised, identity.fetchUrl, "normalised fetch URL")
            assertEquals(identity.fetchUrl, FacebookPostIdentity.parse(identity.fetchUrl)?.fetchUrl, "idempotent")
        }
    }

    @Test fun onlyVideoAndReelSharesHaveTheVideoShareFlag() {
        for (kind in listOf("v", "r"))
            assertTrue(accepted("https://www.facebook.com/share/$kind/Synthetic/").isVideoShare)
        for (path in listOf("share/p/Synthetic/", "share/Synthetic/", "watch/?v=900000000000003"))
            assertFalse(accepted("https://www.facebook.com/$path").isVideoShare)
    }

    @Test fun eventPagesAreNotPostsEvenWhenTheirPathLooksLikeOne() {
        for (host in listOf("facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com", "mbasic.facebook.com"))
            for (path in listOf("events/900000000000001/", "events/posts/900000000000001"))
                assertNull(FacebookPostIdentity.parse("https://$host/$path"))
    }

    @Test fun requestedIdsAreKnownOnlyWhenTheLinkNamesThem() {
        assertEquals("123456789", accepted("https://www.facebook.com/story.php?story_fbid=123456789&id=42").postId)
        assertNull(accepted("https://www.facebook.com/permalink.php?story_fbid=pfbid0AbCd&id=42").postId, "pfbid is not a node id")
        assertEquals("987654321", accepted("https://www.facebook.com/somepage/posts/987654321").postId)
        assertEquals("555", accepted("https://www.facebook.com/photo/?fbid=555").photoId)
        assertEquals("777", accepted("https://www.facebook.com/watch/?v=777").videoId)
        assertEquals("1195289147628387", accepted("https://www.facebook.com/reel/1195289147628387").videoId)
        val share = accepted("https://www.facebook.com/share/p/1AbC/")
        assertTrue(share.isShortlink)
        assertNull(share.postId); assertNull(share.photoId); assertNull(share.videoId)
        assertTrue(accepted("https://fb.watch/x1/").isShortlink)
        assertFalse(accepted("https://www.facebook.com/photo/?fbid=555").isShortlink)
    }

    @Test fun everythingElseIsRejected() {
        for (url in listOf(
            "http://www.facebook.com/share/p/1AbC/",
            "https://www.facebook.com.evil.example/share/p/1AbC/",
            "https://evilfacebook.com/share/p/1AbC/",
            "https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.com",
            "https://user@www.facebook.com/share/p/1AbC/",
            "https://www.facebook.com:8443/share/p/1AbC/",
            "https://www.facebook.com/",
            "https://www.facebook.com/login/?next=https%3A%2F%2Fwww.facebook.com%2Fphoto%2F%3Ffbid%3D1",
            "https://www.facebook.com/share/x/1AbC/",
            "https://www.facebook.com/somepage",
            "https://www.facebook.com/photo/?fbid=abc",
            "https://www.facebook.com/photo/?fbid=1&fbid=2",
            "https://www.facebook.com/story.php?story_fbid=1",
            "https://www.facebook.com/watch/?v=1#frag",
            "https://fb.watch/",
            "https://www.linkedin.com/posts/synthetic-activity-123",
            "https://scontent.example.fbcdn.net/1.jpg")) {
            assertNull(FacebookPostIdentity.parse(url), "rejected shape")
        }
    }

    @Test fun loginWallPathsAreExact() {
        for (url in listOf("https://www.facebook.com/login", "https://www.facebook.com/login/", "https://m.facebook.com/login.php?next=x",
                "https://www.facebook.com/checkpoint/", "https://web.facebook.com/checkpoint"))
            assertTrue(FacebookPostIdentity.isLoginWall(assertNotNull(MediaHttpPolicy.parse(url))), "login wall")
        for (url in listOf("https://www.facebook.com/login.evil", "https://www.facebook.com/loginx/", "https://www.facebook.com/checkpoint.evil/",
                "https://www.facebook.com/login/evil/", "https://evil.example/login/"))
            assertFalse(FacebookPostIdentity.isLoginWall(assertNotNull(MediaHttpPolicy.parse(url))), "not a login wall")
    }

    @Test fun identityNeverRendersItsUrl() {
        val identity = accepted("https://www.facebook.com/photo/?fbid=555")
        assertFalse(identity.toString().contains("facebook") || identity.toString().contains("555"))
    }

    @Test fun imageCdnAllowlistIsHttpsScontentOnFbcdnOnly() {
        for (url in listOf("https://scontent.example.fbcdn.net/1.jpg", "https://scontent-iad3-1.xx.fbcdn.net/v/t39/1.jpg?x=1",
                "https://scontent.fnkc1-1.fna.fbcdn.net/v/1.jpg", "https://z-p3-scontent.fnkc1-1.fna.fbcdn.net/v/1.jpg")) {
            assertTrue(MediaHttpPolicy.isFacebookImageCdn(assertNotNull(MediaHttpPolicy.parse(url))), "allowed CDN")
        }
        for (url in listOf("https://external.example.fbcdn.net/1.jpg", "https://video.example.fbcdn.net/1.mp4",
                "https://scontent.example.fbcdn.net.evil.example/1.jpg", "https://fbcdn.net/1.jpg",
                "https://xscontent.example.fbcdn.net/1.jpg", "https://scontent.example.fbsbx.com/1.jpg",
                "https://z-p3-video.example.fbcdn.net/1.mp4", "https://z-p3-xscontent.example.fbcdn.net/1.jpg",
                "https://zz-p3-scontent.example.fbcdn.net/1.jpg", "https://z-p3-scontent.example.fbcdn.net.evil.example/1.jpg",
                "https://www.facebook.com/1.jpg")) {
            assertFalse(MediaHttpPolicy.isFacebookImageCdn(assertNotNull(MediaHttpPolicy.parse(url))), "rejected CDN")
        }
        assertNull(MediaHttpPolicy.parse("http://scontent.example.fbcdn.net/1.jpg"), "https only")
    }
}
