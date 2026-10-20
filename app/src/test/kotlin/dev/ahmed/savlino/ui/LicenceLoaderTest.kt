package dev.ahmed.savlino.ui

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LicenceLoaderTest {
    @Test fun readsAndClosesAssetOffCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        var closed = false
        val text = loadLicenceText {
            assertNotSame(caller, Thread.currentThread())
            object : ByteArrayInputStream("licence fixture".toByteArray()) {
                override fun close() { closed = true; super.close() }
            }
        }
        assertEquals("licence fixture", text); assertTrue(closed)
    }
    @Test fun longNoticeSplitsIntoBoundedChunksThatRejoinExactly() {
        val text = (1..205).joinToString("\n") { "line $it" } + "\n"
        val chunks = licenceChunks(text)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.lines().size <= 80 })
        assertEquals(text, chunks.joinToString("\n"))
    }
    @Test fun missingAssetLeavesNoText() = runBlocking {
        assertNull(loadLicenceText { throw IOException("missing") })
    }
}
