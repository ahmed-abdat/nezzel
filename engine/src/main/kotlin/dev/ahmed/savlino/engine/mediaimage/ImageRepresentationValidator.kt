package dev.ahmed.savlino.engine.mediaimage

import dev.ahmed.savlino.domain.MediaImageFormat
import dev.ahmed.savlino.domain.MediaImageMetadata
import dev.ahmed.savlino.domain.MediaImageProof
import dev.ahmed.savlino.domain.Size
import java.security.MessageDigest
import java.util.zip.CRC32

internal const val MAX_IMAGE_AXIS = 16384L
internal const val MAX_IMAGE_PIXELS = 100_000_000L

internal fun imageFormatFor(mimeType: String?): MediaImageFormat? {
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return null
    return MediaImageFormat.entries.singleOrNull { it.mimeType == mime }
}

internal fun dimensionFailure(width: Long, height: Long): ImageFailure? = when {
    width <= 0 || height <= 0 -> ImageFailure.INVALID_IMAGE
    width > MAX_IMAGE_AXIS || height > MAX_IMAGE_AXIS || width * height > MAX_IMAGE_PIXELS -> ImageFailure.DIMENSIONS
    else -> null
}

/**
 * Complete-representation validation for JPEG/PNG/still WebP/AVIF and GIF. Bounded incremental state only: no pixel
 * decode, no allocation proportional to the image, and proof only after the whole body was structurally
 * consumed. GIF87a/GIF89a bytes override declared MIME; other encodings must agree.
 */
class ImageRepresentationValidator {
    fun validate(bytes: ByteArray, mimeType: String, length: Int = bytes.size): ImageValidationResult {
        val stream = newStream(mimeType)
        stream.accept(bytes, 0, length)?.let { return ImageValidationResult.Failure(it) }
        return stream.finish()
    }

    fun newStream(mimeType: String) = Stream(imageFormatFor(mimeType))

    class Stream internal constructor(private val declaredFormat: MediaImageFormat?) {
        private val digest = MessageDigest.getInstance("SHA-256")
        private val signature = ByteArray(6)
        private var signatureSize = 0
        private var format: MediaImageFormat? = null
        private var parser: FormatParser? = null
        private var failure: ImageFailure? = null
        private var measured = 0L
        private var finished = false

        fun accept(bytes: ByteArray, offset: Int, count: Int): ImageFailure? {
            failure?.let { return it }
            if (finished || offset < 0 || count < 0 || offset > bytes.size - count) return fail(ImageFailure.INVALID_IMAGE)
            if (count == 0) return null
            digest.update(bytes, offset, count)
            measured += count
            var consumed = 0
            if (parser == null) {
                consumed = minOf(count, signature.size - signatureSize)
                bytes.copyInto(signature, signatureSize, offset, offset + consumed)
                signatureSize += consumed
                if (signatureSize < signature.size) return null
                val magic = String(signature, Charsets.US_ASCII)
                format = if (magic == "GIF87a" || magic == "GIF89a") MediaImageFormat.GIF else declaredFormat
                parser = when (format) {
                    MediaImageFormat.PNG -> PngParser()
                    MediaImageFormat.JPEG -> JpegParser()
                    MediaImageFormat.WEBP -> WebpParser()
                    MediaImageFormat.GIF -> GifParser()
                    MediaImageFormat.AVIF -> AvifParser()
                    null -> return fail(ImageFailure.UNSUPPORTED)
                }
                checkNotNull(parser).feed(signature, 0, signature.size)?.let { return fail(it) }
            }
            return checkNotNull(parser).feed(bytes, offset + consumed, count - consumed)?.let(::fail)
        }

