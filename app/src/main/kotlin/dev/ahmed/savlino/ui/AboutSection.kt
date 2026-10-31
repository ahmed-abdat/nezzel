package dev.ahmed.savlino.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ahmed.savlino.update.AppUpdates
import dev.ahmed.savlino.update.WhatsNewSheet
import dev.ahmed.savlino.jobs.NezzelApplication
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.Space

/**
 * One bundled open-source component. [asset] is a licence text shipped in the APK, or null when none is bundled.
 * [large] texts (the generated native notices, about 1 MB) open in a full-screen lazy viewer instead of inline.
 */
internal data class NoticeComponent(val name: String, val licence: String, val spdx: String, val url: String,
    val asset: String? = null, val large: Boolean = false)

/** Mirrors THIRD_PARTY_NOTICES.md. Legal names, SPDX ids and URLs are not translated. */
internal object OpenSourceNotices {
    private const val NATIVE = "licenses/NOTICES-native.txt"
    private const val GPL = "licenses/youtubedl-android-GPL-3.0.txt"
    val components = listOf(
        NoticeComponent("yt-dlp", "The Unlicense", "Unlicense", "https://github.com/yt-dlp/yt-dlp"),
        NoticeComponent("youtubedl-android", "GNU General Public License v3.0", "GPL-3.0", "https://github.com/yausername/youtubedl-android", GPL),
        NoticeComponent("FFmpeg", "GNU General Public License v3.0 or later", "GPL-3.0-or-later", "https://ffmpeg.org/legal.html", GPL),
        NoticeComponent("Python", "Python Software Foundation License 2.0", "PSF-2.0", "https://docs.python.org/3.12/license.html", NATIVE, large = true),
        NoticeComponent("Native libraries (FFmpeg and Python)", "Various open-source licences", "SEE-NOTICES",
            "https://github.com/ahmed-abdat/nezzel/blob/main/SOURCES.md", NATIVE, large = true),
        NoticeComponent("QuickJS", "MIT License", "MIT", "https://bellard.org/quickjs/", NATIVE, large = true),
        NoticeComponent("NewPipe Extractor", "GNU General Public License v3.0 or later", "GPL-3.0-or-later",
            "https://github.com/TeamNewPipe/NewPipeExtractor", GPL),
        NoticeComponent("Rhino", "Mozilla Public License 2.0", "MPL-2.0", "https://github.com/mozilla/rhino", "licenses/rhino-MPL-2.0.txt"),
        NoticeComponent("Rhino notices (V8-derived parts)", "BSD 3-Clause License", "BSD-3-Clause",
            "https://github.com/mozilla/rhino/blob/Rhino1_8_1_Release/NOTICE.txt", "licenses/rhino-NOTICE.txt"),
        NoticeComponent("Protocol Buffers (Java Lite)", "BSD 3-Clause License", "BSD-3-Clause",
            "https://github.com/protocolbuffers/protobuf", "licenses/protobuf-BSD-3-Clause.txt"),
        NoticeComponent("nanojson", "Apache License 2.0", "Apache-2.0", "https://github.com/TeamNewPipe/nanojson",
            "licenses/nanojson-APACHE-2.0.txt"),
        NoticeComponent("desugar_jdk_libs", "GNU General Public License v2.0 with Classpath Exception",
            "GPL-2.0-only WITH Classpath-exception-2.0", "https://github.com/google/desugar_jdk_libs",
            "licenses/desugar_jdk_libs-GPL-2.0-CPE.txt"),
        NoticeComponent("desugar_jdk_libs_configuration (R8)", "BSD 3-Clause License", "BSD-3-Clause",
            "https://r8.googlesource.com/r8", "licenses/desugar_jdk_libs_configuration-BSD-3-Clause.txt"),
        NoticeComponent("JSR 305 annotations", "Apache License 2.0", "Apache-2.0", "https://github.com/findbugsproject/findbugs",
            "licenses/jsr305-APACHE-2.0.txt"),
        NoticeComponent("OkHttp", "Apache License 2.0", "Apache-2.0", "https://github.com/square/okhttp", "licenses/okhttp-APACHE-2.0.txt"),
        NoticeComponent("Okio", "Apache License 2.0", "Apache-2.0", "https://github.com/square/okio", "licenses/okio-APACHE-2.0.txt"),
        NoticeComponent("AndroidX Media3", "Apache License 2.0", "Apache-2.0", "https://github.com/androidx/media",
            "licenses/okhttp-APACHE-2.0.txt"),
        NoticeComponent("Guava", "Apache License 2.0", "Apache-2.0", "https://github.com/google/guava", "licenses/okhttp-APACHE-2.0.txt"),
        NoticeComponent("Material Color Utilities", "Apache License 2.0", "Apache-2.0",
            "https://github.com/material-foundation/material-color-utilities", "licenses/material-color-utilities-APACHE-2.0.txt"),
        NoticeComponent("Material Symbols", "Apache License 2.0", "Apache-2.0", "https://github.com/google/material-design-icons",
            "licenses/material-symbols-APACHE-2.0.txt"),
        NoticeComponent("Noto Sans Arabic", "SIL Open Font License 1.1", "OFL-1.1", "https://github.com/notofonts/arabic", "licenses/noto-sans-arabic-OFL.txt"),
    )
}

private val ltr = TextDirection.Ltr

