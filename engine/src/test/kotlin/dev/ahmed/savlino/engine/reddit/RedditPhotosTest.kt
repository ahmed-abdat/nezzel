package dev.ahmed.savlino.engine.reddit

import dev.ahmed.savlino.domain.MediaRendition
import dev.ahmed.savlino.engine.FakeEngine
import dev.ahmed.savlino.engine.LegacyMediaInspectionAdapter
import dev.ahmed.savlino.engine.MediaSourceInspector
import dev.ahmed.savlino.engine.MediaSourceRoute
import dev.ahmed.savlino.engine.mediahttp.reply
import dev.ahmed.savlino.engine.mediaimage.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

/**
 * Image and gallery posts shaped like Reddit's logged-out page (shreddit `content-href`, `gallery-carousel` pages
 * with `srcset` / `data-lazy-srcset`); every id, name and signature synthetic.
 */
internal fun preview(name: String, width: Int, sig: String) =
    "https://preview.redd.it/$name.jpg?width=$width&amp;format=pjpg&amp;auto=webp&amp;s=$sig"

internal fun imagePage(content: String = "https://i.redd.it/synthimg0003.jpeg", extra: String = ""): ByteArray =
    postPage("image", content = content, extra = extra, title = "A synthetic photo").let { page ->
    String(page).replace("<div slot=\"post-media-container\">", """<div slot="post-media-container">
        <shreddit-media-lightbox-listener><img class="preview-img" alt="" src="${preview("photo-v0-synthimg0003", 640, "04")}"
          srcset="${preview("photo-v0-synthimg0003", 320, "05")} 320w, ${preview("photo-v0-synthimg0003", 640, "04")} 640w,
          ${preview("photo-v0-synthimg0003", 1080, "06")} 1080w"></shreddit-media-lightbox-listener>
        <img src="https://styles.redditmedia.com/t5_synthetic/styles/communityIcon_synthetic.png" alt="">""").toByteArray()
}

/** Four pages: a preview-only photo, a lazy photo whose link names its i.redd.it file, a clip and a non-Reddit image. */
internal fun galleryPage(extra: String = ""): ByteArray {
    val other = """<shreddit-post id="t3_9zzzzzz" post-type="gallery" permalink="/r/other/comments/9zzzzzz/other/">
        <gallery-carousel><ul><li slot="page-1"><figure><img src="${preview("other-v0-othergal0001", 640, "99")}"></figure></li>
        </ul></gallery-carousel></shreddit-post>"""
    val gallery = """<shreddit-async-loader bundlename="gallery_carousel"><gallery-carousel><ul>
        <li slot="page-1"><img class="blur" src="${preview("first-v0-synthgal0001", 108, "10")}">
          <figure><img src="${preview("first-v0-synthgal0001", 640, "11")}"
            srcset="${preview("first-v0-synthgal0001", 320, "12")} 320w, ${preview("first-v0-synthgal0001", 1080, "13")} 1080w"></figure></li>
        <li slot="page-2"><figure><a href="https://i.redd.it/synthgal0002.png"><img
            data-lazy-src="${preview("second-v0-synthgal0002", 640, "21")}"
            data-lazy-srcset="${preview("second-v0-synthgal0002", 320, "22")} 320w, ${preview("second-v0-synthgal0002", 640, "21")} 640w"></a></figure></li>
        <li slot="page-3"><shreddit-player-2 src="https://v.redd.it/synthclip001/HLSPlaylist.m3u8"></shreddit-player-2></li>
        <li slot="page-4"><figure><img src="https://example.org/elsewhere.jpg"></figure></li>
        </ul></gallery-carousel></shreddit-async-loader>"""
    return String(postPage("gallery", content = "https://www.reddit.com/gallery/$POST_ID", title = "A synthetic gallery", extra = extra))
        .replace("<shreddit-app>", "<shreddit-app>$other")
        .replace("<div slot=\"post-media-container\">", "<div slot=\"post-media-container\">$gallery").toByteArray()
}

/** A gallery of [count] i.redd.it photos, each with a 320w preview. */
internal fun longGalleryPage(count: Int): ByteArray {
    val pages = (1..count).joinToString("") { n ->
        """<li slot="page-$n"><figure><a href="https://i.redd.it/long$n.jpg"><img src="${preview("long-v0-long$n", 320, "$n")}"></a></figure></li>"""
    }
    return String(postPage("gallery", content = "https://www.reddit.com/gallery/$POST_ID", title = "A long gallery"))
        .replace("<div slot=\"post-media-container\">", "<div slot=\"post-media-container\"><gallery-carousel><ul>$pages</ul></gallery-carousel>")
        .toByteArray()
}

private fun unescaped(url: String) = url.replace("&amp;", "&")

