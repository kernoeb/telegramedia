package app.telegramedia.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Supplies the 32-byte key TDLib uses to encrypt its on-disk database
 * (`SetTdlibParameters.databaseEncryptionKey`).
 *
 * The key is random per install and never stored in the clear: it is wrapped with a
 * hardware-backed AES key held in the Android Keystore (non-exportable — the raw key
 * material never leaves the secure hardware on devices that have it). Only the wrapped
 * blob lands in [SharedPreferences], so on a rooted/compromised device the database
 * stays unreadable without also defeating the Keystore.
 *
 * The same key is returned on every launch, so an existing encrypted database keeps
 * opening across restarts.
 */
class DatabaseKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the stable 32-byte database key, generating + persisting it on first use. */
    @Synchronized
    fun getOrCreateKey(): ByteArray {
        prefs.getString(PREF_WRAPPED_KEY, null)?.let { stored ->
            runCatching { return unwrap(stored) }
            // A Keystore that can no longer decrypt the blob (e.g. key invalidated) means
            // the DB it protected is unrecoverable anyway; fall through and mint a new one.
        }
        val key = ByteArray(DB_KEY_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        // commit() (synchronous), not apply(): the key MUST be durably stored before it
        // is handed to TDLib, or a process death before the async flush would leave the
        // freshly-encrypted database unreadable (a new, different key next launch).
        prefs.edit().putString(PREF_WRAPPED_KEY, wrap(key)).commit()
        return key
    }

    private fun wrap(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val ciphertext = cipher.doFinal(plaintext)
        // Prefix the IV so unwrap can reconstruct the GCM spec.
        val packed = cipher.iv + ciphertext
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun unwrap(stored: String): ByteArray {
        val packed = Base64.decode(stored, Base64.NO_WRAP)
        val iv = packed.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = packed.copyOfRange(GCM_IV_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "telegramedia_db_key_wrapper"
        const val PREFS_NAME = "secure_db_key"
        const val PREF_WRAPPED_KEY = "wrapped_db_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DB_KEY_BYTES = 32
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
