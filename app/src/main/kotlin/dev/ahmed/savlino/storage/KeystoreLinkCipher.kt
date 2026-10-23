package dev.ahmed.savlino.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the reviewed link of a saved download (Copy link). Never logs, and never throws with the link in a message.
 */
interface LinkCipher {
    /** IV + AES-GCM ciphertext, or null when no key can be used; nothing is stored then (no plain fallback). */
    fun seal(link: String): ByteArray?
    fun open(blob: ByteArray): Opened
    /** Deletes the key: every stored blob becomes [Opened.Gone]; the next [seal] makes a new key. */
    fun forget()

    sealed interface Opened {
        class Link(val text: String) : Opened
        /** Keystore failed for now: hide the link, keep the blob. */
        data object Unavailable : Opened
        /** Key gone or blob invalid: hide the link and drop the blob. */
        data object Gone : Opened
    }
}

/** Where the AES key lives. */
interface LinkKeySource {
    /** The key; a new one when [create] and none exists; null when none exists. Throws when the store fails. */
    fun load(create: Boolean): SecretKey?
    fun delete()
}

class AesGcmLinkCipher(private val keys: LinkKeySource) : LinkCipher {
    private val lock = Any()
    // Not a lazy singleton: forget() must drop the handle so later seals make a fresh key without a restart.
    private var cached: SecretKey? = null

    private fun key(create: Boolean): SecretKey? = synchronized(lock) { cached ?: keys.load(create)?.also { cached = it } }

    override fun seal(link: String): ByteArray? = try {
        val key = key(create = true)
        if (key == null) null else {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
            val iv = cipher.iv
            if (iv?.size != IV_BYTES) null else iv + cipher.doFinal(link.toByteArray(Charsets.UTF_8))
        }
    } catch (_: Exception) { null }

    override fun open(blob: ByteArray): LinkCipher.Opened {
        if (blob.size <= IV_BYTES + TAG_BYTES) return LinkCipher.Opened.Gone
        val key = try { key(create = false) } catch (_: Exception) { return LinkCipher.Opened.Unavailable }
            ?: return LinkCipher.Opened.Gone
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, blob, 0, IV_BYTES))
            LinkCipher.Opened.Link(String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8))
        } catch (_: AEADBadTagException) { LinkCipher.Opened.Gone }
        catch (_: KeyPermanentlyInvalidatedException) { LinkCipher.Opened.Gone }
        catch (_: Exception) { LinkCipher.Opened.Unavailable }
    }

    override fun forget() = synchronized(lock) {
        cached = null
        try { keys.delete() } catch (_: Exception) {}
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
    }
}

/** Per-install AES-256 key in AndroidKeyStore (non-exportable, no user auth), only for Copy link. */
object KeystoreLinkCipher {
    private const val ALIAS = "nezzel-share-links-v1"

    private val keystore = object : LinkKeySource {
        private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        override fun load(create: Boolean): SecretKey? {
            (store().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            if (!create) return null
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
        }
        override fun delete() = store().deleteEntry(ALIAS)
    }
    private val shared: LinkCipher = AesGcmLinkCipher(keystore)

    /** JVM tests have no AndroidKeyStore: they install a software-key cipher. */
    @androidx.annotation.VisibleForTesting var testOverride: LinkCipher? = null

    fun current(): LinkCipher = testOverride ?: shared
}
