package dev.ahmed.savlino.downloads

import dev.ahmed.savlino.PrivacySettings
import dev.ahmed.savlino.R
import dev.ahmed.savlino.descendants
import dev.ahmed.savlino.renderThemed
import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.texts
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w400dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CopyLinkUiTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val video = SavedMedia(uri = Uri.parse("content://media/external_primary/downloads/1"),
        filename = "Movie.mp4", title = "Movie", mimeType = "video/mp4", size = null, addedSeconds = 100, height = 720, bitrateKbps = null, durationMillis = 84000, volumeName = "external_primary")
    private val audio = video.copy(uri = Uri.parse("content://media/external_primary/downloads/2"), filename = "Song.mp3",
        title = "Song", mimeType = "audio/mpeg")
    private val link = "https://www.example.com/watch?v=1"
    private fun nodes(a: ComponentActivity): List<SemanticsNode> {
        compose.waitForIdle()
        return semanticsNodes(a, rounds = 4, idleMillis = 200)
    }
    private fun clickable(a: ComponentActivity, label: String): SemanticsNode? = nodes(a).lastOrNull {
        it.config.getOrNull(SemanticsActions.OnClick) != null &&
            (texts(descendants(it)).contains(label) || descendants(it).any { child ->
                child.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label)
            })
    }
    private fun click(a: ComponentActivity, label: String) {
        assertEquals(true, (clickable(a, label) ?: error("Missing clickable $label")).config[SemanticsActions.OnClick].action?.invoke())
    }
    private fun render(content: @Composable () -> Unit, check: (ComponentActivity) -> Unit) = renderThemed({ nodes(it) }, content, check)
    @Composable private fun screen(copy: LinkCopy?) = CompositionLocalProvider(LocalLinkCopy provides copy) {
        DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(listOf(video, audio)))
    }

    @Test fun copyLinkShowsOnlyForAFileWithALinkAndCopiesThatLink() {
        val copied = mutableListOf<String>()
        val copy = LinkCopy(find = { if (it.uri == video.uri) link else null }, copy = { copied += it })
        render({ screen(copy) }) { a ->
            val label = context.getString(R.string.copy_link)
            click(a, context.getString(R.string.saved_more, audio.title))
            assertNull(clickable(a, label))
            click(a, context.getString(R.string.share_file))
            click(a, context.getString(R.string.saved_more, video.title))
            val row = clickable(a, label) ?: error("Missing Copy link")
            // Host only, without www; never the full link.
            assertTrue(texts(descendants(row)).contains("example.com"))
            assertFalse(texts(nodes(a)).any { it.contains("watch") })
            click(a, label)
            assertEquals(listOf(link), copied)
        }
    }

    @Test fun withoutLinkCopyTheSheetHasNoCopyLink() = render({ screen(null) }) { a ->
        click(a, context.getString(R.string.saved_more, video.title))
        assertNotNull(clickable(a, context.getString(R.string.share_file)))
        assertNull(clickable(a, context.getString(R.string.copy_link)))
    }

    @Test fun clearLinkHistoryClearsOnlyAfterConfirmation() {
        var cleared = 0
        render({ PrivacySettings { cleared++ } }) { a ->
            click(a, context.getString(R.string.clear_link_history))
            assertTrue(texts(nodes(a)).contains(context.getString(R.string.clear_link_history_title)))
            click(a, context.getString(R.string.cancel))
            assertEquals(0, cleared)
            click(a, context.getString(R.string.clear_link_history))
            click(a, context.getString(R.string.clear))
            compose.waitForIdle()
            assertEquals(1, cleared)
            assertFalse(texts(nodes(a)).contains(context.getString(R.string.clear_link_history_title)))
        }
    }
}
