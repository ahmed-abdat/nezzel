package dev.ahmed.savlino

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.downloads.DownloadsScreen
import dev.ahmed.savlino.downloads.LibraryLoadState
import dev.ahmed.savlino.downloads.LinkCopy
import dev.ahmed.savlino.downloads.LocalLinkCopy
import dev.ahmed.savlino.jobs.EngineMaintenanceState
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.update.AppUpdates
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
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

/** Design review captures: Settings + Advanced + empty Downloads. Runs only with NEZZEL_CAPTURE_DIR (+ NEZZEL_CAPTURE_PREFIX). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class SettingsFlatCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
    private val prefix = System.getenv("NEZZEL_CAPTURE_PREFIX") ?: "after"

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

    private fun shoot(name: String, height: Int = 832, content: @androidx.compose.runtime.Composable (String) -> Unit) {
        assumeTrue(dir != null)
        for (lang in listOf("en", "ar")) for (dark in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("+$lang-h${height}dp-${if (dark) "night" else "notnight"}")
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                controller.get().setContent { NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) { content(lang) } }
                idle()
                save(controller.get().window.decorView, "$prefix-$name-$lang-${if (dark) "dark" else "light"}")
            } finally { controller.pause().stop().destroy() }
        }
    }

    private fun <T> withUpdates(checkedAt: Long? = null, block: (AppUpdates) -> T): T {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("app-updates", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().apply { checkedAt?.let { putLong("success", it) } }.commit()
        val owner = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val production = object : android.content.ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getPackageName() = "dev.ahmed.savlino" }
        return try { block(AppUpdates(production, owner)) } finally { owner.coroutineContext[kotlinx.coroutines.Job]!!.cancel() }
    }

    @Test fun advanced() = withUpdates { updates ->
        shoot("advanced", 1100) { lang ->
            AdvancedSettings(EngineMaintenanceState("2026.08.19", true), {}, updates, automaticOptions = true,
                onClearLinks = {}, parallel = 2, language = lang) {}
        }
    }

    @Test fun settings() = withUpdates { updates ->
        shoot("settings", 1700) { lang ->
            SettingsScreen(LinkViewModel(SavedStateHandle()), lang, ThemePref.System, false, true,
                {}, {}, {}, {}, {}, {}, updates = updates, onClearLinks = {})
        }
    }

    private fun header(name: String, updates: AppUpdates) {
      for (scale in listOf(1f, 1.5f)) shoot("$name${if (scale > 1f) "-big" else ""}", 300) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, scale)) {
                androidx.compose.material3.Surface {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(top = 24.dp)) {
                        dev.ahmed.savlino.update.UpdateSettings(updates) {}
                    }
                }
            }
        }
    }

    @Test fun header() = withUpdates(System.currentTimeMillis() - 300_000) { header("header-latest", it) }

    @Test fun headerOffer() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("app-updates", android.content.Context.MODE_PRIVATE)
        val json = org.json.JSONObject().put("tag_name", "v0.5.0").put("body", "<!-- nezzel:versionCode=99999 -->")
            .put("assets", org.json.JSONArray().put(org.json.JSONObject().put("name", "update.apk").put("size", 100)
                .put("browser_download_url", "https://github.com/asset").put("digest", "sha256:" + "a".repeat(64))))
        val owner = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val production = object : android.content.ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getPackageName() = "dev.ahmed.savlino" }
        try {
            prefs.edit().clear().putString("release", json.toString()).commit()
            header("header-offer", AppUpdates(production, owner))
        } finally { owner.coroutineContext[kotlinx.coroutines.Job]!!.cancel(); prefs.edit().clear().commit() }
    }

    @Test fun downloadsEmpty() = shoot("downloads-empty") { lang ->
        CompositionLocalProvider(LocalLinkCopy provides LinkCopy(find = { null }, copy = {})) {
            DownloadsScreen(emptyList(), lang, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(emptyList()))
        }
    }
}
