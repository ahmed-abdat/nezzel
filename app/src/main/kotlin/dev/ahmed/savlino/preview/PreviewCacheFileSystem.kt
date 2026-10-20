package dev.ahmed.savlino.preview

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions

/** Opaque operations, not a claim that a backend is descriptor anchored. Production wraps actual SDS only. */
internal interface PreviewCacheDirectory : AutoCloseable {
    fun attributes(): PosixFileAttributes
    fun childAttributes(name: Path): PosixFileAttributes
    fun entries(): List<Path>
    fun openDirectory(name: Path): PreviewCacheDirectory
    fun openFile(name: Path, options: Set<OpenOption>): SeekableByteChannel
    fun deleteFile(name: Path)
    fun deleteDirectory(name: Path)
    fun move(source: Path, target: Path)
}

/** Production has no portable/path-based fallback. Injection is restricted to the isolated temporary factory. */
internal open class PreviewCacheFileSystem {
    open fun openRawDirectory(path: Path): DirectoryStream<Path> = Files.newDirectoryStream(path)
    open fun openDirectory(path: Path): PreviewCacheDirectory {
        val stream = openRawDirectory(path)
        if (stream !is SecureDirectoryStream<Path>) {
            stream.close()
            throw IOException("Secure cache storage unavailable")
        }
        return SecurePreviewDirectory(stream)
    }

    open fun createDirectory(path: Path) {
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    }
    open fun openFile(
        directory: PreviewCacheDirectory,
        name: Path,
        options: Set<OpenOption>,
    ): SeekableByteChannel = directory.openFile(name, options)

    open fun move(directory: PreviewCacheDirectory, source: Path, target: Path) { directory.move(source, target) }

    open fun forceFile(channel: FileChannel) { channel.force(true) }
    open fun forceDirectory(channel: FileChannel) { channel.force(true) }
}

private class SecurePreviewDirectory(private val stream: SecureDirectoryStream<Path>) : PreviewCacheDirectory {
    private fun direct(name: Path, dot: Boolean = false): Path {
        if (name.isAbsolute || name.nameCount != 1 || name.toString().isEmpty() || name.toString() == ".." ||
            (!dot && name.toString() == ".")) throw IOException("Invalid cache child")
        return name
    }

    override fun attributes(): PosixFileAttributes =
        (stream.getFileAttributeView(PosixFileAttributeView::class.java) ?: throw IOException("Missing cache attributes")).readAttributes()

    override fun childAttributes(name: Path): PosixFileAttributes =
        (stream.getFileAttributeView(direct(name), PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
            ?: throw IOException("Missing cache attributes")).readAttributes()

    override fun entries(): List<Path> = stream.newDirectoryStream(Paths.get("."), NOFOLLOW_LINKS).use { children ->
        val names = ArrayList<Path>()
        for (child in children) {
            if (names.size >= 128) throw IOException("Too many cache children")
            names.add(direct(child.fileName))
        }
        names
    }

    override fun openDirectory(name: Path): PreviewCacheDirectory =
        SecurePreviewDirectory(stream.newDirectoryStream(direct(name, dot = true), NOFOLLOW_LINKS))

    override fun openFile(name: Path, options: Set<OpenOption>): SeekableByteChannel {
        direct(name, dot = true)
        if (name.toString() == "." && options != setOf<OpenOption>(READ, NOFOLLOW_LINKS)) {
            throw IOException("Invalid directory channel")
        }
        val mode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        return if (CREATE_NEW in options) stream.newByteChannel(name, options + NOFOLLOW_LINKS, mode)
        else stream.newByteChannel(name, options + NOFOLLOW_LINKS)
    }

    override fun deleteFile(name: Path) { stream.deleteFile(direct(name)) }
    override fun deleteDirectory(name: Path) { stream.deleteDirectory(direct(name)) }
    override fun move(source: Path, target: Path) { stream.move(direct(source), stream, direct(target)) }
    override fun close() { stream.close() }
}
