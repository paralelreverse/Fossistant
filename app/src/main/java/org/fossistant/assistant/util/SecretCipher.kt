package org.fossistant.assistant.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (Home Assistant / MCP access tokens) with Android Keystore AES-256-GCM
 * before persisting them to SharedPreferences or JSON files.
 *
 * Legacy plaintext values remain readable via [decrypt], and JVM unit tests without an
 * `AndroidKeyStore` provider fall back cleanly to passthrough values.
 */
object SecretCipher {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "fossistant_secrets_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc:v1:"
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_BITS = 128

    private val lock = Any()

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    fun encrypt(plaintext: String): String {
        if (plaintext.isBlank() || isEncrypted(plaintext)) return plaintext
        return synchronized(lock) {
            runCatching {
                val key = getOrCreateKey() ?: return@synchronized plaintext
                val iv = ByteArray(IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
                val enc = Base64.getEncoder()
                "$PREFIX${enc.encodeToString(iv)}:${enc.encodeToString(encrypted)}"
            }.getOrElse {
                plaintext
            }
        }
    }

    fun decrypt(stored: String): String {
        if (stored.isBlank() || !isEncrypted(stored)) return stored
        return synchronized(lock) {
            runCatching {
                val payload = stored.removePrefix(PREFIX)
                val sep = payload.indexOf(':')
                if (sep <= 0) return@synchronized ""
                val dec = Base64.getDecoder()
                val iv = dec.decode(payload.substring(0, sep))
                val ciphertext = dec.decode(payload.substring(sep + 1))
                val key = getExistingKey() ?: return@synchronized ""
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            }.getOrElse { t ->
                AppLogger.w("SecretCipher", "Failed to decrypt stored secret (${t::class.simpleName})")
                ""
            }
        }
    }

    private fun getExistingKey(): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return ks.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun getOrCreateKey(): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