class RedditPhotosTest : PublicImageTestBase() {
    private fun parse(page: ByteArray) = RedditPostParser.parse(page, page.size, POST_ID)

    @Test fun anImagePostNamesItsOwnIReddItFileAndASmallPreview() {
        val photos = assertIs<RedditPost.Photos>(parse(imagePage()))
        assertEquals(listOf("https://i.redd.it/synthimg0003.jpeg"), photos.originals)
        assertEquals(listOf(unescaped(preview("photo-v0-synthimg0003", 320, "05"))), photos.previews)
        assertEquals("A synthetic photo", photos.title)
        assertEquals("synthetic_author", photos.author)
        assertFalse(photos.previewQuality)
        assertEquals(0, photos.skipped)
    }

    @Test fun anImagePostWithoutItsFileKeepsTheLargestPreviewItNamesAndSaysSo() {
        val photos = assertIs<RedditPost.Photos>(parse(imagePage(content = "https://www.reddit.com$PERMALINK")))
        assertEquals(listOf(unescaped(preview("photo-v0-synthimg0003", 1080, "06"))), photos.originals)
        assertTrue(photos.previewQuality)
    }

    @Test fun aGalleryListsOnlyItsOwnPhotosInPageOrderAndCountsWhatItSkips() {
        val photos = assertIs<RedditPost.Photos>(parse(galleryPage()))
        assertEquals(listOf(unescaped(preview("first-v0-synthgal0001", 1080, "13")), "https://i.redd.it/synthgal0002.png"),
            photos.originals)
        assertEquals(listOf(unescaped(preview("first-v0-synthgal0001", 320, "12")),
            unescaped(preview("second-v0-synthgal0002", 320, "22"))), photos.previews)
        assertTrue(photos.previewQuality)
        assertEquals(2, photos.skipped)
        assertTrue(photos.originals.none { "other" in it })
    }

    @Test fun anAdultPhotoPostAsksForSignInWhileATextOneStillSaysItHoldsNothing() {
        assertEquals(RedditPost.LoginRequired, parse(galleryPage(extra = "nsfw")))
        assertEquals(RedditPost.LoginRequired, parse(imagePage(extra = "nsfw")))
        assertEquals(RedditPost.NoVideo, parse(postPage("text", extra = "nsfw")))
        assertIs<RedditPost.Photos>(parse(galleryPage()))
    }

    @Test fun aGalleryPassesUpToFortyItemsAndRefusesALongerList() {
        assertEquals((1..40).map { "https://i.redd.it/long$it.jpg" }, assertIs<RedditPost.Photos>(parse(longGalleryPage(40))).originals)
        assertEquals(RedditPost.Unknown, parse(longGalleryPage(41)))
    }

    @Test fun onlyRedditImageHostsPass() {
        for (bad in listOf("http://i.redd.it/a.jpg", "https://i.redd.it/a.jpg?x=1", "https://i.redd.it/a/b.jpg",
            "https://external-preview.redd.it/a.jpg?s=1", "https://preview.redd.it/a.gif?format=mp4&s=1",
            "https://i.redd.it.example.org/a.jpg", "https://i.redd.it/a.mp4", "https://i.redd.it:8443/a.jpg"))
            assertEquals(RedditPost.NoVideo, parse(imagePage(content = bad).let {
                String(it).replace(Regex("<shreddit-media-lightbox-listener>.*?</shreddit-media-lightbox-listener>",
                    RegexOption.DOT_MATCHES_ALL), "").toByteArray()
            }), bad)
    }

    // The image route, through the isolated TLS transport.
    private val images = mapOf("/first-v0-synthgal0001.jpg" to raster(40, 30, "jpg", 0x112233),
        "/second-v0-synthgal0002.jpg" to raster(20, 10, "jpg", 0x445566), "/synthgal0002.png" to raster(60, 40, "png", 0x778899),
        "/photo-v0-synthimg0003.jpg" to raster(30, 30, "jpg", 0x223344), "/synthimg0003.jpeg" to raster(64, 48, "jpg", 0x556677))

    private fun site(page: () -> ByteArray, run: (PublicImageFixture) -> Unit) = fixture(respond = { request, socket ->
        val path = request.target.substringBefore('?')
        when (request.headers["host"]) {
            "www.reddit.com" -> socket.reply(body = page(), headers = mapOf("Content-Type" to "text/html"))
            "i.redd.it", "preview.redd.it" -> images[path]?.let {
                socket.reply(body = it, headers = mapOf("Content-Type" to if (path.endsWith(".png")) "image/png" else "image/jpeg"))
            } ?: socket.reply(404, ByteArray(0))
            else -> socket.reply(404, ByteArray(0))
        }
    }, test = run)

