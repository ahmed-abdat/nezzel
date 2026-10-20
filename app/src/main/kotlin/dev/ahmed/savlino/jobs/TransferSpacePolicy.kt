package dev.ahmed.savlino.jobs

import android.system.ErrnoException
import android.system.OsConstants
import dev.ahmed.savlino.domain.Format
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.storage.SpaceGate
import dev.ahmed.savlino.storage.StorageError
import dev.ahmed.savlino.storage.StorageException
import java.io.IOException

/** Q5 preflight reserves are budgets, never claimed media sizes or disk reservations. */
internal object TransferSpacePolicy {
    fun check(space: SpaceGate, format: Format) {
        val output = when (val size = format.size) {
            is Size.Exact -> size.bytes
            is Size.Estimated -> {
                val reserve = Math.addExact(size.bytes, Math.addExact(size.bytes / 4,
                    if (size.bytes % 4 == 0L) 0L else 1L))
                // Q5 defines an aggregate estimated reserve; measured publication is checked later.
                space.beforeTransfer(reserve, 0)
                return
            }
            Size.Unknown -> {
                // Aggregate 1 GiB reserve; SpaceBudget adds its 64 MiB margin on both volumes.
                space.beforeTransfer(1024L * 1024 * 1024, 0)
                return
            }
        }
        space.beforeTransfer(Math.multiplyExact(output,
            if (format.requiresMerge || format.conversion != null) 3L else 1L), output)
    }

    fun isNoSpace(failure: Throwable): Boolean = generateSequence(failure) { it.cause }.any {
        (it is StorageException && it.error == StorageError.InsufficientSpace) ||
            (it is ErrnoException && it.errno == OsConstants.ENOSPC) ||
            (it is IOException && (it.message.orEmpty().contains("ENOSPC", ignoreCase = true) ||
                it.message.orEmpty().contains("no space left", ignoreCase = true)))
    }
}
