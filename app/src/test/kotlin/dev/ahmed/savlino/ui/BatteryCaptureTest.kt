package dev.ahmed.savlino.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.jobs.BatterySettings
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.ui.theme.Space
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

/** Design review capture of the battery-stop failed row (minimal stand-in until JobCard wiring). Runs only with NEZZEL_CAPTURE_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class BatteryCaptureTest {
    private val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)

    @Test fun batteryStopRow() {
        assumeTrue(dir != null)
        for (lang in listOf("en", "ar")) for (dark in listOf(false, true)) for (samsung in listOf(true, false)) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (dark) "night" else "notnight"}")
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
            try {
                controller.get().setContent {
                    NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                        Surface(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = Space.rowH, vertical = Space.s)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
                                    Text("clip.mp4", style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(BatterySettings.errorText), color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodyMedium)
                                    BatteryHelp(DownloadError.StoppedByBattery, {}, exempt = { false }, samsung = samsung)
                                    FilledTonalButton(onClick = {}, modifier = Modifier.padding(top = Space.xs).heightIn(min = 48.dp)) {
                                        Text(stringResource(R.string.try_again))
                                    }
                                }
                            }
                        }
                    }
                }
                repeat(4) { Snapshot.sendApplyNotifications(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) }
                val view = controller.get().window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height / 3, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                dir!!.mkdirs()
                val name = "battery-$lang-${if (dark) "dark" else "light"}${if (samsung) "-samsung" else ""}.png"
                File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { controller.pause().stop().destroy() }
        }
    }
}
