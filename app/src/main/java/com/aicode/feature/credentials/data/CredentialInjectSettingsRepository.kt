package com.aicode.feature.credentials.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for the "auto-inject git credentials to remote server" switch, isolated per server (IP + port).
 *
 * Each remote server is remembered independently: the switch state and "has been asked" flag do not affect each other, so enabling it for server A does not
 * accidentally apply the same state when connecting to server B, and the first-time prompt is shown only once per server.
 */
@Singleton
class CredentialInjectSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val PREFS = "credential_inject_prefs"
        const val KEY_AUTO_INJECT = "auto_inject_cred_"
        const val KEY_ASKED = "asked_auto_inject_"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Server identifier hash: first 8 characters of MD5("host:port"), in the same style as the memory directory hash. */
    fun serverKey(host: String, port: Int): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest("$host:$port".toByteArray(Charsets.UTF_8))
        return java.math.BigInteger(1, digest).toString(16).padStart(32, '0').take(8)
    }

    fun isAutoInjectEnabled(serverKey: String): Boolean =
        prefs.getBoolean(KEY_AUTO_INJECT + serverKey, false)

    fun setAutoInject(serverKey: String, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_INJECT + serverKey, enabled).apply()
    }

    fun hasAsked(serverKey: String): Boolean =
        prefs.getBoolean(KEY_ASKED + serverKey, false)

    fun markAsked(serverKey: String) {
        prefs.edit().putBoolean(KEY_ASKED + serverKey, true).apply()
    }
}