    private fun PublicImageFixture.inspect(raw: String = "https://www.reddit.com$PERMALINK") = runBlocking {
        MediaSourceInspector(inspector, LegacyMediaInspectionAdapter(FakeEngine())).inspect(imageSource(raw), "reddit", MediaSourceRoute.REDDIT)
    }
    private fun PublicImageFixture.save(result: MediaInspectResult.Success): List<ByteArray> =
        result.inspection.items.sortedBy { it.position }.map { item ->
            val rendition = assertIs<MediaRendition.Image>(item.renditions.single())
            val sink = ByteArrayOutputStream()
            assertIs<ImageSaveResult.Success>(runBlocking {
                saver.transfer(saver.newOperation(assertNotNull(result.inspection.selection(rendition.ref))), sink)
            })
            sink.toByteArray()
        }

    @Test fun aGalleryPreviewsSmallCopiesAndSavesEachPhotoInPostOrder() = site(::galleryPage) { f ->
        inspected(f.inspect()).use { result ->
            assertEquals(listOf(0, 1), result.inspection.items.map { it.position })
            assertEquals(2, result.previews.size)
            assertEquals(2, result.discovery.unavailableCount)
            assertTrue(result.discovery.previewQuality)
            assertEquals("A synthetic gallery", result.inspection.items.first().metadata.title)
            // Previews read the 320w copies only; nothing else on the page is requested.
            val previewReads = f.server.requests.filter { it.headers["host"] != "www.reddit.com" }
            assertTrue(previewReads.all { "width=320" in it.target }, previewReads.map { it.target }.toString())
            val saved = f.save(result)
            assertEquals(2, saved.size)
            assertContentEquals(images.getValue("/first-v0-synthgal0001.jpg"), saved[0])
            assertContentEquals(images.getValue("/synthgal0002.png"), saved[1])
            val saves = f.server.requests.takeLast(2)
            assertEquals(listOf("preview.redd.it", "i.redd.it"), saves.map { it.headers["host"] })
            assertTrue("width=1080" in saves[0].target)
            assertEquals("/synthgal0002.png", saves[1].target)
        }
    }

    @Test fun aGalleryLongerThanTheImageCapShowsTheFirstTwentyAndCountsTheRestAsCapped() = fixture(respond = { request, socket ->
        if (request.headers["host"] == "www.reddit.com") socket.reply(body = longGalleryPage(25), headers = mapOf("Content-Type" to "text/html"))
        else socket.reply(body = raster(20, 20, "jpg", 0x101010), headers = mapOf("Content-Type" to "image/jpeg"))
    }) { f ->
        inspected(f.inspect()).use { result ->
            assertEquals((0 until 20).toList(), result.inspection.items.map { it.position })
            assertEquals(5, result.discovery.cappedCount)
            assertEquals(DiscoveryCompleteness.PARTIAL, result.discovery.completeness)
            assertTrue(f.server.requests.none { "long21" in it.target })
        }
    }

    @Test fun anImagePostSavesItsOriginalFile() = site(::imagePage) { f ->
        inspected(f.inspect("https://redd.it/$POST_ID")).use { result ->
            assertEquals(1, result.inspection.items.size)
            assertFalse(result.discovery.previewQuality)
            assertContentEquals(images.getValue("/synthimg0003.jpeg"), f.save(result).single())
            assertEquals("i.redd.it", f.server.requests.last().headers["host"])
        }
    }

    @Test fun everyOtherPostOrAFailedReadHandsTheUsersOwnLinkToYtDlp() {
        val link = "https://www.reddit.com$PERMALINK"
        for (page in listOf({ postPage() }, { postPage("text") }, { postPage("crosspost") }))
            site(page) { f ->
                val redirect = assertIs<MediaInspectResult.VideoRedirect>(f.inspect(link))
                assertEquals(link, redirect.source.link.rawUrl)
                assertTrue(f.server.requests.all { it.headers["host"] == "www.reddit.com" })
            }
        fixture(respond = { _, socket -> socket.reply(404, "gone".toByteArray(), mapOf("Content-Type" to "text/html")) }) { f ->
            assertIs<MediaInspectResult.VideoRedirect>(f.inspect(link))
        }
    }

    @Test fun aPhotoRedirectOffRedditsImageHostsIsNeverFollowed() = fixture(respond = { request, socket ->
        if (request.headers["host"] == "www.reddit.com") socket.reply(body = imagePage(), headers = mapOf("Content-Type" to "text/html"))
        else socket.reply(302, headers = mapOf("Location" to "https://example.org/stolen.jpg"))
    }) { f ->
        val result = f.inspect()
        if (result is MediaInspectResult.Success) result.use { fail("a redirected photo must not be admitted") }
        assertTrue(f.server.requests.none { it.headers["host"] == "example.org" })
    }
}
