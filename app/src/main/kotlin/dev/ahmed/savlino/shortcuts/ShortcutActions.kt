package dev.ahmed.savlino.shortcuts

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.ahmed.savlino.MainActivity

/** Every shortcut opens the foreground Activity; no shortcut component reads the clipboard. */
object ShortcutActions {
    const val PASTE_LINK = "dev.ahmed.savlino.action.PASTE_LINK"
    const val OPEN_DOWNLOADS = "dev.ahmed.savlino.action.OPEN_DOWNLOADS"

    fun intent(context: Context, action: String) = Intent(context, MainActivity::class.java)
        .apply { if (action == PASTE_LINK) setClassName(context, "dev.ahmed.savlino.PasteEntry") }
        .setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun isPasteEntry(intent: Intent): Boolean = intent.action == PASTE_LINK &&
        intent.component?.className?.endsWith(".PasteEntry") == true

    fun pendingIntent(context: Context, action: String): PendingIntent = PendingIntent.getActivity(
        context, 0, intent(context, action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
