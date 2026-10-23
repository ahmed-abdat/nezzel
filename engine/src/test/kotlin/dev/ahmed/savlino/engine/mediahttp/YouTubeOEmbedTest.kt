package dev.ahmed.savlino.engine.mediahttp

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class YouTubeOEmbedTest {
    private val link = "https://youtu.be/abcdefghijk"
    private val thumbnail = "https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg"
    private fun response(token: OperationToken, body: String): MediaHttpResult = MediaHttpResult.Success(
        MediaHttpResponse("application/json", body.toByteArray().size.toLong(),
            MediaHttpTarget("https://www.youtube.com/oembed", token.id, MediaHttpPurpose.PAGE), body.toByteArray()))

    @Test fun successReturnsOnlyRealTitleAndThumbnail() = runBlocking {
        MediaHttpClient.create().use { http ->
            val loader = YouTubeOEmbed(http) { token, url ->
                assertEquals("https://www.youtube.com/oembed?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3Dabcdefghijk&format=json", url)
                response(token, """{"title":"Actual title","thumbnail_url":"$thumbnail","html":"ignored"}""")
            }
            val preview = loader.load(link)!!
            assertEquals("Actual title", preview.title)
            assertEquals(thumbnail, preview.thumbnailUrl)
            assertEquals("EarlyPreview(redacted)", preview.toString())
        }
    }

    @Test fun httpFailureMalformedAndOversizeAreIgnored() = runBlocking {
        MediaHttpClient.create().use { http ->
            for (body in listOf("not JSON", "x".repeat(32 * 1024 + 1))) {
                assertNull(YouTubeOEmbed(http) { token, _ -> response(token, body) }.load(link))
            }
            assertNull(YouTubeOEmbed(http) { _, _ -> MediaHttpResult.Failure(MediaHttpFailure.HTTP_STATUS, 404) }.load(link))
        }
    }

    @Test fun nonYoutubeNeverFetchesAndUntrustedThumbnailIsDropped() = runBlocking {
        MediaHttpClient.create().use { http ->
            val loader = YouTubeOEmbed(http) { token, _ ->
                response(token, """{"title":"Real","thumbnail_url":"https://evil.example/image.jpg"}""")
            }
            assertNull(loader.load("https://youtube.com.evil.example/watch?v=abcdefghijk"))
            assertNull(loader.load("https://example.com/video"))
            assertNull(loader.load("https://user@www.youtube.com/watch?v=abcdefghijk"))
            assertNull(loader.load("https://www.youtube.com:444/watch?v=abcdefghijk"))
            assertNull(loader.load("https://www.youtube.com/channel/abcdefghijk"))
            assertNull(loader.load(link)!!.thumbnailUrl)
        }
    }
}
