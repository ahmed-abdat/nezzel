package dev.ahmed.savlino.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.jobs.BatterySettings
import dev.ahmed.savlino.locale.AppPreferences
import kotlinx.coroutines.flow.Flow

/** No startup/intake request: consume confirmation events only while this Activity is visible. */
@Composable
fun BatteryExemptionEffect(activity: ComponentActivity, preferences: AppPreferences, confirmations: Flow<Unit>) {
    LaunchedEffect(activity, confirmations) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            confirmations.collect {
                if (!preferences.batteryAsked && !BatterySettings.isExempt(activity) && preferences.markBatteryAsked())
                    BatterySettings.requestExemption(activity)
            }
        }
    }
}

/**
 * Failed-row extra for an OS battery stop or refused foreground start: Samsung hint + Battery settings button.
 * Hidden once Nezzel is exempt (re-checked on resume, after the user comes back from Settings).
 */
@Composable
fun BatteryHelp(error: DownloadError?, onBatterySettings: () -> Unit,
    exempt: () -> Boolean = rememberExemptCheck(), samsung: Boolean = BatterySettings.showsSamsungHint()) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var isExempt by remember { mutableStateOf(exempt()) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) isExempt = exempt() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!BatterySettings.offersButton(error, isExempt)) return
    Column {
        if (samsung) Text(stringResource(BatterySettings.samsungHint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onBatterySettings, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(BatterySettings.buttonText))
        }
    }
}

@Composable
private fun rememberExemptCheck(): () -> Boolean {
    val context = LocalContext.current
    return remember(context) { { BatterySettings.isExempt(context) } }
}
