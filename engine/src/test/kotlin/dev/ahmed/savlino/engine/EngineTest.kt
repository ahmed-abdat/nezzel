package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class EngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source = LinkRevision(assertNotNull(LinkInput.parse("https://example.org/a")), 1)
    private val row = Format("720", "mp4", true, true)
    private val inspection = Inspection(source, "fixture-1", listOf(row))
    private fun command() = assertIs<ConfirmResult.Confirmed>(
        DownloadCommand.confirm("job-1", inspection, source, "720", "عنوان")).command

    @Test fun legacyUnavailableAssertionsAndNoTransferEffects() = runBlocking {
        val engine: EngineAdapter = UnavailableEngine()
        assertFalse(engine.isAvailable) // Legacy assertion 14.
        val result = engine.inspect(source, "inspect-1")
        assertEquals(DownloadError.EngineUnavailable, assertIs<InspectResult.Failure>(result).error)
        val directory = temporary.newFolder()
        assertEquals(TransferResult.Failure(DownloadError.EngineUnavailable), engine.download(command(), directory) { fail("unexpected progress") })
        assertTrue(directory.listFiles()!!.isEmpty())
        engine.cancel("never-started")
    }

    @Test fun scriptedInspectionReturnsActualRowsAndTypedFailures() = runBlocking {
        assertEquals(listOf(row), assertIs<InspectResult.Success>(FakeEngine(InspectResult.Success(inspection)).inspect(source, "inspect-1")).inspection.formats)
        for (error in listOf(DownloadError.Unsupported, DownloadError.LoginRequired, DownloadError.Offline, DownloadError.NoFormats)) {
            assertEquals(InspectResult.Failure(error), FakeEngine(InspectResult.Failure(error)).inspect(source, "inspect-1"))
        }
    }

    @Test fun transferScriptEmitsProgressAndReturnsContainedNonemptyFile() = runBlocking {
        val events = listOf(EngineEvent.Progress(1, Size.Unknown), EngineEvent.Progress(3, Size.Estimated(4)), EngineEvent.Merging)
        val engine = FakeEngine(steps = events.map { FakeEngine.Step.Emit(it) } + FakeEngine.Step.Output("video.mp4", byteArrayOf(1, 2, 3)))
        val emitted = mutableListOf<EngineEvent>()
        val directory = temporary.newFolder()
        val cmd = command()
        val output = (engine.download(cmd, directory, emitted::add) as TransferResult.Success).output
        assertEquals(events, emitted)
        assertEquals(File(directory, "video.mp4").canonicalFile, output.file)
        assertContentEquals(byteArrayOf(1, 2, 3), output.file.readBytes())
        assertSame(cmd, engine.commands.single())
    }

    @Test fun scriptedFailureCannotProduceOutput() = runBlocking {
        val engine = FakeEngine(steps = listOf(FakeEngine.Step.Fail(DownloadError.InsufficientSpace), FakeEngine.Step.Output("video.mp4", byteArrayOf(1))))
        val directory = temporary.newFolder()
        assertEquals(TransferResult.Failure(DownloadError.InsufficientSpace), engine.download(command(), directory) {})
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun cancelBeforeRegistrationPreventsTransferAndInspection() = runBlocking {
        val engine = FakeEngine(InspectResult.Success(inspection), listOf(FakeEngine.Step.Output("video.mp4", byteArrayOf(1))))
        engine.cancel("job-1"); engine.cancel("inspect-1")
        val directory = temporary.newFolder()
        assertEquals(TransferResult.Failure(DownloadError.Cancelled), engine.download(command(), directory) { fail("late progress") })
        assertEquals(InspectResult.Failure(DownloadError.Cancelled), engine.inspect(source, "inspect-1"))
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun keyedCancelUnblocksSuspendedTransferAndSkipsLaterOutput() = runBlocking {
        val engine = FakeEngine(steps = listOf(FakeEngine.Step.AwaitCancellation, FakeEngine.Step.Output("video.mp4", byteArrayOf(1))))
        val directory = temporary.newFolder()
        val transfer = async(start = CoroutineStart.UNDISPATCHED) { engine.download(command(), directory) {} }
        engine.cancel("different-job")
        assertFalse(transfer.isCompleted)
        engine.cancel("job-1")
        assertEquals(TransferResult.Failure(DownloadError.Cancelled), withTimeout(2000) { transfer.await() })
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun cancelFromProgressSuppressesSubsequentEvents() = runBlocking {
        val engine = FakeEngine(steps = listOf(FakeEngine.Step.Emit(EngineEvent.Progress(1)),
            FakeEngine.Step.Emit(EngineEvent.Progress(2)), FakeEngine.Step.Output("video.mp4", byteArrayOf(1))))
        val emitted = mutableListOf<EngineEvent>()
        val directory = temporary.newFolder()
        assertEquals(TransferResult.Failure(DownloadError.Cancelled), engine.download(command(), directory) {
            emitted += it; engine.cancel("job-1")
        })
        assertEquals<List<EngineEvent>>(listOf(EngineEvent.Progress(1)), emitted)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun inspectionAndParentCoroutineCancellationAreCooperative() = runBlocking {
        val engine = FakeEngine(waitForInspectCancellation = true)
        val request = async(start = CoroutineStart.UNDISPATCHED) { engine.inspect(source, "inspect-1") }
        engine.cancel("inspect-1")
        assertEquals(InspectResult.Failure(DownloadError.Cancelled), withTimeout(2000) { request.await() })
        val parent = async(start = CoroutineStart.UNDISPATCHED) { engine.inspect(source, "inspect-2") }
        parent.cancelAndJoin()
        assertTrue(parent.isCancelled)
    }

    @Test fun keyedInspectionScriptsCanReturnOutOfOrderWithoutTimingSleeps() = runBlocking {
        val edited = LinkRevision(assertNotNull(LinkInput.parse("https://example.org/b")), 2)
        val editedInspection = Inspection(edited, "fixture-1", listOf(row.copy(id = "1080", height = 1080)))
        val engine = FakeEngine(inspectionScripts = mapOf(
            "old" to FakeEngine.InspectionScript(InspectResult.Success(inspection), awaitRelease = true),
            "new" to FakeEngine.InspectionScript(InspectResult.Success(editedInspection))))
        val old = async(start = CoroutineStart.UNDISPATCHED) { engine.inspect(source, "old") }
        assertFalse(old.isCompleted)
        assertEquals(InspectResult.Success(editedInspection), engine.inspect(edited, "new"))
        engine.releaseInspection("old")
        assertEquals(InspectResult.Success(inspection), withTimeout(2000) { old.await() })
    }

    @Test fun cancelledPausedInspectionNeverReturnsItsQueuedSuccess() = runBlocking {
        val engine = FakeEngine(inspectionScripts = mapOf(
            "paused" to FakeEngine.InspectionScript(InspectResult.Success(inspection), awaitRelease = true)))
        val pending = async(start = CoroutineStart.UNDISPATCHED) { engine.inspect(source, "paused") }
        engine.cancel("paused")
        engine.releaseInspection("paused")
        assertEquals(InspectResult.Failure(DownloadError.Cancelled), withTimeout(2000) { pending.await() })
    }

    @Test fun outputValidationRejectsEscapeSymlinkEmptyAndMultipleFiles() {
        val directory = temporary.newFolder()
        val outside = temporary.newFile().apply { writeText("outside") }
        val empty = File(directory, "empty").apply { createNewFile() }
        val first = File(directory, "first").apply { writeText("one") }
        val second = File(directory, "second").apply { writeText("two") }
        val symlink = File(directory, "link")
        Files.createSymbolicLink(symlink.toPath(), outside.toPath())
        for (outputs in listOf(emptyList(), listOf(outside), listOf(empty), listOf(first, second), listOf(symlink), listOf(directory))) {
            assertFailsWith<IllegalArgumentException> { StagedOutput.validate(directory, outputs) }
        }
        assertEquals(first.canonicalFile, StagedOutput.validate(directory, listOf(first)).file)
        assertEquals("outside", outside.readText())
    }

    @Test fun fakeRejectsOutputEscapeBeforeWriting() = runBlocking {
        val root = temporary.newFolder()
        val directory = File(root, "job").apply { mkdir() }
        val engine = FakeEngine(steps = listOf(FakeEngine.Step.Output("../escape.mp4", byteArrayOf(1))))
        assertEquals(TransferResult.Failure(DownloadError.Failed), engine.download(command(), directory) {})
        assertFalse(File(root, "escape.mp4").exists())
    }
}
