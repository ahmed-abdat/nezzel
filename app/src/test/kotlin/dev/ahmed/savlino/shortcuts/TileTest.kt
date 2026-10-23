package dev.ahmed.savlino.shortcuts

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowService

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [CapturingTile::class])
class TileTest {
    @Test @Config(sdk = [33]) fun android13UsesExplicitIntent() = click(false)
    // API35 exercises the API34+ overload using the repository's already pinned SDK jars.
    @Test @Config(sdk = [35]) fun android14AndLaterUseImmutablePendingIntent() = click(true)
    private fun click(pending: Boolean) {
        val c = Robolectric.buildService(PasteLinkTileService::class.java).create()
        try {
            val service = c.get()
            val shadow = Shadow.extract<CapturingTile>(service)
            service.onStartListening()
            assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)
            assertNull(service.qsTile.subtitle)
            shadow.setLocked(true)
            service.onClick()
            assertNull(shadow.opened)
            assertNotNull(shadow.unlock)
            shadow.unlock!!.run()
            val intent = if (pending) shadowOf(shadow.pending!!).savedIntent else shadow.opened!!
            assertEquals(ComponentName(service.packageName, "dev.ahmed.savlino.PasteEntry"), intent.component)
            assertEquals(ShortcutActions.PASTE_LINK, intent.action)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            if (pending) assertTrue(shadowOf(shadow.pending!!).isImmutable)
            else assertNull(shadow.pending)
            shadow.setLocked(false)
            shadow.opened = null; shadow.pending = null; shadow.unlock = null
            service.onClick()
            assertNull(shadow.unlock)
            assertTrue(shadow.opened != null || shadow.pending != null)
        } finally { c.destroy() }
    }
}

@Implements(TileService::class)
class CapturingTile : ShadowService() {
    private val tile = Shadow.newInstanceOf(Tile::class.java)
    private var lockedForTest = false
    var opened: Intent? = null
    var pending: PendingIntent? = null
    var unlock: Runnable? = null
    fun setLocked(value: Boolean) { lockedForTest = value }
    @Implementation fun getQsTile(): Tile = tile
    @Implementation fun isLocked(): Boolean = lockedForTest
    @Implementation fun startActivityAndCollapse(intent: Intent) { opened = intent }
    @Implementation(minSdk = 34) fun startActivityAndCollapse(intent: PendingIntent) { pending = intent }
    @Implementation fun unlockAndRun(runnable: Runnable) { unlock = runnable }
}
