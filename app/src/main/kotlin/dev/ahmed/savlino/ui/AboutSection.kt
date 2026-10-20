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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
        NoticeComponent("OkHttp", "Apache License 2.0", "Apache-2.0", "https://github.com/square/okhttp", "licenses/okhttp-APACHE-2.0.txt"),
        NoticeComponent("Okio", "Apache License 2.0", "Apache-2.0", "https://github.com/square/okio", "licenses/okio-APACHE-2.0.txt"),
        NoticeComponent("Material Color Utilities", "Apache License 2.0", "Apache-2.0",
            "https://github.com/material-foundation/material-color-utilities", "licenses/material-color-utilities-APACHE-2.0.txt"),
        NoticeComponent("Noto Sans Arabic", "SIL Open Font License 1.1", "OFL-1.1", "https://github.com/notofonts/arabic", "licenses/noto-sans-arabic-OFL.txt"),
    )
}

private val ltr = TextDirection.Ltr

/**
 * Short About group at the bottom of Settings: name and versions as one tight block, who makes Nezzel, then a
 * notice and the licences dialog. The engine version is not repeated here; Settings shows it in its engine row.
 */
@Composable
internal fun AboutSection(versionName: String, versionCode: Int, onHowItWorks: () -> Unit = {}) {
    var showLicences by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(stringResource(R.string.about), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        // Identity, version and build read as one block; the prose starts 16dp below it.
        Column(Modifier.padding(bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_version, NezzelFormat.isolate(versionName)), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.about_build, versionCode), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onHowItWorks, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.onboarding_how_it_works))
        }
        HululsoftAbout()
        Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // A row on the screen's 16dp gutter; a ListItem would inset it a second 16dp.
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { showLicences = true }
            .padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
                Text(stringResource(R.string.about_licences), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.about_licences_help), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
        CrashReportActions()
        Text(stringResource(R.string.about_warranty), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showLicences) LicencesDialog { showLicences = false }
}

@Composable
private fun LicencesDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_licences), modifier = Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                OpenSourceNotices.components.forEach { NoticeRow(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.nezzel_close)) }
        })
}

@Composable
private fun NoticeRow(component: NoticeComponent) {
    val context = LocalContext.current
    var expanded by rememberSaveable(component.name) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(NezzelFormat.isolate(component.name), style = MaterialTheme.typography.titleSmall)
        Text("${component.licence} (${component.spdx})", style = MaterialTheme.typography.bodyMedium.copy(textDirection = ltr))
        Text(NezzelFormat.isolate(component.url), style = MaterialTheme.typography.bodySmall.copy(textDirection = ltr),
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
                text?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(textDirection = ltr)) }
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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Text(NezzelFormat.isolate(name), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.nezzel_close)) }
                }
                LazyColumn(contentPadding = PaddingValues(horizontal = Space.l, vertical = Space.s)) {
                    items(chunks.orEmpty()) { Text(it, style = MaterialTheme.typography.bodySmall.copy(textDirection = ltr)) }
                }
            }
        }
    }
}
