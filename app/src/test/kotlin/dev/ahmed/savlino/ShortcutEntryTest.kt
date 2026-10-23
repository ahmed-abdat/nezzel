package dev.ahmed.savlino

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import dev.ahmed.savlino.locale.AppPreferences
import dev.ahmed.savlino.shortcuts.ShortcutActions
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [CountingClipboard::class])
class ShortcutEntryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val clipboard get() = app.getSystemService(ClipboardManager::class.java)
    private val reads get() = CountingClipboard.allReads

    @Before fun prepare() {
        CountingClipboard.allReads = 0
        AppPreferences(app).apply { automaticOptions = false; suggestionsEnabled = false; markOnboardingSeen() }
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "https://youtu.be/one"))
    }

    @Test fun coldPasteWaitsForResumeAndFocusAndIsConsumedOnce() {
        val c = Robolectric.buildActivity(MainActivity::class.java, ShortcutActions.intent(app, ShortcutActions.PASTE_LINK))
            .create().start().visible()
        try {
            c.windowFocusChanged(true)
            assertEquals(0, reads)
            c.windowFocusChanged(false).resume()
            assertEquals(0, reads)
            c.windowFocusChanged(true)
            assertEquals(1, reads)
            assertEquals("https://youtu.be/one", c.get().links.text)
            assertNull(c.get().intent.action)
            c.windowFocusChanged(false).windowFocusChanged(true).pause().resume()
            assertEquals(1, reads)
        } finally { c.pause().stop().destroy() }
    }

    @Test fun newIntentPasteWaitsForFocusAndIsConsumedOnce() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            c.windowFocusChanged(false)
            c.newIntent(ShortcutActions.intent(app, ShortcutActions.PASTE_LINK))
            assertEquals(0, reads)
            c.windowFocusChanged(true)
            assertEquals(1, reads)
            assertEquals("https://youtu.be/one", c.get().links.text)
            c.windowFocusChanged(false).windowFocusChanged(true)
            assertEquals(1, reads)
            clipboard.setPrimaryClip(ClipData.newPlainText("text", "https://youtu.be/two"))
            c.newIntent(ShortcutActions.intent(app, ShortcutActions.PASTE_LINK))
            assertEquals(2, reads)
            assertEquals("https://youtu.be/two", c.get().links.text)
        } finally { c.pause().stop().destroy() }
    }

    @Test fun neverFocusedDoesNotRead() {
        val c = Robolectric.buildActivity(MainActivity::class.java, ShortcutActions.intent(app, ShortcutActions.PASTE_LINK))
            .create().start().resume().visible()
        try { assertEquals(0, reads); assertEquals("", c.get().links.text) }
        finally { c.pause().stop().destroy() }
    }

    @Test fun shortcutDoesNotAlsoReadForAutomaticClipboardSuggestion() {
        AppPreferences(app).suggestionsEnabled = true
        val c = Robolectric.buildActivity(MainActivity::class.java, ShortcutActions.intent(app, ShortcutActions.PASTE_LINK))
            .create().start().resume().visible()
        try {
            c.windowFocusChanged(true)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, reads)
            assertEquals("https://youtu.be/one", c.get().links.text)
        } finally { c.pause().stop().destroy() }
    }

    @Test fun originalPasteIntentDoesNotReplayAfterConsumedStateIsRestored() {
        val original = ShortcutActions.intent(app, ShortcutActions.PASTE_LINK)
        val first = Robolectric.buildActivity(MainActivity::class.java, Intent(original)).setup()
        first.windowFocusChanged(true)
        assertEquals(1, reads)
        val state = android.os.Bundle()
        first.pause().saveInstanceState(state).stop().destroy()
        val c = Robolectric.buildActivity(MainActivity::class.java, Intent(original)).create(state).start().resume().visible()
        try {
            c.windowFocusChanged(true)
            assertEquals(1, reads)
        } finally { c.pause().stop().destroy() }
    }

    @Test fun pendingSavedPasteIsRestoredWhenTheOriginalEnvelopeWasConsumed() {
        val first = Robolectric.buildActivity(MainActivity::class.java, ShortcutActions.intent(app, ShortcutActions.PASTE_LINK)).setup()
        val state = android.os.Bundle()
        first.pause().saveInstanceState(state).stop().destroy()
        val c = Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java))
            .create(state).start().resume().visible()
        try { c.windowFocusChanged(true); assertEquals(1, reads) }
        finally { c.pause().stop().destroy() }
    }

    @Test fun pasteActionSentDirectlyToExportedMainActivityIsOnlyAPlainLaunch() {
        val direct = Intent(app, MainActivity::class.java).setAction(ShortcutActions.PASTE_LINK)
        val c = Robolectric.buildActivity(MainActivity::class.java, direct).setup()
        try {
            c.windowFocusChanged(true)
            assertEquals(0, reads)
            assertEquals("", c.get().links.text)
            c.newIntent(Intent(direct))
            assertEquals(0, reads)
            assertEquals("", c.get().links.text)
            c.newIntent(ShortcutActions.intent(app, ShortcutActions.PASTE_LINK))
            assertEquals(1, reads)
            assertEquals("https://youtu.be/one", c.get().links.text)
        } finally { c.pause().stop().destroy() }
    }

    @Test fun stoppingBeforeFocusCancelsPasteForALaterManualReturn() {
        val c = Robolectric.buildActivity(MainActivity::class.java, ShortcutActions.intent(app, ShortcutActions.PASTE_LINK)).setup()
        try {
            assertEquals(0, reads)
            c.pause().stop().restart().resume().windowFocusChanged(true)
            assertEquals(0, reads)
            assertEquals("", c.get().links.text)
        } finally { c.pause().stop().destroy() }
    }

    @Test fun originalDownloadsIntentDoesNotOverrideRestoredNavigation() {
        val original = ShortcutActions.intent(app, ShortcutActions.OPEN_DOWNLOADS)
        val first = Robolectric.buildActivity(MainActivity::class.java, Intent(original)).setup()
        first.get().links.navigate("Settings")
        val state = android.os.Bundle()
        first.pause().saveInstanceState(state).stop().destroy()
        val c = Robolectric.buildActivity(MainActivity::class.java, Intent(original)).create(state).start().resume().visible()
        try { assertEquals("Settings", c.get().links.destination) }
        finally { c.pause().stop().destroy() }
    }

    @Test fun onboardingOpensWithoutPastingOrFinishingTour() {
        app.getSharedPreferences("nezzel", 0).edit().clear().commit()
        val c = Robolectric.buildActivity(MainActivity::class.java, ShortcutActions.intent(app, ShortcutActions.PASTE_LINK)).setup()
        try {
            c.windowFocusChanged(true)
            c.newIntent(ShortcutActions.intent(app, ShortcutActions.PASTE_LINK))
            assertEquals(0, reads)
            assertFalse(AppPreferences(app).onboardingSeen)
            assertEquals("", c.get().links.text)
        } finally { c.pause().stop().destroy() }
    }

    @Test fun downloadsActionNavigatesOnColdAndWarmEntry() {
        val c = Robolectric.buildActivity(MainActivity::class.java, ShortcutActions.intent(app, ShortcutActions.OPEN_DOWNLOADS)).setup()
        try {
            assertEquals("Downloads", c.get().links.destination)
            c.get().links.navigate("Home")
            c.newIntent(ShortcutActions.intent(app, ShortcutActions.OPEN_DOWNLOADS))
            assertEquals("Downloads", c.get().links.destination)
            assertNull(c.get().intent.action)
            assertEquals(0, reads)
        } finally { c.pause().stop().destroy() }
    }
}
