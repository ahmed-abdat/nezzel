package dev.ahmed.savlino.report

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.Space
import kotlinx.coroutines.launch

/** Read-only list of exactly the fields [report] sends. Values are data, so they stay left to right. */
@Composable
internal fun ReportPreview(report: Report, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().testTag("report_preview"), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = Space.rowH, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            report.site?.let { Field(R.string.report_field_site, it) }
            report.code?.let { Field(R.string.report_field_error, it) }
            Field(R.string.report_field_app, report.appVersion)
            Field(R.string.report_field_engine, report.engineVersion ?: stringResource(R.string.engine_version_unknown))
            Field(R.string.report_field_android, report.androidSdk.toString())
            report.link?.let { Field(R.string.report_field_link, it) }
            report.trace?.let { trace ->
                Text(stringResource(R.string.report_field_details), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // A stack trace is long and wide: its own bounded, scrollable box, never wrapped into the sheet.
                Box(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())) {
                    Text(trace, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace,
                        textDirection = TextDirection.Ltr))
                }
            }
        }
    }
}

@Composable
private fun Field(label: Int, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(NezzelFormat.isolate(value), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(
            textDirection = TextDirection.Ltr), textAlign = TextAlign.End)
    }
}

/** Sheet body, separate from the popup window so it can be rendered and tested in place. */
@Composable
internal fun FailureReportContent(report: Report, onSend: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("report_sheet").verticalScroll(rememberScrollState())
        .padding(start = Space.xl, end = Space.xl, top = Space.s, bottom = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(stringResource(R.string.report_sheet_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.report_sheet_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ReportPreview(report)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End)) {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
            Button(onClick = onSend, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.report_send)) }
        }
    }
}

/** Opens over the failure card; [report] is null once that failure is gone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FailureReportSheet(report: () -> Report?, onSend: () -> Unit, onDismiss: () -> Unit) {
    // The failure behind the sheet can change (edit, retry); then there is nothing left to report.
    val preview = report() ?: run { SideEffect { onDismiss() }; return }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: (() -> Unit) -> Unit = { then -> scope.launch { sheet.hide() }.invokeOnCompletion { then(); onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        FailureReportContent(preview,
            onSend = { close { onSend() } }, onCancel = { close {} })
    }
}
