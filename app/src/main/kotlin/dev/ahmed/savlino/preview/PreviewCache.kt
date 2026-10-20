package dev.ahmed.savlino.preview

import android.content.Context
import android.graphics.Bitmap
import android.system.Os
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.attribute.UserPrincipal

/** Immutable scalar snapshot. The artwork is a separate consumer-owned copy; cache eviction cannot recycle it. */
internal class CachedPreview(val title: String?, val uploader: String?, val durationSeconds: Long?, val artwork: Bitmap?)
internal class PreviewCacheToken internal constructor(internal val owner: Any, internal val generation: Long)
internal class PreviewCacheKey internal constructor(val hex: String, internal val generation: Long? = null)

internal data class PreviewCacheLimits(
    val diskBytes: Long = 16L * 1024 * 1024,
    val entries: Int = 32,
    val metadataEntryBytes: Int = 16 * 1024,
    val metadataTotalBytes: Long = 64L * 1024,
    val artworkEncodedBytes: Int = 2 * 1024 * 1024,
    val artworkAxis: Int = 480,
    val memoryBytes: Long = 4L * 1024 * 1024,
    val memoryEntries: Int = 8,
) {
    fun valid(): Boolean = diskBytes in 1..16L * 1024 * 1024 && entries in 1..32 &&
        metadataEntryBytes in 1..16 * 1024 && metadataTotalBytes in 1..64L * 1024 &&
        artworkEncodedBytes in 1..2 * 1024 * 1024 && artworkAxis in 1..480 &&
        memoryBytes in 0..4L * 1024 * 1024 && memoryEntries in 0..8
}
internal data class PreviewCacheUsage(
    val available: Boolean, val diskBytes: Long, val entries: Int, val metadataBytes: Long, val memoryBytes: Long, val memoryEntries: Int,
)

/**
 * Synchronous local display cache: callers dispatch I/O off the UI thread. No loader, network, timers, formats or commands.
 * The process registry serializes all owners. Platform cacheDir is trusted only for the initial fixed-root mkdir;
 * malicious same-UID replacement of that framework directory during bootstrap is outside this scoped trust model.
 */
