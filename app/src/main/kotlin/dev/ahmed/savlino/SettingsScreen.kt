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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.downloads.ScreenHeader
import dev.ahmed.savlino.downloads.OldDownloadsOffer
import dev.ahmed.savlino.downloads.rememberOldDownloadsOffer
import dev.ahmed.savlino.jobs.EngineMaintenanceState
import dev.ahmed.savlino.jobs.EngineUpdateStatus
import dev.ahmed.savlino.jobs.NezzelApplication
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.locale.ThemePref
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.ahmed.savlino.ui.*
import dev.ahmed.savlino.ui.nav.ScrollOnTabReselect
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Motion
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.isLargeText
import dev.ahmed.savlino.update.AppUpdates
import dev.ahmed.savlino.update.UpdateAutoSetting
import dev.ahmed.savlino.update.UpdateSettings

/**
 * Everyday choices on one page as a flat list under section headers; automatic options, downloads at the same time, clearing link
 * history, engine tools and the update-check switch sit one level down under Advanced, so the first screen stays
 * short.
 */
@Composable
internal fun SettingsScreen(
    links: LinkViewModel, language: String, theme: ThemePref, wallpaper: Boolean, suggestions: Boolean,
    onLanguage: (String) -> Unit, onTheme: (ThemePref) -> Unit,
    onWallpaper: (Boolean) -> Unit, onSuggestions: (Boolean) -> Unit,
    onAutomaticOptions: (Boolean) -> Unit,
    onHowItWorks: () -> Unit,
    updates: AppUpdates? = (LocalContext.current.applicationContext as? NezzelApplication)?.appUpdates?.takeIf { it.available },
    /** Fire and forget: the caller owns the clear so leaving Settings can't cut it short. */
    onClearLinks: (() -> Unit)? = null,
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    val advancedSnackbar = remember { SnackbarHostState() }
    val oldDownloads = rememberOldDownloadsOffer(advancedSnackbar)
    val app = LocalContext.current.applicationContext as? NezzelApplication
    var helpImprove by remember(app) { mutableStateOf(app?.preferences?.helpImprove ?: true) }
    BackHandler(advanced) { advanced = false }
    val reduced = LocalReducedMotion.current
    AnimatedContent(advanced, transitionSpec = {
        if (reduced) EnterTransition.None togetherWith ExitTransition.None
        else fadeIn(tween(Motion.FADE_IN, 70, Motion.EaseOut)) togetherWith fadeOut(tween(70, easing = Motion.EaseOut))
    }, label = "settings_page") { page ->
        if (page) AdvancedSettings(links.downloads?.let { it.maintenance.state.collectAsState().value }, links::updateEngine, updates,
            links.automaticOptions, onAutomaticOptions, links.confirmPausedUpdate, links::answerPausedUpdate, onClearLinks,
            app?.preferences?.parallelDownloadsFlow?.collectAsState()?.value, { app?.preferences?.chooseParallelDownloads(it) },
            language, helpImprove.takeIf { app != null }, { on -> helpImprove = on; app?.chooseHelpImprove(on) },
            oldDownloads, advancedSnackbar) { advanced = false }
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
    val scroll = rememberScrollState()
    ScrollOnTabReselect { scroll.scrollTo(0) }
    Scaffold(topBar = { ScreenHeader(stringResource(R.string.settings)) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(scroll)
            .padding(top = Space.s, bottom = Space.xl),
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

/** Clear link history once started: leaving the screen or the app can't stop it halfway, and a failure returns false. */
internal suspend fun clearLinkHistory(clear: suspend () -> Unit): Boolean = withContext(NonCancellable) {
    try { clear(); true } catch (_: Exception) { false }
}

/**
 * Forgets every saved file's link after one confirmation (files stay), and the Help improve Nezzel switch: a title only,
 * no supporting text (owner 2026-10-24); PRIVACY.md says what it sends.
 */
@Composable
internal fun PrivacySettings(onClear: (() -> Unit)?, helpImprove: Boolean? = null, onHelpImprove: (Boolean) -> Unit = {}) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    if (onClear == null && helpImprove == null) return
    SettingsGroup(stringResource(R.string.settings_privacy)) {
        if (onClear != null) SettingsNavRow(stringResource(R.string.clear_link_history), R.drawable.ic_ms_link,
            body = stringResource(R.string.clear_link_history_help), chevron = false) { confirm = true }
        helpImprove?.let { SwitchSetting(stringResource(R.string.help_improve), null, it, R.drawable.ic_ms_bug_report, onHelpImprove) }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.clear_link_history_title)) },
        text = { Text(stringResource(R.string.clear_link_history_body)) },
        dismissButton = {
            TextButton(onClick = { confirm = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            TextButton(onClick = { confirm = false; onClear?.invoke() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.clear))
            }
        })
}

/**
 * Automatic options (on by default; few people turn it off), clearing link history, engine tools, the daily update
 * check and the licences; [engine] is null without a download coordinator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdvancedSettings(engine: EngineMaintenanceState?, onUpdateEngine: () -> Unit, updates: AppUpdates?,
    automaticOptions: Boolean? = null, onAutomaticOptions: (Boolean) -> Unit = {},
    confirmPausedUpdate: Boolean = false, onPausedUpdate: (Boolean) -> Unit = {}, onClearLinks: (() -> Unit)? = null,
    parallel: Int? = null, onParallel: (Int) -> Unit = {}, language: String = "en",
    helpImprove: Boolean? = null, onHelpImprove: (Boolean) -> Unit = {},
    oldDownloads: OldDownloadsOffer? = null, snackbar: SnackbarHostState? = null, back: () -> Unit) {
    if (confirmPausedUpdate) AlertDialog(onDismissRequest = { onPausedUpdate(false) },
        title = { Text(stringResource(R.string.engine_update_paused_title)) },
        text = { Text(stringResource(R.string.engine_update_paused_body)) },
        confirmButton = { TextButton(onClick = { onPausedUpdate(true) }) { Text(stringResource(R.string.engine_update_paused_confirm)) } },
        dismissButton = { TextButton(onClick = { onPausedUpdate(false) }) { Text(stringResource(R.string.cancel)) } })
    val scroll = rememberScrollState()
    ScrollOnTabReselect { scroll.scrollTo(0) }
    Scaffold(snackbarHost = { snackbar?.let { SnackbarHost(it) } }, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings_advanced)) }, navigationIcon = {
            IconButton(onClick = back, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(scroll)
            .padding(top = Space.s, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.xl)) {
            automaticOptions?.let { on ->
                SettingsGroup(stringResource(R.string.link_handling)) {
                    SwitchSetting(stringResource(R.string.automatic_options), stringResource(R.string.automatic_options_help),
                        on, R.drawable.ic_ms_bolt, onAutomaticOptions)
                }
            }
            val findOld = oldDownloads?.takeIf { it.canGrant }
            if (parallel != null || findOld != null) SettingsGroup(stringResource(R.string.downloads)) {
                parallel?.let { ParallelSetting(it, language, onParallel) }
                // Gone once the folder is granted; back if the grant is removed.
                findOld?.let { SettingsNavRow(stringResource(R.string.old_downloads_setting), R.drawable.ic_folder,
                    body = stringResource(R.string.old_downloads_setting_body), onClick = it.show) }
            }
            PrivacySettings(onClearLinks, helpImprove, onHelpImprove)
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
                    if (updating) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Space.l))
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

/** One tap: the number of downloads that run together, 1 to 3. The title above says what the numbers mean. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParallelSetting(count: Int, language: String, onCount: (Int) -> Unit) = Column {
    SettingsRow(stringResource(R.string.parallel_downloads), icon = R.drawable.ic_download, body = stringResource(R.string.parallel_downloads_help))
    // Lines up with the title text (past the 24dp icon and its gap) so it reads as part of the row, not a second box.
    val indent = if (isLargeText()) Space.l else Space.l + 24.dp + Space.l
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = indent, end = Space.l, bottom = Space.m)) {
        (1..3).forEach { n ->
            val label = NezzelFormat.count(n, language)
            val spoken = stringResource(R.string.parallel_downloads_option, label)
            SegmentedButton(n == count, { onCount(n) }, SegmentedButtonDefaults.itemShape(n - 1, 3),
                Modifier.heightIn(min = 48.dp).semantics { contentDescription = spoken }) { Text(label) }
        }
    }
}
