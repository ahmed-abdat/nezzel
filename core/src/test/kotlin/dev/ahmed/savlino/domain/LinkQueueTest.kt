package dev.ahmed.savlino.domain

import kotlin.test.*

class LinkQueueTest {
    @Test fun walksDistinctLinksInOrderAndEndsAfterLast() {
        val queue = LinkQueue.afterFirst(LinkInput.candidates(
            "https://first.org/a HTTPS://FIRST.ORG/a https://second.org/b https://third.org/c"))!!
        assertEquals(3, queue.total)
        assertEquals(2, queue.nextNumber)
        assertEquals("https://second.org/b", queue.next.rawUrl)
        val last = queue.advance()!!
        assertEquals(3, last.nextNumber)
        assertEquals("https://third.org/c", last.next.rawUrl)
        assertNull(last.advance())
    }
    @Test fun zeroOrOneCandidateHasNoRemainder() {
        assertNull(LinkQueue.afterFirst(emptyList()))
        assertNull(LinkQueue.afterFirst(LinkInput.candidates("https://first.org/a")))
    }
    @Test fun diagnosticsNeverContainLinks() {
        val queue = LinkQueue.afterFirst(LinkInput.candidates("https://first.org/a https://second.org/b?secret=private"))!!
        assertFalse(queue.toString().contains("https://"))
        assertFalse(queue.toString().contains("secret"))
    }
}
