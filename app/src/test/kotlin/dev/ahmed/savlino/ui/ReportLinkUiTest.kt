package dev.ahmed.savlino.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.*
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.BuildConfig
import dev.ahmed.savlino.R
import dev.ahmed.savlino.descendants
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.engine.FakeEngine
import dev.ahmed.savlino.engine.InspectResult
import dev.ahmed.savlino.renderThemed
import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.texts
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w360dp-h2400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportLinkUiTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val link = "https://example.com/share/post?value=%2f&other=two#part"
    private fun nodes(activity: ComponentActivity): List<SemanticsNode> {
        compose.waitForIdle()
        return semanticsNodes(activity)
    }
    private fun render(error: DownloadError, check: (ComponentActivity) -> Unit) {
        val vm = LinkViewModel(SavedStateHandle(), engine = FakeEngine(InspectResult.Failure(error)),
            currentEngineVersion = { "fixture-engine" })
        vm.edit(link); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(OptionsState.Error(error), vm.optionsState)
        renderThemed({ nodes(it) }, { OptionsSheetContent(vm, "en", vm::dismissOptions) }, check)
    }

    @Test fun failedOffersReportAndOpensWhatsAppOnlyAfterATap() = render(DownloadError.Failed) { activity ->
        val label = activity.getString(R.string.report_link)
        val action = nodes(activity).last { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                descendants(node).any { label in texts(listOf(it)) }
        }
        assertEquals(Role.Button, action.config.getOrNull(SemanticsProperties.Role))
        assertTrue(action.layoutInfo.height / activity.resources.displayMetrics.density >= 48f)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(true, action.config[SemanticsActions.OnClick].action?.invoke())
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals("wa.me", intent.data!!.host)
        assertEquals(listOf(activity.getString(R.string.report_link_intro), link,
            "Nezzel ${BuildConfig.VERSION_NAME} · engine fixture-engine · Failed"),
            intent.data!!.getQueryParameter("text")!!.lines())
    }

    @Test fun offlineDoesNotOfferReport() = render(DownloadError.Offline) { activity ->
        assertFalse(activity.getString(R.string.report_link) in texts(nodes(activity)))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
