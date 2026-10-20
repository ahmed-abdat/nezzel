package dev.ahmed.savlino.preview

import dev.ahmed.savlino.domain.PreviewMetadata
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal data class PreviewAccess(val order: Long, val time: Long)
internal data class PreviewControl(val generation: Long, val highWater: Long, val nextOrder: Long, val access: Map<String, PreviewAccess>)
internal data class PreviewEntryInfo(
    val title: String?, val uploader: String?, val duration: Long?, val generation: Long, val written: Long,
    val width: Int, val height: Int, val artworkBytes: Int, val metadataBytes: Int, val diskBytes: Int, val digest: String,
)
internal class PreviewEntryPacket(val info: PreviewEntryInfo, val artwork: ByteArray)

/** Versioned scalar allowlist; no reflection, Java serialization, URL, engine object, or original image encoding. */
internal object PreviewCacheRecords {
    const val HEADER_BYTES = 40
    const val HASH_BYTES = 32
    const val MAX_CONTROL_BYTES = 68 + 32 * 48
    const val TTL = 21_600_000L
    private const val VERSION = 1
    private const val ENTRY_MAGIC = 0x4e505631
    private const val CONTROL_MAGIC = 0x4e504331
    val hexKey = Regex("[0-9a-f]{64}")
    private val privateText = Regex(
        "[a-z][a-z0-9+.-]*://|\\bwww\\.|(?<!:)//[^\\s/]+|[^\\s:@]+:[^\\s@]+@[^\\s]+|" +
            "\\b(?:cookie|authorization|password|token|signature)\\s*[:=]|\\bbearer\\s+\\S+",
        RegexOption.IGNORE_CASE,
    )

    fun text(value: String?, limit: Int): String? = PreviewMetadata.text(value, limit)?.takeUnless { privateText.containsMatchIn(it) }
    fun duration(value: Long?): Long? = value?.takeIf { it in 1..PreviewMetadata.MAX_DURATION_SECONDS }
    fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun fixedName(label: String): String = hex(hash(label.toByteArray(Charsets.UTF_8)))

