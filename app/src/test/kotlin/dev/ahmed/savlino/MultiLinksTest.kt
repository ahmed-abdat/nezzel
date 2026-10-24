package dev.ahmed.savlino

import dev.ahmed.savlino.domain.*
import org.junit.Assert.*
import org.junit.Test

class MultiLinksTest {
    @Test fun oneOrNoLinkKeepsTheSingleFlow() {
        assertNull(MultiLinks.from(null))
        assertNull(MultiLinks.from("no links here"))
        assertNull(MultiLinks.from("look https://youtu.be/abcdefghijk"))
        assertNull(MultiLinks.from("https://youtu.be/abcdefghijk http://YOUTU.BE/abcdefghijk")) // same link twice
    }
    @Test fun twoOrMoreDistinctLinksKeepSourceOrder() {
        val found = MultiLinks.from("first https://www.tiktok.com/@a/video/1, then https://x.com/a/status/2 and https://www.tiktok.com/@a/video/1 again")!!
        assertEquals(listOf("https://www.tiktok.com/@a/video/1", "https://x.com/a/status/2"), found.links.map { it.rawUrl })
        assertFalse(found.truncated)
    }
    @Test fun moreThanFiftyTakesTheFirstFifty() {
        val text = (1..60).joinToString("\n") { "https://example.org/v/$it" }
        val found = MultiLinks.from(text)!!
        assertEquals(50, found.links.size); assertTrue(found.truncated)
        assertEquals("https://example.org/v/50", found.links.last().rawUrl)
        assertEquals(50, MultiLinks.from((1..50).joinToString(" ") { "https://example.org/v/$it" })!!.links.size)
        assertFalse(MultiLinks.from((1..50).joinToString(" ") { "https://example.org/v/$it" })!!.truncated)
    }
    @Test fun labelsShowHostAndShortPathButNeverTheQuery() {
        val link = LinkInput.parse("https://www.tiktok.com/@name/video/1234567890123456789?secret=token")!!
        assertEquals("tiktok.com", MultiLinkLabel.host(link))
        val short = MultiLinkLabel.short(link)
        assertTrue(short.startsWith("tiktok.com/@name/")); assertFalse("secret" in short); assertTrue(short.length <= 32)
        assertEquals("youtu.be/abcdefghijk", MultiLinkLabel.short(LinkInput.parse("https://youtu.be/abcdefghijk?si=x")!!))
        assertFalse(MultiLinks.from("https://a.org/1?t=secret https://b.org/2").toString().contains("secret"))
    }
}
