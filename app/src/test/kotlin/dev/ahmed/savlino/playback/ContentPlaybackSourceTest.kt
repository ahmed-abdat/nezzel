package dev.ahmed.savlino.playback

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContentPlaybackSourceTest {
    @Test fun forbiddenSchemesCannotReachAnySource() {
        val context: Context = RuntimeEnvironment.getApplication()
        for (uri in listOf("https://invalid.test/a", "http://invalid.test/a", "file:///missing", "data:text/plain,a", "asset:///a", "relative")) {
            val source = ContentPlaybackSource.Factory(context).createDataSource()
            try { source.open(DataSpec(Uri.parse(uri))); fail("Source accepted non-content URI") }
            catch (error: LocalSourceException) { assertEquals(PlaybackFailure.UNAVAILABLE, error.failure); assertFalse(error.toString().contains(uri)) }
            finally { source.close() }
        }
    }
    @Test fun validContentStillReportsUnavailableWhenUnreadable() {
        val source = ContentPlaybackSource.Factory(RuntimeEnvironment.getApplication()).createDataSource()
        try { source.open(DataSpec(Uri.parse("content://missing/items/1"))); fail() }
        catch (error: LocalSourceException) { assertEquals(PlaybackFailure.UNAVAILABLE, error.failure) }
        finally { source.close() }
    }
}