internal class PreviewCache private constructor(
    private val registryKey: String,
    private val lock: Any,
    parent: Path,
    owner: UserPrincipal?,
    private val clock: () -> Long,
    private val limits: PreviewCacheLimits,
    fs: PreviewCacheFileSystem,
    private val artworkCodec: PreviewArtworkCodec,
    private val production: Boolean,
) : AutoCloseable {
    private class MemoryArtwork(val pixels: Bitmap, val digest: String)
    private var storage: PreviewCacheStorage? = null
    private var control: PreviewControl? = null
    private var generation = 0L
    private var available = false
    private var closed = false
    private val index = linkedMapOf<String, PreviewEntryInfo>()
    private val memory = LinkedHashMap<String, MemoryArtwork>(8, 0.75f, true)

    init {
        try {
            if (!limits.valid() || owner == null) throw IOException("Cache unavailable")
            val now = clock()
            if (now <= 0) throw IOException("Invalid cache clock")
            val disk = PreviewCacheStorage(parent, owner, fs, limits.diskBytes)
            storage = disk
            // Never clean an interrupted control/clear barrier into an apparently healthy old generation.
            if (disk.attributes(PreviewCacheStorage.CONTROL_TEMP) != null) throw IOException("Unfinished cache barrier")
            cleanJunk()
            control = if (disk.attributes(PreviewCacheStorage.CONTROL) == null) {
                if (disk.names().isNotEmpty()) throw IOException("Missing cache control")
                val fresh = PreviewControl(0, now, 0, emptyMap())
                if (PreviewCacheRecords.encodeControl(fresh).size.toLong() + PreviewCacheRecords.MAX_CONTROL_BYTES > limits.diskBytes ||
                    PreviewCacheRecords.encodeControl(fresh).size.toLong() * 2 > limits.metadataTotalBytes) throw IOException("Cache budget too small")
                if (!disk.writeControl(fresh)) throw IOException("Cache initialization failed")
                fresh
            } else disk.control()
            generation = control!!.generation
            available = true
            cleanup(now)
        } catch (_: Exception) { disable() }
    }

    fun token(): PreviewCacheToken = synchronized(lock) { PreviewCacheToken(this, generation) }

    fun key(token: PreviewCacheToken, normalizedSource: String, adapterRecipe: String, runtime: String): PreviewCacheKey = synchronized(lock) {
        // No source identity is stored on the key or cache. Invalid tokens still produce no executable/display state.
        PreviewCacheKey(PreviewCacheRecords.identity(normalizedSource, adapterRecipe, runtime, token.generation), token.generation)
    }

    fun get(key: PreviewCacheKey, token: PreviewCacheToken): CachedPreview? = synchronized(lock) {
        if (!valid(key, token)) return@synchronized null
        var decoded: Bitmap? = null
        var consumerCopy: Bitmap? = null
        try {
            val now = clock()
            if (now <= 0) { expireData(); return@synchronized null }
            verifyControl()
            cleanup(now)
            if (now < control!!.highWater) return@synchronized null
            val info = index[key.hex] ?: return@synchronized null
            var privatePixels = memory[key.hex]?.takeIf { it.digest == info.digest }?.pixels
            if (info.artworkBytes > 0 && privatePixels == null) {
                val packet = storage!!.entry(key.hex, limits)
                decoded = artworkCodec.decode(packet.artwork, limits.artworkAxis, limits.artworkAxis.toLong() * limits.artworkAxis * 4)
                if (decoded == null || decoded!!.width != info.width || decoded!!.height != info.height) {
                    removeEntry(key.hex)
                    return@synchronized null
                }
                privatePixels = decoded
            }
            if (privatePixels != null) {
                consumerCopy = privatePixels.copy(Bitmap.Config.ARGB_8888, false)
                    ?: return@synchronized null
                if (consumerCopy === privatePixels) throw IOException("Artwork ownership failure")
            }
            // Persist read high-water/order before exposing a snapshot, including memory hits.
            if (!advanceControl(now, key.hex, token)) return@synchronized null
            storage!!.verify()
            if (!valid(key, token) || key.hex !in index) return@synchronized null
            decoded?.let { pixels -> if (admit(key.hex, pixels, info.digest)) decoded = null }
            CachedPreview(info.title, info.uploader, info.duration, consumerCopy).also { consumerCopy = null }
        } catch (_: Exception) {
            disable()
            null
        } finally {
            decoded?.takeUnless { it.isRecycled }?.recycle()
            consumerCopy?.takeUnless { it.isRecycled }?.recycle()
        }
    }

    fun put(key: PreviewCacheKey, token: PreviewCacheToken, title: String? = null, uploader: String? = null,
        durationSeconds: Long? = null, artwork: Bitmap? = null): Boolean = synchronized(lock) {
        if (!valid(key, token)) return@synchronized false
        var sampled: EncodedPreviewArtwork? = null
        var pixelsAdmitted = false
        try {
            val now = clock()
            if (now <= 0) { expireData(); return@synchronized false }
            verifyControl()
            cleanup(now)
            if (now < control!!.highWater) return@synchronized false
            if (artwork != null) {
                sampled = artworkCodec.encodeSampled(artwork, limits.artworkAxis, limits.artworkEncodedBytes)
                    ?: return@synchronized false
            }
            val bytes = PreviewCacheRecords.encodeEntry(PreviewCacheRecords.text(title, 300), PreviewCacheRecords.text(uploader, 120),
                PreviewCacheRecords.duration(durationSeconds), generation, now, sampled, limits) ?: return@synchronized false
            val packet = PreviewCacheRecords.decodeEntry(bytes, limits)
            if (!reserve(bytes.size.toLong(), packet.info.metadataBytes.toLong(), newEntry = true)) return@synchronized false
            val disk = storage!!
            val expected = control!!
            if (!disk.writeEntry(key.hex, bytes) { valid(key, token) && disk.control() == expected }) return@synchronized false
            if (!valid(key, token)) return@synchronized false
            dropMemory(key.hex)
            index[key.hex] = packet.info
            if (!advanceControl(now, key.hex, token)) return@synchronized false
            sampled?.let { pixelsAdmitted = admit(key.hex, it.pixels, packet.info.digest) }
            true
        } catch (_: Exception) {
            disable()
            false
        } finally {
            if (!pixelsAdmitted) sampled?.pixels?.takeUnless { it.isRecycled }?.recycle()
        }
    }

    fun clear(): Boolean = synchronized(lock) {
        if (!available || closed) return@synchronized false
        // Invalidate first, even if disk durability subsequently fails. Failed clear is never reported as success.
        val prior = control ?: return@synchronized false
        try {
            verifyControl()
            generation = Math.addExact(generation, 1)
            clearMemory()
            // Clear cannot evict entries to make space before its generation barrier is durable.
            if (storage!!.usedBytes() + PreviewCacheRecords.MAX_CONTROL_BYTES > limits.diskBytes ||
                metadataUsed() + PreviewCacheRecords.MAX_CONTROL_BYTES > limits.metadataTotalBytes) throw IOException("Clear barrier cannot fit")
            val next = PreviewControl(generation, prior.highWater, prior.nextOrder, emptyMap())
            if (!storage!!.writeControl(next) { available && !closed && storage!!.controlIgnoringPending() == prior }) {
                throw IOException("Clear barrier interrupted")
            }
            control = next
            expireData()
            storage!!.syncDirectory()
            storage!!.verify()
            true
        } catch (_: Exception) {
            disable()
            false
        }
    }

    fun usage(): PreviewCacheUsage = synchronized(lock) {
        if (!available || closed) return@synchronized PreviewCacheUsage(false, 0, 0, 0, 0, 0)
        try {
            verifyControl()
            cleanup(clock())
            PreviewCacheUsage(true, storage!!.usedBytes(), index.size, metadataUsed(), memoryBytes(), memory.size)
        } catch (_: Exception) {
            disable()
            PreviewCacheUsage(false, 0, 0, 0, 0, 0)
        }
    }

    private fun valid(key: PreviewCacheKey, token: PreviewCacheToken): Boolean = available && !closed && token.owner === this &&
        token.generation == generation && key.generation == generation && PreviewCacheRecords.hexKey.matches(key.hex) && key.hex !in PreviewCacheStorage.RESERVED

    private fun verifyControl() {
        storage!!.verify()
        if (storage!!.control() != control) throw IOException("Cache control changed")
    }

    private fun cleanJunk() {
        val disk = storage!!
        for (name in disk.names()) {
            if (name == PreviewCacheStorage.CONTROL_TEMP) throw IOException("Unfinished cache barrier")
            val attrs = disk.attributes(name) ?: continue
            if (!attrs.isRegularFile || attrs.isSymbolicLink || name == PreviewCacheStorage.PAYLOAD_TEMP ||
                (name != PreviewCacheStorage.CONTROL && !PreviewCacheRecords.hexKey.matches(name))) disk.remove(name)
        }
    }

    /** Validates bounded display bytes only. It deliberately does not decode every image during a disk scan. */
    private fun cleanup(now: Long) {
        val disk = storage!!
        cleanJunk()
        if (now <= 0 || now < control!!.highWater) { expireData(); return }
        val validated = linkedMapOf<String, PreviewEntryInfo>()
        for (name in disk.names()) {
            if (name in PreviewCacheStorage.RESERVED) continue
            val packet = try { disk.entry(name, limits) } catch (_: Exception) {
                disk.verify()
                disk.remove(name)
                dropMemory(name)
                continue
            }
            val info = packet.info
            val access = control!!.access[name]
            if (info.generation != generation || info.written > now || now - info.written >= PreviewCacheRecords.TTL ||
                info.written > control!!.highWater || access == null || access.time < info.written || access.time > now) {
                disk.remove(name); dropMemory(name)
            } else validated[name] = info
        }
        index.clear(); index.putAll(validated)
        memory.keys.toList().filter { name -> name !in index || memory[name]?.digest != index[name]?.digest }.forEach(::dropMemory)
        while (index.size > limits.entries || metadataUsed() > limits.metadataTotalBytes || disk.usedBytes() > limits.diskBytes) {
            val victim = leastRecent() ?: throw IOException("Cache budget cannot be restored")
            removeEntry(victim)
        }
    }

    private fun expireData() {
        val disk = storage!!
        for (name in disk.names()) if (name != PreviewCacheStorage.CONTROL && name != PreviewCacheStorage.CONTROL_TEMP) disk.remove(name)
        index.clear()
        clearMemory()
    }

    private fun leastRecent(protect: String? = null): String? = index.keys.filter { it != protect }
        .minWithOrNull(compareBy<String> { control!!.access[it]?.order ?: 0 }.thenBy { it })

    /** U already includes the old destination/control; E and C are added once, before CREATE_NEW. */
    private fun reserve(entryBytes: Long, metadataBytes: Long, newEntry: Boolean, protect: String? = null): Boolean {
        val disk = storage!!
        val controlTemp = PreviewCacheRecords.MAX_CONTROL_BYTES.toLong()
        if (entryBytes + controlTemp + PreviewCacheRecords.encodeControl(control!!).size > limits.diskBytes ||
            metadataBytes + controlTemp + PreviewCacheRecords.encodeControl(control!!).size > limits.metadataTotalBytes) return false
        while (disk.usedBytes() + entryBytes + controlTemp > limits.diskBytes ||
            metadataUsed() + metadataBytes + controlTemp > limits.metadataTotalBytes || index.size + (if (newEntry) 1 else 0) > limits.entries) {
            val victim = leastRecent(protect) ?: return false
            removeEntry(victim)
        }
        return true
    }

    private fun advanceControl(now: Long, accessed: String, token: PreviewCacheToken): Boolean {
        if (!reserve(0, 0, newEntry = false, protect = accessed)) return false
        val prior = control!!
        val nextOrder = Math.addExact(prior.nextOrder, 1)
        val access = prior.access.filterKeys { it in index }.toMutableMap()
        access[accessed] = PreviewAccess(nextOrder, now)
        val next = PreviewControl(generation, maxOf(prior.highWater, now), nextOrder, access.toMap())
        val disk = storage!!
        if (!disk.writeControl(next) { available && token.owner === this && token.generation == generation &&
                disk.controlIgnoringPending() == prior }) return false
        control = next
        return true
    }

    private fun metadataUsed(): Long = index.values.sumOf { it.metadataBytes.toLong() } +
        (storage!!.attributes(PreviewCacheStorage.CONTROL)?.size() ?: 0)
    private fun memoryBytes(): Long = memory.values.sumOf { it.pixels.allocationByteCount.toLong() }
    private fun removeEntry(name: String) { storage!!.remove(name); index.remove(name); dropMemory(name) }
    private fun dropMemory(name: String) { memory.remove(name)?.pixels?.takeUnless { it.isRecycled }?.recycle() }
    private fun clearMemory() { memory.keys.toList().forEach(::dropMemory) }
    private fun admit(name: String, pixels: Bitmap, digest: String): Boolean {
        dropMemory(name)
        val bytes = pixels.allocationByteCount.toLong()
        if (limits.memoryEntries == 0 || bytes > limits.memoryBytes) return false
        while (memory.size >= limits.memoryEntries || memoryBytes() + bytes > limits.memoryBytes) dropMemory(memory.keys.first())
        memory[name] = MemoryArtwork(pixels, digest)
        return true
    }
    private fun disable() {
        available = false; clearMemory(); index.clear()
        storage?.close(); storage = null
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true; disable(); storage?.close(); storage = null
        }
        synchronized(ownersLock) { if (owners[registryKey] === this) owners.remove(registryKey) }
    }

    companion object {
        private val ownersLock = Any()
        private val owners = mutableMapOf<String, PreviewCache>()
        private val productionRoots = mutableSetOf<String>()
        private fun rootId(parent: File): String = File(parent.canonicalFile, PreviewCacheStorage.ROOT).path

        fun shared(context: Context): PreviewCache = synchronized(ownersLock) {
            val parent = context.applicationContext.cacheDir
            val id = rootId(parent)
            productionRoots += id
            owners[id]?.let { check(it.production); return@synchronized it }
            val owner = try { FileSystems.getDefault().userPrincipalLookupService.lookupPrincipalByName(Os.getuid().toString()) }
                catch (_: Exception) { null }
            PreviewCache(id, Any(), parent.toPath(), owner, System::currentTimeMillis, PreviewCacheLimits(),
                PreviewCacheFileSystem(), PreviewArtworkCodec(), true).also { owners[id] = it }
        }

        internal fun openIsolated(temporaryDirectory: File, owner: UserPrincipal, clock: () -> Long,
            limits: PreviewCacheLimits = PreviewCacheLimits(), fileSystem: PreviewCacheFileSystem = PreviewCacheFileSystem(),
            artworkCodec: PreviewArtworkCodec = PreviewArtworkCodec()): PreviewCache = synchronized(ownersLock) {
            val id = rootId(temporaryDirectory)
            val temp = File(System.getProperty("java.io.tmpdir")).canonicalFile.toPath()
            require(temporaryDirectory.canonicalFile.toPath().startsWith(temp)) { "Isolated cache requires a temporary directory" }
            check(id !in productionRoots) { "Production cache cannot use isolated storage" }
            check(id !in owners) { "Cache already has a live owner" }
            PreviewCache(id, Any(), temporaryDirectory.toPath(), owner, clock, limits, fileSystem, artworkCodec, false).also { owners[id] = it }
        }
    }
}
