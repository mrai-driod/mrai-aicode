package com.aicode.feature.settings.data

import android.content.Context
import com.aicode.core.security.KeystoreCipher
import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.data.local.dao.AIProviderDao
import com.aicode.feature.workspace.data.local.dao.RemoteConnectionDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time migration that encrypts and writes back historically plaintext sensitive fields: provider API keys (plus proxy passwords, custom request headers, script params),
 * remote connection passwords/passphrases, and SSH private key files. These fields were previously stored in plaintext; after switching to Keystore encryption, existing data must be migrated.
 *
 * Idempotent: already-encrypted fields are skipped, so it can be called repeatedly.
 */
@Singleton
class SecretEncryptionMigrator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val aiProviderDao: AIProviderDao,
    private val remoteConnectionDao: RemoteConnectionDao
) {
    private companion object {
        const val TAG = "SecretEncryptionMigrator"
    }

    suspend fun migrateIfNeeded() = withContext(Dispatchers.IO) {
        runCatching { migrateProviders() }.onFailure { FileLogger.w(TAG, "Failed to migrate provider sensitive fields", it) }
        runCatching { migrateConnections() }.onFailure { FileLogger.w(TAG, "Failed to migrate connection sensitive fields", it) }
        runCatching { migrateSshKeys() }.onFailure { FileLogger.w(TAG, "Failed to migrate SSH private key files", it) }
    }

    /** Returns the value as-is if already encrypted, otherwise encrypts it (idempotent). */
    private fun enc(value: String): String =
        if (KeystoreCipher.isEncryptedString(value)) value else KeystoreCipher.encryptString(value)

    private suspend fun migrateProviders() {
        val providers = aiProviderDao.getAllProvidersOnce()
        val migrated = providers.map { p ->
            p.copy(
                apiKey = enc(p.apiKey),
                apiKeys = enc(p.apiKeys),
                proxyPassword = enc(p.proxyPassword),
                customHeaders = enc(p.customHeaders),
                scriptParams = enc(p.scriptParams)
            )
        }
        if (migrated != providers) {
            aiProviderDao.insertAllProviders(migrated)
            FileLogger.i(TAG, "Encrypted sensitive fields for ${providers.size} providers")
        }
    }

    private suspend fun migrateConnections() {
        val connections = remoteConnectionDao.getAllConnectionsOnce()
        val migrated = connections.map { c ->
            c.copy(
                authData = if (c.authType.equals("PASSWORD", ignoreCase = true)) enc(c.authData) else c.authData,
                passphrase = c.passphrase?.let { enc(it) }
            )
        }
        if (migrated != connections) {
            remoteConnectionDao.insertAllConnections(migrated)
            FileLogger.i(TAG, "Encrypted sensitive fields for ${connections.size} connections")
        }
    }

    private fun migrateSshKeys() {
        val files = File(context.filesDir, "ssh_keys").listFiles() ?: return
        var count = 0
        files.forEach { file ->
            if (!file.isFile) return@forEach
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
            if (!KeystoreCipher.isEncryptedBytes(bytes)) {
                runCatching { file.writeBytes(KeystoreCipher.encryptBytes(bytes)) }
                    .onSuccess { count++ }
                    .onFailure { FileLogger.w(TAG, "Failed to encrypt private key file: ${file.name}", it) }
            }
        }
        if (count > 0) FileLogger.i(TAG, "Encrypted $count SSH private key files")
    }
}
