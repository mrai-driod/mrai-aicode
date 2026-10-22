package com.aicode.feature.settings.data.local

import android.content.Context

/**
 * Persistence for the model picker sheet's "collapse by provider" expanded state: remembers which providers the user collapsed, kept across reopening the sheet and restarts.
 *
 * Stores the set of collapsed providers (empty by default = all expanded); ids left behind after a provider is deleted have no side effects. The forced expansion during search is not written to this state.
 */
class ModelSheetCollapseStore(context: Context) {

    private val prefs = context.getSharedPreferences("model_sheet_collapse", Context.MODE_PRIVATE)

    fun collapsedProviderIds(): Set<String> =
        prefs.getStringSet(KEY, null)?.toSet() ?: emptySet()

    /** Write a copy of the new set: SharedPreferences must not reuse an already-stored instance. */
    fun save(collapsedProviderIds: Set<String>) {
        prefs.edit().putStringSet(KEY, HashSet(collapsedProviderIds)).apply()
    }

    private companion object {
        const val KEY = "collapsed_provider_ids"
    }
}