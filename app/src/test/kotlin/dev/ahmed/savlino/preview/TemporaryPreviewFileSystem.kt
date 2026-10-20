package dev.ahmed.savlino.preview

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions

/**
 * TEST ONLY: actual temporary file I/O for policy/codec effects on Mac hosts without SDS.
 * Path-based handles do NOT prove descriptor anchoring, Android capability, or physical crash durability.
 * This class never implements SecureDirectoryStream and cannot be selected by shared(context).
 */
internal open class TemporaryPreviewFileSystem : PreviewCacheFileSystem() {
    override fun openDirectory(path: Path): PreviewCacheDirectory = PortableTemporaryDirectory(path)

    override fun createDirectory(path: Path) {
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    }

    override fun openFile(directory: PreviewCacheDirectory, name: Path, options: Set<OpenOption>): SeekableByteChannel =
        directory.openFile(name, options)

    override fun move(directory: PreviewCacheDirectory, source: Path, target: Path) {
        directory.move(source, target)
    }

    override fun forceFile(channel: FileChannel) { channel.force(true) }
    override fun forceDirectory(channel: FileChannel) { channel.force(true) }
}

/** Observed nofollow/fileKey guards cover sequential changes only; there is no openat guarantee here. */
private class PortableTemporaryDirectory(private val path: Path) : PreviewCacheDirectory {
    private val original = readDirectoryAttributes()
    private var closed = false

    private fun readDirectoryAttributes(): PosixFileAttributes =
        Files.readAttributes(path, PosixFileAttributes::class.java, NOFOLLOW_LINKS).also {
            if (!it.isDirectory || it.isSymbolicLink || it.fileKey() == null) throw IOException("Not a temporary directory")
        }

    private fun verify(): PosixFileAttributes {
        if (closed) throw IOException("Temporary directory closed")
        return readDirectoryAttributes().also {
            if (it.fileKey() != original.fileKey() || it.owner() != original.owner()) throw IOException("Temporary directory changed")
        }
    }

    private fun child(name: Path, allowDot: Boolean = false): Path {
        verify()
        if (name.isAbsolute || name.nameCount != 1 || name.toString().isEmpty() || name.toString() == ".." ||
            (!allowDot && name.toString() == ".")) throw IOException("Not a direct temporary child")
        return path.resolve(name)
    }

    override fun attributes(): PosixFileAttributes = verify()

    override fun childAttributes(name: Path): PosixFileAttributes {
        val result = Files.readAttributes(child(name), PosixFileAttributes::class.java, NOFOLLOW_LINKS)
        verify()
        return result
    }

    override fun entries(): List<Path> {
        verify()
        val names = Files.newDirectoryStream(path).use { stream ->
            val entries = ArrayList<Path>()
            for (entry in stream) {
                if (entries.size >= 128) throw IOException("Too many temporary children")
                entries.add(entry.fileName)
            }
            entries
        }
        verify()
        return names
    }

    override fun openDirectory(name: Path): PreviewCacheDirectory {
        val directory = PortableTemporaryDirectory(child(name, allowDot = true))
        try {
            verify()
            return directory
        } catch (failure: Exception) {
            directory.close()
            throw failure
        }
    }

    override fun openFile(name: Path, options: Set<OpenOption>): SeekableByteChannel {
        if (name.toString() == "." && options != setOf<OpenOption>(READ, NOFOLLOW_LINKS)) {
            throw IOException("Directory channel must be read-only")
        }
        val channel = Files.newByteChannel(child(name, allowDot = true), options + NOFOLLOW_LINKS)
        try {
            verify()
            return channel
        } catch (failure: Exception) {
            channel.close()
            throw failure
        }
    }

    override fun deleteFile(name: Path) {
        val target = child(name)
        if (Files.isDirectory(target, NOFOLLOW_LINKS)) throw IOException("Not a flat file")
        Files.delete(target)
        verify()
    }

    override fun deleteDirectory(name: Path) {
        val target = child(name)
        if (!Files.isDirectory(target, NOFOLLOW_LINKS)) throw IOException("Not a direct directory")
        Files.delete(target) // fails for nonempty dirs; no recursion.
        verify()
    }

    override fun move(source: Path, target: Path) {
        Files.move(child(source), child(target), ATOMIC_MOVE, REPLACE_EXISTING)
        verify()
    }

    override fun close() { closed = true }
}
