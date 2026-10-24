package dev.ahmed.savlino.engine.playlist

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PlaylistListerTest {
    private val source = LinkRevision(LinkInput.parse("https://youtube.com/playlist?list=PLabcdefghij")!!, 1)
    private fun flat(entries: String, count: Int = 87) = """{"_type":"playlist","title":"Fixture playlist","playlist_count":$count,"entries":[$entries]}"""
    private fun entry(n: Int, extra: String = "") = """{"id":"v${n.toString().padStart(10, '0')}","title":"Fixture $n","duration":60,"availability":"public","live_status":"not_live"$extra}"""

    @Test fun readsThe87EntryFlatShapeAndKeepsUnknownSizesOutOfTheModel(): Unit = runBlocking {
        val lister = PlaylistLister({ _, _, _ -> null }, { _, start, end ->
            assertEquals(1, start); assertEquals(100, end)
            flat((1..87).joinToString(",") { entry(it) })
        })
        val page = assertIs<PlaylistResult.Success>(lister.list(source)).page
        assertEquals(87, page.items.size)
        assertEquals(87L, page.totalCount)
        assertEquals("v0000000001", page.items.first().id)
        assertTrue(page.items.all { it.selectable && it.durationSeconds == 60L })
        assertNull(page.next)
        assertFailsWith<UnsupportedMetadataException> { YtdlpMetadataMapper.map(flat(entry(1)), source, "fixture") }
    }

    @Test fun unavailableRowsStayVisibleButCannotBeSelectedAndDuplicatesAppearOnce(): Unit = runBlocking {
        val json = flat(listOf(entry(1), entry(1),
            entry(2, ",\"availability\":\"private\""),
            entry(3, ",\"title\":\"[Deleted video]\""),
            entry(4, ",\"live_status\":\"is_live\""),
            entry(5, ",\"duration\":0"),
            entry(6, ",\"live_status\":\"is_upcoming\""),
            entry(7, ",\"availability\":\"subscriber_only\"" )).joinToString(","), 7)
        val page = assertIs<PlaylistResult.Success>(PlaylistLister({ _, _, _ -> null }, { _, _, _ -> json }).list(source)).page
        assertEquals(7, page.items.size)
        assertEquals(listOf(null, PlaylistUnavailable.Private, PlaylistUnavailable.Unavailable, PlaylistUnavailable.Live,
            PlaylistUnavailable.Unavailable, PlaylistUnavailable.Live, PlaylistUnavailable.Private), page.items.map { it.unavailable })
    }

    @Test fun pagingTokenPreservesOffsetsAndDeduplicatesAcrossPages(): Unit = runBlocking {
        val lister = PlaylistLister({ _, _, _ -> null }, { _, start, end ->
            when (start) {
                1 -> { assertEquals(100, end); flat((1..100).joinToString(",") { entry(it) }, 101) }
                else -> { assertEquals(101, start); assertEquals(200, end); flat(entry(100) + "," + entry(101), 101) }
            }
        })
        val first = assertIs<PlaylistResult.Success>(lister.list(source)).page
        val second = assertIs<PlaylistResult.Success>(lister.list(source, first.next)).page
        assertEquals(listOf("v0000000101"), second.items.map { it.id })
        assertNull(second.next)
    }

    @Test fun nativeContinuationFailureRestartsFlatFromOneWithoutDroppingUnseenRows(): Unit = runBlocking {
        val lister = PlaylistLister({ _, token, _ ->
            if (token != null) throw IllegalStateException()
            PlaylistPage("Fixture", listOf(PlaylistItem("v0000000002", "https://youtube.com/watch?v=v0000000002", "Fixture", 60)),
                101, PlaylistPageToken(source.link.normalizedUrl, 1, org.schabi.newpipe.extractor.Page("https://youtube.com/continuation")))
        }, { _, start, end ->
            if (start == 1) {
                assertEquals(100, end)
                flat((1..100).joinToString(",") { entry(it) }, 101)
            } else {
                assertEquals(101, start); assertEquals(200, end)
                flat(entry(101), 101)
            }
        })
        val first = assertIs<PlaylistResult.Success>(lister.list(source)).page
        val second = assertIs<PlaylistResult.Success>(lister.list(source, first.next)).page
        val third = assertIs<PlaylistResult.Success>(lister.list(source, second.next)).page
        val ids = (first.items + second.items + third.items).map { it.id }
        assertEquals(101, ids.size)
        assertEquals((1..101).map { "v${it.toString().padStart(10, '0')}" }.toSet(), ids.toSet())
        assertNull(third.next)
    }

    @Test fun npeFailureFallsBackAndMixPrivateAndChannelsNeverFetch(): Unit = runBlocking {
        val lister = PlaylistLister({ _, _, _ -> throw IllegalStateException() }, { _, _, _ -> flat(entry(1), 1) })
        assertEquals(1, assertIs<PlaylistResult.Success>(lister.list(source)).page.items.size)
        val blocked = PlaylistLister({ _, _, _ -> error("Must not fetch") }, { _, _, _ -> error("Must not fetch") })
        for ((url, error) in listOf("https://youtube.com/watch?v=abcdefghijk&list=RDabcdefghijk" to DownloadError.Unsupported,
            "https://youtube.com/playlist?list=WL" to DownloadError.LoginRequired,
            "https://youtube.com/@fixture/videos" to DownloadError.Unsupported)) {
            assertEquals(error, assertIs<PlaylistResult.Failure>(blocked.list(LinkRevision(LinkInput.parse(url)!!, 1))).error)
        }
    }
}
