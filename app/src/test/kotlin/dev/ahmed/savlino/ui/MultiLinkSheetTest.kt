package dev.ahmed.savlino.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.locale.AppLocale
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "en-w384dp-h832dp-xxhdpi")
class MultiLinkSheetTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val text = listOf("https://www.tiktok.com/@nouakchott.food/video/7423456789012345678",
        "https://www.instagram.com/reel/C9xYzAbCdEf/", "https://youtu.be/dQw4w9WgXcQ", "https://x.com/someone/status/1845678901234567890",
        "https://www.facebook.com/watch?v=1234567890").joinToString("\n")

    private fun show(language: String, dark: Boolean, input: String = text, body: (MultiLinkViewModel, ComponentActivity) -> Unit) {
        RuntimeEnvironment.setQualifiers("+$language-${if (dark) "night" else "notnight"}")
        AppLocale.choose(RuntimeEnvironment.getApplication(), language)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var submitted: Pair<String, Int>? = null
        val vm = MultiLinkViewModel(scope, { title, _, links -> submitted = title to links.size; true })
        try {
            vm.open(MultiLinks.from(input)!!)
            controller.get().setContent {
                NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim.copy(alpha = .32f)) {
                        Box(contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                            Surface(Modifier.fillMaxWidth().fillMaxHeight(.92f), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow) { MultiLinkSheetContent(vm, language) }
                        }
                    }
                }
            }
            compose.waitForIdle()
            body(vm, controller.get())
            lastSubmitted = submitted
        } finally { vm.close(); scope.cancel(); controller.pause().stop().destroy() }
    }
    private var lastSubmitted: Pair<String, Int>? = null

    @Test fun rowsRemoveTargetsLabelsAndOneDownloadTap() {
        show("en", false) { vm, _ ->
            compose.onNodeWithText("5 links").assertExists()
            compose.onNodeWithText("Download 5 videos").assertIsEnabled()
            val remove = compose.onNodeWithContentDescription("Remove tiktok.com")
            val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
            assertTrue(remove.fetchSemanticsNode().boundsInRoot.height / density >= 48f)
            remove.performClick(); compose.waitForIdle()
            assertEquals(4, vm.links.size)
            compose.onNodeWithText("Download 4 videos").performClick(); compose.waitForIdle()
        }
        assertEquals("4 links" to 4, lastSubmitted)
    }
    @Test fun moreThanFiftyShowsTheNote() {
        show("en", false, (1..55).joinToString(" ") { "https://example.org/v/$it" }) { _, _ ->
            compose.onNodeWithText("Only the first 50 links").assertExists()
            compose.onNodeWithText("50 links").assertExists()
        }
    }
    @Test fun arabicUsesPluralForms() {
        show("ar", false) { _, _ -> compose.onNodeWithText("5 روابط", useUnmergedTree = true).assertExists() }
    }

    /** Design review captures; runs only with NEZZEL_CAPTURE_DIR. */
    @Test fun captures() {
        val dir = System.getenv("NEZZEL_CAPTURE_DIR")?.let(::File)
        assumeTrue(dir != null)
        dir!!.mkdirs()
        fun save(activity: ComponentActivity, name: String) {
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        for (language in listOf("en", "ar")) for (dark in listOf(false, true))
            show(language, dark) { _, activity -> save(activity, "multi-link-$language-${if (dark) "dark" else "light"}") }
        show("ar", false, (1..55).joinToString(" ") { "https://example.org/v/$it" }) { _, activity -> save(activity, "multi-link-ar-light-first-50") }
    }
}
