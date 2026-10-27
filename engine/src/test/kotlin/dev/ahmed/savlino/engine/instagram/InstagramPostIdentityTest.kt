package dev.ahmed.savlino.engine.instagram

import dev.ahmed.savlino.engine.mediahttp.HttpsUrl
import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy
import org.junit.Test
import kotlin.test.*

class InstagramPostIdentityTest {
    @Test fun prefixedAndShortHostLinksFetchOnlyCanonicalPost() {
        for (host in listOf("instagr.am", "www.instagr.am", "www.instagram.com")) {
            val post = assertNotNull(InstagramPostIdentity.parse("https://$host/synthetic.user/p/SynthPost01/"))
            assertEquals(InstagramLinkKind.POST, post.kind)
            assertEquals(IG_POST, post.fetchUrl)
            assertEquals(InstagramLinkKind.REEL, InstagramPostIdentity.parse("https://$host/synthetic/reel/SynthPost01/")?.kind)
        }
    }

    @Test fun postReelAndTvShapesKeepOnlyTheShortcode() {
        val cases = mapOf(
            "https://www.instagram.com/p/SynthPost01/" to InstagramLinkKind.POST,
            "https://instagram.com/p/SynthPost01" to InstagramLinkKind.POST,
            "https://www.instagram.com/p/SynthPost01/?igsh=Synthetic&utm_source=ig_web_copy_link" to InstagramLinkKind.POST,
            "https://m.instagram.com/p/SynthPost01/" to InstagramLinkKind.POST,
            "https://WWW.Instagram.com/p/SynthPost01/" to InstagramLinkKind.POST,
            "https://www.instagram.com/reel/SynthPost01/" to InstagramLinkKind.REEL,
            "https://www.instagram.com/reels/SynthPost01/" to InstagramLinkKind.REEL,
            "https://www.instagram.com/tv/SynthPost01/" to InstagramLinkKind.TV)
        for ((url, kind) in cases) {
            val identity = assertNotNull(InstagramPostIdentity.parse(url), url)
            assertEquals(kind, identity.kind, url)
            assertEquals("SynthPost01", identity.code, url)
            assertEquals("https://www.instagram.com/p/SynthPost01/", identity.fetchUrl, url)
        }
        assertEquals("InstagramPostIdentity(redacted)", InstagramPostIdentity.parse(IG_POST).toString())
    }

    @Test fun otherInstagramPathsHostsAndSchemesAreRejected() {
        for (url in listOf("https://www.instagram.com/", "https://www.instagram.com/synthetic_user/",
            "https://www.instagram.com/stories/synthetic_user/123/", "https://www.instagram.com/p/",
            "https://www.instagram.com/p/abc/", "https://www.instagram.com/p/Synth.Post/", "https://www.instagram.com/p/SynthPost01/c/1/",
            "https://www.instagram.com/explore/tags/x/",
            "http://www.instagram.com/p/SynthPost01/", "https://www.instagram.com:8443/p/SynthPost01/",
            "https://user@www.instagram.com/p/SynthPost01/", "https://instagram.com.evil.example/p/SynthPost01/",
            "https://evilinstagram.com/p/SynthPost01/", "https://www.threads.net/p/SynthPost01/",
            "https://www.instagram.com/p/${"A".repeat(65)}/", "https://www.instagram.com/p/Abcd/"))
            assertNull(InstagramPostIdentity.parse(url), url)
    }

    @Test fun shortcodeLengthBoundsAreFiveToSixtyFour() {
        assertNull(InstagramPostIdentity.parse("https://www.instagram.com/p/Ab_-/"))
        assertEquals("Ab_-1", InstagramPostIdentity.parse("https://www.instagram.com/p/Ab_-1/")?.code)
        assertEquals("A".repeat(64), InstagramPostIdentity.parse("https://www.instagram.com/p/${"A".repeat(64)}/")?.code)
    }

