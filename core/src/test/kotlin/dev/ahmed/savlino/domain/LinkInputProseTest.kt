package dev.ahmed.savlino.domain

import kotlin.test.*

class LinkInputProseTest {
    @Test fun proseDelimitersAndArabicPrefixesKeepTheIntendedLink() {
        val cases = listOf(
            "(https://youtu.be/clipA)" to "https://youtu.be/clipA",
            "الرابط:https://youtu.be/clipA" to "https://youtu.be/clipA",
            "شاهد https://youtu.be/clipA،" to "https://youtu.be/clipA",
            "شاهد https://youtu.be/clipA؛" to "https://youtu.be/clipA",
            "شاهد https://youtu.be/clipA؟" to "https://youtu.be/clipA",
            "Watch https://youtu.be/clipA." to "https://youtu.be/clipA",
            "[https://m.youtube.com/watch?v=clipA]" to "https://m.youtube.com/watch?v=clipA",
            "«https://vm.tiktok.com/clipA/»" to "https://vm.tiktok.com/clipA/",
            "(https://example.org/a_(b))" to "https://example.org/a_(b)",
        )
        cases.forEachIndexed { index, (input, expected) ->
            assertEquals(expected, LinkInput.parse(input)?.rawUrl, "prose case $index")
        }
    }

    @Test fun signedQueryBytesSurviveExtractionWithoutDecodingOrTrimming() {
        val raw = "HTTPS://YouTube.COM:443/Case/%2f?sig=A%2bB&z=2&z=1&token=x,+;!?:@()[]#Part."
        for (input in listOf(raw, "الرابط:$raw", "($raw)", "«$raw»")) {
            assertEquals(raw, LinkInput.parse(input)?.rawUrl)
        }
        // A closing parenthesis is legal in a signed query even without an opening one.
        assertEquals("https://youtu.be/clipA?sig=abc)", LinkInput.parse("https://youtu.be/clipA?sig=abc)")?.rawUrl)
    }

    @Test fun repeatedLinksResolveOnceAndDistinctLinksUseFirstValidCandidate() {
        assertEquals("https://youtu.be/clipA", LinkInput.parse("https://youtu.be/clipA https://youtu.be/clipA")?.rawUrl)
        assertEquals("https://youtu.be/clipA", LinkInput.parse("https://youtu.be/clipA https://youtu.be/clipB")?.rawUrl)
        assertEquals("https://youtu.be/clipA", LinkInput.parse("http://example.org/no https://localhost/no https://youtu.be/clipA")?.rawUrl)
        assertEquals(listOf("https://youtu.be/clipA", "https://youtu.be/clipB"),
            LinkInput.candidates("https://youtu.be/clipA HTTPS://YOUTU.BE/clipA https://youtu.be/clipB").map { it.rawUrl })
    }
}
