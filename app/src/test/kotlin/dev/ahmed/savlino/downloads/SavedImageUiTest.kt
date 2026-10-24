package dev.ahmed.savlino.downloads

import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.descendants
import dev.ahmed.savlino.texts
import dev.ahmed.savlino.renderThemed
import android.content.*
import android.database.Cursor
import android.net.Uri
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.NezzelFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w400dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SavedImageUiTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val image get() = SavedMedia(uri = Uri.parse("content://media/external/downloads/3"),
        filename = "Photo.jpg", title = "Photo", mimeType = "image/jpeg", size = null,
        addedSeconds = 100, height = 720, bitrateKbps = 128, durationMillis = 84000)
    private val video get() = image.copy(uri = Uri.parse("content://media/external/downloads/1"),
        filename = "Movie.mp4", title = "Movie", mimeType = "video/mp4")
    private val audio get() = image.copy(uri = Uri.parse("content://media/external/downloads/2"),
        filename = "Song.mp3", title = "Song", mimeType = "audio/mpeg")
    private fun nodes(a: ComponentActivity): List<SemanticsNode> {
        compose.waitForIdle()
        return semanticsNodes(a, rounds = 4, idleMillis = 200)
    }
    private fun string(name: String): String {
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        assertTrue("Missing localized resource: $name", id != 0)
        return context.getString(id)
    }
    private fun clickNode(a: ComponentActivity, label: String): SemanticsNode = nodes(a).lastOrNull {
        it.config.getOrNull(SemanticsActions.OnClick) != null &&
            (texts(descendants(it)).contains(label) || descendants(it).any { child ->
                child.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label)
            })
    } ?: error("Missing clickable $label")
    private fun click(a: ComponentActivity, label: String) {
        assertEquals(true, clickNode(a, label).config[SemanticsActions.OnClick].action?.invoke())
    }
    private fun render(content: @Composable () -> Unit, check: (ComponentActivity) -> Unit) = renderThemed({ nodes(it) }, content, check)
    @Composable private fun screen(media: List<SavedMedia>, open: (SavedMedia) -> Unit = {},
        share: (SavedMedia) -> Unit = {}, openWith: (SavedMedia) -> Unit = {}, delete: (SavedMedia) -> Unit = {}) {
        DownloadsScreen(emptyList(), context.resources.configuration.locales[0].language,
            {}, {}, open, share, openWith, null, delete, {}, libraryLoad = LibraryLoadState.Ready(media))
    }
    @Test fun imageChipChangesVisibleRowsAndAllVideoAudioStayCorrect() = render({ screen(listOf(image, video, audio)) }) { a ->
        click(a, string("filter_images"))
        assertTrue(texts(nodes(a)).contains(NezzelFormat.firstStrong("Photo")))
        assertFalse(texts(nodes(a)).contains(NezzelFormat.firstStrong("Movie")))
        assertFalse(texts(nodes(a)).contains(NezzelFormat.firstStrong("Song")))
        click(a, context.getString(R.string.filter_videos))
        assertTrue(texts(nodes(a)).contains(NezzelFormat.firstStrong("Movie")))
        assertFalse(texts(nodes(a)).contains(NezzelFormat.firstStrong("Photo")))
        click(a, context.getString(R.string.audio_group))
        assertTrue(texts(nodes(a)).contains(NezzelFormat.firstStrong("Song")))
        assertFalse(texts(nodes(a)).contains(NezzelFormat.firstStrong("Photo")))
        click(a, context.getString(R.string.filter_all))
        val all = texts(nodes(a))
        listOf("Photo", "Movie", "Song").forEach { assertTrue(all.toString(), all.contains(NezzelFormat.firstStrong(it))) }
    }
    private fun wrappedChips() {
        RuntimeEnvironment.setFontScale(2f)
        render({ screen(listOf(image, video, audio)) }) { a ->
            assertEquals(2f, a.resources.configuration.fontScale, 0f)
            val labels = listOf(context.getString(R.string.filter_all), context.getString(R.string.filter_videos),
                context.getString(R.string.audio_group), string("filter_images"))
            val chips = labels.map { clickNode(a, it) }
            val density = context.resources.displayMetrics.density
            val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
            chips.forEach { chip ->
                val bounds = chip.boundsInRoot
                // Chips reserve at least 48dp for their layout and touch target.
                assertTrue("48dp touch height: $bounds", maxOf(bounds.height, chip.layoutInfo.height.toFloat()) / density >= 47.9f)
                assertTrue("48dp touch width: $bounds", maxOf(bounds.width, chip.layoutInfo.width.toFloat()) / density >= 47.9f)
                assertTrue("chip outside width $screenWidth: $bounds", bounds.left >= 0 && bounds.right <= screenWidth + .5f)
            }
            assertTrue("Chips must wrap at 320dp/200%: ${chips.map { it.boundsInRoot }}", chips.map { it.boundsInRoot.top }.distinct().size > 1)
            labels.forEach { label ->
                click(a, label)
                assertEquals(true, clickNode(a, label).config[SemanticsProperties.Selected])
            }
            assertTrue(texts(nodes(a)).contains(NezzelFormat.firstStrong("Photo")))
        }
    }
    @Test @Config(qualifiers = "en-w320dp-h1200dp-mdpi") fun english200PercentChipsWrapAndRemain48dpClickable() = wrappedChips()
    @Test @Config(qualifiers = "ar-w320dp-h1200dp-mdpi") fun arabic200PercentChipsWrapAndRemain48dpClickable() {
        wrappedChips()
        assertEquals(View.LAYOUT_DIRECTION_RTL, context.resources.configuration.layoutDirection)
    }
    @Test fun imageMetadataAndThumbnailSuppressMisleadingAudiovisualFields() = render({
        Column { Text(mediaMeta(image, "en")); MediaThumbnail(image, Modifier.size(112.dp, 63.dp)) }
    }) { a ->
        val text = texts(nodes(a))
        assertEquals(listOf(NezzelFormat.isolate(context.getString(R.string.saved_size_unknown))), text)
    }
    @Test fun audiovisualMetadataAndDurationRemainVisible() = render({
        Column { Text(mediaMeta(video, "en")); MediaThumbnail(video, Modifier.size(112.dp, 63.dp)); Text(mediaMeta(audio, "en")) }
    }) { a ->
        val text = texts(nodes(a))
        assertTrue(text.toString(), text.any { it.contains("720p") })
        assertTrue(text.toString(), text.any { it.contains("128 kbps") })
        assertTrue(text.toString(), text.contains("1:24"))
    }
    @Test @Config(qualifiers = "ar-w400dp-h1200dp-mdpi")
    fun savedAudioBitrateUsesArabicResource() = render({ Text(mediaMeta(audio, "ar")) }) { a ->
        assertTrue(texts(nodes(a)).any { it.contains("128 kbps") })
    }
    @Test @Config(qualifiers = "fr-w400dp-h1200dp-mdpi")
    fun savedAudioBitrateUsesFrenchResource() = render({ Text(mediaMeta(audio, "fr")) }) { a ->
        assertTrue(texts(nodes(a)).any { it.contains("128 kbit/s") })
    }
    private fun fixture(omitWidth: Boolean = false, check: (SavedMediaRepository, SavedMediaRepositoryTest.Provider) -> Unit) {
        val provider = object : SavedMediaRepositoryTest.Provider() {
            override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
                super.query(uri, if (omitWidth) columns?.filterNot { it == "width" }?.toTypedArray() else columns, selection, args, sort)
        }.apply { attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" }) }
        ShadowContentResolver.registerProviderInternal("media", provider)
        try { check(SavedMediaRepository(context.contentResolver, context.packageName), provider) } finally { provider.db.close() }
    }
    @Test @Config(qualifiers = "ar-w400dp-h1200dp-mdpi") fun providerImageDimensionsAreActualLatinLtrAndMissingAxesStayHonest() = fixture { repo, provider ->
        provider.insertRow(2, "image/jpeg", 200)
        provider.db.execSQL("UPDATE media SET width = 800, height = 596, _size = 42232 WHERE _id = 2")
        var item = repo.query().first { it.mimeType == "image/jpeg" }
        render({ Text(mediaMeta(item, "ar")) }) { a ->
            val n = nodes(a).single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.contains("800") } == true }
            assertEquals(NezzelFormat.isolate("800 × 596 · 42.2\u00a0KB"), texts(listOf(n)).single())
        }
        listOf("width = NULL", "width = 0", "width = -1", "height = NULL", "height = 0",
            "height = -1", "width = 2147483648", "width = 4294967297", "width = 9223372036854775807",
            "height = 2147483648", "height = 4294967297", "height = 9223372036854775807").forEach { assignment ->
            provider.db.execSQL("UPDATE media SET width = 800, height = 596 WHERE _id = 2")
            provider.db.execSQL("UPDATE media SET $assignment WHERE _id = 2")
            item = repo.query().first { it.mimeType == "image/jpeg" }
            render({ Text(mediaMeta(item, "ar")) }) { a ->
                assertEquals(assignment, listOf(NezzelFormat.isolate("42.2\u00a0KB")), texts(nodes(a)))
            }
        }
    }
    @Test fun providerOmittingWidthColumnDoesNotInventImageDimensions() = fixture(omitWidth = true) { repo, provider ->
        provider.insertRow(2, "image/png", 200)
        val item = repo.query().first { it.mimeType == "image/png" }
        render({ Text(mediaMeta(item, "en")) }) { a ->
            assertEquals(listOf(NezzelFormat.isolate(context.getString(R.string.saved_size_unknown))), texts(nodes(a)))
        }
    }
    @Test fun imageRowAndMenuActOnExactImageAndDeleteRequiresConfirmation() = fixture { repo, provider ->
        provider.insertRow(2, "image/png", 200)
        val item = repo.query().first { it.mimeType == "image/png" }
        var opened: Intent? = null
        var shared: Intent? = null
        var openWith: Intent? = null
        render({ screen(listOf(item), open = { opened = MediaActions.open(it) }, share = { shared = MediaActions.share(it) },
            openWith = { openWith = MediaActions.open(it) }, delete = { repo.delete(it.uri) }) }) { a ->
            val openRow = nodes(a).single { it.config.getOrNull(SemanticsActions.OnClick)?.label == context.getString(R.string.open_file) }
            assertTrue(openRow.config.isMergingSemanticsOfDescendants)
            assertEquals(true, openRow.config[SemanticsActions.OnClick].action?.invoke())
            assertEquals(item.uri, opened!!.data)
            assertEquals("image/png", opened!!.type)
            val more = context.getString(R.string.saved_more, item.title)
            click(a, more); click(a, context.getString(R.string.share_file))
            @Suppress("DEPRECATION") val send = shared!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            @Suppress("DEPRECATION") val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            assertEquals(item.uri, stream)
            assertEquals("image/png", send.type)
            click(a, more); click(a, context.getString(R.string.saved_open_with))
            assertEquals(item.uri, openWith!!.data)
            click(a, more); click(a, context.getString(R.string.saved_delete))
            assertEquals(2, repo.query().size)
            click(a, context.getString(R.string.cancel))
            assertEquals(2, repo.query().size)
            click(a, more); click(a, context.getString(R.string.saved_delete))
            assertEquals(2, repo.query().size)
            click(a, context.getString(R.string.saved_delete))
            assertEquals(listOf(1L), repo.query().map { ContentUris.parseId(it.uri) })
        }
    }
}
