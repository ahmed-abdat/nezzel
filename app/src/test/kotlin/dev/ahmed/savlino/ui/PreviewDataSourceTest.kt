package dev.ahmed.savlino.ui

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import okhttp3.Call
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class PreviewDataSourceTest {
    // An HLS playlist can point at plain-HTTP segments or keys: no request may leave, so the site's headers never go in clear.
    @Test fun plainHttpAddressNeverRequested() {
        var requests = 0
        val calls = Call.Factory { requests++; error("no request expected") }
        val source = PreviewDataSource(calls, mapOf("Referer" to "https://media.example/watch"))
        val error = assertFailsWith<HttpDataSource.HttpDataSourceException> {
            source.open(DataSpec(Uri.parse("http://media.example/segment1.ts")))
        }
        assertEquals(PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED, error.reason)
        assertTrue(requests == 0)
    }
}
