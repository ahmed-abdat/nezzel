package dev.ahmed.savlino.engine.spotify

import org.junit.Assert.*
import org.junit.Test
import dev.ahmed.savlino.engine.mediahttp.*
import kotlinx.coroutines.runBlocking

class SpotifyMetadataTest {
    private val id = "0123456789ABCDEFGHIJKL"
    private fun page(entity: String) = """<html><script type="application/json" id="__NEXT_DATA__">{"props":{"pageProps":{"state":{"data":{"entity":$entity}}}}}</script></html>"""

    @Test fun supportedUrlsAreExactPublicSpotifyItems() {
        for (type in listOf("track", "album", "playlist")) for (prefix in listOf("", "intl-fr/")) {
            val link = SpotifyLink.parse("https://open.spotify.com/$prefix$type/$id?si=fixture")!!
            assertEquals(type, link.type)
            assertEquals("https://open.spotify.com/embed/$type/$id", link.embedUrl)
        }
        for (url in listOf("http://open.spotify.com/track/$id", "https://open.spotify.com.evil.test/track/$id",
            "https://open.spotify.com/artist/$id", "https://open.spotify.com/track/", "https://open.spotify.com/track/$id/extra",
            "https://open.spotify.com:444/track/$id", "https://user@open.spotify.com/track/$id",
            "https://open.spotify.com/track/not-an-id")) assertNull(url, SpotifyLink.parse(url))
    }

    @Test fun embedTracksAndListsBecomeOnlySearchMetadata() {
        val track = SpotifyMetadata.parseEmbed(page("""{"name":"Song","artists":[{"name":"Artist"},{"name":"Guest"}],"duration":123000}"""), "track")!!
        assertEquals("Artist, Guest Song", track.searchWords)
        assertEquals(123000L, track.tracks.single().durationMs)
        for (type in listOf("album", "playlist")) {
            val list = SpotifyMetadata.parseEmbed(page("""{"name":"Collection","subtitle":"Curator","trackList":[{"title":"Song","subtitle":"Artist","duration":123000}]}"""), type)!!
            assertEquals("Collection", list.title)
            assertEquals("Curator", list.subtitle)
            assertNull(list.searchWords)
            assertEquals("Artist Song", list.tracks.single().searchWords)
        }
    }

    @Test fun missingOrMalformedMetadataFailsInsteadOfInventingSongs() {
        for (entity in listOf("{}", "null", """{"name":"Song"}""", """{"name":"Song","artists":[]}""",
            """{"name":12,"artists":[{"name":"Artist"}]}"""))
            assertNull(SpotifyMetadata.parseEmbed(page(entity), "track"))
        for (entity in listOf("{}", """{"name":"Collection","trackList":[]}""",
            """{"name":"Collection","trackList":[{"title":"Song"}]}"""))
            assertNull(SpotifyMetadata.parseEmbed(page(entity), "album"))
        for (body in listOf("", "<html>Sign in</html>", page("{"))) assertNull(SpotifyMetadata.parseEmbed(body, "track"))
        assertEquals("Public title", SpotifyMetadata.parseOEmbed("""{"title":"Public title"}""")?.searchWords)
        for (body in listOf("{}", "{", """{"title":12}""")) assertNull(SpotifyMetadata.parseOEmbed(body))
    }

    @Test fun readerUsesOembedOnlyAfterAnEmbedParseFailure() = runBlocking {
        MediaHttpClient.create().use { http ->
            val calls = mutableListOf<String>()
            var embed = page("""{"name":"Song","artists":[{"name":"Artist"}]}""")
            var transportFailure = false
            var fallback = """{"title":"Fallback title"}"""
            val reader = SpotifyReader(http) { token, request ->
                calls += request.url
                if (transportFailure) MediaHttpResult.Failure(MediaHttpFailure.NETWORK) else {
                    val body = (if (request.url.contains("/embed/")) embed else fallback).toByteArray()
                    MediaHttpResult.Success(MediaHttpResponse("text/html", body.size.toLong(),
                        MediaHttpTarget(request.url, token.id, MediaHttpPurpose.PAGE), body))
                }
            }
            val source = "https://open.spotify.com/track/$id?si=private-fixture"
            assertEquals("Artist Song", reader.load(source)?.searchWords)
            assertEquals(1, calls.size)
            calls.clear(); embed = "<html>Changed page</html>"
            assertEquals("Fallback title", reader.load(source)?.searchWords)
            assertEquals(2, calls.size)
            assertEquals(SpotifyLink.parse(source)!!.oembedUrl, calls.last())
            assertFalse(calls.last().contains("private-fixture"))
            calls.clear(); fallback = "{}"
            assertNull(reader.load(source)); assertEquals(2, calls.size)
            calls.clear(); transportFailure = true
            assertNull(reader.load(source)); assertEquals(1, calls.size)
            calls.clear()
            assertNull(reader.load("https://example.com/track/$id")); assertTrue(calls.isEmpty())
        }
    }
}
