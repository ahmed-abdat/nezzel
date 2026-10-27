package dev.ahmed.savlino.engine.reddit

import org.junit.Test
import kotlin.test.*

class RedditPostIdentityTest {
    private fun read(raw: String) = RedditPostIdentity.parse(raw)?.let { Triple(it.fetchUrl, it.postId, it.videoId) }

    @Test fun postLinksReadOneWwwPageWithoutTheirQuery() {
        val page = "https://www.reddit.com$PERMALINK"
        for (raw in listOf(page, "https://reddit.com$PERMALINK?utm_source=share&utm_medium=web", "https://old.reddit.com$PERMALINK",
            "https://m.reddit.com${PERMALINK.trimEnd('/')}", "https://www.reddit.com${PERMALINK}c0mment1/"))
            assertEquals(Triple(page, POST_ID, null), read(raw), raw)
        assertEquals(Triple("https://www.reddit.com/user/someone/comments/$POST_ID/a_post/", POST_ID, null),
            read("https://www.reddit.com/user/someone/comments/$POST_ID/a_post"))
        // Without a slug Reddit serves a shell; the bare id page redirects to the full permalink.
        for (raw in listOf("https://www.reddit.com/r/synthetic/comments/$POST_ID/", "https://www.reddit.com/comments/$POST_ID",
            "https://redd.it/$POST_ID"))
            assertEquals(Triple("https://www.reddit.com/comments/$POST_ID/", POST_ID, null), read(raw), raw)
    }

    @Test fun shareAndVideoLinksWaitForTheirRedirect() {
        assertEquals(Triple("https://www.reddit.com/r/synthetic/s/AbC123xyz/", null, null), read("https://www.reddit.com/r/synthetic/s/AbC123xyz"))
        for (raw in listOf("https://v.redd.it/$VIDEO_ID", "https://www.reddit.com/video/$VIDEO_ID/"))
            assertEquals(Triple("https://www.reddit.com/video/$VIDEO_ID/", null, VIDEO_ID), read(raw), raw)
    }

    @Test fun otherHostsAndPagesAreNotPosts() {
        for (raw in listOf("http://www.reddit.com$PERMALINK", "https://www.reddit.com:8443$PERMALINK", "https://reddit.com.evil.test$PERMALINK",
            "https://evilreddit.com$PERMALINK", "https://www.reddit.com/r/synthetic/", "https://www.reddit.com/login/",
            "https://i.redd.it/abc.jpg", "https://www.reddit.com/r/synthetic/comments/UPPER/", "not a link"))
            assertNull(read(raw), raw)
    }
}
