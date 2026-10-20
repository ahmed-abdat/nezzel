package dev.ahmed.savlino.update

import androidx.activity.ComponentActivity
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.*
import dev.ahmed.savlino.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateScreenTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val release = Release("0.4.4", 99999, "update.apk", 100, "https://github.com/asset", "a".repeat(64), listOf("Improved"), listOf("تحسين"))
    private fun nodes(a: ComponentActivity): List<SemanticsNode> { compose.waitForIdle(); return semanticsNodes(a) }
    private fun renderState(stage: UpdateStage, canInstall: Boolean = true, failure: UpdateFailure? = null) =
        renderThemed({ nodes(it) }, {
            UpdateScreen(AppUpdateState(release = release, transfer = release, stage = stage, failure = failure, bytes = 50),
                context.resources.configuration.locales[0].language, canInstall, {}, {}, {}, {}, {}, {})
        }) { a ->
            val all = nodes(a)
            val shown = texts(all)
            val expected = when (stage) {
                UpdateStage.IDLE -> R.string.update_skip
                UpdateStage.DOWNLOADING -> R.string.cancel
                UpdateStage.VERIFYING -> R.string.update_verifying
                UpdateStage.READY -> if (canInstall) R.string.update_install else R.string.update_permission_action
                UpdateStage.ERROR -> R.string.try_again
                UpdateStage.INSTALLING -> R.string.update_install
            }
            assertTrue(shown.contains(context.getString(expected)))
            val buttons = all.filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertTrue(buttons.isNotEmpty())
            buttons.forEach { node -> assertTrue("48dp touch target", node.layoutInfo.height / context.resources.displayMetrics.density >= 47.9f) }
            assertTrue(all.any { context.getString(R.string.back) in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
            assertFalse(shown.any { text -> text.any { it in '٠'..'٩' } })
        }
    @Test fun idleEnglish() = renderState(UpdateStage.IDLE)
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun idleArabic() = renderState(UpdateStage.IDLE)
    @Test fun downloadingEnglish() = renderState(UpdateStage.DOWNLOADING)
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun downloadingArabic() = renderState(UpdateStage.DOWNLOADING)
    @Test fun verifyingEnglish() = renderState(UpdateStage.VERIFYING)
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun verifyingArabic() = renderState(UpdateStage.VERIFYING)
    @Test fun permissionEnglish() = renderState(UpdateStage.READY, false)
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun permissionArabic() = renderState(UpdateStage.READY, false)
    @Test fun installEnglish() = renderState(UpdateStage.READY)
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun installArabic() = renderState(UpdateStage.READY)
    @Test fun errorsEnglish() { UpdateFailure.entries.forEach { renderState(UpdateStage.ERROR, failure = it) } }
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun errorsArabic() { UpdateFailure.entries.forEach { renderState(UpdateStage.ERROR, failure = it) } }
    @Test fun cardIsCompactAndDismissButtonHasAccessibleName() = renderThemed({ nodes(it) }, {
        UpdateCard(release, {}, {})
    }) { a ->
        val all = nodes(a)
        assertTrue(all.any { context.getString(R.string.update_not_now) in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
        val bounds = all.first { it.config.getOrNull(SemanticsProperties.TestTag) == "app-update-card" }.boundsInRoot
        assertTrue(bounds.height / context.resources.displayMetrics.density <= 88f)
    }

    @Test fun confirmationIsHeldUntilForegroundRouteConsumesItOnce() {
        val owner = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val production = object : android.content.ContextWrapper(context) {
            override fun getPackageName() = UpdatePolicy.PACKAGE
        }
        val updates = AppUpdates(production, owner)
        val confirmation = android.content.Intent("dev.ahmed.savlino.TEST_INSTALL_CONFIRMATION")
        try {
            updates.holdConfirmation(confirmation)
            assertSame(confirmation, updates.state.value.confirmation)
            renderThemed({ nodes(it) }, { AppUpdateRoute(updates, "en") {} }) { activity ->
                assertEquals(confirmation.action, org.robolectric.Shadows.shadowOf(activity).nextStartedActivity?.action)
                assertNull(updates.state.value.confirmation)
                nodes(activity)
                assertNull(org.robolectric.Shadows.shadowOf(activity).nextStartedActivity)
                val lifecycle = activity.lifecycle as androidx.lifecycle.LifecycleRegistry
                lifecycle.currentState = androidx.lifecycle.Lifecycle.State.STARTED
                nodes(activity)
                updates.holdConfirmation(confirmation)
                nodes(activity)
                assertSame(confirmation, updates.state.value.confirmation)
                assertNull(org.robolectric.Shadows.shadowOf(activity).nextStartedActivity)
                lifecycle.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
                nodes(activity)
                assertEquals(confirmation.action, org.robolectric.Shadows.shadowOf(activity).nextStartedActivity?.action)
                assertNull(updates.state.value.confirmation)
            }
        } finally { owner.coroutineContext[kotlinx.coroutines.Job]!!.cancel() }
    }
}
