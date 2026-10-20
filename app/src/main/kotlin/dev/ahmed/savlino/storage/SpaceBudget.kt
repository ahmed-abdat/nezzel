package dev.ahmed.savlino.storage

import android.os.StatFs
import java.io.File
import java.io.IOException

/** Resource-mappable failures; messages never contain filenames, URLs or provider diagnostics. */
enum class StorageError { InvalidPath, InsufficientSpace, UnknownSizeRequiresBound, SaveFailed, FolderUnavailable, Cancelled, CopySizeMismatch, InvalidPublication }
class StorageException(val error: StorageError, cause: Throwable? = null) : IOException(error.name, cause)
fun interface FreeSpace { fun availableBytes(location: File): Long }
interface SpaceGate {
    fun beforeTransfer(stagingBytes: Long?, outputBytes: Long?, unknownSizeUpperBound: Long? = null)
    fun beforePublication(outputBytes: Long)
}

/** A preflight check, not a disk reservation. Actual I/O can still fail as other apps consume space. */
class SpaceBudget(
    private val stagingVolume: File,
    private val outputVolume: File,
    private val freeSpace: FreeSpace = FreeSpace { StatFs(it.absolutePath).availableBytes },
    private val marginBytes: Long = 64L * 1024 * 1024,
) : SpaceGate {
    init { require(marginBytes >= 0) }

    /**
     * Caller supplies the sum of all simultaneous staging inputs/intermediates and final output.
     * Unknown bytes fail closed unless the caller supplies an explicit, enforceable upper bound
     * for EACH unknown component. That bound is a safety limit, never a fabricated media size.
     * Charge the sum plus a 64 MiB default margin against BOTH volumes: conservative on separate
     * volumes and safe when staging/output share a filesystem. No transfer may start on failure.
     */
    override fun beforeTransfer(stagingBytes: Long?, outputBytes: Long?, unknownSizeUpperBound: Long?) {
        require(stagingBytes == null || stagingBytes >= 0)
        require(outputBytes == null || outputBytes >= 0)
        require(unknownSizeUpperBound == null || unknownSizeUpperBound > 0)
        fun bounded(bytes: Long?): Long = bytes ?: unknownSizeUpperBound
            ?: throw StorageException(StorageError.UnknownSizeRequiresBound)
        val required = sum(bounded(stagingBytes), bounded(outputBytes), marginBytes)
        check(stagingVolume, required)
        check(outputVolume, required)
    }

    /** Staging already occupies space; only the additional published copy and margin are charged. */
    override fun beforePublication(outputBytes: Long) {
        require(outputBytes >= 0)
        check(outputVolume, sum(outputBytes, marginBytes))
    }

    private fun sum(vararg bytes: Long): Long = try {
        bytes.fold(0L, Math::addExact)
    } catch (overflow: ArithmeticException) {
        throw StorageException(StorageError.InsufficientSpace, overflow)
    }

    private fun check(volume: File, required: Long) {
        val available = try { freeSpace.availableBytes(volume) } catch (failure: RuntimeException) {
            // An inaccessible/unknown volume is not evidence of available space.
            throw StorageException(StorageError.InsufficientSpace, failure)
        }
        if (available < required) throw StorageException(StorageError.InsufficientSpace)
    }
}
