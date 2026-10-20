package dev.ahmed.savlino.engine.telegram

import dev.ahmed.savlino.engine.mediaimage.*
import org.junit.Test
import kotlin.test.*

class TelegramPostTest {
    private val identity = TelegramPostIdentity.parse("https://telegram.me/synthetic_channel/123?single")!!
    private fun bytes(name: String) = javaClass.getResourceAsStream("/telegram/$name.html")!!.use { it.readBytes() }
    private fun parse(name: String) = TelegramPostParser().parse(bytes(name), identity)

    @Test fun identityOnlyAcceptsPublicPostPathsAndBuildsCanonicalEmbed() {
        assertEquals("https://t.me/synthetic_channel/123?embed=1&mode=tme", identity.fetchUrl)
        for (url in listOf("https://t.me/c/123/456", "https://t.me/+invite", "https://t.me/synthetic_channel", "https://t.me/share/123", "https://t.me/synthetic_channel/123/extra", "https://t.me.evil.test/synthetic_channel/123", "https://user@t.me/synthetic_channel/123"))
            assertNull(TelegramPostIdentity.parse(url))
    }
    @Test fun usernamesHaveFiveToThirtyTwoCharacters() {
        for (name in listOf("abcd", "a".repeat(33))) assertNull(TelegramPostIdentity.parse("https://t.me/$name/123"))
        for (name in listOf("abcde", "a".repeat(32))) assertNotNull(TelegramPostIdentity.parse("https://t.me/$name/123"))
    }
    @Test fun nonPhotoWidgetsHandBackAndNestedDecoysDoNot() {
        for (widget in listOf("roundvideo", "roundvideo_player", "roundvideo_wrap", "video_player", "document_wrap", "audio_player", "voice_player")) {
            fun html(content: String) = "<div class='tgme_widget_message' data-post='synthetic_channel/123'>$content</div>".toByteArray()
            val media = "<div class='tgme_widget_message_$widget'></div>"
            assertIs<TelegramParseResult.Video>(TelegramPostParser().parse(html(media), identity), widget)
            val mixed = bytes("single").toString(Charsets.UTF_8).replace("</body>", "$media</body>")
                .replace("Synthetic caption</div>", "Synthetic caption</div>$media")
            assertTrue(assertIs<TelegramParseResult.Success>(TelegramPostParser().parse(mixed.toByteArray(), identity)).discovery.hasVideo, widget)
            for (wrapper in listOf("tgme_widget_message_link_preview", "tgme_widget_message_reply", "tgme_widget_message_reply_to")) {
                assertEquals(ImageFailure.NO_MEDIA, assertIs<TelegramParseResult.Failure>(
                    TelegramPostParser().parse(html("<div class='$wrapper'>$media</div>"), identity)).reason, "$widget in $wrapper")
            }
        }
    }
    @Test fun singleAndGroupedPhotosKeepPostOrder() {
        assertEquals(listOf("https://cdn1.telesco.pe/file/syntheticPhoto1"), assertIs<TelegramParseResult.Success>(parse("single")).candidates)
        val album = assertIs<TelegramParseResult.Success>(parse("album"))
        assertEquals(listOf("https://cdn1.telesco.pe/file/syntheticPhoto1", "https://cdn1.telesco.pe/file/syntheticPhoto2"), album.candidates)
        assertEquals(DiscoveryCompleteness.COMPLETE, album.discovery.completeness)
    }
    @Test fun paddedCdnFileTokensAndImageSuffixesRemainUsable() {
        val html = bytes("single").toString(Charsets.UTF_8).replace("syntheticPhoto1", "syntheticPhoto1==.jpg")
        assertEquals(listOf("https://cdn1.telesco.pe/file/syntheticPhoto1==.jpg"),
            assertIs<TelegramParseResult.Success>(TelegramPostParser().parse(html.toByteArray(), identity)).candidates)
    }
    @Test fun videoHandsBackButLinkPreviewsAndUnavailablePostsAreNotPhotos() {
        assertIs<TelegramParseResult.Video>(parse("video"))
        assertEquals(ImageFailure.NO_MEDIA, assertIs<TelegramParseResult.Failure>(parse("no-media")).reason)
        assertEquals(ImageFailure.PRIVATE, assertIs<TelegramParseResult.Failure>(parse("private")).reason)
    }
    @Test fun repliedAndOtherPostsNeverBecomeAttachments() {
        val html = bytes("single").toString(Charsets.UTF_8)
        val photo = "<a class='tgme_widget_message_photo_wrap' style=\"background-image:url('https://cdn1.telesco.pe/file/syntheticDecoy')\"></a>"
        val reply = "<div class='tgme_widget_message_reply'>$photo</div>"
        val other = "<div class='tgme_widget_message' data-post='synthetic_channel/999'>$photo</div>"
        val withDecoys = html.replace("</body>", "$other</body>").replace("Synthetic caption</div>", "Synthetic caption</div>$reply")
        assertEquals(listOf("https://cdn1.telesco.pe/file/syntheticPhoto1"),
            assertIs<TelegramParseResult.Success>(TelegramPostParser().parse(withDecoys.toByteArray(), identity)).candidates)
    }
    @Test fun identityMismatchUnsafeAssetsAndOversizedBodiesAreRefused() {
        val parser = TelegramPostParser()
        val html = bytes("single").toString(Charsets.UTF_8)
        assertEquals(ImageFailure.SOURCE_REFUSED, assertIs<TelegramParseResult.Failure>(parser.parse(html.replace("synthetic_channel/123", "synthetic_channel/999").toByteArray(), identity)).reason)
        for (host in listOf("cdn1.telesco.pe.evil.test", "localhost", "127.0.0.1"))
            assertEquals(ImageFailure.SOURCE_REFUSED, assertIs<TelegramParseResult.Failure>(parser.parse(html.replace("cdn1.telesco.pe", host).toByteArray(), identity)).reason)
        assertEquals(ImageFailure.BUDGET, assertIs<TelegramParseResult.Failure>(parser.parse(ByteArray(1024 * 1024 + 1), identity)).reason)
    }
}
