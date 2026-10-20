package dev.ahmed.savlino.engine.linkedin

import org.junit.Test
import kotlin.test.*

class LinkedInPostIdentityTest {
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
}
