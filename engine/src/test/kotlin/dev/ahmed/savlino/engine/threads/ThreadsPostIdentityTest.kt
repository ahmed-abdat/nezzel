package dev.ahmed.savlino.engine.threads

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import kotlin.test.*

class ThreadsPostIdentityTest {
    private val post = "@synthetic_user/post/SynthPost01"
    private val canonical = "https://www.threads.com/$post"

    @Test fun hostsAndPostPathsKeepTheUserAndCode() {
        for (url in listOf(
            "https://www.threads.com/$post",
            "https://threads.com/$post/",
            "https://www.threads.net/$post/media",
            "https://threads.net/$post?share=1#top",
            "https://WWW.THREADS.COM/$post",
            "https://www.threads.com/%40synthetic_user/post/SynthPost01")) {
            val identity = assertNotNull(ThreadsPostIdentity.parse(url), url)
            assertEquals("synthetic_user", identity.user, url)
            assertEquals("SynthPost01", identity.code, url)
            assertEquals(canonical, identity.fetchUrl, url)
        }
        assertTrue(ThreadsPostIdentity.isPageRequest(canonical.toHttpUrl()))
        assertFalse(ThreadsPostIdentity.isPageRequest("https://threads.net/$post".toHttpUrl()))
        assertFalse(ThreadsPostIdentity.isPageRequest("$canonical?share=1".toHttpUrl()))
        assertEquals("21664484729113730357", ThreadsPostIdentity.mediaId("SynthPost01"))
        assertEquals("1386527023068903329077", ThreadsPostIdentity.mediaId("SynthVid0001"))
    }

    @Test fun profilesAndOtherPathsStayRejected() {
        for (url in listOf(
            "https://www.threads.com/@synthetic_user",
            "https://www.threads.com/@synthetic_user/",
            "https://www.threads.com/@synthetic_user/post",
            "https://www.threads.com/@synthetic_user/replies",
            "https://www.threads.net/p/SynthPost01/",
            "https://www.threads.com/t/SynthPost01",
            "https://www.threads.com/@./post/SynthPost01",
            "https://www.threads.com/@../post/SynthPost01",
            "https://www.threads.com/@.user/post/SynthPost01",
            "https://www.threads.com/@user./post/SynthPost01",
            "https://threads.com.evil.example/$post",
            "https://www.instagram.com/$post",
            "http://www.threads.com/$post"))
            assertNull(ThreadsPostIdentity.parse(url), url)
        assertNull(ThreadsPostIdentity.mediaId("Synth+Post"))
    }
}
