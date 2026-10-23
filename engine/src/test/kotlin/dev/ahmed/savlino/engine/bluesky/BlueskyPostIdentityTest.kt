package dev.ahmed.savlino.engine.bluesky

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import kotlin.test.*

/** url-shapes: did:web actors and the post's likes/reposted-by/quotes views are the post itself. */
class BlueskyPostIdentityTest {
    private val plc = "did:plc:abcdefghijklmnopqrstuvwx"

    @Test fun acceptedShapesKeepActorAndRkey() {
        val cases = mapOf(
            "https://bsky.app/profile/synthetic.test/post/3synthetic001/likes" to "synthetic.test",
            "https://bsky.app/profile/synthetic.test/post/3synthetic001/reposted-by" to "synthetic.test",
            "https://bsky.app/profile/$plc/post/3synthetic001/quotes/" to plc,
            "https://bsky.app/profile/did:web:synthetic.example.org/post/3synthetic001" to "did:web:synthetic.example.org",
            "https://bsky.app/profile/did:web:synthetic.test/post/3synthetic001/likes" to "did:web:synthetic.test")
        for ((url, actor) in cases) {
            val identity = assertNotNull(BlueskyPostIdentity.parse(url), url)
            assertEquals(actor, identity.actor, url)
            assertEquals("3synthetic001", identity.rkey, url)
        }
        val web = assertNotNull(BlueskyPostIdentity.parse(cases.keys.elementAt(3)))
        assertEquals("did:web:synthetic.example.org", web.did) // no handle resolution
        assertTrue(BlueskyPostIdentity.isApiRequest(web.threadUrl(web.did!!).toHttpUrl()))
    }

    @Test fun rejectedShapesStayRejected() {
        for (url in listOf("https://bsky.app/profile/synthetic.test", "https://bsky.app/profile/synthetic.test/post/3synthetic001/replies",
            "https://bsky.app/profile/synthetic.test/post/3synthetic001/likes/extra", "https://bsky.app/profile/did:web:localhost/post/3synthetic001",
            "https://bsky.app/profile/did:web:evil.test%3A8080/post/3synthetic001", "https://bsky.app/profile/did:web:Evil.Test/post/3synthetic001",
            "https://bsky.app/profile/did:key:z6Mk/post/3synthetic001", "https://bsky.app/profile/localhost/post/3synthetic001",
            "https://bsky.app.evil.test/profile/synthetic.test/post/3synthetic001"))
            assertNull(BlueskyPostIdentity.parse(url), url)
        assertFalse(BlueskyPostIdentity.isApiRequest(("https://public.api.bsky.app/xrpc/app.bsky.feed.getPostThread" +
            "?uri=at%3A%2F%2Fdid%3Aweb%3Alocalhost%2Fapp.bsky.feed.post%2F3s&depth=0").toHttpUrl()))
    }
}
