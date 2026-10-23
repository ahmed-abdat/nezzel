package dev.ahmed.savlino.shortcuts

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class PasteLinkTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            subtitle = null
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { openPaste() } else openPaste()
    }

    @Suppress("DEPRECATION")
    private fun openPaste() {
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(ShortcutActions.pendingIntent(this, ShortcutActions.PASTE_LINK))
        else startActivityAndCollapse(ShortcutActions.intent(this, ShortcutActions.PASTE_LINK))
    }
}
