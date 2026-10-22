package com.aicode.feature.agent.domain.container

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Persistent storage for SSH host fingerprints, keyed by `host:port`. */
interface SshHostKeyStore {
    fun get(host: String, port: Int): String?
    fun save(host: String, port: Int, fingerprint: String)
    fun remove(host: String, port: Int)
    fun entries(): Map<String, String>
}

private const val PREFS_NAME = "ssh_host_keys"

/** Implementation backed by the app's private SharedPreferences. */
@Singleton
class SharedPrefsSshHostKeyStore @Inject constructor(
    @ApplicationContext context: Context
) : SshHostKeyStore {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(host: String, port: Int): String = "$host:$port"

    override fun get(host: String, port: Int): String? = preferences.getString(key(host, port), null)

    override fun save(host: String, port: Int, fingerprint: String) {
        preferences.edit().putString(key(host, port), fingerprint).apply()
    }

    override fun remove(host: String, port: Int) {
        preferences.edit().remove(key(host, port)).apply()
    }

    override fun entries(): Map<String, String> = preferences.all.mapNotNull { (key, value) ->
        (value as? String)?.let { key to it }
    }.toMap()
}

/** Computes the SHA-256 fingerprint of a host public key, in the form `SHA256:<base64>`, matching the format of ssh-keygen -lf. */
fun sshHostKeyFingerprint(key: PublicKey): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

/** The host key needs user confirmation (first connection, or the saved fingerprint changed). changed=false means first time, true means the fingerprint changed. */
class SshHostKeyPendingException(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val changed: Boolean
) : Exception("SSH host key needs confirmation: $host:$port")

class SshHostKeyVerifier @Inject constructor(
    private val store: SshHostKeyStore
) : net.schmizz.sshj.transport.verification.HostKeyVerifier {
    /** Details of the most recent verification failure (pending confirmation); sshj wraps exceptions thrown by verify into a TransportException,
     *  so the connection layer cannot catch them by exception type and they are passed through this field instead. consumePending clears it after reading. */
    @Volatile
    private var pending: SshHostKeyPendingException? = null

    fun consumePending(): SshHostKeyPendingException? {
        val p = pending
        pending = null
        return p
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): MutableList<String> = mutableListOf()

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        pending = null
        val fingerprint = sshHostKeyFingerprint(key)
        val saved = store.get(hostname, port)
        if (saved == null) {
            val e = SshHostKeyPendingException(hostname, port, key.algorithm, fingerprint, changed = false)
            pending = e
            throw e
        }
        if (saved != fingerprint) {
            val e = SshHostKeyPendingException(hostname, port, key.algorithm, fingerprint, changed = true)
            pending = e
            throw e
        }
        return true
    }
}