        fun finish(): ImageValidationResult {
            failure?.let { return ImageValidationResult.Failure(it) }
            if (finished) return ImageValidationResult.Failure(fail(ImageFailure.INVALID_IMAGE))
            finished = true
            val parsed = parser ?: return ImageValidationResult.Failure(fail(ImageFailure.INVALID_IMAGE))
            parsed.finish()?.let { return ImageValidationResult.Failure(fail(it)) }
            if (measured <= 0) return ImageValidationResult.Failure(fail(ImageFailure.INVALID_IMAGE))
            val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            return ImageValidationResult.Valid(MediaImageMetadata(checkNotNull(format), Size.Exact(measured),
                parsed.width.toInt(), parsed.height.toInt(), MediaImageProof(sha, measured)))
        }

        private fun fail(reason: ImageFailure): ImageFailure { failure = reason; return reason }
        override fun toString() = "ImageRepresentationValidator.Stream(redacted)"
    }
}

internal interface FormatParser {
    val width: Long
    val height: Long
    /** Returns a failure as soon as the consumed prefix is already invalid. */
    fun feed(bytes: ByteArray, offset: Int, count: Int): ImageFailure?
    /** Returns null only when the consumed bytes are one complete representation. */
    fun finish(): ImageFailure?
}

private fun u32(buffer: ByteArray, at: Int): Long =
    ((buffer[at].toLong() and 255) shl 24) or ((buffer[at + 1].toLong() and 255) shl 16) or
        ((buffer[at + 2].toLong() and 255) shl 8) or (buffer[at + 3].toLong() and 255)
private fun le16(buffer: ByteArray, at: Int): Long =
    (buffer[at].toLong() and 255) or ((buffer[at + 1].toLong() and 255) shl 8)
private fun le24(buffer: ByteArray, at: Int): Long =
    (buffer[at].toLong() and 255) or ((buffer[at + 1].toLong() and 255) shl 8) or ((buffer[at + 2].toLong() and 255) shl 16)
private fun le32(buffer: ByteArray, at: Int): Long = le24(buffer, at) or ((buffer[at + 3].toLong() and 255) shl 24)

/** GIF block framing only; pixel decoding stays in the bounded platform first-frame decoder. */
private class GifParser : FormatParser {
    private enum class State { HEADER, PALETTE, BLOCK, FRAME, CODE_SIZE, EXTENSION, LENGTH, DATA, DONE }
    private var state = State.HEADER
    private var afterPalette = State.BLOCK
    private val header = ByteArray(13)
    private val frame = ByteArray(9)
    private var position = 0
    private var remaining = 0
    private var globalPalette = false
    private var imageData = false
    private var sawData = false
    private var sawImage = false
    private var firstBlockSize = 0
    override var width = 0L; private set
    override var height = 0L; private set

