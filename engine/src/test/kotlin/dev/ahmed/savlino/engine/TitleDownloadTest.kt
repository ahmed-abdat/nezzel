package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class TitleDownloadTest {
    @get:Rule val temp = TemporaryFolder()
    // This seam receives an already reviewed link. Intake intentionally rejects reserved .invalid hosts.
    private val source = LinkRevision(ValidatedLink::class.java.getDeclaredConstructor(
        String::class.java, String::class.java, Host::class.java).newInstance(
        "https://example.invalid/video", "https://example.invalid/video", Host.FACEBOOK), 1)

    private class Runtime(private val directory: File, private val extension: String) : YtdlpRuntime {
        override val initialization = EngineInitialization()
        var rewrite: (File, String) -> Unit = { _, _ -> fail("Unexpected rewrite") }
        override fun initialize() = Unit
        override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
            if ("--version" in args) return "test-version"
            File(directory, "output.$extension").apply { writeText("downloaded") }.also {
                File(directory, "result-path.txt").writeText(it.absolutePath)
            }
            return ""
        }
        override fun rewriteTitle(file: File, title: String, processId: String) = rewrite(file, title)
        override fun destroy(processId: String) = false
        override fun update() = Unit
    }

    @Test fun progressiveAndMergedMp4UseConfirmedFilenameTitleAfterTransferBeforeReturningOutput() = runBlocking {
        for (merge in listOf(false, true)) {
            val directory = temp.newFolder().canonicalFile
            val row = Format("hd", "mp4", true, !merge, audioFormatId = if (merge) "audio" else null, requiresMerge = merge)
            val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job", Inspection(source,
                "test-version", listOf(row), title = "Confirmed title"), source, row.id, "ignored")).command
            var rewritten = false
            val events = mutableListOf<EngineEvent>()
            val runtime = Runtime(directory, "mp4").apply { rewrite = { file, title ->
                assertEquals(EngineEvent.Merging, events.lastOrNull())
                assertEquals("downloaded", file.readText())
                assertEquals("Confirmed title", title)
                file.writeText("rewritten")
                rewritten = true
            } }
            val result = assertIs<TransferResult.Success>(YoutubedlEngine(runtime).download(command, directory, events::add))
            assertTrue(rewritten)
            assertEquals("rewritten", result.output.file.readText())
        }
    }

    @Test fun optionalRewriteFailureStillReturnsOriginalSuccessfulDownload() = runBlocking {
        val directory = temp.newFolder().canonicalFile
        val row = Format("hd", "mp4", true, true)
        val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job", Inspection(source,
            "test-version", listOf(row)), source, row.id, "Confirmed title")).command
        val runtime = Runtime(directory, "mp4").apply { rewrite = { _, _ -> throw IllegalStateException() } }
        val result = assertIs<TransferResult.Success>(YoutubedlEngine(runtime).download(command, directory) {})
        assertEquals("downloaded", result.output.file.readText())
    }

    @Test fun audioOnlyAndAnimatedGifVideoNeverEnterTitleRewrite() = runBlocking {
        for (row in listOf(Format("audio", "mp4", false, true), Format("audio", "m4a", false, true),
            Format("audio", "mp3", false, true), Format("gif", "mp4", true, false, animatedGif = true),
            Format("video", "mkv", true, true), Format("video", "webm", true, true))) {
            val directory = temp.newFolder().canonicalFile
            val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job", Inspection(source,
                "test-version", listOf(row)), source, row.id, "Confirmed title")).command
            val result = assertIs<TransferResult.Success>(YoutubedlEngine(Runtime(directory, row.container))
                .download(command, directory) {})
            assertEquals("downloaded", result.output.file.readText())
        }
    }

    @Test fun cancellationDuringTitleRewriteStillCancelsDownload() = runBlocking {
        val directory = temp.newFolder().canonicalFile
        val row = Format("hd", "mp4", true, true)
        val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job", Inspection(source,
            "test-version", listOf(row)), source, row.id, "Confirmed title")).command
        val runtime = Runtime(directory, "mp4")
        val engine = YoutubedlEngine(runtime)
        runtime.rewrite = { _, _ -> engine.cancel("job") }
        assertEquals(TransferResult.Failure(DownloadError.Cancelled), engine.download(command, directory) {})
        assertEquals("downloaded", File(directory, "output.mp4").readText())
    }
}
