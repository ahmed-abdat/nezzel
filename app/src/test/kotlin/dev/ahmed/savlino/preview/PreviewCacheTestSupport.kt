package dev.ahmed.savlino.preview

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.Random
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** All cache payloads and adversarial files are real local files; no provider/network fixtures. */
internal abstract class PreviewCacheTestSupport {
    @get:Rule val temporary = TemporaryFolder()
    protected var now = 1_800_000_000_000L
    protected val root: File get() = File(temporary.root, "nezzel-preview")
    private val caches = mutableListOf<PreviewCache>()
    private val bitmaps = mutableListOf<Bitmap>()

    protected fun open(
        limits: PreviewCacheLimits = PreviewCacheLimits(),
        fileSystem: PreviewCacheFileSystem = TemporaryPreviewFileSystem(),
        codec: PreviewArtworkCodec = PreviewArtworkCodec(),
        parent: File = temporary.root,
    ): PreviewCache = PreviewCache.openIsolated(
        parent, Files.getOwner(parent.toPath(), NOFOLLOW_LINKS), { now }, limits, fileSystem, codec,
    ).also { caches += it }

    protected fun track(cache: PreviewCache): PreviewCache = cache.also { caches += it }
    protected fun remember(bitmap: Bitmap): Bitmap = bitmap.also { bitmaps += it }

    protected fun image(width: Int = 32, height: Int = 24, noisy: Boolean = false): Bitmap {
        val bitmap = remember(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))
        if (noisy) {
            val random = Random(317)
            val pixels = IntArray(width * height) { random.nextInt() or (0xff shl 24) }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        } else bitmap.eraseColor(Color.RED)
        return bitmap
    }

    protected fun key(cache: PreviewCache, id: String = "one", token: PreviewCacheToken = cache.token()): PreviewCacheKey =
        cache.key(token, "https://example.org/video/$id", "video-v1", "engine-2025")

    protected fun store(
        cache: PreviewCache,
        id: String = "one",
        artwork: Bitmap? = image(),
        title: String = "Display $id",
    ): PreviewCacheKey {
        val token = cache.token()
        val key = key(cache, id, token)
        assertTrue("cache must persist display pixels/metadata, not remain a no-op", cache.put(
            key, token, title, "قناة", 42, artwork,
        ))
        return key
    }

    protected fun hit(cache: PreviewCache, key: PreviewCacheKey, token: PreviewCacheToken = cache.token()): CachedPreview {
        val result = cache.get(key, token)
        assertNotNull("valid local display must be readable", result)
        result!!.artwork?.let(::remember)
        return result
    }

    protected fun entry(key: PreviewCacheKey): File = File(root, key.hex)

    protected fun ownedFiles(): List<File> {
        if (!root.isDirectory) return emptyList()
        return Files.newDirectoryStream(root.toPath()).use { entries ->
            entries.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.map { it.toFile() }
        }
    }

    protected fun ownedBytes(): Long = ownedFiles().sumOf { it.length() }

    protected fun assertDiskBounds(cache: PreviewCache, maxBytes: Long = 16L * 1024 * 1024, maxEntries: Int = 32) {
        val usage = cache.usage()
        assertTrue("cache disabled unexpectedly", usage.available)
        assertEquals("usage must include actual control and staged bytes", ownedBytes(), usage.diskBytes)
        assertTrue("disk budget exceeded", ownedBytes() <= maxBytes)
        assertTrue("entry budget exceeded", usage.entries <= maxEntries)
        assertTrue("metadata budget exceeded", usage.metadataBytes <= 64L * 1024)
    }

    @After fun release() {
        caches.asReversed().forEach { it.close() }
        bitmaps.forEach { if (!it.isRecycled) it.recycle() }
    }
}

/** Counting wraps the actual decoder; assertions also require actual image pixels/hits. */
internal class CountingPreviewCodec : PreviewArtworkCodec() {
    var decodes = 0
    override fun decode(bytes: ByteArray, maxAxis: Int, maxAllocationBytes: Long): Bitmap? {
        decodes++
        return super.decode(bytes, maxAxis, maxAllocationBytes)
    }
}

/** Real file operations with finite one-shot failures, never a fake disk or fabricated cache usage. */
internal class RecordingPreviewFileSystem(private val root: File) : TemporaryPreviewFileSystem() {
    val observedBytes = mutableListOf<Long>()
    var rejectNewFile = false
    var failNextFileForce = false
    var failNextDirectoryForce = false
    var failNextMove = false
    var afterNextFileForce: (() -> Unit)? = null

    private fun sample() {
        val files = if (root.isDirectory) Files.newDirectoryStream(root.toPath()).use { stream ->
            stream.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
        } else emptyList()
        observedBytes += files.sumOf { Files.size(it) }
    }

    override fun openFile(directory: PreviewCacheDirectory, name: Path, options: Set<OpenOption>): SeekableByteChannel {
        if (rejectNewFile && CREATE_NEW in options) throw IOException("Injected create failure")
        return super.openFile(directory, name, options).also { sample() }
    }

    override fun forceFile(channel: FileChannel) {
        sample()
        if (failNextFileForce) {
            failNextFileForce = false
            throw IOException("Injected file force failure")
        }
        super.forceFile(channel)
        afterNextFileForce?.also { afterNextFileForce = null }?.invoke()
        sample()
    }

    override fun forceDirectory(channel: FileChannel) {
        sample()
        if (failNextDirectoryForce) {
            failNextDirectoryForce = false
            throw IOException("Injected directory force failure")
        }
        super.forceDirectory(channel)
        sample()
    }

    override fun move(directory: PreviewCacheDirectory, source: Path, target: Path) {
        sample()
        if (failNextMove) {
            failNextMove = false
            throw IOException("Injected atomic move failure")
        }
        super.move(directory, source, target)
        sample()
    }
}
