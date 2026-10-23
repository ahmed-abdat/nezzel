package dev.ahmed.savlino

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.downloads.ScreenHeader
import dev.ahmed.savlino.jobs.EngineMaintenanceState
import dev.ahmed.savlino.jobs.EngineUpdateStatus
import dev.ahmed.savlino.jobs.NezzelApplication
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.*
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Motion
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.update.AppUpdates
import dev.ahmed.savlino.update.UpdateAutoSetting
import dev.ahmed.savlino.update.UpdateSettings

/**
 * Everyday choices on one page in grouped cards; automatic options, engine tools and the update-check switch sit
 * one level down under Advanced, so the first screen stays short.
 */
@Composable
internal fun SettingsScreen(
    links: LinkViewModel, language: String, theme: ThemePref, wallpaper: Boolean, suggestions: Boolean,
    onLanguage: (String) -> Unit, onTheme: (ThemePref) -> Unit,
    onWallpaper: (Boolean) -> Unit, onSuggestions: (Boolean) -> Unit,
    onAutomaticOptions: (Boolean) -> Unit,
    onHowItWorks: () -> Unit,
    updates: AppUpdates? = (LocalContext.current.applicationContext as? NezzelApplication)?.appUpdates?.takeIf { it.available },
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    BackHandler(advanced) { advanced = false }
    val reduced = LocalReducedMotion.current
    AnimatedContent(advanced, transitionSpec = {
        if (reduced) EnterTransition.None togetherWith ExitTransition.None
        else fadeIn(tween(Motion.FADE_IN, 70, Motion.EaseOut)) togetherWith fadeOut(tween(70, easing = Motion.EaseOut))
    }, label = "settings_page") { page ->
        if (page) AdvancedSettings(links.downloads?.let { it.maintenance.state.collectAsState().value }, links::updateEngine, updates,
            links.automaticOptions, onAutomaticOptions, links.confirmPausedUpdate, links::answerPausedUpdate) { advanced = false }
        else MainSettings(links, language, theme, wallpaper, suggestions, onLanguage, onTheme, onWallpaper,
            onSuggestions, onAutomaticOptions, onHowItWorks, updates) { advanced = true }
    }
}

@Composable
private fun MainSettings(
    links: LinkViewModel, language: String, theme: ThemePref, wallpaper: Boolean, suggestions: Boolean,
    onLanguage: (String) -> Unit, onTheme: (ThemePref) -> Unit,
    onWallpaper: (Boolean) -> Unit, onSuggestions: (Boolean) -> Unit,
    onAutomaticOptions: (Boolean) -> Unit, onHowItWorks: () -> Unit, updates: AppUpdates?, onAdvanced: () -> Unit,
) {
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    val languages = listOf("ar" to stringResource(R.string.language_ar), "en" to stringResource(R.string.language_en),
        "fr" to stringResource(R.string.language_fr))
    val themes = listOf(ThemePref.System to stringResource(R.string.theme_system), ThemePref.Light to stringResource(R.string.theme_light),
        ThemePref.Dark to stringResource(R.string.theme_dark))
    Scaffold(topBar = { ScreenHeader(stringResource(R.string.settings)) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
            .padding(start = Space.l, end = Space.l, top = Space.s, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.xl)) {
            UpdateSettings(updates) { links.navigate("AppUpdate") }
            SettingsGroup(stringResource(R.string.settings_general)) {
                SettingsNavRow(stringResource(R.string.language), R.drawable.ic_ms_language,
                    value = languages.firstOrNull { it.first == language }?.second) { picker = "language" }
                SettingsNavRow(stringResource(R.string.appearance), R.drawable.ic_ms_contrast,
                    value = themes.first { it.first == theme }.second) { picker = "theme" }
                if (Build.VERSION.SDK_INT >= 31) {
                    // Wallpaper colors exist from Android 12; the title says the rest.
                    SwitchSetting(stringResource(R.string.match_wallpaper), null, wallpaper, R.drawable.ic_ms_palette, onWallpaper)
                }
            }
            SettingsGroup(stringResource(R.string.link_handling)) {
                SwitchSetting(stringResource(R.string.copied_suggestions), stringResource(R.string.copied_help), suggestions,
                    R.drawable.ic_ms_content_paste, onSuggestions)
            }
            SettingsGroup(stringResource(R.string.settings_saving)) {
                SaveFolderSetting()
                SettingsNavRow(stringResource(R.string.settings_advanced), R.drawable.ic_ms_tune,
                    body = stringResource(R.string.settings_advanced_help), onClick = onAdvanced)
            }
            AboutSection(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, onHowItWorks, language = language, updates = updates)
        }
    }
    when (picker) {
        "language" -> ChoiceDialog(stringResource(R.string.language), languages, language, onLanguage) { picker = null }
        "theme" -> ChoiceDialog(stringResource(R.string.appearance), themes, theme, onTheme) { picker = null }
    }
}