    override fun feed(bytes: ByteArray, offset: Int, count: Int): ImageFailure? {
        var index = offset
        val end = offset + count
        while (index < end) {
            val value = bytes[index].toInt() and 255
            when (state) {
                State.HEADER -> {
                    header[position++] = bytes[index++]
                    if (position == header.size) {
                        val magic = String(header, 0, 6, Charsets.US_ASCII)
                        if (magic != "GIF87a" && magic != "GIF89a") return ImageFailure.INVALID_IMAGE
                        width = le16(header, 6); height = le16(header, 8)
                        dimensionFailure(width, height)?.let { return it }
                        globalPalette = header[10].toInt() and 0x80 != 0
                        palette(header[10].toInt(), State.BLOCK)
                    }
                }
                State.PALETTE, State.DATA -> {
                    val take = minOf(remaining, end - index)
                    index += take; remaining -= take
                    if (remaining == 0) state = if (state == State.PALETTE) afterPalette else State.LENGTH
                }
                State.BLOCK -> {
                    index++
                    position = 0
                    state = when (value) {
                        0x2C -> State.FRAME
                        0x21 -> State.EXTENSION
                        0x3B -> State.DONE
                        else -> return ImageFailure.INVALID_IMAGE
                    }
                }
                State.FRAME -> {
                    frame[position++] = bytes[index++]
                    if (position == frame.size) {
                        val frameWidth = le16(frame, 4); val frameHeight = le16(frame, 6)
                        dimensionFailure(frameWidth, frameHeight)?.let { return it }
                        if (le16(frame, 0) + frameWidth > width || le16(frame, 2) + frameHeight > height ||
                            frame[8].toInt() and 0x18 != 0 || (!globalPalette && frame[8].toInt() and 0x80 == 0))
                            return ImageFailure.INVALID_IMAGE
                        palette(frame[8].toInt(), State.CODE_SIZE)
                    }
                }
                State.CODE_SIZE -> {
                    if (value !in 2..8) return ImageFailure.INVALID_IMAGE
                    index++; imageData = true; sawData = false; firstBlockSize = 0
                    state = State.LENGTH
                }
                State.EXTENSION -> {
                    index++; imageData = false
                    firstBlockSize = when (value) { 0xF9 -> 4; 0xFF -> 11; 0x01 -> 12; else -> 0 }
                    state = State.LENGTH
                }
                State.LENGTH -> {
                    index++
                    if (firstBlockSize != 0 && value != firstBlockSize) return ImageFailure.INVALID_IMAGE
                    firstBlockSize = 0
                    if (value == 0) {
                        if (imageData) {
                            if (!sawData) return ImageFailure.INVALID_IMAGE
                            sawImage = true
                        }
                        state = State.BLOCK
                    } else {
                        sawData = true; remaining = value; state = State.DATA
                    }
                }
                State.DONE -> return ImageFailure.INVALID_IMAGE
            }
        }
        return null
    }

    private fun palette(packed: Int, next: State) {
        afterPalette = next
        remaining = if (packed and 0x80 != 0) 3 * (1 shl ((packed and 7) + 1)) else 0
        state = if (remaining > 0) State.PALETTE else next
    }

    override fun finish(): ImageFailure? = if (state == State.DONE && sawImage) null else ImageFailure.INVALID_IMAGE
}

private class PngParser : FormatParser {
    private enum class State { SIGNATURE, HEADER, DATA, CRC, DONE }
    private val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private val header = ByteArray(8)
    private val ihdr = ByteArray(13)
    private val crcBytes = ByteArray(4)
    private val crc = CRC32()
    private var state = State.SIGNATURE
    private var position = 0
    private var remaining = 0L
    private var type = ""
    private var chunks = 0
    private var sawIdat = false
    override var width = 0L; private set
    override var height = 0L; private set

    override fun feed(bytes: ByteArray, offset: Int, count: Int): ImageFailure? {
        var index = offset
        val end = offset + count
        while (index < end) {
            when (state) {
                State.SIGNATURE -> {
                    if (bytes[index++] != signature[position++]) return ImageFailure.INVALID_IMAGE
                    if (position == signature.size) { state = State.HEADER; position = 0 }
                }
                State.HEADER -> {
                    header[position++] = bytes[index++]
                    if (position == header.size) startChunk()?.let { return it }
                }
                State.DATA -> {
                    val take = minOf(remaining, (end - index).toLong()).toInt()
                    crc.update(bytes, index, take)
                    if (type == "IHDR") bytes.copyInto(ihdr, (13 - remaining).toInt(), index, index + take)
                    index += take
                    remaining -= take
                    if (remaining == 0L) { state = State.CRC; position = 0 }
                }
                State.CRC -> {
                    crcBytes[position++] = bytes[index++]
                    if (position == 4) endChunk()?.let { return it }
                }
                State.DONE -> return ImageFailure.INVALID_IMAGE // Appended bytes after IEND.
            }
        }
        return null
    }

