package dev.ahmed.savlino.playback

import android.content.ComponentName
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Robolectric's default bind reports a null component, which Media3 dereferences; name the service, no binder. */
internal fun registerAudioServiceBinding() {
    val app = RuntimeEnvironment.getApplication()
    shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, AudioPlaybackService::class.java), null)
}
