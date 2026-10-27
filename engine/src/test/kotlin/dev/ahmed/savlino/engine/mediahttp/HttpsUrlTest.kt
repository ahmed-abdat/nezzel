package dev.ahmed.savlino.engine.mediahttp

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HttpsUrlTest {
    @Test fun fragmentsAreStrippedWithoutChangingPathOrQueryBytes() {
        val raw = "https://media.test/a%2Fb.jpg?q=%2f&x=1"
        assertEquals(raw, HttpsUrl.parse("$raw#fragment")?.toString())
        val base = assertNotNull(HttpsUrl.parse("https://media.test/base"))
        assertEquals(raw, HttpsUrl.resolve(base, "/a%2Fb.jpg?q=%2f&x=1#fragment")?.toString())
    }
}
