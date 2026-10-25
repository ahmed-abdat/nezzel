package dev.ahmed.savlino

import androidx.activity.ComponentActivity
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.jobs.EngineMaintenanceState
import dev.ahmed.savlino.jobs.EngineUpdateStatus
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.update.AppUpdates
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w360dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun nodes(a: ComponentActivity): List<SemanticsNode> { compose.waitForIdle(); return semanticsNodes(a) }
    private fun clickable(a: ComponentActivity, label: String): SemanticsNode = nodes(a).last { node ->
        node.config.getOrNull(SemanticsActions.OnClick) != null && descendants(node).any { label in texts(listOf(it)) }
    }
    private fun click(node: SemanticsNode) { assertEquals(true, node.config[SemanticsActions.OnClick].action?.invoke()) }
    private fun <T> withUpdates(block: (AppUpdates) -> T): T {
        val owner = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val production = object : android.content.ContextWrapper(context) { override fun getPackageName() = "dev.ahmed.savlino" }
        return try { block(AppUpdates(production, owner)) } finally { owner.coroutineContext[kotlinx.coroutines.Job]!!.cancel() }
    }

    @Test fun tipsSwitchDefaultsOnAndPersistsOffEnglish() = tipsSwitch()
    @Test fun tipsCanBeTurnedOffWithoutAnAvailableUpdater() = renderThemed({ nodes(it) }, {
        AdvancedSettings(null, {}, null) {}
    }) { a ->
        click(clickable(a, context.getString(R.string.tips_title)))
        assertFalse(dev.ahmed.savlino.locale.AppPreferences(context).tips)
    }
    @Test @Config(qualifiers = "ar-w360dp-h1600dp-mdpi") fun tipsSwitchDefaultsOnAndPersistsOffArabic() = tipsSwitch()
    private fun tipsSwitch() = withUpdates { updates ->
        val prefs = dev.ahmed.savlino.locale.AppPreferences(context)
        assertTrue(prefs.tips)
        renderThemed({ nodes(it) }, { AdvancedSettings(null, {}, updates) {} }) { a ->
            val row = clickable(a, context.getString(R.string.tips_title))
            assertEquals(ToggleableState.On, row.config[SemanticsProperties.ToggleableState])
            assertTrue(row.layoutInfo.height / context.resources.displayMetrics.density >= 48f)
            click(row)
            nodes(a)
            assertFalse(dev.ahmed.savlino.locale.AppPreferences(context).tips)
            assertEquals(ToggleableState.Off, clickable(a, context.getString(R.string.tips_title)).config[SemanticsProperties.ToggleableState])
        }
    }

    @Test fun languageRowShowsCurrentValueAndPickerAppliesChoice() = withUpdates { updates ->
        val chosen = mutableListOf<String>()
        renderThemed({ nodes(it) }, {
            SettingsScreen(LinkViewModel(SavedStateHandle()), "ar", ThemePref.Dark, false, true,
                { chosen += it }, {}, {}, {}, {}, {}, updates = updates)
        }) { a ->
            val row = clickable(a, context.getString(R.string.language))
            assertTrue(texts(descendants(row)).contains(context.getString(R.string.language_ar)))
            assertTrue(texts(descendants(clickable(a, context.getString(R.string.appearance)))).contains(context.getString(R.string.theme_dark)))
            click(row)
            val english = nodes(a).single { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                context.getString(R.string.language_en) in texts(descendants(it)) }
            click(english)
            assertEquals(listOf("en"), chosen)
            assertTrue(nodes(a).none { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton })
        }
    }

    @Test fun languagePickerOffersFrenchAndShowsItAsCurrentChoice() = withUpdates { updates ->
        val chosen = mutableListOf<String>()
        @androidx.compose.runtime.Composable fun settings(language: String) {
            SettingsScreen(LinkViewModel(SavedStateHandle()), language, ThemePref.System, false, true,
                { chosen += it }, {}, {}, {}, {}, {}, updates = updates)
        }
        renderThemed({ nodes(it) }, { settings("ar") }) { a ->
            click(clickable(a, context.getString(R.string.language)))
            val choices = nodes(a).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
            assertEquals(setOf("العربية", "English", "Français"),
                choices.flatMap { texts(descendants(it)) }.toSet())
            click(choices.single { "Français" in texts(descendants(it)) })
            assertEquals(listOf("fr"), chosen)
            assertTrue(nodes(a).none { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton })
        }
        renderThemed({ nodes(it) }, { settings("fr") }) { a ->
            val row = clickable(a, context.getString(R.string.language))
            assertTrue(texts(descendants(row)).contains("Français"))
            click(row)
            val french = nodes(a).single { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                "Français" in texts(descendants(it)) }
            assertEquals(true, french.config.getOrNull(SemanticsProperties.Selected))
        }
    }

    @Test fun advancedHoldsEngineToolsAndUpdateSwitchAndBackReturns() = withUpdates { updates ->
        val toggled = mutableListOf<Boolean>()
        renderThemed({ nodes(it) }, {
            SettingsScreen(LinkViewModel(SavedStateHandle()), "en", ThemePref.System, false, true,
                {}, {}, {}, {}, { toggled += it }, {}, updates = updates, onClearLinks = {})
        }) { a ->
            val auto = context.getString(R.string.settings_updates_auto)
            val options = context.getString(R.string.automatic_options)
            val clear = context.getString(R.string.clear_link_history)
            // The first page stays short: no automatic-options switch, no clear-history row, no engine row, no update switch.
            assertFalse(texts(nodes(a)).contains(auto))
            assertFalse(texts(nodes(a)).contains(options))
            assertFalse(texts(nodes(a)).contains(clear))
            assertFalse(texts(nodes(a)).contains(context.getString(R.string.settings_privacy)))
            assertFalse(texts(nodes(a)).contains(context.getString(R.string.about_licences_short)))
            assertFalse(texts(nodes(a)).contains(context.getString(R.string.engine_update_now)))
            click(clickable(a, context.getString(R.string.settings_advanced)))
            compose.waitUntil(5_000) { auto in texts(nodes(a)) }
            assertEquals(Role.Switch, clickable(a, auto).config.getOrNull(SemanticsProperties.Role))
            assertEquals(Role.Switch, clickable(a, options).config.getOrNull(SemanticsProperties.Role))
            assertTrue(texts(nodes(a)).contains(context.getString(R.string.settings_privacy)))
            clickable(a, clear)
            // The notices still ship in the app, one level down.
            assertEquals(Role.Button, clickable(a, context.getString(R.string.about_licences_short)).config.getOrNull(SemanticsProperties.Role))
            click(clickable(a, options))
            assertEquals(listOf(false), toggled)
            click(nodes(a).last { node -> node.config.getOrNull(SemanticsActions.OnClick) != null && descendants(node).any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(context.getString(R.string.back)) == true } })
            compose.waitUntil(5_000) { context.getString(R.string.settings_general) in texts(nodes(a)) }
            assertFalse(texts(nodes(a)).contains(auto))
        }
    }

    @Test fun engineRowShowsVersionStatusAndStartsUpdate() {
        var updates = 0
        renderThemed({ nodes(it) }, {
            AdvancedSettings(EngineMaintenanceState("2026.08.19", true), { updates++ }, null) {}
        }) { a ->
            val row = clickable(a, context.getString(R.string.engine_update_now))
            val shown = texts(descendants(row))
            // Plain words; version details belong to About's support line.
            assertFalse(shown.any { "2026.08.19" in it })
            assertTrue(shown.contains(context.getString(R.string.engine_update_body)))
            assertTrue(shown.contains(context.getString(R.string.engine_update_help)))
            assertFalse(texts(nodes(a)).any { "2026.08.19" in it })
            click(row)
            assertEquals(1, updates)
        }
        renderThemed({ nodes(it) }, {
            AdvancedSettings(EngineMaintenanceState("2026.08.19", true, EngineUpdateStatus.UPDATING), { updates++ }, null) {}
        }) { a ->
            val row = clickable(a, context.getString(R.string.engine_update_now))
            assertTrue(row.config.contains(SemanticsProperties.Disabled))
            assertTrue(texts(descendants(row)).contains(context.getString(R.string.engine_updating)))
        }
    }
}
