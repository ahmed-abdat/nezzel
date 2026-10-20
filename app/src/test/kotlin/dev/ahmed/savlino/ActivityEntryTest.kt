package dev.ahmed.savlino

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ActivityEntryTest {
    private fun share(url: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    @Test fun activityHonorsAutomaticLoadingPreferenceAcrossResumeAndShareIntents() {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as dev.ahmed.savlino.jobs.IsolatedTestApplication
        val prefs = dev.ahmed.savlino.locale.AppPreferences(app).apply {
            automaticOptions = false; suggestionsEnabled = false
        }
        val store = dev.ahmed.savlino.jobs.JobStore(app)
        app.coordinatorOverride = dev.ahmed.savlino.jobs.DownloadCoordinator(app,
            engine = dev.ahmed.savlino.engine.FakeEngine(dev.ahmed.savlino.engine.InspectResult.Failure(dev.ahmed.savlino.domain.DownloadError.Offline)), store = store)
        val controller = Robolectric.buildActivity(MainActivity::class.java, share("https://youtu.be/one")).setup()
        try {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(dev.ahmed.savlino.ui.OptionsState.Hidden, controller.get().links.optionsState)
            controller.get().links.edit("")
            prefs.automaticOptions = true
            controller.pause().resume()
            controller.newIntent(share("https://youtu.be/two"))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(dev.ahmed.savlino.domain.DownloadError.Offline,
                (controller.get().links.optionsState as dev.ahmed.savlino.ui.OptionsState.Error).error)
            assertTrue(store.all().isEmpty())
            assertNull(controller.get().links.takeConfirmedCommand())
        } finally {
            controller.pause().stop().destroy(); store.close(); app.coordinatorOverride = null
        }
    }
    @Test fun createNewIntentAndRotationKeepEditing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, share("https://youtu.be/one")).setup()
        try {
            assertEquals("https://youtu.be/one", controller.get().links.text)
            controller.get().links.edit("draft")
            controller.newIntent(share("https://youtu.be/two"))
            assertEquals("draft", controller.get().links.text)
            assertTrue(controller.get().links.hasPendingShare)
            controller.configurationChange()
            assertEquals("draft", controller.get().links.text)
            controller.get().links.acceptShare()
            assertEquals("https://youtu.be/two", controller.get().links.text)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun recreationDisposesFocusedEditorBeforeAcceptingNextShare() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            controller.get().links.editorFocused = true
            controller.configurationChange()
            assertFalse(controller.get().links.editorFocused)
            controller.newIntent(share("https://youtu.be/next"))
            assertEquals("https://youtu.be/next", controller.get().links.text)
            assertFalse(controller.get().links.hasPendingShare)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun savedInstanceStateRestoresDraftAfterFreshActivity() {
        val first = Robolectric.buildActivity(MainActivity::class.java, share("https://youtu.be/one")).setup()
        first.get().links.edit("edited after share")
        val state = android.os.Bundle()
        first.pause().saveInstanceState(state).stop().destroy()
        val restored = Robolectric.buildActivity(MainActivity::class.java, share("https://youtu.be/one")).create(state).start().resume().visible()
        try { assertEquals("edited after share", restored.get().links.text) }
        finally { restored.pause().stop().destroy() }
    }
}