    @Test fun loginWallIsExactlyTheLoginAndChallengePaths() {
        for (path in listOf("/accounts/login", "/accounts/login/", "/accounts/login/?next=%2Fp%2FSynthPost01%2F",
            "/challenge", "/challenge/", "/challenge/action/Synthetic/"))
            assertTrue(InstagramPostIdentity.isLoginWall(HttpsUrl.parse("https://www.instagram.com$path")!!), path)
        for (path in listOf("/accounts/login-evil/", "/accounts/loginx", "/challenge-x/", "/challenges/", "/x/accounts/login/",
            "/accounts/", "/p/SynthPost01/"))
            assertFalse(InstagramPostIdentity.isLoginWall(HttpsUrl.parse("https://www.instagram.com$path")!!), path)
        assertFalse(InstagramPostIdentity.isLoginWall(HttpsUrl.parse("https://evil.example/accounts/login/")!!))
    }

    @Test fun onlyTheCanonicalPostPageIsAPageRequest() {
        assertTrue(InstagramPostIdentity.isPageRequest(HttpsUrl.parse(IG_POST)!!))
        for (url in listOf("https://www.instagram.com/p/SynthPost01", "https://instagram.com/p/SynthPost01/",
            "https://www.instagram.com/p/SynthPost01/?x=1", "https://www.instagram.com/reel/SynthPost01/",
            "https://www.instagram.com/api/graphql/", "https://www.instagram.com/p/SynthPost01/embed/"))
            assertFalse(InstagramPostIdentity.isPageRequest(HttpsUrl.parse(url)!!), url)
    }

    @Test fun cdnAllowlistIsTheObservedInstagramImageHostsAndPathsOnly() {
        for (url in listOf(igImageUrl(1), igImageUrl(2, host = "scontent-xyz1-1.cdninstagram.com"),
            igImageUrl(3, host = "scontent.cdninstagram.com"), "https://instagram.fabc2-1.fna.fbcdn.net/v/t51.2885-15/e35/1_n.jpg"))
            assertTrue(MediaHttpPolicy.isInstagramImage(HttpsUrl.parse(url)!!), url)
        for (url in listOf("https://scontent.xx.fbcdn.net${igImagePath(1)}", "https://instagram.com${igImagePath(1)}",
            "https://www.instagram.com${igImagePath(1)}", "https://evil.cdninstagram.com.example${igImagePath(1)}",
            "https://instagram.fxyz1-1.fna.fbcdn.net.evil.example${igImagePath(1)}", "https://static.cdninstagram.com${igImagePath(1)}",
            "https://$IG_CDN/o1/v/t16/f2/m86/Synthetic.mp4", "https://$IG_CDN/v/t51.82787-15/1_n.mp4",
            "https://$IG_CDN/rsrc.php/v3/x.png", "https://${IG_CDN}:8443${igImagePath(1)}"))
            assertFalse(HttpsUrl.parse(url)?.let(MediaHttpPolicy::isInstagramImage) == true, url)
    }

    /** url-shapes: a share id is not a shortcode, so /share/ (and other reserved first segments) never parse as a post. */
    @Test fun reservedFirstSegmentsAreNeverAUserPrefix() {
        val accepted = mapOf(
            "https://www.instagram.com/synthetic.user/p/SynthPost01/" to "https://www.instagram.com/p/SynthPost01/",
            "https://m.instagram.com/synthetic_user/reel/SynthPost01/?igsh=x" to "https://www.instagram.com/reel/SynthPost01/",
            "https://instagr.am/p/SynthPost01" to "https://www.instagram.com/p/SynthPost01/",
            "https://www.instagram.com/p/SynthPost01/?img_index=1" to "https://www.instagram.com/p/SynthPost01/")
        for ((url, legacy) in accepted) assertEquals(legacy, InstagramPostIdentity.parse(url)?.legacyUrl, url)
        for (url in listOf("https://www.instagram.com/share/reel/SynthShare1/", "https://www.instagram.com/share/p/SynthShare1/",
            "https://www.instagram.com/share/SynthShare1/", "https://www.instagram.com/stories/p/SynthPost01/",
            "https://www.instagram.com/explore/p/SynthPost01/", "https://www.instagram.com/accounts/reel/SynthPost01/",
            "https://www.instagram.com/p/reel/SynthPost01/", "https://www.instagram.com/reels/tv/SynthPost01/",
            "https://www.instagram.com/stories/synthetic_user/123/", "https://www.instagram.com/Share/p/SynthPost01/",
            "https://www.instagram.com/SHARE/reel/SynthShare1/", "https://www.instagram.com/Stories/p/SynthPost01/"))
            assertNull(InstagramPostIdentity.parse(url), url)
    }
}
