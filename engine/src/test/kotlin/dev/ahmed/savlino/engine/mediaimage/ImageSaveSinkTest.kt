package dev.ahmed.savlino.engine.mediaimage

import java.io.IOException
import java.io.OutputStream
import org.junit.Test
import kotlin.test.*

/** Socket-free checks of the actual staging-write boundary used by ImageSaveTransfer. */
class ImageSaveSinkTest {
    @Test fun stagingEnospcKeepsOnlyTheTypedFailure() {
        val output = object : OutputStream() {
            override fun write(value: Int) = throw IOException("ENOSPC private diagnostics")
            override fun write(bytes: ByteArray, offset: Int, length: Int) = write(0)
            override fun close() = error("caller owns the sink")
        }
        val sink = ProofSink(output, ImageRepresentationValidator().newStream("image/png")) { true }
        val failure = assertFailsWith<IOException> { sink.write(raster()) }
        assertEquals("INSUFFICIENT_SPACE", sink.failure?.name)
        assertEquals("image_save_aborted", failure.message)
        assertNull(failure.cause)
    }

    @Test fun unrelatedStagingIoRemainsGeneric() {
        val output = object : OutputStream() {
            override fun write(value: Int) = throw IOException("private diagnostics")
            override fun write(bytes: ByteArray, offset: Int, length: Int) = write(0)
        }
        val sink = ProofSink(output, ImageRepresentationValidator().newStream("image/png")) { true }
        assertFailsWith<IOException> { sink.write(raster()) }
        assertEquals(ImageFailure.STAGING, sink.failure)
    }
}
