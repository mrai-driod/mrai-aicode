package com.aicode.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Symmetric encryption for sensitive data: the key is stored in the Android Keystore (non-exportable, hardware-protected when a TEE is present),
 * and data is encrypted with AES-256-GCM. Used for private key files and password/API Key fields in the database.
 *
 * The key is bound to the device: clearing app data or directly copying the data directory to another device makes the ciphertext undecryptable, which is an expected cost;
 * cross-device migration goes through backup (backups are exported in plaintext and re-encrypted with the new device's key on import).
 *
 * Plaintext compatibility: the decryption entry returns non-ciphertext input unchanged, for smooth migration of legacy plaintext data.
 */
object KeystoreCipher {
    private const val TAG = "KeystoreCipher"
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "aicode_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    /** String ciphertext prefix; values without this prefix are treated as legacy plaintext. */
    private const val STRING_PREFIX = "AICODEENC1:"

    /** Byte ciphertext file header; used to distinguish encrypted files from legacy plaintext files. */
    private val BYTE_MAGIC = "AICODEENC1\u0000".toByteArray(Charsets.US_ASCII)

    private val lock = Any()

    private fun secretKey(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generator.generateKey()
        }
    }

    fun encryptBytes(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return BYTE_MAGIC + iv + ciphertext
    }

    fun isEncryptedBytes(bytes: ByteArray): Boolean =
        bytes.size >= BYTE_MAGIC.size + IV_LEN && BYTE_MAGIC.indices.all { bytes[it] == BYTE_MAGIC[it] }

    fun decryptBytes(encrypted: ByteArray): ByteArray {
        require(isEncryptedBytes(encrypted)) { "Not encrypted data" }
        return runCatching {
            val iv = encrypted.copyOfRange(BYTE_MAGIC.size, BYTE_MAGIC.size + IV_LEN)
            val ciphertext = encrypted.copyOfRange(BYTE_MAGIC.size + IV_LEN, encrypted.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        }.getOrElse { e ->
            // When the key is invalidated (app data cleared / data directory copied to another device / Keystore reset / cross-package migration) the ciphertext cannot be decrypted.
            // It must degrade to empty instead of throwing -- callers are mostly inside a Room Flow map, where one exception would
            // crash the whole Flow and reach the uncaught handler, directly crashing the process (reproduced in production 2026-10-04).
            com.aicode.core.util.FileLogger.e(
                TAG, "Failed to decrypt bytes (key unavailable), degrading to empty: ${e.javaClass.simpleName}: ${e.message}"
            )
            ByteArray(0)
        }
    }

    fun isEncryptedString(value: String?): Boolean = value != null && value.startsWith(STRING_PREFIX)

    /** Encrypts a string; an empty string is returned unchanged, to avoid turning "not filled in" into non-empty ciphertext. */
    fun encryptString(plain: String): String {
        if (plain.isEmpty()) return plain
        return STRING_PREFIX + Base64.getEncoder().encodeToString(encryptBytes(plain.toByteArray(Charsets.UTF_8)))
    }

    /** Decrypts a string; non-ciphertext (legacy plaintext) is returned unchanged. When the key is invalidated it degrades to an empty string and logs (see [decryptBytes]). */
    fun decryptString(value: String): String {
        if (!isEncryptedString(value)) return value
        return runCatching {
            val body = Base64.getDecoder().decode(value.removePrefix(STRING_PREFIX))
            String(decryptBytes(body), Charsets.UTF_8)
        }.getOrElse { e ->
            com.aicode.core.util.FileLogger.e(
                TAG, "Failed to decrypt string (key unavailable), degrading to empty string: ${e.javaClass.simpleName}: ${e.message}"
            )
            ""
        }
    }
}
