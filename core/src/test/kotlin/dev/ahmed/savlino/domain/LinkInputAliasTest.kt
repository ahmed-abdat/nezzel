package dev.ahmed.savlino.domain

import kotlin.test.*

class LinkInputAliasTest {
    @Test fun facebookOutboundWrapperBecomesItsTargetWithoutNetwork() {
        val wrapped = "https://l.facebook.com/l.php?u=https%3A%2F%2Fyoutu.be%2Fabcdefghijk%3Fsi%3Dx&h=AT0synthetic"
        assertEquals("https://youtu.be/abcdefghijk?si=x", LinkInput.parse(wrapped)?.rawUrl)
        assertEquals(Host.YOUTUBE, LinkInput.parse(wrapped)?.host)
        assertEquals("https://www.dailymotion.com/video/x1",
            LinkInput.parse("https://lm.facebook.com/l.php?u=http%3A%2F%2Fwww.dailymotion.com%2Fvideo%2Fx1")?.rawUrl)
    }

    @Test fun wrappedTargetsAreValidatedLikeAnyPastedLink() {
        for (target in listOf("ftp%3A%2F%2Fexample.org%2Fx", "javascript%3Aalert(1)", "https%3A%2F%2F127.0.0.1%2Fx",
                "https%3A%2F%2Flocalhost%2Fx", "%E0%A4%A", "https%3A%2F%2Fa.example%2F%20b"))
            assertNull(LinkInput.parse("https://l.facebook.com/l.php?u=$target"), target)
        // No single u= (missing or repeated): the wrapper stays as pasted.
        assertEquals("https://l.facebook.com/l.php?h=x", LinkInput.parse("https://l.facebook.com/l.php?h=x")?.rawUrl)
    }

    @Test fun fbComIsFacebookWithItsPathAndQueryKept() {
        assertEquals("https://www.facebook.com/watch/?v=123", LinkInput.parse("https://fb.com/watch/?v=123")?.rawUrl)
        assertEquals("https://www.facebook.com/reel/5", LinkInput.parse("http://www.fb.com/reel/5")?.rawUrl)
        assertEquals(Host.FACEBOOK, LinkInput.parse("https://fb.com/reel/5")?.host)
        assertEquals("https://fb.me/abc", LinkInput.parse("https://fb.me/abc")?.rawUrl)
    }
}