    private fun startChunk(): ImageFailure? {
        val length = u32(header, 0)
        type = String(header, 4, 4, Charsets.ISO_8859_1)
        if (length > Int.MAX_VALUE || type.any { it !in 'A'..'Z' && it !in 'a'..'z' }) return ImageFailure.INVALID_IMAGE
        if (type == "acTL" || type == "fcTL" || type == "fdAT") return ImageFailure.ANIMATED
        if ((chunks == 0) != (type == "IHDR")) return ImageFailure.INVALID_IMAGE
        if ((type == "IHDR" && length != 13L) || (type == "IEND" && length != 0L)) return ImageFailure.INVALID_IMAGE
        chunks++
        crc.reset()
        crc.update(header, 4, 4)
        remaining = length
        position = 0
        state = if (length == 0L) State.CRC else State.DATA
        return null
    }

    private fun endChunk(): ImageFailure? {
        if (u32(crcBytes, 0) != crc.value) return ImageFailure.INVALID_IMAGE
        position = 0
        state = State.HEADER
        when (type) {
            "IHDR" -> {
                width = u32(ihdr, 0)
                height = u32(ihdr, 4)
                if (width > Int.MAX_VALUE || height > Int.MAX_VALUE) return ImageFailure.INVALID_IMAGE
                dimensionFailure(width, height)?.let { return it }
                val depth = ihdr[8].toInt()
                val color = ihdr[9].toInt()
                val allowed = when (color) {
                    0 -> depth in setOf(1, 2, 4, 8, 16)
                    3 -> depth in setOf(1, 2, 4, 8)
                    2, 4, 6 -> depth == 8 || depth == 16
                    else -> false
                }
                if (!allowed || ihdr[10].toInt() != 0 || ihdr[11].toInt() != 0 || ihdr[12].toInt() !in 0..1)
                    return ImageFailure.INVALID_IMAGE
            }
            "IDAT" -> sawIdat = true
            "IEND" -> state = State.DONE
        }
        return null
    }

    override fun finish(): ImageFailure? = if (state == State.DONE && sawIdat) null else ImageFailure.INVALID_IMAGE
}

private class JpegParser : FormatParser {
    private enum class State { SOI, MARKER_PREFIX, MARKER, LENGTH, SEGMENT, SCAN, SCAN_PREFIX, DONE }
    private var state = State.SOI
    private var position = 0
    private var marker = 0
    private val length = ByteArray(2)
    private val frame = ByteArray(5)
    private var remaining = 0
    private var segmentLength = 0
    private var sawFrame = false
    private var sawScan = false
    override var width = 0L; private set
    override var height = 0L; private set

    override fun feed(bytes: ByteArray, offset: Int, count: Int): ImageFailure? {
        var index = offset
        val end = offset + count
        while (index < end) {
            val value = bytes[index].toInt() and 255
            when (state) {
                State.SOI -> {
                    if (value != (if (position == 0) 0xFF else 0xD8)) return ImageFailure.INVALID_IMAGE
                    index++
                    if (++position == 2) state = State.MARKER_PREFIX
                }
                State.MARKER_PREFIX -> {
                    if (value != 0xFF) return ImageFailure.INVALID_IMAGE
                    index++
                    state = State.MARKER
                }
                State.MARKER -> {
                    index++
                    if (value != 0xFF) marker(value)?.let { return it }
                }
                State.LENGTH -> {
                    length[position++] = bytes[index++]
                    if (position == 2) {
                        segmentLength = ((length[0].toInt() and 255) shl 8) or (length[1].toInt() and 255)
                        if (segmentLength < 2) return ImageFailure.INVALID_IMAGE
                        remaining = segmentLength - 2
                        position = 0
                        state = State.SEGMENT
                        if (remaining == 0) endSegment()?.let { return it }
                    }
                }
                State.SEGMENT -> {
                    val take = minOf(remaining, end - index)
                    if (isFrame(marker)) {
                        val copy = minOf(take, frame.size - position).coerceAtLeast(0)
                        bytes.copyInto(frame, position, index, index + copy)
                        position += copy
                    }
                    index += take
                    remaining -= take
                    if (remaining == 0) endSegment()?.let { return it }
                }
                State.SCAN -> {
                    var next = index
                    while (next < end && bytes[next] != 0xFF.toByte()) next++
                    index = next
                    if (index < end) { index++; state = State.SCAN_PREFIX }
                }
                State.SCAN_PREFIX -> {
                    index++
                    when (value) {
                        0x00, in 0xD0..0xD7 -> state = State.SCAN
                        0xFF -> Unit
                        else -> marker(value)?.let { return it }
                    }
                }
                State.DONE -> return ImageFailure.INVALID_IMAGE // Appended bytes after EOI.
            }
        }
        return null
    }

