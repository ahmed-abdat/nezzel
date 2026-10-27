package dev.ahmed.savlino.engine.reddit

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.engine.mediahttp.reply
import dev.ahmed.savlino.engine.mediaimage.PublicImageFixture
import dev.ahmed.savlino.engine.mediaimage.PublicImageTestBase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/** yt-dlp reads a Reddit post through its own video for inspection and for every download, never the post link. */
class RedditEngineTest : PublicImageTestBase() {
    @get:Rule val temp = TemporaryFolder()
    private val post = "https://www.reddit.com$PERMALINK"
    private val manifest = "https://v.redd.it/$VIDEO_ID/DASHPlaylist.mpd"
    private val source = LinkRevision(LinkInput.parse(post)!!, 1)
    private val dash = """{"id":"DASHPlaylist","title":"DASHPlaylist","formats":[{"format_id":"18","ext":"mp4","vcodec":"avc1.42001E",
        "acodec":"mp4a.40.2","width":640,"height":360,"filesize":1000,"protocol":"https","url":"https://v.redd.it/$VIDEO_ID/x.mp4"}]}"""

    private class Runtime(val page: String) : YtdlpRuntime {
        override val initialization = EngineInitialization()
        val calls = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        override fun initialize() = Unit
        override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
            if ("--version" in args) return "version"
            calls += args
            if ("--dump-json" in args) return page
            val output = File(args[args.indexOf("-o") + 1].replace("%(ext)s", "mp4"))
            output.writeText("m".repeat(1000))
            File(output.parentFile, "result-path.txt").writeText(output.path)
            return ""
        }
        override fun destroy(processId: String) = true
        override fun update() = Unit
    }

    private fun postServer(test: (PublicImageFixture) -> Unit) = fixture(respond = { request, socket ->
        if ("solution=" in request.target) socket.reply(body = postPage(), headers = mapOf("Content-Type" to "text/html"))
        else socket.reply(body = challengePage(), headers = mapOf("Content-Type" to "text/html"))
    }, test = test)

    @Test fun inspectionAndDownloadsReadTheVideoEvenAfterTheInfoJsonIsLost() = postServer { f -> runBlocking {
        val runtime = Runtime(dash)
        val reader = RedditPostReader(f.http.client)
        val cached = YoutubedlEngine(runtime, infoCache = YtdlpInfoCache(temp.newFolder()), reddit = reader)
        val inspection = assertIs<InspectResult.Success>(cached.inspect(source, "inspect")).inspection
        assertEquals("A synthetic & quoted \"video\"", inspection.title)
        assertEquals(manifest, runtime.calls.single().last())
        val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job", inspection, source, "18", "t")).command
        cached.prepareDownload(command)
        assertIs<TransferResult.Success>(cached.download(command, temp.newFolder()) {})
        assertTrue("--load-info-json" in runtime.calls.last())

        // No info JSON at all (cache gone or unwritable): the download still names the video, re-read from the post.
        val bare = YoutubedlEngine(runtime, reddit = reader)
        bare.prepareDownload(command)
        val pages = f.server.requests.size
        assertIs<TransferResult.Success>(bare.download(command, temp.newFolder()) {})
        assertEquals(manifest, runtime.calls.last().last())
        assertEquals(pages + 2, f.server.requests.size)
        assertTrue(runtime.calls.none { post in it })
    } }

    @Test fun aPostThatLostItsVideoFailsTheDownloadHonestly() = fixture(respond = { request, socket ->
        socket.reply(if ("solution=" in request.target) 404 else 200,
            if ("solution=" in request.target) "gone".toByteArray() else challengePage(), mapOf("Content-Type" to "text/html"))
    }) { f -> runBlocking {
        val runtime = Runtime(dash)
        val inspection = assertIs<InspectResult.Success>(YoutubedlEngine(runtime).inspect(source, "inspect")).inspection
        val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job", inspection, source, "18", "t")).command
        val engine = YoutubedlEngine(runtime, reddit = RedditPostReader(f.http.client))
        engine.prepareDownload(command)
        runtime.calls.clear()
        assertEquals(DownloadError.Unavailable, assertIs<TransferResult.Failure>(engine.download(command, temp.newFolder()) {}).error)
        assertTrue(runtime.calls.isEmpty())
    } }
}