/**
 * Automatic options (on by default; few people turn it off), engine tools, the daily update check and the licences;
 * [engine] is null without a download coordinator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdvancedSettings(engine: EngineMaintenanceState?, onUpdateEngine: () -> Unit, updates: AppUpdates?,
    automaticOptions: Boolean? = null, onAutomaticOptions: (Boolean) -> Unit = {},
    confirmPausedUpdate: Boolean = false, onPausedUpdate: (Boolean) -> Unit = {}, back: () -> Unit) {
    if (confirmPausedUpdate) AlertDialog(onDismissRequest = { onPausedUpdate(false) },
        title = { Text(stringResource(R.string.engine_update_paused_title)) },
        text = { Text(stringResource(R.string.engine_update_paused_body)) },
        confirmButton = { TextButton(onClick = { onPausedUpdate(true) }) { Text(stringResource(R.string.engine_update_paused_confirm)) } },
        dismissButton = { TextButton(onClick = { onPausedUpdate(false) }) { Text(stringResource(R.string.cancel)) } })
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings_advanced)) }, navigationIcon = {
            IconButton(onClick = back, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
            .padding(start = Space.l, end = Space.l, top = Space.s, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.xl)) {
            automaticOptions?.let { on ->
                SettingsGroup(stringResource(R.string.link_handling)) {
                    SwitchSetting(stringResource(R.string.automatic_options), stringResource(R.string.automatic_options_help),
                        on, R.drawable.ic_ms_bolt, onAutomaticOptions)
                }
            }
            engine?.let { engine ->
                val updating = engine.status == EngineUpdateStatus.UPDATING
                val builtIn = engine.usingBuiltIn && engine.status in setOf(EngineUpdateStatus.IDLE, EngineUpdateStatus.FAILED)
                SettingsGroup(stringResource(R.string.download_tools)) {
                    SettingsNavRow(stringResource(R.string.engine_update_now), R.drawable.ic_ms_build,
                        body = stringResource(R.string.engine_update_body),
                        note = stringResource(if (builtIn) R.string.engine_update_rolled_back else when (engine.status) {
                            EngineUpdateStatus.IDLE -> R.string.engine_update_help
                            EngineUpdateStatus.UPDATING -> R.string.engine_updating
                            EngineUpdateStatus.SUCCEEDED -> R.string.engine_updated
                            EngineUpdateStatus.FAILED -> R.string.engine_update_failed
                            EngineUpdateStatus.BUSY -> R.string.engine_update_busy
                        }), enabled = !updating, chevron = false, liveNote = true, onClick = onUpdateEngine)
                    if (updating) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            updates?.let { updates ->
                SettingsGroup(stringResource(R.string.settings_updates_heading)) { UpdateAutoSetting(updates) }
            }
            // Technical identifiers and the legal notices only matter when someone asks, so they sit last and small.
            Row(Modifier.padding(start = Space.l), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_support_info, engine?.version ?: stringResource(R.string.engine_version_unknown)) + " ·",
                    Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LicencesLink()
            }
        }
    }
}
