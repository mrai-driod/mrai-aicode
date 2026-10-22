package com.aicode.core.datastore

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.aicode.core.util.FileLogger

private const val TAG = "PreferencesCorruption"

/**
 * Corruption handler shared by all preference DataStores.
 *
 * Without a handler, [androidx.datastore.core.CorruptionException] is thrown directly to the caller, and landing in
 * a ViewModel/repository coroutine it crashes the whole app. Worse, any IOException on read is wrapped by protobuf
 * into InvalidProtocolBufferException, then thrown by PreferencesSerializer as proto corruption,
 * so a single transient IO failure can crash the app (example: EBADF).
 *
 * This uniformly changes it to "discard the file and rebuild with defaults": a single settings file that is corrupted or unreadable only loses that group of settings,
 * without crashing or repeatedly triggering on every launch; the exception is written to the log for troubleshooting.
 */
val preferencesCorruptionHandler: ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { error ->
        FileLogger.e(TAG, "Preferences file unavailable, reset to defaults", error)
        emptyPreferences()
    }
