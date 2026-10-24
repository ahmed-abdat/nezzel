package dev.ahmed.savlino.downloads

import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.snapshots.Snapshot
import dev.ahmed.savlino.AdvancedSettings
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Design review captures of the old-downloads offer. Runs only with NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class OldDownloadsCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val offer = OldDownloadsOffer(card = true, canGrant = true, show = {}, dismiss = {})
    private val granted = OldDownloadsOffer(card = false, canGrant = false, show = {}, dismiss = {})
    private val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Download/Nezzel")
    private fun found(name: String, mime: String, added: Long, path: String = "Download/Nezzel/") = SavedMedia(
        DocumentsContract.buildDocumentUriUsingTree(tree, "primary:$path$name"), name, name.substringBeforeLast('.'), mime,
        8_400_000L + added * 1000, added, if (mime.startsWith("audio")) null else 1080, null,
        if (mime.startsWith("image")) null else 94_000L, width = if (mime.startsWith("audio")) null else 1920, relativePath = path)
    private val ownFile = SavedMedia(Uri.parse("content://media/external_primary/downloads/7"), "Morning walk.mp4", "Morning walk",
        "video/mp4", 12_000_000, 1_761_300_000, 1080, null, 31_000, width = 1920, relativePath = "Download/Nezzel/",
        volumeName = "external_primary")
    private val foundFiles = listOf(found("Cooking recipe.mp4", "video/mp4", 1_761_200_000),
        found("Lecture part 2.m4a", "audio/mp4", 1_760_900_000), found("Family photo.jpg", "image/jpeg", 1_760_800_000),
        found("Status 4.mp4", "video/mp4", 1_760_700_000, "Download/Nezzel/Statuses/"))

    private fun idle() = repeat(4) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    }

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable (String) -> Unit) {
        assumeTrue(dir != null)
        for (lang in listOf("en", "ar")) for (dark in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                controller.get().setContent { NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) { content(lang) } }
                idle()
                val root = controller.get().window.decorView
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                dir!!.mkdirs()
                File(dir, "$name-$lang-${if (dark) "dark" else "light"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun cardOnEmptyLibrary() = shoot("card-empty") { lang ->
        DownloadsScreen(emptyList(), lang, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(emptyList()), oldDownloads = offer)
    }

    @Test fun cardAboveFirstOwnFile() = shoot("card-with-file") { lang ->
        DownloadsScreen(emptyList(), lang, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(listOf(ownFile)), oldDownloads = offer)
    }

    @Test fun libraryWithFoundFiles() = shoot("library-found") { lang ->
        DownloadsScreen(emptyList(), lang, {}, {}, {}, {}, {}, null, {}, {},
            libraryLoad = LibraryLoadState.Ready(listOf(ownFile) + foundFiles), oldDownloads = granted)
    }

    @Test fun settingsAdvancedRow() = shoot("settings-row") { lang ->
        AdvancedSettings(null, {}, null, parallel = 2, language = lang, oldDownloads = offer) {}
    }
}
