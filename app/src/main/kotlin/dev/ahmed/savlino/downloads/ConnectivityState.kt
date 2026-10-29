package dev.ahmed.savlino.downloads

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.ahmed.savlino.jobs.Connectivity

/** Settled offline state for rows; the connectivity module owns capability and timing decisions. */
val LocalOffline = staticCompositionLocalOf { false }

fun isOffline(context: Context): Boolean = Connectivity.get(context).snapshot.offline
fun isMetered(context: Context): Boolean = Connectivity.get(context).snapshot.metered

@Composable
fun rememberOffline(): Boolean = Connectivity.get(LocalContext.current).state.collectAsState().value.offlineSettled

@Composable
fun rememberMetered(): Boolean = Connectivity.get(LocalContext.current).state.collectAsState().value.metered
