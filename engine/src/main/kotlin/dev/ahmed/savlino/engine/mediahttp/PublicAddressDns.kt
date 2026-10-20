package dev.ahmed.savlino.engine.mediahttp

import java.net.InetAddress
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okhttp3.Dns

/** Override lookup only: default newCall selects OkHttp5.5's inline LookupDnsCall branch. */
internal class PublicAddressDns internal constructor(private val token: OperationToken,
    private val rawResolver: (String) -> List<InetAddress>, private val executor: ExecutorService) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        token.requireActive()
        val future = try {
            executor.submit<List<InetAddress>> {
                // Copy before admitting the set, so subsequent caller mutation cannot replace a route.
                rawResolver(hostname).take(17).map { InetAddress.getByAddress(it.address) }
            }
        } catch (_: RejectedExecutionException) { throw MediaHttpException(MediaHttpFailure.BUSY) }
        val unregister = token.register(future) { future.cancel(true) }
        try {
            token.requireActive()
            val answers = future.get(token.remainingNanos().coerceAtLeast(1), TimeUnit.NANOSECONDS)
            token.requireActive()
            if (answers.isEmpty() || answers.size > 16 || answers.any { !MediaHttpPolicy.allows(it) })
                throw MediaHttpException(MediaHttpFailure.ADDRESS_REJECTED)
            return answers
        } catch (_: TimeoutException) {
            token.stop(MediaHttpFailure.TIMEOUT)
            throw MediaHttpException(token.checkActive() ?: MediaHttpFailure.TIMEOUT)
        } catch (_: CancellationException) {
            throw MediaHttpException(token.checkActive() ?: MediaHttpFailure.NETWORK)
        } catch (_: ExecutionException) {
            throw MediaHttpException(token.checkActive() ?: MediaHttpFailure.NETWORK)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MediaHttpException(token.checkActive() ?: MediaHttpFailure.NETWORK)
        } finally {
            unregister()
            future.cancel(true) // Does not claim that a blocked OS resolver has stopped.
        }
    }
}
