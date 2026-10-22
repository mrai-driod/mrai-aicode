package com.aicode.feature.agent.domain.container

import android.content.Context
import com.aicode.core.security.KeystoreCipher
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import net.schmizz.sshj.SSHClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted reading and writing of SSH private key files: keys are stored on disk as ciphertext encrypted with
 * the Keystore, and are decrypted in memory on read before being handed to sshj.
 *
 * Plaintext exists only in memory; even if a process inside the container reads the private key file it sees only
 * ciphertext. Legacy plaintext files are lazily encrypted and written back on first read.
 */
@Singleton
class SshPrivateKeyStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "SshPrivateKeyStore"
    }

    /** Encrypts and writes a private key file (overwriting the previous contents). */
    fun write(path: String, plain: ByteArray) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(KeystoreCipher.encryptBytes(plain))
    }

    /** Reads the private key plaintext PEM: ciphertext is decrypted; legacy plaintext is returned as-is and lazily encrypted and written back. */
    fun readPem(path: String): String {
        val file = File(path)
        val bytes = file.readBytes()
        if (!KeystoreCipher.isEncryptedBytes(bytes)) {
            // Legacy plaintext file: use it as plaintext this time, and try to encrypt and write it back (failure does not block the connection).
            runCatching { file.writeBytes(KeystoreCipher.encryptBytes(bytes)) }
                .onFailure { FileLogger.w(TAG, "Failed to encrypt-migrate plaintext private key: ${file.name}", it) }
            return String(bytes, Charsets.UTF_8)
        }
        return String(KeystoreCipher.decryptBytes(bytes), Charsets.UTF_8)
    }

    /** Computes the SHA-256 fingerprint of the public key corresponding to a private key; returns null when an encrypted private key (requiring a passphrase) cannot be parsed. */
    fun fingerprint(pem: String): String? = try {
        val keyProvider = SSHClient().loadKeys(pem, null, null)
        sshHostKeyFingerprint(keyProvider.getPublic())
    } catch (e: Exception) {
        null
    }
}
