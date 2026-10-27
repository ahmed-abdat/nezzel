package dev.ahmed.savlino

import android.content.ContentProvider
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.downloads.DownloadsScreen
import dev.ahmed.savlino.downloads.LibraryLoadState
import dev.ahmed.savlino.downloads.LibraryState
import dev.ahmed.savlino.downloads.SavedMedia
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.nav.TabContent
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
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

/** Serves generated gradient frames as thumbnails, so renders show the real loader's path with pictures. */
class FixtureThumbnails : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val index = uri.lastPathSegment!!.toInt()
        val file = File(context!!.cacheDir, "thumb-$index.png")
        if (!file.exists()) {
            val (from, to) = PALETTES[index % PALETTES.size]
            val bitmap = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawRect(0f, 0f, 320f, 200f, Paint().apply {
                shader = LinearGradient(0f, 0f, 320f, 200f, from.toInt(), to.toInt(), Shader.TileMode.CLAMP)
            })
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
    private companion object {
        val PALETTES = listOf(0xFF2E7D7A to 0xFFB2DFDB, 0xFF5D4037 to 0xFFFFCC80, 0xFF283593 to 0xFF90CAF9,
            0xFF37474F to 0xFFB0BEC5, 0xFFBF360C to 0xFFFFE0B2, 0xFF33691E to 0xFFDCE775, 0xFF455A64 to 0xFFCFD8DC)
    }
}

private object HomeFixtures {
    private fun item(id: Int, name: String, duration: Long, size: Long) = SavedMedia(
        uri = Uri.parse("content://nezzel.fixture/thumb/$id"), filename = name, title = name.substringBeforeLast('.'),
        mimeType = "video/mp4", size = size, addedSeconds = 1_790_000_000L - id * 3600L, height = 1080, bitrateKbps = null,
        durationMillis = duration, width = 1920)
    val library = listOf(item(0, "Big Buck Bunny.mp4", 596_000, 118_000_000), item(1, "Sintel.mp4", 888_000, 164_000_000),
        item(2, "Spring.mp4", 464_000, 92_000_000), item(3, "Tears of Steel.mp4", 734_000, 131_000_000),
        item(4, "Cosmos Laundromat.mp4", 730_000, 81_700_000), item(5, "Charge.mp4", 231_000, 47_000_000),
        item(6, "Wing It.mp4", 254_000, 51_000_000), item(7, "Coffee Run.mp4", 184_000, 39_000_000),
        item(8, "Agent 327.mp4", 231_000, 47_000_000), item(9, "Elephants Dream.mp4", 654_000, 103_000_000))
    val running = DownloadProgress("r1", "Caminandes Llamigos.mp4", DownloadPhase.DOWNLOADING, bytes = 52_000_000, total = Size.Exact(118_000_000),
        bytesPerSecond = 3_100_000.0, remainingTransferSeconds = 21, stalled = false, qualityHeight = 1080)
}

/** The real Home + T1 bar, as NezzelApp wires them, with fixture rows instead of the journal and MediaStore. */
@Composable
private fun HomeShell(lang: String, dark: Boolean, withDownloads: Boolean, copied: Boolean, route: String, onRoute: (String) -> Unit = {},
                      onPaste: () -> Unit = {}, load: LibraryLoadState? = null, fontScale: Float = 1f) {
    // The engine is shown as available (the real-world look); automatic options stay off, so nothing is inspected.
    val vm = remember { LinkViewModel(SavedStateHandle(), engine = dev.ahmed.savlino.engine.FakeEngine()).apply {
        automaticOptions = false
        if (copied) suggest(resumed = true, focused = true, foreground = true) { "https://youtube.com/watch?v=aqz-KE-bpKQ" }
    } }
    val media = if (withDownloads) HomeFixtures.library else emptyList()
    val jobs = if (withDownloads) listOf(HomeFixtures.running) else emptyList()
    val library = remember(media) { LibraryState(load ?: LibraryLoadState.Ready(media), {}, {}, {}, null, {}, {}, {}, { _, _ -> }) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    CompositionLocalProvider(LocalLayoutDirection provides if (lang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale)) {
        NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
            NavigationScaffold(route, { jobs }, lang, onRoute, updateAvailable = withDownloads) {
                TabContent(route) { r ->
                    when (r) {
                        "Downloads" -> DownloadsScreen(jobs, lang, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(media))
                        "Settings" -> SettingsScreen(vm, lang, if (dark) ThemePref.Dark else ThemePref.Light, false, true, {}, {}, {}, {}, {}, {}, updates = null)
                        "Statuses" -> dev.ahmed.savlino.statuses.ui.StatusesScreen(null, lang)
                        else -> HomeScreen(vm, { jobs }, library, lang, onPaste, {}, {}, {})
                    }
                }
            }
        }
    }
}

