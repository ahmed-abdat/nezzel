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
    data class Budget(val staging: Long, val output: Long) {
        val total: Long get() = Math.addExact(staging, output)
    }

    fun check(space: SpaceGate, format: Format, stagedBytes: Long = 0): Budget {
        val budget = budget(format, stagedBytes)
        space.beforeTransfer(budget.staging, budget.output)
        return budget
    }

    fun budget(format: Format, stagedBytes: Long = 0): Budget {
        require(stagedBytes >= 0)
        fun remaining(budget: Long) = (budget - minOf(budget, stagedBytes)).coerceAtLeast(0)
        val output = when (val size = format.size) {
            is Size.Exact -> size.bytes
            is Size.Estimated -> {
                val reserve = Math.addExact(size.bytes, Math.addExact(size.bytes / 4,
                    if (size.bytes % 4 == 0L) 0L else 1L))
                // Q5 defines an aggregate estimated reserve; measured publication is checked later.
                return Budget(remaining(reserve), 0)
            }
            Size.Unknown -> {
                // Aggregate 1 GiB reserve; SpaceBudget adds its 64 MiB margin on both volumes.
                return Budget(remaining(1024L * 1024 * 1024), 0)
            }
        }
        val intermediate = if (format.requiresMerge || format.conversion != null) Math.multiplyExact(output, 2L) else 0L
        return Budget(Math.addExact(remaining(output), intermediate), output)
    }

    fun isNoSpace(failure: Throwable): Boolean = generateSequence(failure) { it.cause }.any {
        (it is StorageException && it.error == StorageError.InsufficientSpace) ||
            (it is ErrnoException && it.errno == OsConstants.ENOSPC) ||
            (it is IOException && (it.message.orEmpty().contains("ENOSPC", ignoreCase = true) ||
                it.message.orEmpty().contains("no space left", ignoreCase = true)))
    }
}

/**
 * Process-local future space budgets; a new process starts empty after journal recovery. Another job's share is its
 * budget less what it has already staged, read now: written bytes already left free space, and a restart that clears
 * staging counts its whole budget again.
 */
internal class TransferSpaceLedger(private val staged: (String) -> Long = { 0L }) {
    private val reservations = mutableMapOf<String, Format>()
    @Synchronized fun reserve(id: String, space: SpaceGate, format: Format, stagedBytes: Long = 0) {
        check(id !in reservations)
        val budget = TransferSpacePolicy.budget(format, stagedBytes)
        val others = reservations.entries.fold(0L) { sum, (other, otherFormat) ->
            Math.addExact(sum, TransferSpacePolicy.budget(otherFormat, staged(other).coerceAtLeast(0)).total)
        }
        space.beforeTransfer(Math.addExact(budget.staging, others), budget.output)
        reservations[id] = format
    }
    @Synchronized fun release(id: String) { reservations.remove(id) }
}