    private fun isFrame(value: Int) = value in 0xC0..0xCF && value != 0xC4 && value != 0xC8 && value != 0xCC

    private fun marker(value: Int): ImageFailure? {
        marker = value
        position = 0
        return when (value) {
            0x00, 0xD8 -> ImageFailure.INVALID_IMAGE
            0xD9 -> if (sawFrame && sawScan) { state = State.DONE; null } else ImageFailure.INVALID_IMAGE
            0x01, in 0xD0..0xD7 -> { state = State.MARKER_PREFIX; null }
            else -> {
                // Only baseline, extended sequential and progressive Huffman frames are accepted.
                if (isFrame(value) && value !in 0xC0..0xC2) return ImageFailure.INVALID_IMAGE
                state = State.LENGTH
                null
            }
        }
    }

    private fun endSegment(): ImageFailure? {
        if (isFrame(marker)) {
            if (sawFrame || segmentLength < 8 || position < frame.size) return ImageFailure.INVALID_IMAGE
            height = (((frame[1].toInt() and 255) shl 8) or (frame[2].toInt() and 255)).toLong()
            width = (((frame[3].toInt() and 255) shl 8) or (frame[4].toInt() and 255)).toLong()
            dimensionFailure(width, height)?.let { return it }
            sawFrame = true
        }
        position = 0
        state = if (marker == 0xDA) {
            if (!sawFrame) return ImageFailure.INVALID_IMAGE
            sawScan = true
            State.SCAN
        } else State.MARKER_PREFIX
        return null
    }

    override fun finish(): ImageFailure? = if (state == State.DONE) null else ImageFailure.INVALID_IMAGE
}

/** ISO BMFF framing; only ftyp and meta payloads are retained, never encoded pixel data. */
private class AvifParser : FormatParser {
    private val header = ByteArray(16)
    private var headerSize = 0
    private var remaining = 0L
    private var payload: ByteArray? = null
    private var position = 0
    private var type = ""
    private var sawFtyp = false
    private var sawMeta = false
    private var toEof = false
    override var width = 0L; private set
    override var height = 0L; private set

    override fun feed(bytes: ByteArray, offset: Int, count: Int): ImageFailure? {
        var index = offset
        val end = offset + count
        while (index < end) {
            if (toEof) return null
            if (remaining > 0) {
                val take = minOf(remaining, (end - index).toLong()).toInt()
                payload?.let { bytes.copyInto(it, position, index, index + take); position += take }
                remaining -= take; index += take
                if (remaining == 0L) endBox()?.let { return it }
            } else {
                header[headerSize++] = bytes[index++]
                if (headerSize == 8 && u32(header, 0) != 1L || headerSize == 16)
                    startBox()?.let { return it }
            }
        }
        return null
    }

