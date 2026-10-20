package dev.ahmed.savlino.engine.mediaimage

import org.junit.Test
import kotlin.test.*

class PostTextTest {
    @Test fun everyLinkLikeTokenIsRemovedBeforeItCanBecomeAFileName() {
        for (link in listOf("192.0.2.1:8080/path", "192.0.2.1", "mailto:x@y.z", "x@y.z", "//host/p", "host.local:8443",
            "localhost:8080/x", "[2001:db8::1]:443/x", "https://e.org/a", "(https://e.org/a).", "<www.example.org/x?y#z>",
            "example.org", "lnkd.in/abc", "tel:+123", "javascript:alert(1)", "data:text/plain,hi", "HTTPS://E.ORG"))
            assertEquals("Title end", PostText.firstLine("Title $link end"), link)
    }

    @Test fun ordinaryWordsTimesAndVersionsStay() {
        assertEquals("Launch day: photos at 10:30 (v1.2) e.g. U.S.", PostText.firstLine("Launch day: photos at 10:30 (v1.2) e.g. U.S."))
        assertEquals("first line", PostText.firstLine("\n   first line\nsecond https://x.org"))
        assertNull(PostText.firstLine("https://only.example/link"))
        assertNull(PostText.firstLine(null))
        // A control or zero-width character glued to a link cannot hide it.
        assertEquals("Caption", PostText.firstLine("Caption \u0001https://e.org/x"))
        assertEquals("Caption", PostText.firstLine("Caption \u200Bwww.e.org/x"))
    }
}
