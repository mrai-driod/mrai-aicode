package com.aicode.feature.agent.domain.container

import android.content.Context
import com.aicode.core.security.KeystoreCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** SSH login key (private key) entry: used to authenticate at connection time. */
data class SshLoginKey(
    val id: String,
    val name: String,
    /** Absolute path of the private key file (inside the app's private directory). */
    val path: String,
    /** Public key fingerprint (SHA256:...); null when an encrypted key cannot be parsed. */
    val fingerprint: String?,
    /** Private key passphrase (only needed for encrypted private keys); null when unencrypted. */
    val passphrase: String? = null
)

/** Persistent storage for SSH login keys (private keys); the private key files themselves live in the app's private directory. */
interface SshLoginKeyStore {
    fun entries(): List<SshLoginKey>
    fun add(key: SshLoginKey)
    fun remove(id: String)
}

private const val PREFS_NAME = "ssh_login_keys"

/** Implementation backed by the app's private SharedPreferences. */
@Singleton
class SharedPrefsSshLoginKeyStore @Inject constructor(
    @ApplicationContext context: Context
) : SshLoginKeyStore {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun nameKey(id: String) = "key_${id}_name"
    private fun pathKey(id: String) = "key_${id}_path"
    private fun fingerprintKey(id: String) = "key_${id}_fingerprint"
    private fun passphraseKey(id: String) = "key_${id}_passphrase"

    override fun entries(): List<SshLoginKey> =
        preferences.all.keys
            .filter { it.endsWith("_name") }
            .mapNotNull { raw ->
                val id = raw.removeSuffix("_name").removePrefix("key_")
                val name = preferences.getString(raw, null) ?: return@mapNotNull null
                val path = preferences.getString(pathKey(id), null) ?: return@mapNotNull null
                val passphrase = preferences.getString(passphraseKey(id), null)
                    ?.let { runCatching { KeystoreCipher.decryptString(it) }.getOrNull() }
                SshLoginKey(
                    id = id,
                    name = name,
                    path = path,
                    fingerprint = preferences.getString(fingerprintKey(id), null),
                    passphrase = passphrase
                )
            }

    override fun add(key: SshLoginKey) {
        val editor = preferences.edit()
            .putString(nameKey(key.id), key.name)
            .putString(pathKey(key.id), key.path)
            .putString(fingerprintKey(key.id), key.fingerprint)
        val passphrase = key.passphrase
        if (passphrase.isNullOrEmpty()) {
            editor.remove(passphraseKey(key.id))
        } else {
            editor.putString(passphraseKey(key.id), KeystoreCipher.encryptString(passphrase))
        }
        editor.apply()
    }

    override fun remove(id: String) {
        preferences.edit()
            .remove(nameKey(id))
            .remove(pathKey(id))
            .remove(fingerprintKey(id))
            .remove(passphraseKey(id))
            .apply()
    }
}