    private fun startBox(): ImageFailure? {
        type = String(header, 4, 4, Charsets.US_ASCII)
        val size = if (headerSize == 16) boxSize64(header, 8) else u32(header, 0)
        if (!sawFtyp && type != "ftyp") return ImageFailure.INVALID_IMAGE
        if (size == 0L) {
            if (type != "mdat" || !sawMeta) return ImageFailure.INVALID_IMAGE
            toEof = true
            headerSize = 0
            return null
        }
        if (size < headerSize) return ImageFailure.INVALID_IMAGE
        if (type == "ftyp" && (sawFtyp || size - headerSize < 8)) return ImageFailure.INVALID_IMAGE
        if (type == "meta" && sawMeta) return ImageFailure.INVALID_IMAGE
        val cap = when (type) { "ftyp" -> 4096; "meta" -> 65536; else -> null }
        if (cap != null && size > cap) return ImageFailure.BUDGET
        remaining = size - headerSize
        payload = if (cap != null) ByteArray(remaining.toInt()) else null
        position = 0
        headerSize = 0
        return if (remaining == 0L) endBox() else null
    }

    private fun endBox(): ImageFailure? {
        val body = payload
        payload = null
        when (type) {
            "ftyp" -> {
                val brands = checkNotNull(body)
                if (brands.size < 8 || brands.size % 4 != 0) return ImageFailure.INVALID_IMAGE
                val major = String(brands, 0, 4, Charsets.US_ASCII)
                if (major == "avis") return ImageFailure.ANIMATED
                if (major != "avif" && (8 until brands.size step 4).none {
                    String(brands, it, 4, Charsets.US_ASCII) == "avif"
                }) return ImageFailure.INVALID_IMAGE
                sawFtyp = true
            }
            "meta" -> {
                try { readMeta(checkNotNull(body)) }
                catch (_: InvalidBox) { return ImageFailure.INVALID_IMAGE }
                dimensionFailure(width, height)?.let { return it }
                sawMeta = true
            }
        }
        return null
    }

    private class InvalidBox : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }
    private fun requireBox(condition: Boolean) { if (!condition) throw InvalidBox() }
    private data class Box(val type: String, val data: Int, val end: Int)

    private fun boxes(bytes: ByteArray, start: Int, end: Int): List<Box> {
        val result = mutableListOf<Box>()
        var at = start
        while (at < end) {
            requireBox(end - at >= 8)
            val shortSize = u32(bytes, at)
            val header = if (shortSize == 1L) 16 else 8
            requireBox(end - at >= header)
            val size = if (header == 16) boxSize64(bytes, at + 8) else shortSize
            requireBox(size >= header && size <= end - at)
            result += Box(String(bytes, at + 4, 4, Charsets.US_ASCII), at + header, at + size.toInt())
            at += size.toInt()
        }
        return result
    }

    private fun readMeta(bytes: ByteArray) {
        requireBox(bytes.size >= 4 && u32(bytes, 0) == 0L)
        val children = boxes(bytes, 4, bytes.size)
        val primaryBoxes = children.filter { it.type == "pitm" }
        requireBox(primaryBoxes.size <= 1)
        val primary = primaryBoxes.singleOrNull()?.let {
            val reader = Reader(bytes, it)
            val version = reader.fullBox()
            val id = when (version) { 0 -> reader.u16().toLong(); 1 -> reader.u32(); else -> throw InvalidBox() }
            reader.done()
            id
        }
        val iprp = children.filter { it.type == "iprp" }.singleOrNull() ?: throw InvalidBox()
        val groups = boxes(bytes, iprp.data, iprp.end)
        val ipco = groups.filter { it.type == "ipco" }.singleOrNull() ?: throw InvalidBox()
        val properties = boxes(bytes, ipco.data, ipco.end)
        var associated: List<Box>? = null
        for (ipma in groups.filter { it.type == "ipma" }) {
            val reader = Reader(bytes, ipma)
            val version = reader.byte()
            requireBox(version in 0..1)
            val flags = (reader.byte() shl 16) or (reader.byte() shl 8) or reader.byte()
            requireBox(flags and 1 == flags)
            val entries = reader.u32()
            requireBox(entries <= (ipma.end - reader.at) / 3)
            repeat(entries.toInt()) {
                val id = if (version == 0) reader.u16().toLong() else reader.u32()
                val count = reader.byte()
                val selected = mutableListOf<Box>()
                repeat(count) {
                    val index = if (flags == 1) reader.u16() and 0x7FFF else reader.byte() and 0x7F
                    requireBox(index <= properties.size)
                    if (index != 0) selected += properties[index - 1]
                }
                if (id == primary) {
                    requireBox(associated == null)
                    associated = selected
                }
            }
            reader.done()
        }
        // A single unambiguous ispe is safe without associations; transforms still need an owner.
        val chosen = associated ?: properties.also {
            requireBox(groups.none { group -> group.type == "ipma" } &&
                it.count { p -> p.type == "ispe" } == 1 && it.none { p -> p.type == "irot" })
        }
        val dimensions = chosen.filter { it.type == "ispe" }.singleOrNull() ?: throw InvalidBox()
        val reader = Reader(bytes, dimensions)
        requireBox(reader.fullBox() == 0)
        width = reader.u32(); height = reader.u32()
        reader.done()
        val rotations = chosen.filter { it.type == "irot" }
        requireBox(rotations.size <= 1)
        rotations.singleOrNull()?.let {
            val rotation = Reader(bytes, it)
            val angle = rotation.byte()
            requireBox(angle in 0..3)
            rotation.done()
            if (angle and 1 != 0) { val originalWidth = width; width = height; height = originalWidth }
        }
    }

    private inner class Reader(private val bytes: ByteArray, private val box: Box) {
        var at = box.data; private set
        fun byte(): Int { requireBox(at < box.end); return bytes[at++].toInt() and 255 }
        fun u16(): Int = (byte() shl 8) or byte()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
        fun fullBox(): Int {
            val version = byte()
            requireBox(byte() == 0 && byte() == 0 && byte() == 0)
            return version
        }
        fun done() = requireBox(at == box.end)
    }

    override fun finish(): ImageFailure? =
        if (!sawFtyp || !sawMeta || headerSize != 0 || remaining != 0L) ImageFailure.INVALID_IMAGE
        else dimensionFailure(width, height)
}

