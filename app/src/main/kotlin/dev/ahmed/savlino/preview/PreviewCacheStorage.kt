package dev.ahmed.savlino.preview

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal

/** All payload operations are directory-handle relative. Only trusted framework-root bootstrap uses mkdir(path). */
internal class PreviewCacheStorage(
    private val parentPath: Path, private val owner: UserPrincipal, private val fs: PreviewCacheFileSystem,
    private val maxOwnedBytes: Long,
) : AutoCloseable {
    private var parent: PreviewCacheDirectory? = null
    private var root: PreviewCacheDirectory? = null
    private var parentChannel: FileChannel? = null
    private var rootChannel: FileChannel? = null
    private lateinit var parentKey: Any
    private lateinit var rootKey: Any
    private val directoryMode = PosixFilePermissions.fromString("rwx------")

    init {
        try {
            val before = pathAttributes()
            checkDirectory(before)
            parentKey = before.fileKey() ?: throw IOException("Missing cache parent identity")
            parent = fs.openDirectory(parentPath)
            verifyParent()
            try { fs.createDirectory(parentPath.resolve(ROOT)) } catch (_: FileAlreadyExistsException) { /* verify existing, no retry/delete */ }
            verifyParent()
            root = parent!!.openDirectory(Paths.get(ROOT))
            val attributes = root!!.attributes()
            checkDirectory(attributes, exactMode = true)
            rootKey = attributes.fileKey() ?: throw IOException("Missing cache identity")
            verify()
            parentChannel = directoryChannel(parent!!)
            rootChannel = directoryChannel(root!!)
            fs.forceDirectory(parentChannel!!)
            fs.forceDirectory(rootChannel!!)
            verify()
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    private fun pathAttributes(): PosixFileAttributes = Files.readAttributes(parentPath, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
    private fun checkDirectory(attributes: PosixFileAttributes, exactMode: Boolean = false) {
        if (!attributes.isDirectory || attributes.isSymbolicLink || attributes.owner() != owner || attributes.fileKey() == null ||
            (exactMode && attributes.permissions() != directoryMode)) throw IOException("Invalid cache directory")
    }
    private fun verifyParent() {
        val current = pathAttributes(); checkDirectory(current)
        val anchored = parent!!.attributes(); checkDirectory(anchored)
        if (current.fileKey() != parentKey || anchored.fileKey() != parentKey) throw IOException("Cache parent changed")
    }
    fun verify() {
        verifyParent()
        val current = parent!!.childAttributes(Paths.get(ROOT)); checkDirectory(current, true)
        val anchored = root!!.attributes(); checkDirectory(anchored, true)
        if (current.fileKey() != rootKey || anchored.fileKey() != rootKey) throw IOException("Cache root changed")
    }
    private fun directoryChannel(directory: PreviewCacheDirectory): FileChannel {
        val channel = fs.openFile(directory, Paths.get("."), setOf(READ, NOFOLLOW_LINKS))
        return channel as? FileChannel ?: run { channel.close(); throw IOException("Cache force unavailable") }
    }

    fun names(): List<String> {
        verify()
        val names = root!!.entries()
        if (names.size > 128) throw IOException("Too many cache children")
        return names.map {
            if (it.isAbsolute || it.nameCount != 1 || it.toString() in listOf("", ".", "..")) throw IOException("Invalid cache child")
            it.toString()
        }.also { verify() }
    }
    fun attributes(name: String): PosixFileAttributes? {
        verify()
        return try { root!!.childAttributes(child(name)).also { verify() } } catch (_: NoSuchFileException) { null }
    }
    fun usedBytes(): Long {
        var bytes = 0L
        for (name in names()) {
            val attributes = attributes(name) ?: continue
            if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() < 0) throw IOException("Unaccountable cache child")
            bytes = Math.addExact(bytes, attributes.size())
        }
        return bytes
    }
    fun remove(name: String) {
        val attributes = attributes(name) ?: return
        if (attributes.isDirectory && !attributes.isSymbolicLink) root!!.deleteDirectory(child(name))
        else root!!.deleteFile(child(name))
        verify()
    }

    fun control(): PreviewControl {
        if (attributes(CONTROL_TEMP) != null) throw IOException("Unfinished cache control barrier")
        return controlIgnoringPending()
    }
    /** Used only by the serialized precommit check while our own control temp exists. */
    fun controlIgnoringPending(): PreviewControl {
        return PreviewCacheRecords.decodeControl(read(CONTROL, PreviewCacheRecords.MAX_CONTROL_BYTES.toLong()))
    }
    fun entry(name: String, limits: PreviewCacheLimits): PreviewEntryPacket =
        PreviewCacheRecords.decodeEntry(read(name, limits.artworkEncodedBytes.toLong() + limits.metadataEntryBytes,
            headerLimits = limits), limits)

    private fun read(name: String, maxBytes: Long, headerLimits: PreviewCacheLimits? = null): ByteArray {
        val before = attributes(name) ?: throw NoSuchFileException("Missing cache child")
        if (!before.isRegularFile || before.isSymbolicLink || before.owner() != owner || before.size() !in 1..maxBytes) {
            throw IOException("Invalid cache file")
        }
        val result = fs.openFile(root!!, child(name), setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            if (channel.size() != before.size()) throw IOException("Cache size changed")
            if (headerLimits == null) {
                ByteArray(before.size().toInt()).also { readFully(channel, it) }
            } else {
                if (before.size() < PreviewCacheRecords.HEADER_BYTES) throw IOException("Truncated cache header")
                val header = ByteArray(PreviewCacheRecords.HEADER_BYTES)
                readFully(channel, header)
                PreviewCacheRecords.validateEntryHeader(header, before.size(), headerLimits)
                val remainder = ByteArray(before.size().toInt() - header.size)
                readFully(channel, remainder)
                header + remainder
            }.also {
                if (channel.read(ByteBuffer.allocate(1)) != -1) throw IOException("Cache grew during read")
            }
        }
        val after = attributes(name) ?: throw IOException("Cache disappeared")
        if (!after.isRegularFile || after.isSymbolicLink || after.fileKey() != before.fileKey() || after.size() != before.size()) {
            throw IOException("Cache changed during read")
        }
        verify()
        return result
    }

    fun writeControl(value: PreviewControl, valid: () -> Boolean = { true }): Boolean =
        atomicWrite(CONTROL_TEMP, CONTROL, PreviewCacheRecords.encodeControl(value), valid)

    fun writeEntry(key: String, bytes: ByteArray, valid: () -> Boolean): Boolean = atomicWrite(PAYLOAD_TEMP, key, bytes, valid)

    private fun atomicWrite(temp: String, target: String, bytes: ByteArray, valid: () -> Boolean): Boolean {
        verify()
        val channel = fs.openFile(root!!, child(temp), setOf(WRITE, CREATE_NEW, NOFOLLOW_LINKS))
        channel.use {
            enforceBytes()
            val file = it as? FileChannel ?: throw IOException("Cache force unavailable")
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) if (file.write(buffer) <= 0) throw IOException("Cache write made no progress")
            fs.forceFile(file)
            enforceBytes()
        }
        verify()
        if (!valid()) {
            if (temp == PAYLOAD_TEMP) remove(temp)
            return false
        }
        fs.move(root!!, child(temp), child(target))
        enforceBytes()
        verify()
        fs.forceDirectory(rootChannel!!)
        verify()
        return true
    }

    private fun child(name: String): Path {
        val path = Paths.get(name)
        if (path.isAbsolute || path.nameCount != 1 || name in listOf("", ".", "..")) throw IOException("Invalid cache filename")
        return path
    }
    private fun enforceBytes() { if (usedBytes() > maxOwnedBytes) throw IOException("Cache byte limit exceeded") }
    fun syncDirectory() { verify(); fs.forceDirectory(rootChannel!!); verify() }
    override fun close() {
        listOf<AutoCloseable?>(rootChannel, parentChannel, root, parent).forEach { try { it?.close() } catch (_: Exception) { /* no private logs */ } }
        rootChannel = null; parentChannel = null; root = null; parent = null
    }
    companion object {
        const val ROOT = "nezzel-preview"
        val CONTROL = PreviewCacheRecords.fixedName("nezzel-preview-control-v1")
        val CONTROL_TEMP = PreviewCacheRecords.fixedName("nezzel-preview-control-pending-v1")
        val PAYLOAD_TEMP = PreviewCacheRecords.fixedName("nezzel-preview-payload-pending-v1")
        val RESERVED = setOf(CONTROL, CONTROL_TEMP, PAYLOAD_TEMP)

        private fun readFully(channel: SeekableByteChannel, bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) if (channel.read(buffer) <= 0) throw IOException("Truncated cache file")
        }
    }
}
