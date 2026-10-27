package dev.ahmed.savlino.engine

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/** MPEG-1 Layer III frame checks for the chunked MP3 encode. Pure apart from [patchLameTag]'s small file edit. */
internal object Mp3Frames {
    const val SAMPLES = 1152
    /** LAME's compile-time encoder delay, the same in every instance. */
    const val ENCODER_DELAY = 576
    private val bitrates = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val rates = intArrayOf(44100, 48000, 32000)

    /**
     * Start offsets of every frame plus the end offset, or null unless the stream is only CBR MPEG-1 Layer III frames
     * without CRC at [bitrateKbps]/[sampleRate], each with main_data_begin 0 (no reservoir), ending on a frame boundary.
     */
    fun offsets(input: InputStream, bitrateKbps: Int, sampleRate: Int): LongArray? {
        val starts = ArrayList<Long>()
        val head = ByteArray(6)
        var position = 0L
        while (true) {
            val read = readUpTo(input, head)
            if (read == 0) break
            if (read < head.size) return null
            val length = frameLength(head, bitrateKbps, sampleRate) ?: return null
            if (mainDataBegin(head) != 0) return null
            starts += position
            if (!skip(input, (length - head.size).toLong())) return null
            position += length
        }
        if (starts.isEmpty()) return null
        return (starts + position).toLongArray()
    }

    /** Byte length of the frame whose header starts [header], or null when it isn't the expected kind. */
    fun frameLength(header: ByteArray, bitrateKbps: Int, sampleRate: Int): Int? {
        if (header.size < 4) return null
        val h = (header[0].u shl 24) or (header[1].u shl 16) or (header[2].u shl 8) or header[3].u
        if (h ushr 21 != 0x7FF || (h ushr 19) and 3 != 3 || (h ushr 17) and 3 != 1 || (h ushr 16) and 1 != 1) return null
        val bitrate = bitrates.getOrNull((h ushr 12) and 0xF) ?: return null
        val rate = rates.getOrNull((h ushr 10) and 3) ?: return null
        if (bitrate != bitrateKbps || rate != sampleRate) return null
        return 144_000 * bitrate / rate + ((h ushr 9) and 1)
    }

    /** First 9 bits of the side info (no CRC, so it follows the 4-byte header). */
    private fun mainDataBegin(head: ByteArray) = (head[4].u shl 1) or (head[5].u ushr 7)

    /**
     * Writes LAME's delay (576) and padding into the Info frame a `-c copy` mux leaves at zero, so gapless players
     * trim the same lead-in and tail as a single-pass file, then fixes the tag CRC. Skips (false) when the file has
     * no such frame, its frame count isn't [frames] or the padding doesn't fit 12 bits.
     */
    fun patchLameTag(file: File, totalSamples: Long, frames: Long): Boolean = RandomAccessFile(file, "rw").use { raf ->
        if (raf.length() < INFO_BYTES) return false
        val head = ByteArray(10)
        raf.readFully(head)
        var start = 0L
        if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            val size = (head[6].u shl 21) or (head[7].u shl 14) or (head[8].u shl 7) or head[9].u
            start = 10L + size + if (head[5].u and 0x10 != 0) 10 else 0
        }
        if (start + INFO_BYTES > raf.length()) return false
        val frame = ByteArray(INFO_BYTES)
        raf.seek(start)
        raf.readFully(frame)
        if (!patchInfoFrame(frame, totalSamples, frames)) return false
        raf.seek(start)
        raf.write(frame)
        true
    }

    /** In-place patch of the first [INFO_BYTES] of an Info/Xing frame. */
    fun patchInfoFrame(frame: ByteArray, totalSamples: Long, expectedFrames: Long): Boolean {
        if (frame.size < INFO_BYTES || frame[0].u != 0xFF || frame[1].u and 0xE0 != 0xE0) return false
        val mono = frame[3].u ushr 6 == 3
        val xing = 4 + if (mono) 17 else 32
        val tag = String(frame, xing, 4, Charsets.ISO_8859_1)
        if (tag != "Info" && tag != "Xing") return false
        val flags = int32(frame, xing + 4)
        if (flags and 1 == 0) return false
        val frames = int32(frame, xing + 8).toLong() and 0xFFFFFFFFL
        // The mux counted what it got: a short pipe shows here even when the padding would still fit.
        if (frames != expectedFrames) return false
        val lame = xing + 8 + listOf(1 to 4, 2 to 4, 4 to 100, 8 to 4).sumOf { (bit, size) -> if (flags and bit != 0) size else 0 }
        // The tag CRC sits at byte 190 of the frame only when all four Xing fields are present.
        if (lame + LAME_FIELDS_TO_CRC != LAME_CRC_SPAN) return false
        val padding = frames * SAMPLES - totalSamples - ENCODER_DELAY
        if (padding !in 0..4095) return false
        val field = lame + 21
        frame[field] = (ENCODER_DELAY ushr 4).toByte()
        frame[field + 1] = (((ENCODER_DELAY and 0xF) shl 4) or (padding ushr 8).toInt()).toByte()
        frame[field + 2] = padding.toInt().toByte()
        val crc = crc16(frame, 0, LAME_CRC_SPAN)
        frame[LAME_CRC_SPAN] = (crc ushr 8).toByte()
        frame[LAME_CRC_SPAN + 1] = crc.toByte()
        return true
    }

    /** CRC-16/ARC, the LAME tag checksum. */
    fun crc16(bytes: ByteArray, from: Int, length: Int): Int {
        var crc = 0
        for (i in from until from + length) {
            crc = crc xor bytes[i].u
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }

    /** Bytes 0..189 are checksummed; the CRC follows them. */
    const val LAME_CRC_SPAN = 190
    private const val LAME_FIELDS_TO_CRC = 34
    private const val INFO_BYTES = LAME_CRC_SPAN + 2

    private fun int32(b: ByteArray, at: Int) = (b[at].u shl 24) or (b[at + 1].u shl 16) or (b[at + 2].u shl 8) or b[at + 3].u
    // InputStream.readNBytes/skipNBytes need API 33/34; min SDK is 29.
    private fun readUpTo(input: InputStream, into: ByteArray): Int {
        var total = 0
        while (total < into.size) {
            val read = input.read(into, total, into.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }
    private fun skip(input: InputStream, count: Long): Boolean {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) left -= skipped else if (input.read() < 0) return false else left--
        }
        return true
    }
    private val Byte.u get() = toInt() and 0xFF
}