private fun boxSize64(bytes: ByteArray, at: Int): Long {
    val high = u32(bytes, at)
    return if (high > Int.MAX_VALUE) -1 else (high shl 32) or u32(bytes, at + 4)
}

private class WebpParser : FormatParser {
    private enum class State { RIFF, HEADER, DATA, PAD, DONE }
    private val riff = ByteArray(12)
    private val header = ByteArray(8)
    private val payload = ByteArray(10)
    private var state = State.RIFF
    private var position = 0
    private var consumed = 0L
    private var riffEnd = 0L
    private var fourcc = ""
    private var chunkSize = 0L
    private var remaining = 0L
    private var chunks = 0
    private var canvasWidth = 0L
    private var canvasHeight = 0L
    private var sawImage = false
    override var width = 0L; private set
    override var height = 0L; private set

    override fun feed(bytes: ByteArray, offset: Int, count: Int): ImageFailure? {
        var index = offset
        val end = offset + count
        while (index < end) {
            if (state == State.DONE || (state != State.RIFF && consumed >= riffEnd)) return ImageFailure.INVALID_IMAGE
            when (state) {
                State.RIFF -> {
                    riff[position++] = bytes[index++]; consumed++
                    if (position == riff.size) {
                        if (String(riff, 0, 4, Charsets.ISO_8859_1) != "RIFF" ||
                            String(riff, 8, 4, Charsets.ISO_8859_1) != "WEBP") return ImageFailure.INVALID_IMAGE
                        val size = le32(riff, 4)
                        if (size < 4 + 8 || size % 2 != 0L) return ImageFailure.INVALID_IMAGE
                        riffEnd = size + 8
                        position = 0
                        state = State.HEADER
                    }
                }
                State.HEADER -> {
                    header[position++] = bytes[index++]; consumed++
                    if (position == header.size) startChunk()?.let { return it }
                }
                State.DATA -> {
                    val take = minOf(remaining, (end - index).toLong()).toInt()
                    val already = (chunkSize - remaining).toInt().coerceAtMost(payload.size)
                    val copy = minOf(take, payload.size - already).coerceAtLeast(0)
                    bytes.copyInto(payload, already, index, index + copy)
                    index += take; consumed += take; remaining -= take
                    if (remaining == 0L) endChunk()?.let { return it }
                }
                State.PAD -> {
                    index++; consumed++
                    afterChunk()
                }
                State.DONE -> return ImageFailure.INVALID_IMAGE
            }
        }
        return null
    }

