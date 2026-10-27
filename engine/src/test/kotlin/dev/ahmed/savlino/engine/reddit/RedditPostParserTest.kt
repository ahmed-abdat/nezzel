package dev.ahmed.savlino.engine.reddit

import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy
import org.junit.Test
import kotlin.test.*

internal const val POST_ID = "1abcdef"
internal const val VIDEO_ID = "synthvid0001"
internal const val PERMALINK = "/r/synthetic/comments/$POST_ID/synthetic_video_post/"

/** The saved logged-out check page (2026-10-27), with its seed, token and target replaced. */
internal fun challengePage(action: String = PERMALINK): ByteArray =
    checkNotNull(RedditPostParserTest::class.java.getResource("/reddit/challenge.html")).readText()
        .replace("action=\"$PERMALINK\"", "action=\"$action\"").toByteArray()

/**
 * A post page shaped like Reddit's logged-out page (2026-10-27): attribute names, player and packaged-media JSON as
 * served, every value synthetic. A feed item of another post comes first, so only the requested post may count.
 */
internal fun postPage(type: String = "video", id: String = POST_ID, content: String = "https://v.redd.it/$VIDEO_ID",
    player: Boolean = type == "video", extra: String = "", title: String = "A synthetic &amp; quoted &quot;video&quot;"): ByteArray {
    val feed = """<shreddit-post permalink="/r/other/comments/9zzzzzz/other/" content-href="https://v.redd.it/othervid0001"
        id="t3_9zzzzzz" post-title="Other" post-type="video"><shreddit-player post-id="t3_9zzzzzz"
        src="https://v.redd.it/othervid0001/HLSPlaylist.m3u8?a=1"></shreddit-player></shreddit-post>"""
    val media = """{&quot;playbackMp4s&quot;:{&quot;duration&quot;:44,&quot;permutations&quot;:[]}}"""
    val playerTag = if (!player) "" else """<shreddit-player src="https://v.redd.it/$VIDEO_ID/HLSPlaylist.m3u8?f=sd%2CsubsAll&amp;v=1&amp;a=1795712867%2Csigned"
        class="block h-full w-full" autoplay caption-url="https://v.redd.it/$VIDEO_ID/wh_ben_en.vtt" packaged-media-json="$media"
        post-id="t3_$id" post-type="video" poster="https://external-preview.redd.it/synthetic-v0-abc.jpeg?width=640&amp;crop=smart&amp;s=00"
        preview="https://v.redd.it/$VIDEO_ID/CMAF_96.mp4"></shreddit-player>"""
    return """<!DOCTYPE html><html lang="en"><head><title>Reddit - The heart of the internet</title></head><body>
        <shreddit-app>$feed
        <shreddit-post class="block xs:mt-0" permalink="$PERMALINK" content-href="$content" view-context="CommentsPage"
          comment-count="3" is-embeddable created-timestamp="2026-10-27T04:35:30.184000+0000" domain="v.redd.it" id="t3_$id"
          post-title="$title" post-language="en" post-type="$type" score="1" subreddit-prefixed-name="r/synthetic"
          author="synthetic_author" $extra subreddit-name="synthetic">
          <div slot="post-media-container">$playerTag</div>
          <shreddit-post-text-body></shreddit-post-text-body>
        </shreddit-post></shreddit-app></body></html>""".toByteArray()
}

class RedditPostParserTest {
    private fun parse(page: ByteArray, id: String = POST_ID, video: String? = null) = RedditPostParser.parse(page, page.size, id, video)

    @Test fun aVideoPostNamesItsOwnDashManifestTitleAndPicture() {
        val video = assertIs<RedditPost.Video>(parse(postPage()))
        assertEquals("https://v.redd.it/$VIDEO_ID/DASHPlaylist.mpd", video.manifestUrl)
        assertEquals("A synthetic & quoted \"video\"", video.title)
        assertEquals("synthetic_author", video.author)
        assertEquals(44, video.durationSeconds)
        assertEquals("external-preview.redd.it", MediaHttpPolicy.parse(assertNotNull(video.thumbnailUrl))?.host)
        // A v.redd.it link must land on the post holding that same video.
        assertIs<RedditPost.Video>(parse(postPage(), video = VIDEO_ID))
        assertEquals(RedditPost.Unknown, parse(postPage(), video = "othervid0001"))
    }

    @Test fun textPhotoGalleryAndPollPostsHoldNoVideo() {
        for (type in listOf("text", "image", "gallery", "poll"))
            assertEquals(RedditPost.NoVideo, parse(postPage(type, content = "https://www.reddit.com$PERMALINK")), type)
    }

    @Test fun aLinkPostHandsOnItsOutsidePageButNeverAnotherRedditPage() {
        assertEquals("https://www.youtube.com/watch?v=aaaaaaaaaaa",
            assertIs<RedditPost.Link>(parse(postPage("link", content = "https://www.youtube.com/watch?v=aaaaaaaaaaa"))).url)
        for (inside in listOf("https://www.reddit.com/r/other/comments/9zzzzzz/", "https://i.redd.it/x.jpg", "http://example.com/"))
            assertEquals(RedditPost.Unknown, parse(postPage("link", content = inside)), inside)
    }

    @Test fun anAdultPostAsksForSignInAndUnknownShapesKeepTheOldPath() {
        assertEquals(RedditPost.LoginRequired, parse(postPage(extra = "nsfw")))
        assertEquals(RedditPost.Unknown, parse(postPage(player = false)))
        assertEquals(RedditPost.Unknown, parse(postPage("crosspost")))
        assertEquals(RedditPost.Unknown, parse(postPage(content = "https://v.redd.it/../x")))
        // Only the requested post counts: a feed item's video never stands in for a missing one.
        assertEquals(RedditPost.Unknown, parse(postPage(id = "2bbbbbb")))
        assertEquals(RedditPost.Unknown, parse(challengePage()))
    }
}
