package dev.ahmed.savlino.engine.pinterest

import dev.ahmed.savlino.engine.mediahttp.HttpsUrl
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinterestPostIdentityTest {
    private val pin = "100000000000000001"
    private val canonical = "https://www.pinterest.com/pin/$pin/"

    @Test fun countrySlugAndInviteLandingsAreTheSamePin() {
        for (url in listOf(
            canonical,
            "https://pinterest.com/pin/$pin",
            "https://www.pinterest.co.uk/pin/synthetic-title--$pin/sent/?trk=1",
            "https://co.pinterest.com/pin/$pin/",
            "https://in.pinterest.com/pin/Synthetic_Title--$pin"))
            assertEquals(canonical, PinterestPostIdentity.parse(url)?.fetchUrl, url)
    }

    @Test fun shortLinksBoardsAndLookalikeHostsAreNotPins() {
        for (url in listOf(
            "https://pin.it/AbCd123",
            "https://api.pinterest.com/url_shortener/AbCd123/redirect/",
            "https://www.pinterest.com/synthetic/board/",
            "https://www.pinterest.com/pin/",
            "https://www.pinterest.com/pin/not-a-number/",
            "https://user@www.pinterest.com/pin/$pin/",
            "https://www.pinterest.com.evil.example/pin/$pin/",
            "http://www.pinterest.com/pin/$pin/",
            "https://www.pinterest.com/pin/$pin/repins/"))
            assertNull(PinterestPostIdentity.parse(url), url)
    }

    @Test fun onlyTheCanonicalPageIsRequested() {
        assertTrue(PinterestPostIdentity.isPageRequest(HttpsUrl.parse(canonical)!!))
        assertFalse(PinterestPostIdentity.isPageRequest(HttpsUrl.parse("https://www.pinterest.com/pin/$pin/sent/")!!))
        assertFalse(PinterestPostIdentity.isPageRequest(HttpsUrl.parse("https://www.pinterest.com/pin/$pin/?trk=1")!!))
        assertFalse(PinterestPostIdentity.isPageRequest(HttpsUrl.parse("https://co.pinterest.com/pin/$pin/")!!))
        assertTrue(PinterestPostIdentity.isLoginWall(HttpsUrl.parse("https://www.pinterest.com/login/")!!))
        assertFalse(PinterestPostIdentity.isPinterestHost("api.pinterest.com"))
        assertFalse(PinterestPostIdentity.isPinterestHost("pin.it"))
    }
}
