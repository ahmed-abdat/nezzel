package dev.ahmed.savlino.viewer

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ViewerStateTest {
    private fun item(n: Int) = ViewerItem(Uri.parse("content://media/external/images/$n"), n % 2 == 0, "image/jpeg")
    private val box = Size(1000f, 2000f)

    @Test fun startsAtTheTappedItemAndFallsBackToTheFirst() {
        val items = (1..5).map(::item)
        assertEquals(2, viewerStart(items, items[2].uri))
        assertEquals(0, viewerStart(items, Uri.parse("content://gone/9")))
    }

    @Test fun pinchStaysBetweenOneAndFourTimes() {
        val zoom = ZoomState()
        zoom.transform(10f, Offset.Zero, Offset(500f, 1000f), box)
        assertEquals(ZoomState.MAX, zoom.scale)
        zoom.transform(.01f, Offset.Zero, Offset(500f, 1000f), box)
        assertEquals(1f, zoom.scale)
        assertFalse(zoom.zoomed)
        assertEquals(Offset.Zero, zoom.offset)
    }

    @Test fun doubleTapZoomsAroundThePointThenResets() {
        val zoom = ZoomState()
        zoom.toggle(Offset(250f, 500f), box)
        assertTrue(zoom.zoomed)
        assertEquals(ZoomState.DOUBLE_TAP, zoom.scale, .001f)
        // The tapped point stays put: the image moves toward the opposite corner.
        assertTrue(zoom.offset.x > 0 && zoom.offset.y > 0)
        zoom.toggle(Offset(250f, 500f), box)
        assertFalse(zoom.zoomed)
        assertEquals(Offset.Zero, zoom.offset)
    }

    @Test fun panNeverShowsPastTheImageEdge() {
        val zoom = ZoomState()
        zoom.transform(2f, Offset(99_999f, -99_999f), Offset(500f, 1000f), box)
        assertEquals(Offset(500f, -1000f), zoom.offset)
    }
}
