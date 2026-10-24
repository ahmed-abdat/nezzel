package dev.ahmed.savlino.jobs

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Android 16 QPR2 Live Update: the same notification, rebuilt with the framework builder (androidx.core 1.13 has no
 * ProgressStyle) as a promoted ongoing progress. Any failure keeps the plain notification; it is never lost.
 */
internal object LiveUpdate {
    /** 36.1 (QPR2) is where promotion lands; a plain API 36 phone keeps today's look. */
    val supported: Boolean by lazy {
        Build.VERSION.SDK_INT >= 36 && try { Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1 } catch (_: LinkageError) { false }
    }

    /** The user can turn Live Updates off for Nezzel in system settings; then the plain notification stays. */
    @RequiresApi(36)
    fun canPost(manager: NotificationManager): Boolean = try { manager.canPostPromotedNotifications() } catch (_: Exception) { false }

    @RequiresApi(36)
    fun apply(context: Context, plain: Notification, spec: LiveSpec): Notification = try {
        if (Build.VERSION.SDK_INT_FULL < Build.VERSION_CODES_FULL.BAKLAVA_1) return plain
        // One segment, no points or tracker icon: only the real percent, or a moving bar when there is none.
        val style = Notification.ProgressStyle().setProgressSegments(listOf(Notification.ProgressStyle.Segment(100)))
        if (spec.indeterminate) style.setProgressIndeterminate(true) else style.setProgress(spec.percent ?: 0)
        Notification.Builder.recoverBuilder(context, plain).setStyle(style).setRequestPromotedOngoing(true)
            .apply { spec.chip?.let(::setShortCriticalText) }.build()
    } catch (_: Exception) { plain } catch (_: LinkageError) { plain }
}
