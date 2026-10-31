package dev.ahmed.savlino.engine.playlist

import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.engine.youtube.NpeSearch
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
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

    @Test fun mapsEachKindAndDropsRejectedIds() {
        val video = stream("https://www.youtube.com/watch?v=abcdefghijk", "Title", "Chan", 95,
            "https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg")
        val mapped = NpeSearch.item(video, SearchFilter.VIDEOS)!!
        assertEquals(SearchKind.VIDEO, mapped.kind)
        assertEquals("abcdefghijk", mapped.id)
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", mapped.url)
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/mqdefault.jpg", mapped.thumbnailUrl)
        assertEquals("Chan", mapped.channel)
        assertEquals(95L, mapped.durationSeconds)

        val song = NpeSearch.item(stream("https://music.youtube.com/watch?v=bbcdefghijk", "Song", "Artist", 200),
            SearchFilter.MUSIC)!!
        assertEquals(SearchKind.VIDEO, song.kind)
        assertEquals("https://www.youtube.com/watch?v=bbcdefghijk", song.url)

        val listId = "PLabcdefghijklmnopqrstuvwxyz012345"
        val list = NpeSearch.item(playlist("https://www.youtube.com/playlist?list=$listId", "List", "Owner", 12,
            "https://yt3.ggpht.com/a=s176"), SearchFilter.PLAYLISTS)!!
        assertEquals(SearchKind.PLAYLIST, list.kind)
        assertEquals(listId, list.id)
        assertEquals("https://www.youtube.com/playlist?list=$listId", list.url)
        assertEquals("Owner", list.channel)
        assertEquals(12, list.videoCount)
        assertEquals("https://yt3.ggpht.com/a=s176", list.thumbnailUrl)

        val channelId = "UCabcdefghijklmnopqrstuv"
        val channel = NpeSearch.item(channel("https://www.youtube.com/channel/$channelId", "Name",
            "https://yt3.googleusercontent.com/avatar=s176"), SearchFilter.CHANNELS)!!
        assertEquals(SearchKind.CHANNEL, channel.kind)
        assertEquals("https://www.youtube.com/channel/$channelId/videos", channel.url)
        assertEquals("https://yt3.googleusercontent.com/avatar=s176", channel.thumbnailUrl)
        assertNull(NpeSearch.item(channel("https://www.youtube.com/channel/$channelId", "Name",
            "https://yt3.googleusercontent.com/avatar=s176"), SearchFilter.CHANNELS)!!.channel)

        assertNull(NpeSearch.item(stream("https://www.youtube.com/watch?v=short", "T", "C", 1), SearchFilter.VIDEOS))
        assertNull(NpeSearch.item(stream("https://www.youtube.com/shorts/abcdefghijk", "T", "C", 1), SearchFilter.VIDEOS))
        assertNull(NpeSearch.item(stream("https://www.youtube.com/watch?v=abcdefghijk&list=RDabcdefghijk", "T", "C", 1), SearchFilter.VIDEOS))
        assertNull(NpeSearch.item(stream("https://www.youtube.com/watch?v=abcdefghijk", "T", "C", 1).apply { isShortFormContent = true }, SearchFilter.VIDEOS))
        assertNull(NpeSearch.item(stream("https://www.youtube.com/watch?v=abcdefghijk", "Live", "C", 1, type = StreamType.LIVE_STREAM), SearchFilter.VIDEOS))
        assertNull(NpeSearch.item(stream("https://evil.example/watch?v=abcdefghijk", "Ad", "C", 1), SearchFilter.VIDEOS))
        assertNull(NpeSearch.item(playlist("https://www.youtube.com/playlist?list=$listId", "List", "Owner", 3, null), SearchFilter.VIDEOS))
        val mix = playlist("https://www.youtube.com/playlist?list=RDabcdefghijk", "Mix", "YouTube", 10, null)
            .apply { playlistType = PlaylistInfo.PlaylistType.MIX_STREAM }
        assertNull(NpeSearch.item(mix, SearchFilter.PLAYLISTS))
        assertNull(NpeSearch.item(playlist("https://www.youtube.com/playlist?list=not-a-list", "Bad", "O", 1, null), SearchFilter.PLAYLISTS))
        assertNull(NpeSearch.item(playlist("https://www.youtube.com/playlist?list=$listId", "List", "Owner", -1,
            "https://evil.example/a.jpg"), SearchFilter.PLAYLISTS)!!.let { it.videoCount ?: it.thumbnailUrl })
        assertNull(NpeSearch.item(channel("https://www.youtube.com/channel/UCshort", "Name", null), SearchFilter.CHANNELS))
        assertNull(NpeSearch.item(channel("https://www.youtube.com/@name", "Name", null), SearchFilter.CHANNELS))
    }

    private fun stream(url: String, name: String, uploader: String, seconds: Long, thumb: String? = null,
        type: StreamType = StreamType.VIDEO_STREAM) = StreamInfoItem(0, url, name, type).apply {
        uploaderName = uploader
        duration = seconds
        thumb?.let { thumbnails = listOf(Image(it, Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.MEDIUM)) }
    }

    private fun playlist(url: String, name: String, uploader: String, count: Long, thumb: String?) =
        PlaylistInfoItem(0, url, name).apply {
            uploaderName = uploader
            streamCount = count
            thumb?.let { thumbnails = listOf(Image(it, Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.LOW)) }
        }

    private fun channel(url: String, name: String, thumb: String?) = ChannelInfoItem(0, url, name).apply {
        thumb?.let { thumbnails = listOf(Image(it, Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.MEDIUM)) }
    }
}
