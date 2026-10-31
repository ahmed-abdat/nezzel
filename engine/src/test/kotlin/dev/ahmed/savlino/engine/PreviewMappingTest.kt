package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import kotlin.test.*

class PreviewMappingTest {
    private val source = LinkRevision(LinkInput.parse("https://example.org/video")!!, 1)
    private fun map(fields: String) = YtdlpMetadataMapper.map("{\"formats\":[],$fields}", source, "v1")
    @Test fun previewUsesRealFieldsAndFractionalDuration() {
        val result = map(""""title":"عنوان 🌊","fulltitle":"fallback","uploader":"قناة","channel":"fallback",
            "duration":225.9,"thumbnail":"https://i.ytimg.com/preview.jpg"""")
        assertEquals("عنوان 🌊", result.title); assertEquals("قناة", result.uploader)
        assertEquals(226L, result.durationSeconds); assertEquals("https://i.ytimg.com/preview.jpg", result.thumbnailUrl)
        for ((seconds, expected) in listOf(18.4 to 18L, 18.5 to 19L, 634.9 to 635L, 59.5 to 60L)) {
            assertEquals(expected, map("\"duration\":$seconds").durationSeconds)
        }
    }
    @Test fun htmlEntitiesInTitleAndUploaderAreDecodedOnceAndStillCleaned() {
        val result = map(""""title":"commence!! &#128074;&#128075; | Beast Camp Training",
            "uploader":"A &amp; B &quot;x&quot; &#39;y&#39; &lt;z&gt; &#x1F44B;&#7; &amp;#128074;"""")
        assertEquals("commence!! \uD83D\uDC4A\uD83D\uDC4B | Beast Camp Training", result.title)
        assertEquals("A & B \"x\" 'y' <z> \uD83D\uDC4B &#128074;", result.uploader)
    }
    @Test fun tiktokEmojiEscapedTwiceShowAsEmoji() {
        val result = map(""""extractor_key":"TikTok","title":"warrior &amp;#128558;\u200d&amp;#128168;&amp;#x1F602; #fyp"""")
        assertEquals("warrior \uD83D\uDE2E\u200D\uD83D\uDCA8\uD83D\uDE02 #fyp", result.title)
        // Not a code point: kept as written.
        assertEquals("a &#1114112; &#xD800; b", map(""""extractor_key":"TikTok","title":"a &amp;#1114112; &amp;#xD800; b"""").title)
    }
    @Test fun facebookEngagementPrefixIsStrippedOnlyForFacebookExactShape() {
        fun fb(title: String) = map(""""extractor_key":"Facebook","title":"$title"""").title
        assertEquals("When your trying to help | Beast Camp Training",
            fb("9.7K views &#xb7; 352 reactions | When your trying to help | Beast Camp Training"))
        assertEquals("Asif Nawab Butt on Reels", fb("119 reactions · 1.4K shares | Asif Nawab Butt on Reels"))
        assertEquals("Reel", map(""""extractor_key":"FacebookReel","title":"1 view · 1 reaction | Reel"""").title)
        // Not the exact two-count shape, not Facebook, or nothing left: kept as-is.
        for (kept in listOf("10 views | Paris", "1,204 views | Real title", "5 plays | Clip", "9.7K views · 352 reactions",
            "Top 10 views | Paris", "9.7K views · 352 reactions · Title", "views · 3 reactions | Title",
            "9.7K views · 352 reactions | ", "352 reactions · 9.7K views | Title", "2024 | 10 views · 3 reactions | Recap",
            "100 views · 5 comments | Live")) assertEquals(kept.trim(), fb(kept))
        assertEquals("9.7K views · 352 reactions | Title",
            map(""""extractor_key":"Youtube","title":"9.7K views · 352 reactions | Title"""").title)
    }
    @Test fun absentHostileAndWrongTypedFieldsStayUnknown() {
        for (fields in listOf("\"title\":null", """"title":123,"uploader":{},"duration":-5,"thumbnail":"file:///etc/passwd"""",
            """"duration":1e100,"thumbnail":"https://user@example.org/a"""")) {
            val result = map(fields)
            assertNull(result.title); assertNull(result.uploader); assertNull(result.durationSeconds); assertNull(result.thumbnailUrl)
        }
    }
    @Test fun fallbackFieldsAndBestValidThumbnailAreUsed() {
        val result = map(""""title":"\u202e","fulltitle":"Full title","uploader":" ","channel":"Channel",
            "duration":0,"thumbnail":"http://example.org/no","thumbnails":[
                {"url":"https://i.ytimg.com/small.jpg","width":120},
                {"url":"https://localhost/hostile.jpg","width":2000},
                {"url":"https://i.ytimg.com/large.jpg","width":1280}]
        """)
        assertEquals("Full title", result.title); assertEquals("Channel", result.uploader)
        assertNull(result.durationSeconds); assertEquals("https://i.ytimg.com/large.jpg", result.thumbnailUrl)
    }
    @Test fun thumbnailFallbackPrefersLargestBoundedWidthOverOversizeAndUnknown() {
        val result = map(""""thumbnails":[
            {"url":"https://i.ytimg.com/unknown.jpg"},
            {"url":"https://i.ytimg.com/720.jpg","width":720},
            {"url":"https://i.ytimg.com/1280.jpg","width":1280},
            {"url":"https://i.ytimg.com/4096.jpg","width":4096}]
        """)
        assertEquals("https://i.ytimg.com/1280.jpg", result.thumbnailUrl)
        assertNull(map(""""thumbnails":[{"url":"https://i.ytimg.com/oversize.jpg","width":1281}]""").thumbnailUrl)
    }
    @Test fun unknownWidthFallbackUsesLastCandidateAndTopLevelStillWins() {
        val candidates = """"thumbnails":[{"url":"https://i.ytimg.com/first.jpg","width":0},
            {"url":"https://i.ytimg.com/last.jpg"}]"""
        assertEquals("https://i.ytimg.com/last.jpg", map(candidates).thumbnailUrl)
        assertEquals("https://i.ytimg.com/main.jpg",
            map(""""thumbnail":"https://i.ytimg.com/main.jpg",$candidates""").thumbnailUrl)
    }
}