/** Latin blocks stay left-to-right, and the block itself sits on the layout's start edge. */
@Composable
private fun startAlign() = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left

/**
 * About group at the bottom of Settings: how-to, notes, maker and a small version line for support.
 */
@Composable
internal fun AboutSection(versionName: String, versionCode: Int, onHowItWorks: () -> Unit = {},
    language: String = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language,
    updates: AppUpdates? = (LocalContext.current.applicationContext as? NezzelApplication)?.appUpdates) {
    val context = LocalContext.current
    var showWhatsNew by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
        SettingsGroup(stringResource(R.string.about)) {
            SettingsNavRow(stringResource(R.string.onboarding_how_it_works), R.drawable.ic_ms_help, onClick = onHowItWorks)
            if (updates?.available == true && updates.currentNotes() != null) SettingsNavRow(stringResource(R.string.update_whats_new), R.drawable.ic_ms_celebration) {
                showWhatsNew = true
            }
            val contactIntro = stringResource(R.string.contact_whatsapp_intro)
            SettingsNavRow(stringResource(R.string.contact_whatsapp), R.drawable.logo_whatsapp) {
                ReportLink.open(context, contactIntro)
            }
            CrashReportActions()
        }
        Box(Modifier.padding(horizontal = Space.l)) { HululsoftAbout() }
        Text(stringResource(R.string.about_version, NezzelFormat.isolate(versionName)),
            Modifier.padding(horizontal = Space.l), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showWhatsNew) updates?.currentNotes()?.let { notes ->
        WhatsNewSheet(notes, language) { showWhatsNew = false }
    }
}

/**
 * The small "Licences" link beside the support line in Advanced. Few people look, but GPL-3.0 §5(d) and the bundled
 * Apache/BSD/OFL parts need the notices inside the app; the licence paragraph and warranty open with them.
 */
@Composable
internal fun LicencesLink() {
    var show by rememberSaveable { mutableStateOf(false) }
    val full = stringResource(R.string.about_licences)
    TextButton(onClick = { show = true }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.about_licences_short), Modifier.semantics { contentDescription = full },
            style = MaterialTheme.typography.bodySmall)
    }
    if (show) LicencesDialog { show = false }
}

@Composable
private fun LicencesDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_licences), modifier = Modifier.semantics { heading() }) },
        text = {
            val align = startAlign()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium.copy(textAlign = align))
                OpenSourceNotices.components.forEach { NoticeRow(it) }
                Text(stringResource(R.string.about_warranty), style = MaterialTheme.typography.bodySmall.copy(textAlign = align),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.nezzel_close)) }
        })
}

@Composable
private fun NoticeRow(component: NoticeComponent) {
    val context = LocalContext.current
    val align = startAlign()
    var expanded by rememberSaveable(component.name) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(NezzelFormat.isolate(component.name), style = MaterialTheme.typography.titleSmall.copy(textDirection = ltr, textAlign = align))
        Text(NezzelFormat.isolate("${component.licence} (${component.spdx})"), style = MaterialTheme.typography.bodyMedium.copy(textDirection = ltr, textAlign = align))
        Text(NezzelFormat.isolate(component.url), style = MaterialTheme.typography.bodySmall.copy(textDirection = ltr, textAlign = align),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (component.large) component.asset?.let { asset ->
            var open by rememberSaveable(component.name) { mutableStateOf(false) }
            val spoken = stringResource(R.string.about_show_licence, component.name)
            TextButton(onClick = { open = true }, contentPadding = PaddingValues(vertical = Space.s),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = spoken }) {
                Text(stringResource(R.string.about_view_licence))
            }
            if (open) LicenceTextScreen(component.name, asset) { open = false }
        } else component.asset?.let { asset ->
            // Short visible action under the already-named entry; TalkBack still hears which component it opens.
            val spoken = stringResource(if (expanded) R.string.about_hide_licence else R.string.about_show_licence, component.name)
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(vertical = Space.s),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = spoken }) {
                Text(stringResource(if (expanded) R.string.about_hide_licence_short else R.string.about_view_licence))
            }
            if (expanded) {
                val text by produceState<String?>(null, context, asset) {
                    value = loadLicenceText { context.assets.open(asset) }
                }
                text?.let { Text(NezzelFormat.isolate(it), style = MaterialTheme.typography.bodySmall.copy(textDirection = ltr, textAlign = align)) }
            }
        }
    }
}

/** Full-screen viewer for very long notices: only visible chunks are laid out, so 1 MB of text stays responsive. */
@Composable
private fun LicenceTextScreen(name: String, asset: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val chunks by produceState<List<String>?>(null, context, asset) {
        value = loadLicenceText { context.assets.open(asset) }?.let(::licenceChunks)
    }
    val align = startAlign()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Text(NezzelFormat.isolate(name), style = MaterialTheme.typography.titleMedium.copy(textDirection = ltr, textAlign = align),
                        modifier = Modifier.weight(1f).semantics { heading() })
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.nezzel_close)) }
                }
                LazyColumn(contentPadding = PaddingValues(horizontal = Space.l, vertical = Space.s)) {
                    items(chunks.orEmpty()) { Text(NezzelFormat.isolate(it), style = MaterialTheme.typography.bodySmall.copy(textDirection = ltr, textAlign = align)) }
                }
            }
        }
    }
}
