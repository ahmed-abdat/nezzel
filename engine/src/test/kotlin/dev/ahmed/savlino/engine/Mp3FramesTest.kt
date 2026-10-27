package dev.ahmed.savlino.engine

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class Mp3FramesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture(name: String) = javaClass.getResource("/mp3/$name")!!.readBytes()

    /** One frame: header fields as given, side info main_data_begin [mdb], zero payload. */
    private fun frame(kbps: Int, rate: Int, pad: Int = 0, crc: Boolean = false, layer: Int = 1, version: Int = 3,
        mdb: Int = 0, kbpsIndex: Int? = null): ByteArray {
        val bitrateIndex = kbpsIndex ?: listOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320).indexOf(kbps)
        val rateIndex = listOf(44100, 48000, 32000).indexOf(rate)
        val h = (0x7FF shl 21) or (version shl 19) or (layer shl 17) or ((if (crc) 0 else 1) shl 16) or
            (bitrateIndex shl 12) or (rateIndex shl 10) or (pad shl 9)
        val bytes = ByteArray(144_000 * kbps / rate + pad)
        for (i in 0..3) bytes[i] = (h ushr (24 - 8 * i)).toByte()
        bytes[4] = (mdb ushr 1).toByte()
        bytes[5] = ((mdb and 1) shl 7).toByte()
        return bytes
    }
    private fun offsets(bytes: ByteArray, kbps: Int, rate: Int) = Mp3Frames.offsets(ByteArrayInputStream(bytes), kbps, rate)

    @Test fun cbrFramesAt128To320And44kOr48kWithAndWithoutPaddingChainToTheEnd() {
        for (kbps in listOf(128, 192, 320)) for (rate in listOf(44100, 48000)) {
            val frames = listOf(frame(kbps, rate), frame(kbps, rate, pad = 1), frame(kbps, rate))
            val bytes = frames.reduce(ByteArray::plus)
            val expected = frames.runningFold(0L) { at, f -> at + f.size }
            assertEquals(expected, offsets(bytes, kbps, rate)?.toList(), "$kbps@$rate")
            assertEquals(144_000 * kbps / rate + 1, Mp3Frames.frameLength(frame(kbps, rate, pad = 1), kbps, rate))
        }
    }

    @Test fun crcLayerTwoMpegTwoOtherRatesAndReservoirFramesAreRejected() {
        val good = frame(192, 44100)
        for (bad in listOf(frame(192, 44100, crc = true), frame(192, 44100, layer = 2), frame(192, 44100, version = 2),
            frame(192, 44100, mdb = 1), frame(192, 44100, mdb = 256), frame(160, 44100), frame(192, 48000),
            frame(192, 44100, kbpsIndex = 15))) {
            assertNull(offsets(good + bad + good, 192, 44100))
        }
        assertNull(offsets(ByteArray(0), 192, 44100))
        assertNull(offsets(good + good.copyOf(100), 192, 44100), "a cut-off frame")
        assertNull(offsets(good + byteArrayOf(0, 0, 0, 0, 0, 0, 0), 192, 44100), "trailing bytes")
    }

    @Test fun bareChunkFramesFromHostLameParse() {
        val raw = fixture("raw-frames.mp3")
        val at = assertNotNull(offsets(raw, 128, 44100))
        assertEquals(14, at.size)
        assertEquals(raw.size.toLong(), at.last())
    }

    @Test fun crcMatchesWhatLameWrote() {
        for (name in listOf("lame-info.mp3", "copy-mux-info.mp3")) {
            val frame = infoFrame(fixture(name))
            val stored = (frame[190].toInt() and 0xFF shl 8) or (frame[191].toInt() and 0xFF)
            assertEquals(stored, Mp3Frames.crc16(frame, 0, Mp3Frames.LAME_CRC_SPAN), name)
        }
    }

    @Test fun copyMuxInfoFrameGetsLamesDelayAndPaddingAndAValidCrc() {
        val lame = infoFrame(fixture("lame-info.mp3"))
        val file = temp.newFile("output.temp.enc").apply { writeBytes(fixture("copy-mux-info.mp3")) }
        val before = file.readBytes()
        assertTrue(Mp3Frames.patchLameTag(file, 13230, 13))
        val after = file.readBytes()
        // delay 576, padding 13·1152 − 13230 − 576 = 1170, as LAME wrote them.
        assertEquals(lame.copyOfRange(177, 180).toList(), after.copyOfRange(177, 180).toList())
        assertEquals(listOf(0x24, 0x04, 0x92), after.copyOfRange(177, 180).map { it.toInt() and 0xFF })
        assertEquals((after[190].toInt() and 0xFF shl 8) or (after[191].toInt() and 0xFF), Mp3Frames.crc16(after, 0, 190))
        // Only the delay/padding field and the CRC change; audio frames stay untouched.
        assertEquals((0 until before.size).filter { before[it] != after[it] }.toSet() - setOf(177, 178, 179, 190, 191), emptySet())
    }

    @Test fun infoFrameAfterAnId3v2TagIsFound() {
        val tag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 20) + ByteArray(20)
        val file = temp.newFile("tagged.mp3").apply { writeBytes(tag + fixture("copy-mux-info.mp3")) }
        assertTrue(Mp3Frames.patchLameTag(file, 13230, 13))
        val bytes = file.readBytes()
        assertEquals(listOf(0x24, 0x04, 0x92), bytes.copyOfRange(30 + 177, 30 + 180).map { it.toInt() and 0xFF })
        assertEquals(tag.toList(), bytes.copyOf(30).toList())
    }

    @Test fun paddingOutside12BitsAWrongFrameCountOrNoInfoFrameLeavesTheFileAlone() {
        // 12 frames: padding 12·1152 − 13230 − 576 = 18 fits, only the count is wrong.
        for ((bytes, samples, frames) in listOf(Triple(fixture("copy-mux-info.mp3"), 13230L * 2, 13L),
            Triple(fixture("copy-mux-info.mp3"), 1L, 13L), Triple(fixture("copy-mux-info.mp3"), 13230L, 12L),
            Triple(fixture("raw-frames.mp3"), 13230L, 13L))) {
            val file = File(temp.newFolder(), "x.mp3").apply { writeBytes(bytes) }
            assertFalse(Mp3Frames.patchLameTag(file, samples, frames))
            assertContentEquals(bytes, file.readBytes())
        }
    }

    private fun infoFrame(bytes: ByteArray): ByteArray {
        val start = if (bytes[0] == 'I'.code.toByte()) 10 + ((bytes[6].toInt() shl 21) or (bytes[7].toInt() shl 14) or
            (bytes[8].toInt() shl 7) or bytes[9].toInt()) else 0
        return bytes.copyOfRange(start, start + 192)
    }
}