    private fun startChunk(): ImageFailure? {
        fourcc = String(header, 0, 4, Charsets.ISO_8859_1)
        chunkSize = le32(header, 4)
        position = 0
        if (consumed + chunkSize + (chunkSize and 1) > riffEnd) return ImageFailure.INVALID_IMAGE
        if (fourcc == "ANIM" || fourcc == "ANMF") return ImageFailure.ANIMATED
        val image = fourcc == "VP8 " || fourcc == "VP8L"
        if (chunks == 0 && !image && fourcc != "VP8X") return ImageFailure.INVALID_IMAGE
        if (chunks > 0 && fourcc == "VP8X") return ImageFailure.INVALID_IMAGE
        if (image && sawImage) return ImageFailure.INVALID_IMAGE
        if (++chunks > 64) return ImageFailure.INVALID_IMAGE
        remaining = chunkSize
        state = State.DATA
        return if (chunkSize == 0L) endChunk() else null
    }

    private fun endChunk(): ImageFailure? {
        when (fourcc) {
            "VP8X" -> {
                if (chunkSize < 10) return ImageFailure.INVALID_IMAGE
                if (payload[0].toInt() and 0x02 != 0) return ImageFailure.ANIMATED
                canvasWidth = le24(payload, 4) + 1
                canvasHeight = le24(payload, 7) + 1
                dimensionFailure(canvasWidth, canvasHeight)?.let { return it }
            }
            "VP8 " -> {
                if (chunkSize < 10) return ImageFailure.INVALID_IMAGE
                val tag = le24(payload, 0)
                if (tag and 1L != 0L || (payload[3].toInt() and 255) != 0x9D || (payload[4].toInt() and 255) != 0x01 ||
                    (payload[5].toInt() and 255) != 0x2A) return ImageFailure.INVALID_IMAGE
                imageSize(le16(payload, 6) and 0x3FFF, le16(payload, 8) and 0x3FFF)?.let { return it }
            }
            "VP8L" -> {
                if (chunkSize < 5 || (payload[0].toInt() and 255) != 0x2F) return ImageFailure.INVALID_IMAGE
                val bits = le32(payload, 1)
                if (bits ushr 29 != 0L) return ImageFailure.INVALID_IMAGE
                imageSize((bits and 0x3FFF) + 1, ((bits ushr 14) and 0x3FFF) + 1)?.let { return it }
            }
        }
        if (chunkSize and 1 == 1L) { state = State.PAD; return null }
        afterChunk()
        return null
    }

    private fun imageSize(imageWidth: Long, imageHeight: Long): ImageFailure? {
        dimensionFailure(imageWidth, imageHeight)?.let { return it }
        if (canvasWidth > 0 && (canvasWidth != imageWidth || canvasHeight != imageHeight)) return ImageFailure.INVALID_IMAGE
        width = imageWidth
        height = imageHeight
        sawImage = true
        return null
    }

    private fun afterChunk() {
        position = 0
        state = if (consumed == riffEnd) State.DONE else State.HEADER
    }

    override fun finish(): ImageFailure? =
        if (state == State.DONE && consumed == riffEnd && sawImage) null else ImageFailure.INVALID_IMAGE
}
