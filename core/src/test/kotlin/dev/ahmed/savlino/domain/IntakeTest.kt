package dev.ahmed.savlino.domain

import kotlin.test.*

class IntakeTest {
    // These first two tests preserve the 9 parser + 4 suggestion legacy assertions.
    @Test fun legacyParserParity() {
        assertEquals("https://example.org/video?a=1", LinkInput.parse("Watch this https://example.org/video?a=1")?.rawUrl)
        assertEquals("https://example.org/watch", LinkInput.parse("https://example.org/watch")?.rawUrl)
        listOf("file:///private/video.mp4", "javascript:alert(1)", "https://user:secret@example.org/video",
            "https://", null, "ordinary clipboard words")
            .forEach { assertNull(LinkInput.parse(it)) }
    }

    @Test fun legacySuggestionParity() {
        val gate = SuggestionGate()
        assertNull(gate.offer("https://example.org/a", focused = false))
        assertEquals("https://example.org/a", gate.offer("https://example.org/a")?.rawUrl)
        assertNull(gate.offer("https://example.org/a"))
        assertEquals("https://example.org/b", gate.offer("https://example.org/b")?.rawUrl)
    }

    @Test fun rejectUnsafeAuthoritiesWithoutResolvingDns() {
        listOf("http://example.org", "https://localhost", "https://LOCALHOST.", "https://printer.local",
            "https://a.internal", "https://intranet", "https://127.0.0.1", "https://192.168.1.1",
            "https://[::1]", "https://2130706433", "https://0x7f000001", "https://0177.0.0.1",
            "https://example.org:0", "https://example.org:65536", "https://example.org:",
            "https://user@example.org", "https://%65xample.org", "https://example..org",
            "https://example.org\\@evil.com", "https://مثال.إختبار", "https://xn--mgbh0fb.xn--kgbechtv")
            .forEach { assertNull(LinkInput.parse(it), it) }
    }

    @Test fun idnHostsAreRejectedEvenWithAnOtherwisePublicAsciiTld() {
        assertTrue(HostDetector.isPublicDnsHost("example.com"))
        for (host in listOf("xn--mgbh0fb.com", "مثال.com")) {
            // Exercise the host policy directly: URI.host rejects Unicode even before this rule.
            assertFalse(HostDetector.isPublicDnsHost(host), host)
            assertNull(LinkInput.parse("https://$host/video"))
        }
    }

    @Test fun preserveSignedBytesAndOnlyNormalizeSchemeAndHost() {
        val raw = "HTTPS://YouTube.COM:443/Case/%2f?sig=A%2bB&z=2&z=1#Part"
        val link = assertNotNull(LinkInput.parse(raw))
        assertEquals(raw, link.rawUrl)
        assertEquals("https://youtube.com:443/Case/%2f?sig=A%2bB&z=2&z=1#Part", link.normalizedUrl)
        assertEquals(Host.YOUTUBE, link.host)
        assertEquals(Host.YOUTUBE, LinkInput.parse("https://m.youtube.com/a")?.host)
        assertEquals(Host.OTHER, LinkInput.parse("https://youtube.com.evil.org/a")?.host)
    }

    @Test fun boundariesAndAmbiguousWrappedInput() {
        assertNotNull(LinkInput.parse("https://example.org/" + "x".repeat(8172)))
        assertNull(LinkInput.parse("https://example.org/" + "x".repeat(8173)))
        assertEquals("https://example.org/a", LinkInput.parse("https://example.org/a http://example.org/b")?.rawUrl)
        assertEquals("https://example.org/a", LinkInput.parse("https://example.org/a javascript:alert(1)")?.rawUrl)
        assertNull(LinkInput.parse("https://example.org/a%xx"))
        assertEquals("https://example.org/a", LinkInput.parse("(https://example.org/a)")?.rawUrl)
        assertNull(LinkInput.parse("https://example.org/a\u0000"))
        assertEquals("https://example.org/a", LinkInput.parse("عنوان\nhttps://example.org/a")?.rawUrl)
    }

    @Test fun titleWithTrailingColonIsNotMistakenForAnotherUrl() {
        assertEquals("https://example.org/a", LinkInput.parse("Watch this: https://example.org/a")?.rawUrl)
        assertEquals("https://example.org/a", LinkInput.parse("Title: https://example.org/a")?.rawUrl)
    }

    @Test fun gateBlocksEveryIneligibleLifecycleAndIntakeState() {
        val gate = SuggestionGate()
        val url = "https://example.org/a"
        assertNull(gate.offer(url, resumed = false))
        assertNull(gate.offer(url, foreground = false))
        assertNull(gate.offer(url, fieldEmpty = false))
        assertNull(gate.offer(url, shareActive = true))
        assertNotNull(gate.offer(url))
    }

    @Test fun dismissedNormalizedUrlStillOffersQueryAndFragmentVariants() {
        val gate = SuggestionGate()
        gate.dismiss(assertNotNull(LinkInput.parse("https://EXAMPLE.org/a?item=1#first")))
        assertNull(gate.offer("https://example.org/a?item=1#first"))
        assertEquals("https://example.org/a?item=2#first", gate.offer("https://example.org/a?item=2#first")?.rawUrl)
        assertEquals("https://example.org/a?item=1#second", gate.offer("https://example.org/a?item=1#second")?.rawUrl)
    }

    @Test fun dismissedLinksStayDismissedAcrossOtherOffersAndGateRecreation() {
        val gate = SuggestionGate()
        gate.dismiss(assertNotNull(LinkInput.parse("https://EXAMPLE.org/a")))
        assertNotNull(gate.offer("https://example.org/b"))
        gate.dismiss(assertNotNull(LinkInput.parse("https://example.org/c")))
        assertNull(gate.offer("https://example.org/a"))
        assertNull(SuggestionGate(gate.dismissedHashes()).offer("https://example.org/c"))
        assertTrue(gate.dismissedHashes().all { it.matches(Regex("[0-9a-f]{64}")) })
    }
}
