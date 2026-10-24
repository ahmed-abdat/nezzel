package dev.ahmed.savlino.jobs

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.FakeEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BatchCoordinatorTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun warmSQLite() {
        app.deleteDatabase(JobStore.DATABASE_NAME)
        JobStore(app).use { it.writableDatabase }
    }
    @Test fun persistedPauseOrStopIsRestoredBeforeAnyWorkerCanBeScheduled() = runBlocking {
        for ((index, state) in listOf(BatchState.PAUSED, BatchState.STOPPED).withIndex()) {
            JobStore(app).use { jobs ->
                val batches = BatchStore(jobs, batchCipher())
                val id = "restore-$index"
                batches.create(id, "Fixture", Preset.BEST, batchItems(1))
                val item = batches.items(id).single()
                val source = LinkRevision(LinkInput.parse(batches.source(item))!!, 0)
                val format = Format("18", "mp4", true, true)
                val command = (DownloadCommand.confirm(item.jobId, Inspection(source, "fixture", listOf(format)), source,
                    format.id, "Fixture") as ConfirmResult.Confirmed).command
                jobs.enqueue(command, Preset.BEST) // Death before marking the batch item QUEUED.
                if (state == BatchState.STOPPED) batches.stop(id) else batches.state(id, state)
                val coordinator = DownloadCoordinator(app, engine = FakeEngine(), store = jobs,
                    workManager = { error("Persisted batch controls must win before scheduling") })
                coordinator.initialize()
                assertEquals(if (state == BatchState.STOPPED) JobStage.CANCELLED else JobStage.PAUSED, jobs.get(item.jobId)!!.stage)
                assertNull(coordinator.error.value)
            }
        }
    }
}
