package dev.ahmed.savlino.engine.playlist

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.engine.youtube.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import kotlin.test.*

class PlaylistEngineTest {
    private val list = LinkRevision(LinkInput.parse("https://youtube.com/playlist?list=PLabcdefghij")!!, 1)
    private val video = LinkRevision(LinkInput.parse("https://youtube.com/watch?v=abcdefghijk")!!, 1)
    private class Runtime : YtdlpRuntime {
        override val initialization = EngineInitialization()
        override fun initialize() = Unit
        override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String = when {
            "--version" in args -> "fixture"
            "--flat-playlist" in args -> {
                assertTrue("--dump-single-json" in args)
                assertEquals("1:100", args[args.indexOf("--playlist-items") + 1])
                """{"_type":"playlist","entries":[{"id":"abcdefghijk","title":"Fixture","duration":60}]}"""
            }
            else -> {
                assertTrue("--no-playlist" in args)
                """{"format_id":"18","ext":"mp4","vcodec":"h264","acodec":"aac"}"""
            }
        }
        override fun destroy(processId: String) = true
        override fun update() = Unit
    }

    @Test fun nativeFailureUsesTheSeparateFlatCommand(): Unit = runBlocking {
        val fast = object : YoutubeFastSource {
            override fun infoJson(url: String, operationId: String, timeoutMs: Long) = FastResult.Miss
            override fun playlist(source: LinkRevision, token: PlaylistPageToken?, operationId: String, timeoutMs: Long): PlaylistPage? = throw IllegalStateException()
            override fun cancel(operationId: String) = Unit
        }
        val engine = YoutubedlEngine(Runtime(), fastSource = fast, native = NativeState())
        val result = assertIs<PlaylistResult.Success>(engine.listPlaylist(list, "list"))
        assertEquals(listOf("abcdefghijk"), result.page.items.map { it.id })
        assertIs<InspectResult.Success>(engine.inspect(video, "video"))
    }

    @Test fun nativePageTokenIsPassedThroughAndNeverReentersTheFirstPage(): Unit = runBlocking {
        val continuation = Page("https://www.youtube.com/continuation-fixture")
        val lister = PlaylistLister({ source, token, _ ->
            if (token == null) PlaylistPage("Fixture", listOf(PlaylistItem("abcdefghijk", video.link.rawUrl, "One", 60)), 2,
                PlaylistPageToken(source.link.normalizedUrl, 1, continuation))
            else {
                assertSame(continuation, token.nativePage)
                PlaylistPage("Fixture", listOf(PlaylistItem("bcdefghijkl", "https://youtube.com/watch?v=bcdefghijkl", "Two", 60)), 2, null)
            }
        }, { _, _, _ -> error("Native fixture should not fall back") })
        val first = assertIs<PlaylistResult.Success>(lister.list(list)).page
        val second = assertIs<PlaylistResult.Success>(lister.list(list, first.next)).page
        assertEquals(listOf("bcdefghijkl"), second.items.map { it.id })
        assertNull(second.next)
        assertEquals(DownloadError.InvalidInput, assertIs<PlaylistResult.Failure>(lister.list(
            LinkRevision(LinkInput.parse("https://youtube.com/playlist?list=PLdifferent0")!!, 1), first.next)).error)
    }

    @Test fun listingAndVideoInspectionShareTheSlotEvenWhenCancelledNativeWorkerHasNotExited(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val fast = object : YoutubeFastSource {
            override fun infoJson(url: String, operationId: String, timeoutMs: Long): FastResult = error("Native slot is still held")
            override fun playlist(source: LinkRevision, token: PlaylistPageToken?, operationId: String, timeoutMs: Long): PlaylistPage? {
                entered.countDown()
                try { check(release.await(5, TimeUnit.SECONDS)); return null }
                finally { exited.countDown() }
            }
            override fun cancel(operationId: String) = Unit // Models a CPU-bound extractor which ignores cancellation.
        }
        val engine = YoutubedlEngine(Runtime(), fastSource = fast, native = NativeState())
        val listing = async(Dispatchers.Default) { engine.listPlaylist(list, "held-list") }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            engine.cancel("held-list")
            assertEquals(DownloadError.Cancelled, assertIs<PlaylistResult.Failure>(withTimeout(5000) { listing.await() }).error)
            assertIs<InspectResult.Success>(withTimeout(5000) { engine.inspect(video, "video") })
            // A second listing also falls back while the abandoned native call holds the slot.
            assertIs<PlaylistResult.Success>(withTimeout(5000) { engine.listPlaylist(list, "next-list") })
        } finally {
            release.countDown()
            assertTrue(withContext(Dispatchers.IO) { exited.await(5, TimeUnit.SECONDS) })
            listing.cancelAndJoin()
        }
    }

    @Test fun nativeRowsDisablePrivateDeletedLiveUpcomingAndZeroDuration() {
        fun row(name: String = "Fixture", duration: Long = 60, type: StreamType = StreamType.VIDEO_STREAM,
            availability: ContentAvailability = ContentAvailability.AVAILABLE) = StreamInfoItem(0, video.link.rawUrl, name, type).apply {
                this.duration = duration; contentAvailability = availability
            }
        assertTrue(assertNotNull(NpePlaylistPage.item(row())).selectable)
        for (item in listOf(row("[Private video]"), row("[Deleted video]"), row(type = StreamType.LIVE_STREAM),
            row(duration = 0), row(availability = ContentAvailability.UPCOMING), row(availability = ContentAvailability.MEMBERSHIP))) {
            assertFalse(assertNotNull(NpePlaylistPage.item(item)).selectable)
        }
    }
}
