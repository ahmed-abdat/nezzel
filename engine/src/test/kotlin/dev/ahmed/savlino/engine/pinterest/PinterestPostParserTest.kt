package dev.ahmed.savlino.engine.pinterest

import dev.ahmed.savlino.engine.mediahttp.HttpsUrl
import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy
import dev.ahmed.savlino.engine.mediahttp.MediaPageLimits
import dev.ahmed.savlino.engine.mediaimage.DiscoveryCompleteness
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import dev.ahmed.savlino.engine.mediaimage.ImagePhase
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinterestPostParserTest {
    private val pin = "100000000000000001"
    private val original = "https://i.pinimg.com/originals/01/23/45/0123456789abcdef0123456789abcdef.png"
    private val large = "https://i.pinimg.com/736x/01/23/45/0123456789abcdef0123456789abcdef.jpg"
    private val preview = "https://i.pinimg.com/236x/01/23/45/0123456789abcdef0123456789abcdef.jpg"
    private fun bytes(name: String) = javaClass.getResourceAsStream("/pinterest/$name.html")!!.use { it.readBytes() }
    private fun parse(name: String) = PinterestPostParser().parse(bytes(name), pin)
    private fun page(pinJson: String) = """
        <script id="__PWS_INITIAL_PROPS__" type="application/json">
        {"initialReduxState":{"pins":{"$pin":$pinJson}}}
        </script>
    """.trimIndent().toByteArray()

    @Test fun reduxPageKeepsTheUploadAndASmallerPreview() {
        val image = assertIs<PinterestParseResult.Image>(parse("redux-image"))
        assertEquals(original, image.candidate)
        assertEquals(preview, image.preview)
        assertEquals("Synthetic pin", image.title)
        assertEquals("Synthetic author", image.author)
        assertEquals(DiscoveryCompleteness.COMPLETE, image.discovery.completeness)
        assertFalse(image.discovery.previewQuality)
        assertEquals(1, image.discovery.discoveredCount)
    }

    @Test fun relayPageIgnoresAnotherPinAndTheOpenGraphImage() {
        val image = assertIs<PinterestParseResult.Image>(parse("relay-image"))
        assertEquals(original, image.candidate)
        assertEquals(preview, image.preview)
        assertEquals("Synthetic pin", image.title)
        assertNull(image.author)
    }

    @Test fun uploadIsKeptWhenItsPixelsAreNotDeclared() {
        val html = page("""{"is_video":false,"videos":null,"images_orig":{"url":"$original"},
            "images_736x":{"url":"$large","width":703,"height":998}}""")
        assertEquals(original, assertIs<PinterestParseResult.Image>(PinterestPostParser().parse(html, pin)).candidate)
    }

    @Test fun declaredAreaChoosesWhenThereIsNoUpload() {
        val wider = "https://i.pinimg.com/1200x/01/23/45/0123456789abcdef0123456789abcdef.jpg"
        val html = page("""{"is_video":false,"videos":null,"images":{
            "236x":{"url":"$preview","width":236,"height":335},
            "736x":{"url":"$large","width":703,"height":998},
            "1200x":{"url":"$wider","width":1000,"height":1400}}}""")
        val image = assertIs<PinterestParseResult.Image>(PinterestPostParser().parse(html, pin))
        assertEquals(wider, image.candidate)
        assertEquals(preview, image.preview)
        assertTrue(image.discovery.previewQuality)
    }

    @Test fun videoPinKeepsTheExistingPathDespiteItsPoster() {
        assertIs<PinterestParseResult.Video>(parse("video"))
        val story = page("""{"is_video":false,"videos":null,"images":{"orig":{"url":"$original","width":10,"height":10}},
            "story_pin_data":{"pages":[{"blocks":[{"video":{"video_list":{"V_EXP":{"url":"https://v.pinimg.com/videos/synthetic/clip.mp4"}}}}]}]}}""")
        assertIs<PinterestParseResult.Video>(PinterestPostParser().parse(story, pin))
        val embed = page("""{"is_video":false,"videos":null,"domain":"example.org","embed":{"src":"https://player.vimeo.com/video/100000001"},
            "images":{"orig":{"url":"$original","width":10,"height":10}}}""")
        assertIs<PinterestParseResult.Video>(PinterestPostParser().parse(embed, pin))
    }

    @Test fun anArticleLinkIsStillAnImage() {
        val html = page("""{"is_video":false,"videos":null,"domain":"example.org","embed":null,
            "images":{"orig":{"url":"$original","width":400,"height":400}}}""")
        assertEquals(original, assertIs<PinterestParseResult.Image>(PinterestPostParser().parse(html, pin)).candidate)
    }

    @Test fun unsafeAndMismatchedCopiesAreNotTheSavedImage() {
        val evil = "https://evil.example/originals/01/23/45/0123456789abcdef0123456789abcdef.jpg"
        val mismatched = "https://i.pinimg.com/originals/ff/ff/ff/0123456789abcdef0123456789abcdef.jpg"
        val queried = "$original?x=1"
        val html = page("""{"is_video":false,"videos":null,"images":{
            "orig":{"url":"$evil","width":4000,"height":4000},
            "bad":{"url":"$mismatched","width":3000,"height":3000},
            "query":{"url":"$queried","width":2000,"height":2000},
            "736x":{"url":"$large","width":703,"height":998}}}""")
        val image = assertIs<PinterestParseResult.Image>(PinterestPostParser().parse(html, pin))
        assertEquals(large, image.candidate)
        assertTrue(image.discovery.previewQuality)
    }

    @Test fun aSizedCopyAndTheUploadOfTheSameFileAreOneImage() {
        val html = """
            <script id="__PWS_INITIAL_PROPS__" type="application/json">
            {"initialReduxState":{"pins":{"$pin":{"is_video":false,"videos":null,"grid_title":"Synthetic pin","images":{
            "736x":{"url":"$large","width":703,"height":998},"236x":{"url":"$preview","width":236,"height":335}}}}}}
            </script>
            <script type="text/javascript">window.__PWS_RELAY_REGISTER_COMPLETED_REQUEST__("%7B%22pinId%22%3A%22$pin%22%7D", {"data":{"v3GetPinQueryv2":{"data":{"entityId":"$pin","isVideo":false,"videos":null,"images_orig":{"url":"$original"}}}}});</script>
        """.trimIndent().toByteArray()
        val image = assertIs<PinterestParseResult.Image>(PinterestPostParser().parse(html, pin))
        assertEquals(original, image.candidate)
        assertEquals(preview, image.preview)
        assertEquals("Synthetic pin", image.title)
        assertFalse(image.discovery.previewQuality)
    }

    @Test fun anImageOnlyStoryAndADeepStoryVideoStayOnTheVideoPath() {
        val cover = page("""{"is_video":false,"videos":null,"story_pin_data":{"pages":[{"blocks":[{"image":{"url":"$original"}}]}]},
            "images":{"orig":{"url":"$original","width":400,"height":400}}}""")
        assertIs<PinterestParseResult.Video>(PinterestPostParser().parse(cover, pin))
        val camel = page("""{"isVideo":false,"videos":null,"storyPinData":{"pages":[]},
            "images_orig":{"url":"$original","width":400,"height":400}}""")
        assertIs<PinterestParseResult.Video>(PinterestPostParser().parse(camel, pin))
        var deep = """{"video_list":{"V_EXP":{"url":"https://v.pinimg.com/videos/synthetic/clip.mp4"}}}"""
        repeat(9) { deep = """{"wrap":$deep}""" }
        val pads = (1..420).joinToString(",") { "\"n$it\":{\"t\":$it}" }
        val late = page("""{"is_video":false,"videos":null,"images":{"orig":{"url":"$original","width":10,"height":10}},
            "story_pin_data":{$pads,"deep":$deep}}""")
        assertIs<PinterestParseResult.Video>(PinterestPostParser().parse(late, pin))
    }

    @Test fun copiesThatDisagreeOrNameNoPinAreRefused() {
        val other = "https://i.pinimg.com/originals/fe/dc/ba/fedcba9876543210fedcba9876543210.jpg"
        val html = """
            <script id="__PWS_INITIAL_PROPS__" type="application/json">
            {"initialReduxState":{"pins":{"$pin":{"is_video":false,"videos":null,"images":{"orig":{"url":"$original","width":8,"height":8}}}}}}
            </script>
            <script type="text/javascript">window.__PWS_RELAY_REGISTER_COMPLETED_REQUEST__("%7B%22pinId%22%3A%22$pin%22%7D", {"data":{"v3GetPinQueryv2":{"data":{"entityId":"$pin","isVideo":false,"videos":null,"images_orig":{"url":"$other"}}}}});</script>
        """.trimIndent().toByteArray()
        assertEquals(ImageFailure.SOURCE_REFUSED, assertIs<PinterestParseResult.Failure>(PinterestPostParser().parse(html, pin)).reason)
        val videoAndImage = """
            <script id="__PWS_INITIAL_PROPS__" type="application/json">
            {"initialReduxState":{"pins":{"$pin":{"is_video":true,"videos":{"video_list":{"V_720P":{"url":"https://v.pinimg.com/videos/synthetic/clip.mp4"}}}}}}}
            </script>
            <script type="text/javascript">window.__PWS_RELAY_REGISTER_COMPLETED_REQUEST__("%7B%22pinId%22%3A%22$pin%22%7D", {"data":{"v3GetPinQueryv2":{"data":{"entityId":"$pin","isVideo":false,"videos":null,"images_orig":{"url":"$original"}}}}});</script>
        """.trimIndent().toByteArray()
        assertEquals(ImageFailure.SOURCE_REFUSED, assertIs<PinterestParseResult.Failure>(PinterestPostParser().parse(videoAndImage, pin)).reason)
        assertEquals(ImagePhase.PAGE, assertIs<PinterestParseResult.Failure>(PinterestPostParser().parse(bytes("redux-image"), "999")).phase)
        val empty = page("""{"is_video":false,"videos":null,"images":{}}""")
        val none = assertIs<PinterestParseResult.Failure>(PinterestPostParser().parse(empty, pin))
        assertEquals(ImageFailure.NO_MEDIA, none.reason)
        assertEquals(ImagePhase.IMAGE, none.phase)
    }

    @Test fun aPageOverTheCapIsABudgetFailure() {
        val over = ByteArray(MediaPageLimits.PINTEREST + 1)
        assertEquals(ImageFailure.BUDGET, assertIs<PinterestParseResult.Failure>(PinterestPostParser().parse(over, pin)).reason)
    }

    @Test fun imageHostMatchesTheSignatureAndRejectsLookalikes() {
        val signature = "01/23/45/0123456789abcdef0123456789abcdef"
        for (path in listOf("originals/$signature.png", "736x/$signature.jpg", "136x136/$signature.webp", "75x75_RS/$signature.gif"))
            assertTrue(MediaHttpPolicy.isPinterestImage(assertNotNull(HttpsUrl.parse("https://i.pinimg.com/$path"))), path)
        for (url in listOf(
            "https://i.pinimg.com/webapp/app.css",
            "https://i.pinimg.com/custom_covers/200x150/111.jpg",
            "https://i.pinimg.com/originals/ff/ff/ff/0123456789abcdef0123456789abcdef.jpg",
            "https://i.pinimg.com/originals/$signature.png?x=1",
            "https://i.pinimg.com.evil.example/originals/$signature.jpg",
            "https://s.pinimg.com/originals/$signature.jpg",
            "http://i.pinimg.com/originals/$signature.jpg"))
            assertFalse(HttpsUrl.parse(url)?.let(MediaHttpPolicy::isPinterestImage) == true, url)
    }
}
