package dev.ahmed.savlino

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.jobs.DownloadCoordinator
import dev.ahmed.savlino.jobs.IsolatedTestApplication
import dev.ahmed.savlino.jobs.JobStore
import dev.ahmed.savlino.locale.AppPreferences
import dev.ahmed.savlino.shortcuts.ShortcutActions
import dev.ahmed.savlino.ui.OptionsState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w360dp-h800dp-mdpi", shadows = [CountingClipboard::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShortcutMaintenanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as IsolatedTestApplication
    private val url = "https://example.com/video"

    private class Engine : EngineAdapter by FakeEngine() {
        val inspections = AtomicInteger()
        val updates = AtomicInteger()
        val transfers = AtomicInteger()
        override suspend fun installedVersion() = if (updates.get() == 0) "before" else "after"
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            inspections.incrementAndGet()
            if (updates.get() == 0) return InspectResult.Failure(DownloadError.Failed)
            return InspectResult.Success(Inspection(source, "after", listOf(Format("hd", "mp4", true, true, height = 720))))
        }
        override suspend fun updateYtdlp(): EngineUpdateResult {
            updates.incrementAndGet()
            return EngineUpdateResult.Success("before", "after")
        }
        override suspend fun download(command: DownloadCommand, directory: java.io.File,
            progress: (EngineEvent) -> Unit): TransferResult {
            transfers.incrementAndGet()
            error("Intake must not download")
        }
    }

    @Test fun coldPasteFailureReleasesMaintenanceAndRetriesWithoutReadingAgain() = runBlocking {
        CountingClipboard.allReads = 0
        AppPreferences(app).apply { markOnboardingSeen(); automaticOptions = true; suggestionsEnabled = true }
        app.getSharedPreferences("engine-maintenance", 0).edit().putBoolean("launched", true).commit()
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", url))
        val engine = Engine()
        JobStore(app).use { store ->
            val coordinator = DownloadCoordinator(app, engine, store)
            app.coordinatorOverride = coordinator
            val maintenance = async(Dispatchers.IO) { coordinator.maintenance.checkWhenInspectionIdle() }
            val c = Robolectric.buildActivity(MainActivity::class.java,
                ShortcutActions.intent(app, ShortcutActions.PASTE_LINK)).create().start().resume().visible()
            try {
                assertEquals(0, engine.updates.get())
                c.windowFocusChanged(true)
                compose.waitUntil(5000) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    maintenance.isCompleted && c.get().links.optionsState is OptionsState.Ready
                }
                assertTrue(maintenance.await())
                assertEquals(2, engine.inspections.get())
                assertEquals(1, engine.updates.get())
                assertEquals(1, CountingClipboard.allReads)
                assertEquals(0, engine.transfers.get())
                assertEquals(url, c.get().links.text)
            } finally {
                c.pause().stop().destroy()
                maintenance.cancelAndJoin()
                app.coordinatorOverride = null
            }
        }
    }

    @Test fun automaticPasteOptionsReturnHomeFromEveryDestination() {
        CountingClipboard.allReads = 0
        AppPreferences(app).apply { markOnboardingSeen(); automaticOptions = true; suggestionsEnabled = false }
        val engine = Engine().apply { updates.incrementAndGet() }
        JobStore(app).use { store ->
            app.coordinatorOverride = DownloadCoordinator(app, engine, store)
            val c = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                c.windowFocusChanged(true)
                for (destination in listOf("Settings", "Downloads", "AppUpdate")) {
                    c.get().links.navigate(destination)
                    val next = "$url/$destination"
                    app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", next))
                    c.newIntent(ShortcutActions.intent(app, ShortcutActions.PASTE_LINK))
                    compose.waitUntil(5000) {
                        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                        c.get().links.optionsState is OptionsState.Ready
                    }
                    assertEquals(destination, "Home", c.get().links.destination)
                    assertEquals(next, c.get().links.text)
                    compose.onNodeWithText("Link you copied").assertIsDisplayed()
                }
                assertEquals(3, CountingClipboard.allReads)
                assertEquals(3, engine.inspections.get())
                assertEquals(0, engine.transfers.get())
            } finally {
                c.pause().stop().destroy()
                app.coordinatorOverride = null
            }
        }
    }

    @Test fun emptyColdPasteAlsoCompletesTheClipboardDecision() = runBlocking {
        CountingClipboard.allReads = 0
        AppPreferences(app).apply { markOnboardingSeen(); automaticOptions = true; suggestionsEnabled = false }
        app.getSharedPreferences("engine-maintenance", 0).edit().putBoolean("launched", true).commit()
        app.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        val engine = Engine()
        JobStore(app).use { store ->
            val coordinator = DownloadCoordinator(app, engine, store)
            app.coordinatorOverride = coordinator
            val maintenance = async(Dispatchers.IO) { coordinator.maintenance.checkWhenInspectionIdle() }
            val c = Robolectric.buildActivity(MainActivity::class.java,
                ShortcutActions.intent(app, ShortcutActions.PASTE_LINK)).create().start().resume().visible()
            try {
                c.windowFocusChanged(true)
                compose.waitUntil(5000) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    maintenance.isCompleted
                }
                assertTrue(maintenance.await())
                assertEquals(1, engine.updates.get())
                assertEquals(0, engine.inspections.get())
                assertEquals(0, engine.transfers.get())
                assertEquals("empty clipboard is rejected by its description, never read", 0, CountingClipboard.allReads)
                assertEquals("", c.get().links.text)
            } finally {
                c.pause().stop().destroy()
                maintenance.cancelAndJoin()
                app.coordinatorOverride = null
            }
        }
    }
}
