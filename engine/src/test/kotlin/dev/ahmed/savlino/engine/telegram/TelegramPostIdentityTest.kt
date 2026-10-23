package dev.ahmed.savlino.engine.telegram

import org.junit.Test
import kotlin.test.*

/** url-shapes: the web preview and a forum topic message are the same public post. */
class TelegramPostIdentityTest {
    @Test fun previewAndForumShapesNameTheMessage() {
        val cases = mapOf(
            "https://t.me/synthetic_channel/123" to "synthetic_channel/123",
            "https://t.me/s/synthetic_channel/123" to "synthetic_channel/123",
            "https://telegram.me/s/synthetic_channel/123/?before=1" to "synthetic_channel/123",
            "https://t.me/synthetic_forum/5/456" to "synthetic_forum/456",
            "https://t.me/synthetic_forum/5/456/" to "synthetic_forum/456")
        for ((url, key) in cases) {
            val identity = assertNotNull(TelegramPostIdentity.parse(url), url)
            assertEquals(key, identity.postKey, url)
            assertEquals("https://t.me/$key?embed=1&mode=tme", identity.fetchUrl, url)
        }
    }

    @Test fun privateInviteAndOtherShapesStayRejected() {
        for (url in listOf("https://t.me/c/123/456", "https://t.me/c/123/5/456", "https://t.me/s/c/123", "https://t.me/+invite",
            "https://t.me/s/synthetic_channel", "https://t.me/share/123", "https://t.me/s/share/123",
            "https://t.me/synthetic_channel/123/extra", "https://t.me/synthetic_forum/0/456", "https://t.me/synthetic_forum/5/456/7",
            "https://t.me/x/synthetic_channel/123", "https://t.me.evil.test/s/synthetic_channel/123"))
            assertNull(TelegramPostIdentity.parse(url), url)
    }
}
