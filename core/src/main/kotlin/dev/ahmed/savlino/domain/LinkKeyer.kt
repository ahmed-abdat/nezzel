package dev.ahmed.savlino.domain

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Keyed one-way tag for a normalized link (HMAC-SHA256). A plain SHA-256 of a URL can be found by hashing
 * guessed URLs and matched across stores; a per-install secret key prevents both, and [domain] keeps the
 * clipboard and job tags of the same link unrelated. Returns null when no key is available: callers then
 * keep nothing durable rather than fall back to an unkeyed hash.
 */
fun interface LinkKeyer {
    fun key(domain: String, normalizedUrl: String): String?

    companion object {
        const val CLIPBOARD = "clipboard"
        const val JOB_SOURCE = "job-source"

        /** HMAC over `domain NUL url` with [mac], a freshly initialised HMAC-SHA256 for each call. */
        fun tag(mac: Mac, domain: String, normalizedUrl: String): String {
            mac.update(domain.toByteArray(Charsets.UTF_8))
            mac.update(0)
            return mac.doFinal(normalizedUrl.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        /** Raw-secret keyer: process-only session keys and test fakes. Production durable keys live in AndroidKeyStore. */
        fun withSecret(secret: ByteArray): LinkKeyer {
            val spec = SecretKeySpec(secret.copyOf(), "HmacSHA256")
            return LinkKeyer { domain, url -> tag(Mac.getInstance("HmacSHA256").apply { init(spec) }, domain, url) }
        }

        /** Random key for this process only: matches within a session, never across restarts. */
        val session: LinkKeyer by lazy { withSecret(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }) }
    }
}
