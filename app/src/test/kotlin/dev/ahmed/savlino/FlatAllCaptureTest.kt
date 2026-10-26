package dev.ahmed.savlino

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.HomeFooter
import dev.ahmed.savlino.domain.Format
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.jobs.SavedBefore
import dev.ahmed.savlino.ui.EngineUnavailableNote
import dev.ahmed.savlino.ui.FailureContent
import dev.ahmed.savlino.ui.FormatRow
import dev.ahmed.savlino.ui.NextLinkNote
import dev.ahmed.savlino.ui.PendingShareNote
import dev.ahmed.savlino.ui.SavedBeforeNotice
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.update.AppUpdateState
import dev.ahmed.savlino.update.Release
import dev.ahmed.savlino.update.UpdateCard
import dev.ahmed.savlino.update.UpdateScreen
import dev.ahmed.savlino.update.UpdateStage
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

/** Design review captures: flat-vs-container audit (Home notes, options rows, failure block, update screen). Runs only with NEZZEL_CAPTURE_DIR (+ NEZZEL_CAPTURE_PREFIX). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class FlatAllCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val prefix = System.getenv("NEZZEL_CAPTURE_PREFIX") ?: "after"

    private fun idle() = repeat(4) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    }

    private fun save(view: View, name: String, windows: List<View> = listOf(view)) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        windows.filter { it.isAttachedToWindow && it.width > 0 && it.height > 0 }.forEach { it.draw(canvas) }
        dir!!.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shoot(name: String, height: Int = 832, allWindows: Boolean = false, content: @androidx.compose.runtime.Composable (String) -> Unit) {
        assumeTrue(dir != null)
        for (lang in listOf("en", "ar")) for (dark in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("+$lang-h${height}dp-${if (dark) "night" else "notnight"}")
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                controller.get().setContent { NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) { Surface(Modifier.fillMaxSize()) { content(lang) } } }
                idle()
                val activity = controller.get()
                if (allWindows) semanticsNodes(activity)
                save(activity.window.decorView, "$prefix-$name-$lang-${if (dark) "dark" else "light"}",
                    if (allWindows) windowViews(activity) else listOf(activity.window.decorView))
            } finally { controller.pause().stop().destroy() }
        }
    }

    private val release = Release("0.5.0", 99999, "update.apk", 48_000_000, "https://github.com/asset", "a".repeat(64),
        listOf("Settings are a flat list", "Quieter empty Downloads"), listOf("الإعدادات قائمة مسطحة", "تنزيلات فارغة أهدأ"))

    @Test fun homeNotes() = shoot("home-notes", 900) { lang ->
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.l)) {
            UpdateCard(release, lang, {}, {})
            PendingShareNote("youtube.com", {}, {})
            NextLinkNote("tiktok.com", 2, 3, lang, {}, {})
            EngineUnavailableNote()
        }
    }

    @Test fun options() = shoot("options", 900) { lang ->
        fun fmt(id: String, h: Int, mb: Long) = Format(id, "mp4", true, true, height = h, size = Size.Exact(mb * 1_000_000))
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            SavedBeforeNotice(SavedBefore(android.net.Uri.parse("content://media/1"), System.currentTimeMillis() - 86_400_000L * 3), "mp4", lang)
            FormatRow("1080p", fmt("a", 1080, 68), false, true, lang, "Full HD") {}
            FormatRow("720p", fmt("b", 720, 32), true, true, lang, "Good for most phones") {}
            FormatRow("360p", fmt("c", 360, 9), false, true, lang, "Smallest") {}
        }
    }

    @Test fun failure() = shoot("failure", 600) { lang ->
        Column(Modifier.padding(Space.l)) {
            FailureContent(R.string.network_title, R.string.network_body, "youtube.com", {}, { }, {})
        }
    }

    @Test fun updateIdle() = shoot("update-idle", 832) { lang ->
        UpdateScreen(AppUpdateState(release = release), lang, true, {}, {}, {}, {}, {}, {})
    }

    @Test fun updatePermission() = shoot("update-permission", 900) { lang ->
        UpdateScreen(AppUpdateState(release = release, transfer = release, stage = UpdateStage.READY), lang, false, {}, {}, {}, {}, {}, {})
    }

    @Test @Config(sdk = [29])
    fun updateOfferSheet() = shoot("update-offer-sheet", allWindows = true) { lang ->
        dev.ahmed.savlino.update.WhatsNewSheet(
            dev.ahmed.savlino.update.WhatsNew(release.version, release.english, release.arabic), lang,
            update = {}, onDismiss = {})
    }

    private fun shootFooter(name: String, lang: String, dark: Boolean, scale: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit) {
        assumeTrue(dir != null)
        RuntimeEnvironment.setQualifiers("+$lang-h300dp-${if (dark) "night" else "notnight"}")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            controller.get().setContent {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) {
                    NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                        Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(Space.l)) { content() } }
                    }
                }
            }
            idle()
            save(controller.get().window.decorView, "footer-$name-$lang-${if (dark) "dark" else "light"}")
        } finally { controller.pause().stop().destroy() }
    }

    @Composable private fun OldFooter() {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(stringResource(R.string.privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Image(painterResource(R.drawable.hululsoft_mark), null, Modifier.size(16.dp))
                Text(stringResource(R.string.company_made_by, NezzelFormat.isolate(stringResource(R.string.company_name))),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Test fun footer() {
        for (lang in listOf("en", "ar", "fr")) for (dark in listOf(false, true)) {
            shootFooter("before", lang, dark) { OldFooter() }
            shootFooter("after", lang, dark) { HomeFooter() }
        }
        shootFooter("before-large", "ar", false, 2f) { OldFooter() }
        shootFooter("after-large", "ar", false, 2f) { HomeFooter() }
        shootFooter("after-large", "en", false, 2f) { HomeFooter() }
        shootFooter("after-large", "fr", false, 2f) { HomeFooter() }
    }
}
