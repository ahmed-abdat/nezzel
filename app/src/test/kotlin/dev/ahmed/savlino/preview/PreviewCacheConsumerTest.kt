package dev.ahmed.savlino.preview

import android.app.Application
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Local cache consumer seam, not LinkViewModel integration or a phone/network speed benchmark. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class PreviewCacheConsumerTest : PreviewCacheTestSupport() {
    @Test fun coldThenMemoryAndRestartDiskConsumersMeasureActualLoaderAndDecodeCounts() {
        var fixtureLoads = 0
        val codec = CountingPreviewCodec()
        val first = open(codec = codec)
        fun consume(cache: PreviewCache): CachedPreview {
            val token = cache.token()
            val key = key(cache, "fixture", token)
            cache.get(key, token)?.let { cached ->
                cached.artwork?.let(::remember)
                return cached
            }
            fixtureLoads++
            // Local generated pixels stand in for one already-authorized caller load. No URL is fetched.
            assertTrue(cache.put(key, token, "Fixture display", artwork = image(80, 60)))
            return hit(cache, key, token)
        }

        val cold = consume(first)
        assertEquals(Color.RED, cold.artwork!!.getPixel(0, 0))
        assertEquals(1, fixtureLoads)
        val coldDecodes = codec.decodes
        val coldBytes = ownedBytes()
        assertTrue("fixture must actually populate disk", coldBytes > 0)

        val warm = consume(first)
        assertEquals(Color.RED, warm.artwork!!.getPixel(0, 0))
        assertNotSame(cold.artwork, warm.artwork)
        assertEquals("warm memory hit avoids a second caller load", 1, fixtureLoads)
        assertEquals("warm memory hit avoids a second decode", coldDecodes, codec.decodes)
        first.close()

        val restarted = open(codec = codec)
        val diskWarm = consume(restarted)
        assertEquals(Color.RED, diskWarm.artwork!!.getPixel(0, 0))
        assertEquals("validated disk hit also avoids a second caller load", 1, fixtureLoads)
        assertEquals("restart decodes sampled disk artwork once", coldDecodes + 1, codec.decodes)
        consume(restarted)
        assertEquals(coldDecodes + 1, codec.decodes)
        assertDiskBounds(restarted)
        println("PreviewCache fixture: loads cold=1 memory=0 restart=0; decodes cold=$coldDecodes memory=0 restart=1; ownedBytes=$coldBytes/${ownedBytes()}; app/phone unmeasured")
    }
}
