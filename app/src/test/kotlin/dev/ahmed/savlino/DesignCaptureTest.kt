package dev.ahmed.savlino

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.downloads.DownloadsScreen
import dev.ahmed.savlino.downloads.LibraryLoadState
import dev.ahmed.savlino.downloads.SavedMedia
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp

/**
 * Design review captures, not acceptance evidence. Runs only when NEZZEL_CAPTURE_DIR is set;
 * fixture rows are synthetic and never reach the app.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class DesignCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)

    private fun idle() = repeat(4) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    }

    private fun save(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun variants(block: (String, Boolean) -> Unit) {
        for (lang in listOf("en", "ar")) for (dark in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            block(lang, dark)
        }
    }

    private fun composable(name: String, content: @Composable (String) -> Unit) = variants { lang, dark ->
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent {
                NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) { content(lang) }
            }
            idle()
            save(controller.get().window.decorView, "$name-$lang-${if (dark) "dark" else "light"}")
        } finally { controller.pause().stop().destroy() }
    }

    /** 384dp. Reduced motion draws the indeterminate line as a still track, so no animation keeps the clock busy. */
    @Test fun pauseRows() {
        assumeTrue(dir != null)
        RuntimeEnvironment.setQualifiers("+w384dp")
        pauseRowsAt("pause-rows-384")
    }

    private fun pauseRowsAt(file: String) {
        composable(file) { lang ->
            val name = if (lang == "ar") "فيديو العائلة.mp4" else "Family holiday.mp4"
            val running = DownloadProgress("a", name, DownloadPhase.DOWNLOADING, bytes = 42_000_000, total = Size.Exact(100_000_000),
                bytesPerSecond = 2_500_000.0, remainingTransferSeconds = 30, stalled = false, qualityHeight = 720)
            val paused = running.copy(jobId = "b", phase = DownloadPhase.PREPARING, paused = true, hasPartial = true,
                bytesPerSecond = null, remainingTransferSeconds = null, stalled = null)
            val waiting = DownloadProgress("c", name, DownloadPhase.PREPARING, waiting = true, hasPartial = true, total = Size.Exact(100_000_000), qualityHeight = 720)
            val restarting = DownloadProgress("d", name, DownloadPhase.PREPARING, total = Size.Exact(100_000_000), restartEpoch = 2, qualityHeight = 720)
            val failed = DownloadProgress("e", name, DownloadPhase.FAILED, error = DownloadError.Network, retryable = true)
            androidx.compose.runtime.CompositionLocalProvider(dev.ahmed.savlino.downloads.LocalOffline provides true,
                dev.ahmed.savlino.ui.theme.LocalReducedMotion provides true) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    for (job in listOf(running, paused, waiting, restarting, failed))
                        dev.ahmed.savlino.downloads.JobRow(job, {}, {}, {})
                }
            }
        }
    }

    /**
     * Rows with real artwork: photos from NEZZEL_ART_DIR (local, never committed), the last row without any so the
     * kind icon shows next to them. Skipped unless both env vars are set.
     */
    @Test fun thumbRows() {
        val art = System.getenv("NEZZEL_ART_DIR")?.let(::File)
        assumeTrue(dir != null && art != null)
        val files = mapOf("a" to "beach.jpg", "b" to "concert.jpg", "c" to "cooking.jpg", "d" to "tall.jpg", "e" to "city.jpg")
        val load: suspend (String) -> Bitmap? = { id -> files[id]?.let { android.graphics.BitmapFactory.decodeFile(File(art, it).path) } }
        composable("thumb-rows-384") { lang ->
            val ar = lang == "ar"
            val running = DownloadProgress("a", if (ar) "عطلة العائلة.mp4" else "Family holiday.mp4", DownloadPhase.DOWNLOADING,
                bytes = 42_000_000, total = Size.Exact(100_000_000), bytesPerSecond = 2_500_000.0, remainingTransferSeconds = 30, stalled = false)
            val paused = DownloadProgress("b", if (ar) "أغنية المساء.m4a" else "Evening song.m4a", DownloadPhase.PREPARING, bytes = 3_200_000,
                total = Size.Exact(5_000_000), paused = true, hasPartial = true, mimeType = "audio/mp4")
            val waiting = DownloadProgress("c", if (ar) "وصفات سريعة.mp4" else "Recipe tips.mp4", DownloadPhase.PREPARING, waiting = true,
                hasPartial = true, total = Size.Exact(100_000_000), qualityHeight = 720)
            val restarting = DownloadProgress("d", if (ar) "قدر الطبخ.mp4" else "Cooking pot.mp4", DownloadPhase.PREPARING,
                total = Size.Exact(100_000_000), restartEpoch = 2, qualityHeight = 720)
            val failed = DownloadProgress("e", if (ar) "المدينة ليلًا.mp4" else "City at night.mp4", DownloadPhase.FAILED,
                error = DownloadError.Network, retryable = true)
            val noArt = DownloadProgress("f", if (ar) "مقطع بلا صورة.mp4" else "No picture.mp4", DownloadPhase.DOWNLOADING,
                bytes = 70_000_000, total = Size.Exact(100_000_000), bytesPerSecond = 2_500_000.0, remainingTransferSeconds = 12, stalled = false)
            androidx.compose.runtime.CompositionLocalProvider(dev.ahmed.savlino.downloads.LocalOffline provides true,
                dev.ahmed.savlino.ui.theme.LocalReducedMotion provides true, dev.ahmed.savlino.downloads.LocalJobArtwork provides load) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    for (job in listOf(running, paused, waiting, restarting, failed, noArt))
                        dev.ahmed.savlino.downloads.JobRow(job, {}, {}, {})
                }
            }
        }
    }

    @Test fun home() {
        assumeTrue(dir != null)
        variants { lang, dark ->
            val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
            try { idle(); save(controller.get().window.decorView, "home-$lang-${if (dark) "dark" else "light"}") }
            finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun downloads() {
        assumeTrue(dir != null)
        composable("downloads") { lang -> DownloadsScreen(jobs, lang, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(media)) }
    }

    @Test fun downloadsEmpty() {
        assumeTrue(dir != null)
        composable("downloads-empty") { lang -> DownloadsScreen(emptyList(), lang, {}, {}, {}, {}, {}, null, {}, {}) }
    }

    /** Home's live parts with a running job, a copied-link chip and recent files, above the real nav bar. */
    @Test fun homeActive() {
        assumeTrue(dir != null)
        homeActive("home-active")
    }

    @Test fun homeActiveLargeText() {
        assumeTrue(dir != null)
        RuntimeEnvironment.setFontScale(2f)
        try { homeActive("home-active-200") } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    private fun homeActive(name: String) {
        composable(name) { lang ->
            val vm = androidx.compose.runtime.remember { dev.ahmed.savlino.ui.LinkViewModel(androidx.lifecycle.SavedStateHandle()) }
            androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp)) {
                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.app_name), style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                    HomeLinkCard(vm, "youtube.com") {}
                    dev.ahmed.savlino.downloads.RecentFiles(media.take(2), lang, {}) {}
                }
                dev.ahmed.savlino.downloads.DownloadNavigation("Home", jobs, lang) {}
            }
            }
        }
    }

    @Test fun downloadsLargeText() {
        assumeTrue(dir != null)
        RuntimeEnvironment.setFontScale(2f)
        try { composable("downloads-200") { lang -> DownloadsScreen(jobs, lang, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(media)) } }
        finally { RuntimeEnvironment.setFontScale(1f) }
    }

    /** The options sheet and the full quality list for a portrait reel with repeated renditions. */
    @Test fun options() {
        assumeTrue(dir != null)
        composable("options") { lang ->
            val vm = androidx.compose.runtime.remember { readyLinks() }
            androidx.compose.material3.Surface { dev.ahmed.savlino.ui.OptionsSheetContent(vm, lang) {} }
        }
        // A tall window so the whole list (audio, MP3, Other options) is in one capture.
        RuntimeEnvironment.setQualifiers("+h1400dp")
        try { composable("qualities") { lang -> dev.ahmed.savlino.ui.MoreFormatsScreen(androidx.compose.runtime.remember { readyLinks() }, lang) } }
        finally { RuntimeEnvironment.setQualifiers("+h832dp") }
        RuntimeEnvironment.setFontScale(2f)
        try { composable("options-200") { lang ->
            val vm = androidx.compose.runtime.remember { readyLinks() }
            androidx.compose.material3.Surface { dev.ahmed.savlino.ui.OptionsSheetContent(vm, lang) {} }
        } } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    /** Item 8, Download on a stale sheet: "Checking again…" while the link is read again, then the note when the pick changed. */
    @Test fun staleSheet() {
        assumeTrue(dir != null)
        for (changed in listOf(false, true)) composable(if (changed) "stale-changed" else "stale-refreshing") { lang ->
            val vm = androidx.compose.runtime.remember { readyLinks(stale = if (changed) Stale.CHANGED else Stale.HELD) }
            androidx.compose.material3.Surface { dev.ahmed.savlino.ui.OptionsSheetContent(vm, lang) {} }
        }
    }
    private enum class Stale { HELD, CHANGED }

    private fun readyLinks(stale: Stale? = null): dev.ahmed.savlino.ui.LinkViewModel {
        val url = "https://example.org/reel"
        val source = dev.ahmed.savlino.domain.LinkRevision(dev.ahmed.savlino.domain.LinkInput.parse(url)!!, 1)
        fun v(id: String, side: Int, long: Int, codec: String, kb: Long) = dev.ahmed.savlino.domain.Format(id, "mp4", true, false,
            videoCodec = codec, width = side, height = long, size = Size.Estimated(kb * 1000), audioFormatId = "a45", requiresMerge = true)
        val rows = listOf(v("h1080", 1080, 1920, "avc1", 4700), v("v1080", 1080, 1920, "vp09", 3900), v("h720", 720, 1280, "avc1", 1500),
            v("h540", 540, 960, "avc1", 1700), v("h360", 360, 640, "avc1", 940), v("a1080", 1080, 1920, "av01", 3100),
            dev.ahmed.savlino.domain.Format("a45", "m4a", false, true, audioCodec = "mp4a", bitrateKbps = 45, size = Size.Exact(186_300)),
            dev.ahmed.savlino.domain.Format("mp3-128", "mp3", false, true, conversion = dev.ahmed.savlino.domain.AudioConversion.MP3_128, sourceFormatId = "a45", sourceContainer = "m4a"))
        val presets = listOf(dev.ahmed.savlino.domain.Preset.BEST to "h1080", dev.ahmed.savlino.domain.Preset.BALANCED to "h720",
            dev.ahmed.savlino.domain.Preset.SMALL to "h360", dev.ahmed.savlino.domain.Preset.AUDIO_M4A to "a45")
            .map { dev.ahmed.savlino.domain.PresetChoice(it.first, it.second) }
        fun inspection(rows: List<dev.ahmed.savlino.domain.Format>, presets: List<dev.ahmed.savlino.domain.PresetChoice>) =
            dev.ahmed.savlino.engine.InspectResult.Success(dev.ahmed.savlino.domain.Inspection(
            source, "v1", rows, presets, title = "Sunset over the old harbour", uploader = "Harbour club", durationSeconds = 42,
            roughSizeBytes = mapOf("mp3-128" to 680_000L)))
        val first = inspection(rows, presets)
        // The fresh read no longer offers the 1080p rows the sheet showed.
        val fresh = inspection(rows.filterNot { it.id.endsWith("1080") }, presets.filterNot { it.formatId == "h1080" })
        val engine = dev.ahmed.savlino.engine.FakeEngine(first, inspectionScripts = mapOf(
            "2" to dev.ahmed.savlino.engine.FakeEngine.InspectionScript(fresh, awaitRelease = stale == Stale.HELD)))
        var operation = 0
        var clock = 0L
        return dev.ahmed.savlino.ui.LinkViewModel(androidx.lifecycle.SavedStateHandle(), engine = engine, currentEngineVersion = { "v1" },
            newOperationId = { (++operation).toString() }, now = { clock }).apply {
            edit(url); checkLink(); shadowOf(Looper.getMainLooper()).idle(); selectPreset(dev.ahmed.savlino.domain.Preset.BEST)
            if (stale != null) {
                clock += dev.ahmed.savlino.ui.SHEET_FRESH_MS
                confirmDownload(); shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    companion object {
        private const val MB = 1_000_000L
        val jobs = listOf(
            DownloadProgress("j4", "Morning recitation.m4a", DownloadPhase.FAILED, error = DownloadError.values().first(), retryable = true),
            DownloadProgress("j3", "Cooking at home - episode 12.mp4", DownloadPhase.PREPARING, waiting = true),
            DownloadProgress("j2", "Family trip highlights.mp4", DownloadPhase.JOINING, total = Size.Exact(240 * MB)),
            DownloadProgress("j1", "How to fix a bike chain in 5 minutes.mp4", DownloadPhase.DOWNLOADING,
                bytes = 42_800_000, total = Size.Exact(68 * MB), qualityHeight = 720,
                bytesPerSecond = 2_100_000.0, remainingTransferSeconds = 12, stalled = false),
        )
        private val now = System.currentTimeMillis() / 1000
        val media = listOf(
            SavedMedia(Uri.parse("content://media/external/downloads/1"), "Sunset over the old harbour, filmed from the ferry.mp4", "Sunset over the old harbour, filmed from the ferry", "video/mp4", 68 * MB, now - 600, 720, null, 522_000),
            SavedMedia(Uri.parse("content://media/external/downloads/2"), "Podcast episode 41 - slow mornings.m4a", "Podcast episode 41: slow mornings", "audio/mp4", 8_100_000, now - 4000, null, 128, 2_710_000),
            SavedMedia(Uri.parse("content://media/external/downloads/3"), "Kids football final.mp4", "Kids football final", "video/mp4", 312 * MB, now - 90_000, 1080, null, 1_262_000),
            SavedMedia(Uri.parse("content://media/external/downloads/4"), "Poster from the post.jpg", "Poster from the post", "image/jpeg", 410_000, now - 200_000, null, null, null, width = 1080),
        )
    }
}
