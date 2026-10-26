package dev.ahmed.savlino.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ahmed.savlino.playback.*
import java.util.UUID

internal val LocalPlaybackEnvironment = staticCompositionLocalOf<PlaybackEnvironment?> { null }

/** Activity-retained sessions survive recreation, while a dismissed viewer is removed immediately. */
internal class ViewerPlaybackRegistry : ViewModel() {
    val sessions = mutableMapOf<String, PlaybackCoordinator>()
    override fun onCleared() { sessions.values.toList().forEach { it.close() }; sessions.clear() }
}

@Composable
internal fun rememberViewerPlayback(): PlaybackCoordinator {
    val context = LocalContext.current
    val registry: ViewerPlaybackRegistry = viewModel()
    val id = rememberSaveable { UUID.randomUUID().toString() }
    val environment = LocalPlaybackEnvironment.current ?: PlaybackEnvironment.get(context)
    val owner = remember(registry, id) { registry.sessions.getOrPut(id) { PlaybackCoordinator(environment, SessionAudioConnection(environment)) } }
    DisposableEffect(owner, context) {
        onDispose {
            if (context.activity()?.isChangingConfigurations != true) { registry.sessions.remove(id); owner.close() }
        }
    }
    return owner
}
private fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }
