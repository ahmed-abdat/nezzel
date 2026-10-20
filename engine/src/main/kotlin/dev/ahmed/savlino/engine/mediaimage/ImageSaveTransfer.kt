package dev.ahmed.savlino.engine.mediaimage

import android.system.ErrnoException
import android.system.OsConstants
import dev.ahmed.savlino.domain.MediaSelection
import dev.ahmed.savlino.engine.mediahttp.*
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImageSaveOperation internal constructor(internal val selection: MediaSelection) {
    internal val lock = Any()
    internal var cancelled = false
    internal var token: OperationToken? = null
    internal val started = AtomicBoolean()
    override fun toString() = "ImageSaveOperation(opaque)"
}

/**
 * Explicit refetch of one exact returned image selection into caller staging.
 * Fresh U5a Save token (20 MiB streamed cap, 120 s whole deadline); every chunk is validated before
 * it reaches staging. A fully inspected original must match its proof; a thumbnail-backed original
 * has no inspected size/dimensions/proof and gains them only after this complete validation.
 * The caller retains ownership of staging cleanup and publication; this never closes the sink.
 */
class ImageSaveTransfer(private val http: MediaHttpClient, private val references: MediaReferenceStore) {
    private val validator = ImageRepresentationValidator()
    /** Test seams around the atomic admission; never set in production. */
    internal var beforeAdmission: () -> Unit = {}
    internal var afterAdmission: () -> Unit = {}

    fun newOperation(selection: MediaSelection) = ImageSaveOperation(selection)

    suspend fun transfer(operation: ImageSaveOperation, staging: OutputStream): ImageSaveResult =
        withContext(Dispatchers.IO) { transferBlocking(operation, staging) }

    fun cancel(operation: ImageSaveOperation) {
        val token = synchronized(operation.lock) {
            operation.cancelled = true
            operation.token
        }
        token?.let { http.cancel(it.id) }
    }

    private fun transferBlocking(operation: ImageSaveOperation, staging: OutputStream): ImageSaveResult {
        if (synchronized(operation.lock) { operation.cancelled }) return failure(ImageFailure.CANCELLED)
        if (!operation.started.compareAndSet(false, true)) return failure(ImageFailure.UNAVAILABLE)
        val reference = references.resolve(operation.selection) ?: return failure(
            if (references.wasEvicted(operation.selection)) ImageFailure.SAVE_UNAVAILABLE else ImageFailure.STALE)
        if (reference.linkedIn && reference.postContext == null) return failure(ImageFailure.SAVE_UNAVAILABLE)
        val token = http.newSaveOperation()
        try {
            val registered = synchronized(operation.lock) {
                if (operation.cancelled) false else { operation.token = token; true }
            }
            if (!registered) return failure(ImageFailure.CANCELLED)
            beforeAdmission()
            // Atomic with retirement: a replacement after this point cancels the token before more I/O.
            val admission = references.admitSave(reference) { http.cancel(token.id) }
                ?: return failure(retired(operation, reference) ?: ImageFailure.STALE)
            return try {
                afterAdmission()
                transferAdmitted(operation, reference, admission, token, staging)
            } finally { admission.release() }
        } finally {
            token.close()
        }
    }

    private fun transferAdmitted(operation: ImageSaveOperation, reference: StoredImageReference,
        admission: MediaReferenceStore.SaveAdmission, token: OperationToken, staging: OutputStream): ImageSaveResult {
        val pageContext = reference.postContext?.let { stored ->
            http.bindSaveContext(token, stored,
                MediaHttpSaveBinding(reference.source, reference.identity, reference.validity))
                ?: return failure(retired(operation, reference) ?: ImageFailure.SAVE_UNAVAILABLE)
        }
        val stream = validator.newStream(reference.image.format.mimeType)
        // Lock-free: retirement may hold the store lock while draining this Save's in-flight write.
        val sink = ProofSink(staging, stream) { admission.current }
        val transferred = http.transferImage(token,
            PublicMediaRequest(reference.url, MediaHttpPurpose.IMAGE, pageContext, reference.hostRule), sink)
        val success = when (transferred) {
            is MediaHttpImageTransferResult.Failure -> {
                sink.failure?.let { return failure(it) }
                retired(operation, reference)?.let { return failure(it) }
                val reason = mapTransport(transferred.reason)
                return ImageSaveResult.Failure(reason, if (reason == ImageFailure.HTTP) transferred.statusCode else null)
            }
            is MediaHttpImageTransferResult.Success -> transferred
        }
        sink.failure?.let { return failure(it) }
        retired(operation, reference)?.let { return failure(it) }
        val image = when (val validation = stream.finish()) {
            is ImageValidationResult.Failure -> return failure(validation.reason)
            is ImageValidationResult.Valid -> validation.image
        }
        // GIF signatures and complete structure are authoritative even when a CDN reports JPEG.
        if (image.format != dev.ahmed.savlino.domain.MediaImageFormat.GIF) {
            val responseFormat = imageFormatFor(success.mimeType)
                ?: return failure(ImageFailure.UNSUPPORTED)
            if (responseFormat != image.format) return failure(ImageFailure.INVALID_IMAGE)
        }
        val proof = checkNotNull(image.proof)
        if (proof.completeBodySha256 != success.completeBodySha256 || proof.measuredBytes != success.measuredBytes)
            return failure(ImageFailure.PROOF_MISMATCH)
        val inspected = reference.image
        val inspectedProof = inspected.proof
        if ((inspected.width != null && image.width != inspected.width) ||
            (inspected.height != null && image.height != inspected.height) ||
            (inspectedProof != null && (inspectedProof.completeBodySha256 != proof.completeBodySha256 ||
                inspectedProof.measuredBytes != proof.measuredBytes))) return failure(ImageFailure.PROOF_MISMATCH)
        // Final admission: a replacement or cancel during the last write rejects late success.
        retired(operation, reference)?.let { return failure(it) }
        return ImageSaveResult.Success(image)
    }

    private fun retired(operation: ImageSaveOperation, reference: StoredImageReference): ImageFailure? = when {
        synchronized(operation.lock) { operation.cancelled } -> ImageFailure.CANCELLED
        !references.isCurrent(reference) -> ImageFailure.STALE
        else -> null
    }

    private fun failure(reason: ImageFailure) = ImageSaveResult.Failure(reason)
}

/** Validates each chunk before caller staging sees it; records why it aborted the transfer. */
internal class ProofSink(private val staging: OutputStream, private val stream: ImageRepresentationValidator.Stream,
    private val current: () -> Boolean) : OutputStream() {
    @Volatile var failure: ImageFailure? = null
        private set

    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (failure != null) throw SaveAbort()
        if (!current()) abort(ImageFailure.STALE)
        stream.accept(buffer, offset, length)?.let(::abort)
        try { staging.write(buffer, offset, length) }
        catch (failure: Exception) {
            // Classify locally; never carry a sink's private path/message into a result or cause.
            val full = generateSequence<Throwable>(failure) { it.cause }.take(16).any {
                (it is ErrnoException && it.errno == OsConstants.ENOSPC) ||
                    (it is IOException && (it.message.orEmpty().contains("ENOSPC", ignoreCase = true) ||
                        it.message.orEmpty().contains("no space left", ignoreCase = true)))
            }
            abort(if (full) ImageFailure.INSUFFICIENT_SPACE else ImageFailure.STAGING)
        }
    }

    private fun abort(reason: ImageFailure): Nothing {
        failure = reason
        throw SaveAbort()
    }

    override fun flush() = Unit
    override fun close() = Unit // Staging belongs to the caller.
}

private class SaveAbort : IOException("image_save_aborted")
