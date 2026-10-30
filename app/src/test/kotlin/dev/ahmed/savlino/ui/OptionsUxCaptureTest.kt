package dev.ahmed.savlino.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.jobs.SavedBefore
import dev.ahmed.savlino.media.ImageSaver
import dev.ahmed.savlino.locale.AppLocale
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.statuses.FakeStatusLibrary
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.item
import dev.ahmed.savlino.statuses.FakeStatusLibrary.Companion.ready
import dev.ahmed.savlino.statuses.ui.*
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.ui.theme.Space
import java.io.File
import java.time.Duration
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Arabic light owner-review renders with synthetic fixtures; writes only with NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ar-w384dp-h832dp-xxhdpi")
class OptionsUxCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)

    private fun capture(name: String, setup: (CoroutineScope) -> @Composable () -> Unit) {
        assumeTrue(dir != null)
        RuntimeEnvironment.setQualifiers("+ar-notnight")
        AppLocale.choose(RuntimeEnvironment.getApplication(), "ar")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            val body = setup(scope)
            controller.get().setContent {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    NezzelTheme(ThemePref.Light, false) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) { body() }
                    }
                }
            }
            repeat(6) { Snapshot.sendApplyNotifications(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) }
            val view = controller.get().window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            dir!!.mkdirs()
            File(dir, "$name-ar-light.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally { scope.cancel(); controller.pause().stop().destroy() }
    }

    @Test fun playlistMp3AndRememberedQuality() = capture("ux-playlist-quality") { scope ->
        val source = LinkRevision(LinkInput.parse("https://youtube.com/playlist?list=PLabcdefghij")!!, 1)
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun listPlaylist(source: LinkRevision, operationId: String, page: PlaylistPageToken?) =
                PlaylistResult.Success(PlaylistPage("دروس قصيرة", listOf(
                    PlaylistItem("one", "https://youtu.be/abcdefghijk", "الدرس الأول", 60)), 1, null))
        }
        val vm = PlaylistViewModel(engine, scope, { false }, { _, _, _ -> true }, preferredPreset = { Preset.SMALL })
        vm.open(source)
        val body: @Composable () -> Unit = { PlaylistSheetContent(vm, "ar") }
        body
    }

    @Test fun multiLinkMp3AndRememberedQuality() = capture("ux-multi-link-quality") { scope ->
        val vm = MultiLinkViewModel(scope, { _, _, _ -> true }, preferredPreset = { Preset.SMALL })
        vm.open(MultiLinks.from("https://example.org/one https://example.org/two")!!)
        val body: @Composable () -> Unit = { MultiLinkSheetContent(vm, "ar") }
        body
    }

    @Test fun savedBeforeOpen() = capture("ux-saved-before-open") { _ ->
        val saved = SavedBefore(Uri.parse("content://media/external/downloads/7"), 1_700_000_000_000L)
        val body: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().padding(Space.l)) {
                SavedBeforeNotice(saved, "ar")
            }
        }
        body
    }

    @Test fun carouselSaveOnlySound() = capture("ux-carousel-sound") { scope ->
        val staging = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "carousel-capture")
        val saver = ImageSaver(FakeFetch(), RecordingPublisher(), staging, noSpaceCheck, scope)
        val vm = LinkViewModel(SavedStateHandle(), engine = UnavailableEngine(), imageSaver = saver,
            images = FakeImages { tikTokPhotoSuccess(it, 3) }, previewDispatcher = Dispatchers.Unconfined,
            previewImage = { preview ->
                // Opaque synthetic pixels distinguish a loaded preview from the grey placeholder.
                Bitmap.createBitmap(preview.width, preview.height, Bitmap.Config.ARGB_8888)
                    .apply { eraseColor(0xFF9EF2E1.toInt()) }.asImageBitmap()
            })
        vm.edit("https://www.tiktok.com/@synthetic/photo/900000001"); vm.checkLink()
        shadowOf(Looper.getMainLooper()).idle()
        check(vm.imageSaveEnabled && !vm.imageSaving) { "Capture must show the ready Save action" }
        check((vm.optionsState as OptionsState.ImageReady).previews.isNotEmpty())
        val body: @Composable () -> Unit = { OptionsSheetContent(vm, "ar", vm::dismissOptions) }
        body
    }

    @Test fun statusesExactSelectionSize() = capture("ux-statuses-selection-size") { scope ->
        val library = FakeStatusLibrary(ready(listOf(item(1, false).copy(sizeBytes = 2_000_000),
            item(2, false).copy(sizeBytes = 3_000_000))))
        val state = StatusesState(library, scope)
        state.refresh(); shadowOf(Looper.getMainLooper()).idle(); state.selectAll()
        val body: @Composable () -> Unit = {
            CompositionLocalProvider(LocalStatusMedia provides FakeStatusMedia()) { StatusesScreen(library, "ar", state) }
        }
        body
    }
}
