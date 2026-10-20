package dev.ahmed.savlino.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.*
import dev.ahmed.savlino.*
import dev.ahmed.savlino.diagnostics.CrashReports
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w360dp-h2400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrashReportActionsTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val store get() = CrashReports.store(context)
    @Before fun clearReports() { store.deleteReports() }
    private fun nodes(a: ComponentActivity): List<SemanticsNode> { compose.waitForIdle(); return semanticsNodes(a) }
    private fun render(check: (ComponentActivity) -> Unit) =
        renderThemed({ nodes(it) }, { AboutSection("1.2.3", 42) }, check)
    private fun row(a: ComponentActivity, id: Int) = nodes(a).last { node ->
        node.config.getOrNull(SemanticsActions.OnClick) != null &&
            descendants(node).any { context.getString(id) in texts(listOf(it)) }
    }
    private fun click(node: SemanticsNode) { assertEquals(true, node.config[SemanticsActions.OnClick].action?.invoke()) }
    private fun seed() { store.record(Thread("main"), IllegalStateException("https://example.invalid/x content://private/1")) }

    @Test fun aboutHidesCrashActionsWithoutReports() = render { a ->
        assertFalse(texts(nodes(a)).contains(context.getString(R.string.crash_share)))
        assertFalse(texts(nodes(a)).contains(context.getString(R.string.crash_delete)))
        assertNull(shadowOf(a).nextStartedActivity)
    }

    private fun assertShareAndDelete() {
        seed()
        render { a ->
            compose.waitUntil(5_000) { context.getString(R.string.crash_share) in texts(nodes(a)) }
            for (id in listOf(R.string.crash_share, R.string.crash_delete)) {
                val action = row(a, id)
                assertEquals(Role.Button, action.config.getOrNull(SemanticsProperties.Role))
                assertTrue(action.layoutInfo.height / context.resources.displayMetrics.density >= 47.9f)
            }
            assertNull(shadowOf(a).nextStartedActivity) // Merely opening Settings never sends anything.
            click(row(a, R.string.crash_share))
            compose.waitUntil(5_000) { shadowOf(a).peekNextStartedActivity() != null }
            val chooser = shadowOf(a).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action); assertEquals("text/plain", send.type)
            val text = send.getStringExtra(Intent.EXTRA_TEXT)!!
            assertTrue("java.lang.IllegalStateException" in text)
            assertFalse("https:" in text); assertFalse("content:" in text)
            assertFalse(send.hasExtra(Intent.EXTRA_STREAM))
            click(row(a, R.string.crash_delete))
            compose.waitUntil(5_000) { context.getString(R.string.crash_share) !in texts(nodes(a)) }
            assertFalse(store.hasReports()); assertNull(store.latestReports())
            assertFalse(texts(nodes(a)).contains(context.getString(R.string.crash_delete)))
        }
    }
    @Test fun aboutShowsReportsAndSharesOnlyOnTapThenDeletes() = assertShareAndDelete()
    @Config(qualifiers = "ar-w360dp-h2400dp-mdpi")
    @Test fun arabicCrashActionsAreLabelledAndDeleteHidesThem() = assertShareAndDelete()
}
