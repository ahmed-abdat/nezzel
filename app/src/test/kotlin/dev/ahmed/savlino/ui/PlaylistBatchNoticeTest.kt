package dev.ahmed.savlino.ui

import dev.ahmed.savlino.R
import org.junit.Assert.*
import org.junit.Test

class PlaylistBatchNoticeTest {
    @Test fun warnsOnlyWhenTheLinkWouldSaveTheFirstPlaylistVideo() {
        assertNull(playlistNotice("https://youtube.com/playlist?list=PLabcdefghij"))
        assertNull(playlistNotice("https://youtube.com/watch?v=abcdefghijk&list=PLabcdefghij"))
        assertNull(playlistNotice("https://youtu.be/abcdefghijk"))
        assertNull(playlistNotice("https://youtube.com/watch?v=abcdefghijk&list=WL"))
        assertNull(playlistNotice("https://youtu.be/abcdefghijk?list=LL"))
        assertEquals(R.string.playlist_mix_note, playlistNotice("https://youtube.com/playlist?list=RDabcdefghijk"))
        assertEquals(R.string.playlist_mix_note, playlistNotice("https://youtube.com/watch?v=abcdefghijk&list=RDabcdefghijk"))
        assertEquals(R.string.playlist_private_message, playlistNotice("https://youtube.com/playlist?list=WL"))
    }
}
