package dev.ahmed.savlino.engine.threads

import dev.ahmed.savlino.domain.Host
import dev.ahmed.savlino.domain.LinkInput
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.engine.FakeEngine
import dev.ahmed.savlino.engine.LegacyMediaInspectionAdapter
import dev.ahmed.savlino.engine.MediaSourceInspector
import dev.ahmed.savlino.engine.MediaSourceRoute
import dev.ahmed.savlino.engine.YtdlpMetadataMapper
import dev.ahmed.savlino.engine.download.RangeEligibility
import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class ThreadsReaderTest : PublicImageTestBase() {
    private val post = "https://www.threads.com/@synthetic_user/post/SynthPost01"
    private val videoLink = "https://threads.net/@synthetic_user/post/SynthVid0001"
    private fun page(name: String) = javaClass.getResourceAsStream("/threads/$name.html")!!.use { it.readBytes() }
    private fun readPage(result: MediaHttpResult): ImagePageRead = when (result) {
        is MediaHttpResult.Failure -> ImagePageRead.Failed(ImageReadPlan.Failure(
            mapTransport(result.reason), ImagePhase.PAGE, result.statusCode))
        is MediaHttpResult.Success -> result.response.use { ImagePageRead.Body(it.takeBody()) }
    }
    private fun failure(plan: ImageReadPlan, reason: ImageFailure) {
        val failed = assertIs<ImageReadPlan.Failure>(plan)
        assertEquals(reason, failed.reason)
        assertEquals(ImagePhase.PAGE, failed.phase)
    }

    @Test fun navigationHeadersAndTheCanonicalPage() {
        for (url in listOf(post, "https://threads.net/@synthetic_user/post/SynthPost01?share=1"))
            fixture(respond = { _, socket ->
                socket.reply(body = page("image"), headers = mapOf("Content-Type" to "text/html"))
            }) { f ->
                f.http.client.newOperation(ThreadsReader.operation).use { token ->
                    val photos = assertIs<ImageReadPlan.Photos>(
                        ThreadsReader(LinkInput.parse(url)!!).read(f.http.client, token, ::readPage))
                    assertTrue(photos.candidates.single().contains("synthetic_full.jpg"))
                    assertEquals("Synthetic caption", photos.metadata.title)
                    assertEquals("synthetic_user", photos.metadata.uploader)
                    assertNull(photos.videoInfoJson)
                    val request = f.server.requests.single()
                    assertEquals("www.threads.com", request.headers["host"])
                    assertEquals("text/html", request.headers["accept"])
                    assertEquals("document", request.headers["sec-fetch-dest"])
                    assertEquals("navigate", request.headers["sec-fetch-mode"])
                    assertEquals("none", request.headers["sec-fetch-site"])
                    assertNull(request.headers["cookie"])
                    assertEquals("/@synthetic_user/post/SynthPost01", request.target)
                }
            }
    }

    @Test fun anOversizedPageIsBudgetAndARedirectIsRefused() {
        fixture(respond = { _, socket ->
            socket.reply(body = byteArrayOf(1), declared = MediaPageLimits.THREADS + 1L,
                headers = mapOf("Content-Type" to "text/html"))
        }) { f ->
            f.http.client.newOperation(ThreadsReader.operation).use { token ->
                failure(ThreadsReader(LinkInput.parse(post)!!).read(f.http.client, token, ::readPage), ImageFailure.BUDGET)
            }
        }
        fixture(respond = { _, socket ->
            socket.reply(status = 302, body = byteArrayOf(), declared = 0,
                headers = mapOf("Location" to "https://www.threads.com/login"))
        }) { f ->
            f.http.client.newOperation(ThreadsReader.operation).use { token ->
                failure(ThreadsReader(LinkInput.parse(post)!!).read(f.http.client, token, ::readPage), ImageFailure.SOURCE_REFUSED)
                assertEquals(1, f.server.requests.size)
            }
        }
    }

    @Test fun aVideoPlanIsOneProgressiveRenditionForTheDirectDownload() {
            fixture(respond = { _, socket ->
            socket.reply(body = page("video"), headers = mapOf("Content-Type" to "text/html"))
        }) { f ->
            val source = LinkInput.parse(videoLink)!!
            f.http.client.newOperation(ThreadsReader.operation).use { token ->
                val plan = assertIs<ImageReadPlan.Video>(ThreadsReader(source).read(f.http.client, token, ::readPage))
                assertEquals(source.rawUrl, plan.target.rawUrl)
                val info = requireNotNull(plan.infoJson)
                val mapped = YtdlpMetadataMapper.map(info, imageSource(videoLink), "test")
                val row = mapped.formats.single()
                assertEquals("progressive", row.sourceFormatId)
                assertEquals("mp4", row.container)
                assertEquals(1280, row.width)
                assertEquals(720, row.height)
                assertEquals(Size.Unknown, row.size)
                assertTrue(mapped.formats.none { it.id.startsWith("nezzel:mp3:") })
                val direct = assertNotNull(RangeEligibility.select(Host.THREADS, row, info) {
                    VideoCdnPolicy.allows(Host.THREADS, it) != null
                })
                assertTrue(direct.url.contains("synthetic_large.mp4"))
                assertTrue(direct.headers.isEmpty())
            }
            val redirect = assertIs<MediaInspectResult.VideoRedirect>(runBlocking {
                MediaSourceInspector(f.inspector, LegacyMediaInspectionAdapter(FakeEngine()))
                    .inspect(imageSource(videoLink), "post", MediaSourceRoute.THREADS)
            })
            assertEquals(videoLink, redirect.source.link.rawUrl)
            assertEquals(1, f.threadsVideos.size)
            assertTrue(f.server.requests.none { it.target.endsWith(".mp4") })
        }
    }
}
