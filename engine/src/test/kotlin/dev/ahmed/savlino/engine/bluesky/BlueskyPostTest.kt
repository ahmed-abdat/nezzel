package dev.ahmed.savlino.engine.bluesky

import dev.ahmed.savlino.engine.mediaimage.*
import org.junit.Test
import kotlin.test.*

class BlueskyPostTest {
    private val did = "did:plc:abcdefghijklmnopqrstuvwx"
    private val uri = "at://$did/app.bsky.feed.post/3synthetic001"
    private fun bytes(name: String) = javaClass.getResourceAsStream("/bluesky/$name.json")!!.use { it.readBytes() }
    private fun parse(name: String) = BlueskyPostParser().parse(bytes(name), uri)

    @Test fun handleAndDidPathsKeepIdentityAndBoundApiRequests() {
        val handle = assertNotNull(BlueskyPostIdentity.parse("https://bsky.app/profile/synthetic.test/post/3synthetic001?ref=share"))
        assertNull(handle.did)
        assertEquals("https://public.api.bsky.app/xrpc/com.atproto.identity.resolveHandle?handle=synthetic.test", handle.resolveUrl)
        assertEquals(did, BlueskyPostParser().resolveDid(bytes("resolve-handle")))
        val direct = assertNotNull(BlueskyPostIdentity.parse("https://bsky.app/profile/$did/post/3synthetic001"))
        assertEquals(did, direct.did)
        assertEquals(uri, direct.atUri(did))
        assertEquals("https://public.api.bsky.app/xrpc/app.bsky.feed.getPostThread?uri=at%3A%2F%2Fdid%3Aplc%3Aabcdefghijklmnopqrstuvwx%2Fapp.bsky.feed.post%2F3synthetic001&depth=0", direct.threadUrl(did))
        for (url in listOf("https://bsky.app/profile/synthetic.test", "https://bsky.app/profile/localhost/post/3synthetic001", "https://bsky.app.evil.test/profile/synthetic.test/post/3synthetic001"))
            assertNull(BlueskyPostIdentity.parse(url))
    }
    @Test fun singleAlbumAndRecordWithMediaReadOnlyOwnImagesAsJpeg() {
        val single = assertIs<BlueskyParseResult.Success>(parse("single"))
        assertEquals(listOf("https://cdn.bsky.app/img/feed_fullsize/plain/$did/bafkreisynthetic1@jpeg"), single.candidates)
        for (name in listOf("album", "record-with-media")) {
            val photos = assertIs<BlueskyParseResult.Success>(parse(name))
            assertEquals(2, photos.candidates.size)
            assertTrue(photos.candidates.all { it.endsWith("@jpeg") })
            assertEquals("https://cdn.bsky.app/img/feed_thumbnail/plain/$did/bafkreisynthetic2@jpeg", photos.previewCandidates[1])
        }
    }
    @Test fun videoNoMediaAndPrivateResultsAreTruthful() {
        assertIs<BlueskyParseResult.Video>(parse("video"))
        for (name in listOf("no-media", "quoted-only"))
            assertEquals(ImageFailure.NO_MEDIA, assertIs<BlueskyParseResult.Failure>(parse(name)).reason)
        assertEquals(ImageFailure.PRIVATE, assertIs<BlueskyParseResult.Failure>(parse("private")).reason)
    }
    @Test fun stillSuffixIsReplacedAndWrongPostOrCdnAndOversizedBodiesAreRefused() {
        val parser = BlueskyPostParser()
        val json = bytes("single").toString(Charsets.UTF_8)
        for (suffix in listOf("webp", "avif")) {
            val encoded = json.replace("bafkreisynthetic1\"", "bafkreisynthetic1@$suffix\"")
            assertEquals("https://cdn.bsky.app/img/feed_fullsize/plain/$did/bafkreisynthetic1@jpeg",
                assertIs<BlueskyParseResult.Success>(parser.parse(encoded.toByteArray(), uri)).candidates.single())
        }
        for (bad in listOf(json.replace("3synthetic001", "3synthetic999"), json.replace("cdn.bsky.app", "cdn.bsky.app.evil.test")))
            assertEquals(ImageFailure.SOURCE_REFUSED, assertIs<BlueskyParseResult.Failure>(parser.parse(bad.toByteArray(), uri)).reason)
        assertEquals(ImageFailure.BUDGET, assertIs<BlueskyParseResult.Failure>(parser.parse(ByteArray(1024 * 1024 + 1), uri)).reason)
        // url-shapes: a handle may resolve to a well-formed did:web; the thread read still goes only to the public API.
        assertEquals("did:web:synthetic.test", parser.resolveDid("{\"did\":\"did:web:synthetic.test\"}".toByteArray()))
        for (bad in listOf("did:web:localhost", "did:web:Evil.Test", "did:web:evil.test%3A8080", "did:key:z6Mk"))
            assertNull(parser.resolveDid("{\"did\":\"$bad\"}".toByteArray()), bad)
    }
}
