package dev.ahmed.savlino.media

import dev.ahmed.savlino.domain.MediaSelection
import dev.ahmed.savlino.engine.mediaimage.ImageSaveOperation
import dev.ahmed.savlino.engine.mediaimage.ImageSaveResult
import dev.ahmed.savlino.engine.mediaimage.ImageSaveTransfer
import java.io.OutputStream

/** Wraps U5b's ImageSaveTransfer: one operation per transfer, cancellable from another thread. */
class TransferFetch(transfer: () -> ImageSaveTransfer) : ImageFetch {
    private val transfer by lazy(transfer)
    @Volatile private var current: ImageSaveOperation? = null
    override suspend fun transfer(selection: MediaSelection, sink: OutputStream): ImageSaveResult {
        val operation = transfer.newOperation(selection)
        current = operation
        return try { transfer.transfer(operation, sink) } finally { if (current === operation) current = null }
    }
    override fun cancel() { current?.let(transfer::cancel) }
}
