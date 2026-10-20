package dev.ahmed.savlino.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.ahmed.savlino.domain.LinkKeyer
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Per-install HMAC-SHA256 key held by AndroidKeyStore (non-exportable). Durable link tags (handled clipboard
 * links, job sources) use only this key; when the keystore is unavailable, [orNull] is null and nothing
 * durable is keyed (no unkeyed fallback).
 */
class KeystoreLinkKeyer private constructor(private val secret: SecretKey) : LinkKeyer {
    override fun key(domain: String, normalizedUrl: String): String? = try {
        LinkKeyer.tag(Mac.getInstance("HmacSHA256").apply { init(secret) }, domain, normalizedUrl)
    } catch (_: Exception) { null }

    companion object {
        private const val ALIAS = "nezzel-link-tags-v1"
        private val shared: KeystoreLinkKeyer? by lazy { create() }

        /** JVM tests have no AndroidKeyStore: they install a fake key (or null to exercise "unavailable"). */
        @androidx.annotation.VisibleForTesting var testOverride: (() -> LinkKeyer?)? = null

        /** The install's keyer, or null where AndroidKeyStore cannot hold or use an HMAC key. */
        fun orNull(): LinkKeyer? = testOverride?.let { return it() } ?: shared

        /** Resolve only when an IO-backed journal operation first needs a tag. */
        val deferred: LinkKeyer = object : LinkKeyer {
            override fun key(domain: String, normalizedUrl: String): String? = orNull()?.key(domain, normalizedUrl)
        }

        private fun create(): KeystoreLinkKeyer? = try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build())
                }.generateKey()
            KeystoreLinkKeyer(key).takeIf { it.key(LinkKeyer.JOB_SOURCE, "self-test") != null }
        } catch (_: Exception) { null }
    }
}
