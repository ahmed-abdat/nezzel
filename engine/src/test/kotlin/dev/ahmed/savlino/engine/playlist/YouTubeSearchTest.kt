package dev.ahmed.savlino.engine.playlist

import dev.ahmed.savlino.engine.*
import kotlin.test.*

class YouTubeSearchTest {
    @Test fun wordsGoAfterTheSeparatorTrimmedAndCapped() {
        val args = YouTubeSearch.args("  --exec rm\n  -rf   ", 1)
        assertEquals(listOf("--", "ytsearch20:--exec rm -rf"), args.takeLast(2))
        assertEquals(args.lastIndexOf("--"), args.size - 2)
        assertTrue(args.containsAll(listOf("--ignore-config", "--flat-playlist", "--dump-single-json")))
        assertEquals("1:20", args[args.indexOf("--playlist-items") + 1])
        assertEquals("ytsearch40:${"ب".repeat(100)}", YouTubeSearch.args("ب".repeat(150), 21).last())
        assertEquals("21:40", YouTubeSearch.args("x", 21).let { it[it.indexOf("--playlist-items") + 1] })
        assertNull(YouTubeSearch.query(" \u0000\t "))
        assertFailsWith<IllegalArgumentException> { YouTubeSearch.args("   ", 1) }
        assertFailsWith<IllegalArgumentException> { YouTubeSearch.args("x", YouTubeSearch.MAX_RESULTS + 1) }
    }

    @Test fun keepsOnlyExactVideoIdsWithChannelAndLength() {
        fun row(id: String, extra: String = "") = """{"id":"$id","title":"T $id","duration":95$extra}"""
        val rows = (listOf(row("abcdefghij1", ",\"channel\":\"Chan\""), row("abcdefghij1"),
            row("UCabcdefghijklmnopqrstuv"), row("PLabcdefghijklmnopqrstuvwxyz012345"), row("bad id!!!!x"),
            row("abcdefghij2", ",\"uploader\":\"Up\",\"duration\":null")) + (3..19).map { row("abcdefghi%02d".format(it)) })
        val page = YouTubeSearch.parse("""{"_type":"playlist","entries":[${rows.joinToString(",")}]}""", 1)
        assertEquals(19, page.results.size)
        val first = page.results.first()
        assertEquals("https://www.youtube.com/watch?v=abcdefghij1", first.url)
        assertEquals("Chan", first.channel); assertEquals(95L, first.durationSeconds)
        assertEquals("Up", page.results[1].channel); assertNull(page.results[1].durationSeconds)
        assertTrue(page.hasMore) // 23 raw rows ≥ one page, even after drops
        assertFalse(YouTubeSearch.parse("""{"_type":"playlist","entries":[${row("abcdefghij1")}]}""", 1).hasMore)
        assertFalse(YouTubeSearch.parse("""{"_type":"playlist","entries":[${rows.joinToString(",")}]}""", 81).hasMore)
    }
}
