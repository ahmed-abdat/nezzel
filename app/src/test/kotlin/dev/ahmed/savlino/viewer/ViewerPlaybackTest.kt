package dev.ahmed.savlino.viewer

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ViewerPlaybackTest {
    @Test fun plainConstructorFailsClosedToSessionOnlyResume() {
        val item = ViewerItem(Uri.parse("content://test/status/1"), true, "video/mp4")
        val policy = item.javaClass.methods.firstOrNull { it.name == "getResumePolicy" }
        assertNotNull("An unannotated source needs an explicit session-only policy", policy)
        assertEquals("SESSION_ONLY", policy!!.invoke(item)?.toString())
    }
}
