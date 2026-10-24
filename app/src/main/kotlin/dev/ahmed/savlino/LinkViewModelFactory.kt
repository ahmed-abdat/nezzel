package dev.ahmed.savlino

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import dev.ahmed.savlino.jobs.NezzelApplication
import dev.ahmed.savlino.locale.AppPreferences
import dev.ahmed.savlino.ui.LinkViewModel

/** One wiring for the app and the share sheet: each activity gets its own view model over the Application's engine, jobs and stores. */
internal fun linkViewModel(activity: ComponentActivity, preferences: AppPreferences): LinkViewModel {
    val application = activity.application as NezzelApplication
    val downloads = application.downloads
    val media = application.media
    val clipboard = dev.ahmed.savlino.storage.ClipboardPreferences(preferences)
    return ViewModelProvider(activity, object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            @Suppress("UNCHECKED_CAST")
            return LinkViewModel(extras.createSavedStateHandle(),
                engine = downloads.engine, downloads = downloads, coordinatorScope = application.scope,
                initialPreset = preferences.lastPreset, rememberPreset = { preferences.lastPreset = it },
                initialHeight = preferences.lastHeight, rememberHeight = { preferences.lastHeight = it },
                rememberHandledClipboard = clipboard::store, initializeClipboard = clipboard::load,
                images = media.inspect, imageSaver = media.saver, earlyPreview = media::earlyPreview,
                resolveShortLink = media::resolveShortLink,
                usage = application.usage) as T
        }
    })[LinkViewModel::class.java]
}
