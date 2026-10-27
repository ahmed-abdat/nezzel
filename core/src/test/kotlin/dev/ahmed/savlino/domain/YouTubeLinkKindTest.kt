package dev.ahmed.savlino.domain

import kotlin.test.*

class YouTubeLinkKindTest {
    @Test fun classifiesOnlyRecognizedYouTubeShapes() {
        val cases = mapOf(
            "https://www.youtube.com/watch?v=abcdefghijk" to YouTubeLinkKind.Video,
            "https://youtu.be/abcdefghijk" to YouTubeLinkKind.Video,
            "https://youtu.be/abcdefghijk?list=PLabcdefghij" to YouTubeLinkKind.VideoInList,
            "https://m.youtube.com/watch?v=abcdefghijk&list=PLabcdefghij" to YouTubeLinkKind.VideoInList,
            "https://www.youtube.com/playlist?list=PLabcdefghij" to YouTubeLinkKind.Playlist,
            "https://youtube.com/playlist?list=WL" to YouTubeLinkKind.PrivateList,
            "https://youtube.com/watch?v=abcdefghijk&list=LL" to YouTubeLinkKind.Video,
            "https://youtube.com/watch?v=abcdefghijk&list=WL" to YouTubeLinkKind.Video,
            "https://youtu.be/abcdefghijk?list=WL" to YouTubeLinkKind.Video,
            "https://youtu.be/abcdefghijk?list=LL" to YouTubeLinkKind.Video,
            "https://youtube.com/playlist?list=LL" to YouTubeLinkKind.PrivateList,
            "https://youtube.com/@fixture/videos" to YouTubeLinkKind.ChannelVideos,
            "https://youtube.com/channel/UCfixture/videos" to YouTubeLinkKind.ChannelVideos,
            "https://youtube.com/@fixture/shorts" to YouTubeLinkKind.Other,
            "https://youtube.com/@fixture/playlists" to YouTubeLinkKind.Other,
            "https://example.com/playlist?list=PLabcdefghij" to YouTubeLinkKind.Other,
            "https://youtube.com/playlist?list=junk" to YouTubeLinkKind.Other,
            "https://youtube.com/playlist?list=" to YouTubeLinkKind.Other,
            "https://youtube.com/watch?list=PLabcdefghij" to YouTubeLinkKind.Other,
            "https://youtube.com/playlist?list=PLabcdefghij&list=WL" to YouTubeLinkKind.Other,
            "junk" to YouTubeLinkKind.Other,
        )
        cases.forEach { (url, expected) -> assertEquals(expected, YouTubeLinkKind.classify(url), url) }
    }
    @Test fun everyRadioPrefixRequiresAVideoForTheMixNote() {
        // Plain RD + an 11-character video id is the one list that names its seed video (LinkInput aliases it).
        assertEquals(YouTubeLinkKind.Mix, YouTubeLinkKind.classify("https://youtube.com/playlist?list=RDabcdefghijk"))
        for (prefix in listOf("RDMM", "RDAMVM", "RDCLAK", "RDGMEM")) {
            assertEquals(YouTubeLinkKind.Other, YouTubeLinkKind.classify("https://youtube.com/playlist?list=${prefix}abcdefghijk"))
            assertEquals(YouTubeLinkKind.Mix, YouTubeLinkKind.classify("https://youtube.com/watch?v=abcdefghijk&list=${prefix}abcdefghijk"))
        }
    }
}