private fun ComponentActivity.capture(file: File, scale: Float = 1f) {
    val root = window.decorView
    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
    root.draw(Canvas(bitmap))
    val out = if (scale == 1f) bitmap else Bitmap.createScaledBitmap(bitmap, (root.width * scale).toInt(), (root.height * scale).toInt(), true)
    file.parentFile!!.mkdirs()
    file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    if (out !== bitmap) out.recycle()
    bitmap.recycle()
}

private val combos = listOf("en", "ar").flatMap { lang -> listOf(false, true).map { lang to it } }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class HomeRedesignTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)

    private fun show(content: @Composable () -> Unit): AutoCloseable {
        Robolectric.setupContentProvider(FixtureThumbnails::class.java, "nezzel.fixture")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        controller.get().setContent { content() }
        return AutoCloseable { controller.pause().stop().destroy() }
    }

    @Test fun firstRunShowsTheHeroAndNoLibrary() {
        val context = RuntimeEnvironment.getApplication()
        show { HomeShell("en", false, withDownloads = false, copied = false, route = "Home") }.use {
            compose.onNodeWithText(context.getString(R.string.home_hero_title)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.home_hero_sites)).assertExists()
            compose.onAllNodesWithText(context.getString(R.string.recent_files)).assertCountEquals(0)
        }
    }

    @Test fun loadingOrFailedLibraryWithNoJobsIsNotFirstRun() {
        val context = RuntimeEnvironment.getApplication()
        for (load in listOf(LibraryLoadState.Loading(), LibraryLoadState.Error())) {
            show { HomeShell("en", false, withDownloads = false, copied = false, route = "Home", load = load) }.use {
                compose.onAllNodesWithText(context.getString(R.string.home_hero_title)).assertCountEquals(0)
                compose.onAllNodesWithText(context.getString(R.string.home_hero_sites)).assertCountEquals(0)
            }
        }
    }

    @Test fun withDownloadsShowsRunningRecentsAndEarlierInsteadOfTheHero() {
        val context = RuntimeEnvironment.getApplication()
        show { HomeShell("en", false, withDownloads = true, copied = false, route = "Home") }.use {
            compose.onAllNodesWithText(context.getString(R.string.home_hero_title)).assertCountEquals(0)
            compose.onNodeWithText(context.getString(R.string.recent_files)).assertExists()
            compose.onNodeWithText(context.getString(R.string.home_earlier)).assertExists()
            compose.onNodeWithText("Caminandes Llamigos", substring = true).assertExists()
        }
    }

    @Test fun aFinishedDownloadShowsAtTheStartOfTheRecentsRow() {
        var media by mutableStateOf(HomeFixtures.library.drop(1))
        show {
            NezzelTheme(ThemePref.Light, false) { dev.ahmed.savlino.downloads.HomeRecents(media, "en", {}) {} }
        }.use {
            compose.onNodeWithText("Sintel", substring = true).assertIsDisplayed()
            // The new file is inserted before the card the row was anchored on.
            compose.runOnIdle { media = HomeFixtures.library }
            compose.waitForIdle()
            compose.onNodeWithText("Big Buck Bunny", substring = true).assertIsDisplayed()
        }
    }

    @Test fun aRecentCardAudioIncludedOpensTheViewerNotTheSystem() {
        dev.ahmed.savlino.playback.registerAudioServiceBinding()
        val context = RuntimeEnvironment.getApplication()
        val song = HomeFixtures.library[0].copy(uri = Uri.parse("content://nezzel.fixture/thumb/40"), filename = "Song.m4a",
            title = "Song", mimeType = "audio/mp4", height = null, width = null)
        val opened = mutableListOf<SavedMedia>()
        show {
            NezzelTheme(ThemePref.Light, false) { dev.ahmed.savlino.downloads.HomeRecents(listOf(song) + HomeFixtures.library, "en", { opened += it }) {} }
        }.use {
            compose.onNodeWithText("Big Buck Bunny", substring = true).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.viewer_close)).assertExists().performClick()
            compose.waitForIdle()
            compose.onAllNodesWithContentDescription(context.getString(R.string.viewer_close)).assertCountEquals(0)
            assertTrue(opened.isEmpty())
            // Audio plays in the same viewer, so the phone never asks which app should open it.
            compose.onNodeWithText("Song", substring = true).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.viewer_close)).assertExists()
            // Play, or the music screen's failure card when the JVM can't decode the fixture: either way, inside the viewer.
            assertTrue(compose.onAllNodesWithContentDescription(context.getString(R.string.viewer_play)).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText(context.getString(R.string.viewer_retry)).fetchSemanticsNodes().isNotEmpty())
            compose.onAllNodesWithContentDescription(context.getString(R.string.viewer_mute)).assertCountEquals(0)
            assertTrue(opened.isEmpty())
        }
    }

    @Test fun pasteDockRunsTheForegroundPasteAndTheCopiedCardShowsOnlyTheHost() {
        val context = RuntimeEnvironment.getApplication()
        var pastes = 0
        show { HomeShell("en", false, withDownloads = true, copied = true, route = "Home", onPaste = { pastes++ }) }.use {
            compose.onNodeWithText(context.getString(R.string.copied_chip)).assertExists()
            compose.onNodeWithText("youtube.com", substring = true).assertExists()
            compose.onAllNodesWithText("aqz-KE-bpKQ", substring = true).assertCountEquals(0)
            compose.onNodeWithContentDescription(context.getString(R.string.paste_link)).assertHasClickAction()
                .assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f)).performClick()
            assertEquals(1, pastes)
        }
    }

    @Test fun t1HasFourLabelledTabsWithOneSelected() {
        var route by mutableStateOf("Home")
        val context = RuntimeEnvironment.getApplication()
        show { HomeShell("en", false, withDownloads = false, copied = false, route = route, onRoute = { route = it }) }.use {
            val bar = compose.onNodeWithTag("navigation_bar").fetchSemanticsNode()
            val info = bar.config[SemanticsProperties.CollectionInfo]
            assertEquals(1 to 4, info.rowCount to info.columnCount)
            val tabs = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            tabs.assertCountEquals(4)
            tabs.filter(isSelected()).assertCountEquals(1)
            for (label in listOf(R.string.home, R.string.shortcut_downloads_short, R.string.statuses_tab, R.string.settings))
                compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(context.getString(label)))
                    .assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
            compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(context.getString(R.string.statuses_tab)))
                .performClick()
            assertEquals("Statuses", route)
        }
    }

    /** Stills of the real composables: first run / with downloads (+ copied card) × ar/en × light/dark. */
    @Test fun captureStills() {
        assumeTrue(dir != null)
        for ((lang, dark) in combos) for ((name, withDownloads, copied) in listOf(Triple("first-run", false, false),
            Triple("with-downloads", true, true), Triple("with-downloads-no-chip", true, false))) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            Robolectric.setupContentProvider(FixtureThumbnails::class.java, "nezzel.fixture")
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                controller.get().setContent { HomeShell(lang, dark, withDownloads, copied, "Home") }
                repeat(8) { Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(250); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) }
                controller.get().capture(File(dir, "$name-$lang-${if (dark) "dark" else "light"}.png"))
            } finally { controller.pause().stop().destroy() }
        }
        // Large text (1.3x) on a 360dp phone: the hero and its words still fit above the dock.
        RuntimeEnvironment.setQualifiers("en-w360dp-h780dp-notnight-xxhdpi")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent { HomeShell("en", false, false, false, "Home", fontScale = 1.3f) }
            repeat(8) { Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(250); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) }
            controller.get().capture(File(dir, "first-run-en-light-1.3x.png"))
        } finally { controller.pause().stop().destroy() }
    }

    /** The old-downloads offer at normal text, ar/en × light/dark. */
    @Test fun captureOldDownloadsCard() {
        assumeTrue(dir != null)
        for ((lang, dark) in combos) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                controller.get().setContent {
                    CompositionLocalProvider(LocalLayoutDirection provides if (lang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                            androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                                dev.ahmed.savlino.downloads.OldDownloadsCard({}, {}, androidx.compose.ui.Modifier
                                    .padding(dev.ahmed.savlino.ui.theme.Space.m))
                            }
                        }
                    }
                }
                repeat(4) { Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(250); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) }
                controller.get().capture(File(dir, "old-downloads-card/card-$lang-${if (dark) "dark" else "light"}.png"))
            } finally { controller.pause().stop().destroy() }
        }
    }

    /** The floating tab bar: stills × ar/en × light/dark plus one ar-light GIF frame set. */
    @Test fun captureTabBar() {
        assumeTrue(dir != null)
        run {
            val option = File(dir, "tab-bar")
            for ((lang, dark) in combos) {
                RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
                Robolectric.setupContentProvider(FixtureThumbnails::class.java, "nezzel.fixture")
                val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
                try {
                    controller.get().setContent { HomeShell(lang, dark, true, false, "Home") }
                    repeat(8) { Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(250); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) }
                    controller.get().capture(File(option, "home-$lang-${if (dark) "dark" else "light"}.png"))
                } finally { controller.pause().stop().destroy() }
            }
            tabFrames("ar", false, File(option, "frames"))
        }
    }

    private fun tabFrames(lang: String, dark: Boolean, out: File) {
        compose.mainClock.autoAdvance = false
        RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
        Robolectric.setupContentProvider(FixtureThumbnails::class.java, "nezzel.fixture")
        var route by mutableStateOf("Home")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val activity = controller.get()
        try {
            activity.setContent { HomeShell(lang, dark, true, false, route, { route = it }) }
            repeat(6) { compose.mainClock.advanceTimeBy(250); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) }
            var n = 0
            fun grab() { compose.waitForIdle(); activity.capture(File(out, "f-${(n++).toString().padStart(4, '0')}.png"), .5f) }
            grab()
            for (next in listOf("Downloads", "Settings", "Home")) {
                route = next
                Snapshot.sendApplyNotifications()
                repeat(40) { compose.mainClock.advanceTimeBy(10, ignoreFrameDuration = true); grab() }
            }
        } finally { controller.pause().stop().destroy(); compose.mainClock.autoAdvance = true }
    }

    /** Home → Downloads → Settings → Home on a paused clock, one frame every 10 ms (frames/<lang>-<theme>/f-NNNN.png). */
    @Test fun captureTabFrames() {
        assumeTrue(dir != null)
        compose.mainClock.autoAdvance = false
        for ((lang, dark) in combos) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            Robolectric.setupContentProvider(FixtureThumbnails::class.java, "nezzel.fixture")
            var route by mutableStateOf("Home")
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            val activity = controller.get()
            val out = File(dir, "frames/$lang-${if (dark) "dark" else "light"}")
            try {
                activity.setContent { HomeShell(lang, dark, true, false, route, { route = it }) }
                repeat(6) { compose.mainClock.advanceTimeBy(250); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) }
                var n = 0
                fun grab() { compose.waitForIdle(); activity.capture(File(out, "f-${(n++).toString().padStart(4, '0')}.png"), .5f) }
                grab()
                for (next in listOf("Downloads", "Settings", "Home")) {
                    route = next
                    Snapshot.sendApplyNotifications()
                    repeat(40) { compose.mainClock.advanceTimeBy(10, ignoreFrameDuration = true); grab() }
                }
            } finally { controller.pause().stop().destroy() }
        }
    }
}
