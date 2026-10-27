package dev.ahmed.savlino.downloads

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import dev.ahmed.savlino.R
import dev.ahmed.savlino.descendants
import dev.ahmed.savlino.renderThemed
import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.sharing.FileShare
import dev.ahmed.savlino.sharing.FileSharer
import dev.ahmed.savlino.sharing.LocalFileSharer
import dev.ahmed.savlino.texts
import dev.ahmed.savlino.usage.UsageEvents
import dev.ahmed.savlino.waitWhile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The saved-file sheet: installed WhatsApp apps by their own names, then Other apps; without one, the plain Share row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w400dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SavedShareSheetTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val video = SavedMedia(uri = Uri.parse("content://media/external_primary/downloads/1"),
        filename = "Movie.mkv", title = "Movie", mimeType = "video/x-matroska", size = null, addedSeconds = 100, height = 360,
        bitrateKbps = null, durationMillis = 84000, volumeName = "external_primary")
    private val messages = mutableListOf<Int>()

    private fun nodes(a: ComponentActivity): List<SemanticsNode> { compose.waitForIdle(); return semanticsNodes(a, rounds = 4, idleMillis = 200) }
    private fun clickable(a: ComponentActivity, label: String): SemanticsNode? = nodes(a).lastOrNull {
        it.config.getOrNull(SemanticsActions.OnClick) != null && (texts(descendants(it)).contains(label) ||
            descendants(it).any { c -> c.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label) })
    }
    private fun click(a: ComponentActivity, label: String) =
        assertEquals(true, (clickable(a, label) ?: error("Missing $label")).config[SemanticsActions.OnClick].action?.invoke())

    private fun install(pkg: String, label: String) {
        val pm = shadowOf(context.packageManager)
        pm.installPackage(android.content.pm.PackageInfo().apply {
            packageName = pkg
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg; enabled = true; nonLocalizedLabel = label }
        })
        val send = android.content.ComponentName(pkg, "$pkg.Send")
        pm.addActivityIfNotPresent(send)
        pm.addIntentFilterForActivity(send, android.content.IntentFilter(Intent.ACTION_SEND, "video/*").apply { addCategory(Intent.CATEGORY_DEFAULT) })
    }

    private fun render(check: (ComponentActivity) -> Unit) = renderThemed({ nodes(it) }, {
        val activity = LocalContext.current
        val scope = rememberCoroutineScope()
        // Unconfined: the WhatsApp lookup and file check run inline, so a loaded IO pool can never outlast the waits below.
        val sharer = remember { FileSharer(activity, UsageEvents.None, scope, { messages += it }, kotlinx.coroutines.Dispatchers.Unconfined) }
        CompositionLocalProvider(LocalFileSharer provides sharer) {
            DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(listOf(video)))
        }
    }, check)

    @Test fun bothWhatsAppsThenOtherAppsReplaceShare() {
        install(FileShare.WHATSAPP, "WhatsApp")
        install(FileShare.WHATSAPP_BUSINESS, "WA Business")
        render { a ->
            click(a, context.getString(R.string.saved_more, video.title))
            waitWhile({ nodes(a) }, timeoutMs = 15_000) { clickable(a, context.getString(R.string.share_other_apps)) == null }
            listOf("WhatsApp", "WA Business", context.getString(R.string.share_other_apps)).forEach { label ->
                assertTrue("$label row", (clickable(a, label) ?: error("Missing $label")).size.height >= 48)
            }
            assertNull(clickable(a, context.getString(R.string.share_file)))
            // The row goes through the file check first: this fixture's file is not readable, so it says so and opens nothing.
            click(a, "WA Business")
            waitWhile({ nodes(a) }) { messages.isEmpty() }
            assertEquals(listOf(R.string.share_file_unavailable), messages)
            assertNull(shadowOf(a).nextStartedActivity)
        }
    }

    @Test fun noWhatsAppKeepsThePlainShareRow() = render { a ->
        click(a, context.getString(R.string.saved_more, video.title))
        waitWhile({ nodes(a) }, timeoutMs = 15_000) { clickable(a, context.getString(R.string.share_file)) == null }
        assertNull(clickable(a, context.getString(R.string.share_other_apps)))
        assertNull(clickable(a, "WhatsApp"))
    }
}
