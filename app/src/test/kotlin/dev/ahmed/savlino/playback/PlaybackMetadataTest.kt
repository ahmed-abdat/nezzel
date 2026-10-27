package dev.ahmed.savlino.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.AppLocale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], shadows = [CoverRetriever::class])
class PlaybackMetadataTest {
    private val context = RuntimeEnvironment.getApplication()
    private val uri = Uri.parse("content://playback.metadata/saved/1")
    private val provider = NameProvider()
    @Before fun setup() {
        CoverRetriever.picture = null
        CoverRetriever.textReads = 0
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = uri.authority })
        ShadowContentResolver.registerProviderInternal(uri.authority!!, provider)
    }
    @After fun cleanup() { CoverRetriever.picture = null }

    @Test fun displayNameSuppliesTheTitleOffMainThreadWithoutReadingTags() = runBlocking {
        for ((name, title) in listOf("Saved.audio.mp3" to "Saved.audio", "صوت جميل.m4a" to "صوت جميل", "Recording" to "Recording")) {
            provider.name = name
            val metadata = PlaybackMetadata(context).load(uri)
            assertEquals(title, metadata.title)
            assertEquals(AppLocale.localized(context).getString(R.string.app_name), metadata.artist)
            assertFalse(provider.queriedOnMain)
            assertEquals(0, CoverRetriever.textReads)
            assertNull(metadata.description)
            assertNull(metadata.artworkUri)
        }
    }
    @Test fun missingOrUnreadableNameUsesLocalizedFallbackAndBrandArtwork() = runBlocking {
        for (unreadable in listOf(false, true)) {
            provider.name = null; provider.unreadable = unreadable
            val metadata = PlaybackMetadata(context).load(uri)
            assertEquals(AppLocale.localized(context).getString(R.string.playback_audio), metadata.title)
            val bytes = requireNotNull(metadata.artworkData)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            assertEquals(512, bitmap.width); assertEquals(512, bitmap.height)
            assertEquals(context.getColor(R.color.nezzel_brand_teal), bitmap.getPixel(0, 0))
            bitmap.recycle()
        }
    }
    @Test fun embeddedCoverIsDownscaledAndReencoded() = runBlocking {
        val bitmap = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        CoverRetriever.picture = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
        bitmap.recycle()
        val bytes = requireNotNull(PlaybackMetadata(context).load(uri).artworkData)
        assertTrue(bytes.size <= 1_048_576)
        val result = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        assertTrue(result.width <= 512); assertTrue(result.height <= 512)
        assertEquals(2, result.width / result.height)
        assertEquals(android.graphics.Color.RED, result.getPixel(0, 0))
        result.recycle()
    }
    @Test fun invalidAndOversizedCoversUseBrandTile() = runBlocking {
        val fallback = PlaybackMetadata(context).load(uri).artworkData
        for (picture in listOf(byteArrayOf(1, 2, 3), ByteArray(1_048_577))) {
            CoverRetriever.picture = picture
            assertArrayEquals(fallback, PlaybackMetadata(context).load(uri).artworkData)
        }
    }
}

class NameProvider : ContentProvider() {
    var name: String? = "Saved audio.mp3"
    var unreadable = false
    var queriedOnMain = true
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
        queriedOnMain = Looper.myLooper() == Looper.getMainLooper()
        if (unreadable) throw SecurityException()
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
    }
    override fun getType(uri: Uri) = "audio/mpeg"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/** Robolectric has no embedded-picture shadow; keep its source handling and supply only that boundary. */
@Implements(MediaMetadataRetriever::class)
class CoverRetriever : ShadowMediaMetadataRetriever() {
    @Implementation fun getEmbeddedPicture(): ByteArray? = picture
    @Implementation override fun extractMetadata(key: Int): String { textReads++; return "unsafe tag" }
    companion object {
        var picture: ByteArray? = null
        var textReads = 0
    }
}