    fun identity(source: String, recipe: String, runtime: String, generation: Long): String {
        // Feed each component separately so neither concatenation ambiguity nor a combined URL buffer is retained.
        val digest = MessageDigest.getInstance("SHA-256")
        for (component in listOf(source, recipe, runtime)) {
            val bytes = component.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        digest.update(ByteBuffer.allocate(8).putLong(generation).array())
        return hex(digest.digest())
    }

    fun encodeEntry(title: String?, uploader: String?, duration: Long?, generation: Long, written: Long,
        artwork: EncodedPreviewArtwork?, limits: PreviewCacheLimits): ByteArray? {
        val metadata = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.string(title); out.string(uploader); out.writeLong(duration ?: -1)
            }
        }.toByteArray()
        if (metadata.size + HEADER_BYTES + HASH_BYTES > limits.metadataEntryBytes) return null
        val image = artwork?.bytes ?: byteArrayOf()
        if (image.size > limits.artworkEncodedBytes) return null
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(ENTRY_MAGIC); out.writeInt(VERSION); out.writeLong(generation); out.writeLong(written)
            out.writeInt(metadata.size); out.writeInt(image.size)
            out.writeInt(artwork?.pixels?.width ?: 0); out.writeInt(artwork?.pixels?.height ?: 0)
            out.write(metadata); out.write(image)
        }
        val body = output.toByteArray()
        return body + hash(body)
    }

    /** Check header lengths before the storage layer allocates the variable portion. */
    fun validateEntryHeader(header: ByteArray, fileSize: Long, limits: PreviewCacheLimits) {
        val input = DataInputStream(ByteArrayInputStream(header))
        if (input.readInt() != ENTRY_MAGIC || input.readInt() != VERSION) bad()
        if (input.readLong() < 0 || input.readLong() <= 0) bad()
        val meta = input.readInt(); val art = input.readInt(); val width = input.readInt(); val height = input.readInt()
        if (meta < 16 || meta.toLong() + HEADER_BYTES + HASH_BYTES > limits.metadataEntryBytes || art < 0 || art > limits.artworkEncodedBytes) bad()
        if (fileSize != HEADER_BYTES.toLong() + meta + art + HASH_BYTES) bad()
        if (art == 0) {
            if (width != 0 || height != 0) bad()
        } else if (width !in 1..limits.artworkAxis || height !in 1..limits.artworkAxis) bad()
    }

    fun decodeEntry(bytes: ByteArray, limits: PreviewCacheLimits): PreviewEntryPacket {
        if (bytes.size < HEADER_BYTES + HASH_BYTES + 16) bad()
        validateEntryHeader(bytes.copyOfRange(0, HEADER_BYTES), bytes.size.toLong(), limits)
        val body = bytes.copyOfRange(0, bytes.size - HASH_BYTES)
        val checksum = bytes.copyOfRange(bytes.size - HASH_BYTES, bytes.size)
        if (!MessageDigest.isEqual(hash(body), checksum)) bad()
        val input = DataInputStream(ByteArrayInputStream(body))
        input.readInt(); input.readInt()
        val generation = input.readLong(); val written = input.readLong()
        val meta = input.readInt(); val art = input.readInt(); val width = input.readInt(); val height = input.readInt()
        val metadata = ByteArray(meta).also { input.readFully(it) }
        val fields = DataInputStream(ByteArrayInputStream(metadata))
        val title = fields.string(1200); val uploader = fields.string(480); val rawDuration = fields.readLong()
        if (fields.available() != 0 || title != text(title, 300) || uploader != text(uploader, 120)) bad()
        val duration = if (rawDuration == -1L) null else duration(rawDuration) ?: bad()
        return PreviewEntryPacket(PreviewEntryInfo(title, uploader, duration, generation, written, width, height, art,
            HEADER_BYTES + meta + HASH_BYTES, bytes.size, hex(checksum)), ByteArray(art).also { input.readFully(it) })
    }

    fun encodeControl(control: PreviewControl): ByteArray {
        if (control.generation < 0 || control.highWater <= 0 || control.nextOrder < 0 || control.access.size > 32) bad()
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(CONTROL_MAGIC); out.writeInt(VERSION); out.writeLong(control.generation)
            out.writeLong(control.highWater); out.writeLong(control.nextOrder); out.writeInt(control.access.size)
            for ((key, access) in control.access.toSortedMap()) {
                if (!hexKey.matches(key) || access.order !in 1..control.nextOrder || access.time !in 1..control.highWater) bad()
                out.write(ByteArray(32) { key.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
                out.writeLong(access.order); out.writeLong(access.time)
            }
        }
        val body = output.toByteArray()
        return body + hash(body)
    }

    fun decodeControl(bytes: ByteArray): PreviewControl {
        if (bytes.size !in 68..MAX_CONTROL_BYTES) bad()
        val body = bytes.copyOfRange(0, bytes.size - HASH_BYTES)
        if (!MessageDigest.isEqual(hash(body), bytes.copyOfRange(body.size, bytes.size))) bad()
        val input = DataInputStream(ByteArrayInputStream(body))
        if (input.readInt() != CONTROL_MAGIC || input.readInt() != VERSION) bad()
        val generation = input.readLong(); val highWater = input.readLong(); val nextOrder = input.readLong(); val count = input.readInt()
        if (generation < 0 || highWater <= 0 || nextOrder < 0 || count !in 0..32 || bytes.size != 68 + count * 48) bad()
        val access = linkedMapOf<String, PreviewAccess>()
        repeat(count) {
            val key = hex(ByteArray(32).also { input.readFully(it) })
            val order = input.readLong(); val time = input.readLong()
            if (order !in 1..nextOrder || time !in 1..highWater || access.put(key, PreviewAccess(order, time)) != null) bad()
        }
        if (access.values.map { it.order }.toSet().size != access.size) bad()
        return PreviewControl(generation, highWater, nextOrder, access)
    }

    private fun DataOutputStream.string(value: String?) {
        val bytes = value?.toByteArray(Charsets.UTF_8)
        writeInt(bytes?.size ?: -1)
        bytes?.let { write(it) }
    }
    private fun DataInputStream.string(maxBytes: Int): String? {
        val size = readInt()
        if (size == -1) return null
        if (size !in 1..maxBytes || size > available()) bad()
        val bytes = ByteArray(size).also { readFully(it) }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    private fun bad(): Nothing = throw IOException("Invalid display cache record")
}
