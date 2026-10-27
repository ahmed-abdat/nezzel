package dev.ahmed.savlino.engine.linkedin

import dev.ahmed.savlino.engine.mediahttp.HttpsUrl
import org.junit.Test
import kotlin.test.*

class LinkedInPostIdentityTest {
    @Test fun linkedinSignupAndLoginRedirectsAreNamedLoginWalls() {
        fun url(value: String) = checkNotNull(HttpsUrl.parse(value))
        // A members-only post sends logged-out visitors to /signup/cold-join (owner's link, 2026-10-21).
        listOf("https://www.linkedin.com/signup/cold-join", "https://www.linkedin.com/authwall", "https://www.linkedin.com/login",
            "https://linkedin.com/uas/login").forEach { assertTrue(LinkedInPostIdentity.isLinkedInLoginWall(url(it)), it) }
        listOf("https://www.linkedin.com/posts/synthetic-activity-123", "https://other.test/login",
            "https://www.linkedin.com/feed/update/urn:li:activity:123").forEach { assertFalse(LinkedInPostIdentity.isLinkedInLoginWall(url(it)), it) }
    }

    @Test fun linkedinEntryHostsAcceptOnlyProviderHosts() {
        for (host in listOf("linkedin.com", "ae.linkedin.com", "www.linkedin.com"))
            assertTrue(LinkedInPostIdentity.isLinkedInPageEntry(assertNotNull(HttpsUrl.parse("https://$host/posts/synthetic-activity-123/"))))
        assertFalse(LinkedInPostIdentity.isLinkedInPageEntry(assertNotNull(HttpsUrl.parse("https://ae.linkedin.com.evil.com/posts/synthetic-activity-123/"))))
    }

    @Test fun linkedinHopTargetNormalisesOnlyWwwHost() {
        fun url(value: String) = assertNotNull(HttpsUrl.parse(value))
        assertEquals("https://www.linkedin.com/feed/update/urn:li:share:1/",
            LinkedInPostIdentity.linkedInHopTarget(url("https://www.linkedin.com/feed/update/urn%3Ali%3Ashare%3A1/")).toString())
        assertEquals("https://ae.linkedin.com/feed/update/urn%3Ali%3Ashare%3A1/",
            LinkedInPostIdentity.linkedInHopTarget(url("https://ae.linkedin.com/feed/update/urn%3Ali%3Ashare%3A1/")).toString())
    }

    @Test fun entryHostVariantsShareIdentityAndCleanWwwUrl() {
        val clean = "https://www.linkedin.com/posts/synthetic-activity-123/"
        val expected = assertNotNull(LinkedInPostIdentity.parse(clean))
        for (host in listOf("linkedin.com", "ae.linkedin.com", "uk.linkedin.com", "www.linkedin.com")) {
            val actual = assertNotNull(LinkedInPostIdentity.parse("https://$host/posts/synthetic-activity-123/?tracking=x#part"))
            assertTrue(actual.sameAs(expected))
            assertEquals(clean, actual.cleanUrl)
        }
        for (host in listOf("ae.linkedin.com.evil.com", "evil.linkedin.com", "a.linkedin.com", "123.linkedin.com"))
            assertNull(LinkedInPostIdentity.parse("https://$host/posts/synthetic-activity-123/"))
    }

    /** url-shapes: share/ugcPost URNs, the /embed/ form and %3A-encoded URNs name the same post. */
    @Test fun updateUrnShapesKeepTheirTypedId() {
        val cases = mapOf(
            "https://www.linkedin.com/feed/update/urn:li:share:7100000000000000001/" to "share:7100000000000000001",
            "https://www.linkedin.com/feed/update/urn:li:ugcPost:7100000000000000002" to "ugcPost:7100000000000000002",
            "https://www.linkedin.com/embed/feed/update/urn:li:share:7100000000000000001" to "share:7100000000000000001",
            "https://www.linkedin.com/embed/feed/update/urn:li:activity:7100000000000000003/" to "activity:7100000000000000003",
            "https://linkedin.com/feed/update/urn%3Ali%3Aactivity%3A7100000000000000003/?utm=x" to "activity:7100000000000000003",
            "https://www.linkedin.com/feed/update/urn%3ali%3AugcPost%3a7100000000000000002" to "ugcPost:7100000000000000002")
        for ((url, urn) in cases)
            assertEquals("https://www.linkedin.com/feed/update/urn:li:$urn/", LinkedInPostIdentity.parse(url)?.cleanUrl, url)
        val share = assertNotNull(LinkedInPostIdentity.parse(cases.keys.first()))
        assertTrue(share.matchesAttributed("urn:li:share:7100000000000000001"))
        assertTrue(share.sameAs(assertNotNull(LinkedInPostIdentity.parse("https://www.linkedin.com/posts/someone_title-share-7100000000000000001-AbCd/"))))
        // A bridged activity becomes the clean URL; the share link alone keeps its own URN.
        assertEquals("https://www.linkedin.com/feed/update/urn:li:activity:9/", share.withBridgedActivity("9").cleanUrl)
        for (url in listOf("https://www.linkedin.com/feed/update/urn:li:comment:7100000000000000001/",
            "https://www.linkedin.com/feed/update/urn%3Ali%3Ashare%253A7100000000000000001/",
            "https://www.linkedin.com/feed/update/urn%3Ali%3Ashare%3A7100000000000000001%2F/",
            "https://www.linkedin.com/embed/posts/someone-activity-1/", "https://www.linkedin.com/embedfeed/update/urn:li:share:1/",
            "https://evil.test/feed/update/urn:li:share:7100000000000000001/"))
            assertNull(LinkedInPostIdentity.parse(url), url)
    }
}
