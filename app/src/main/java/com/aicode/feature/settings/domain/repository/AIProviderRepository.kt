package com.aicode.feature.settings.domain.repository

import com.aicode.feature.settings.domain.model.AIProviderConfig
import kotlinx.coroutines.flow.Flow

interface AIProviderRepository {
    fun getAllProviders(): Flow<List<AIProviderConfig>>
    suspend fun getProviderById(id: String): AIProviderConfig?
    suspend fun saveProvider(provider: AIProviderConfig)
    suspend fun deleteProvider(id: String)
    suspend fun setSelectedModel(id: String, model: String)
    suspend fun updateModels(id: String, models: List<String>)
    suspend fun setProviderEnabled(id: String, isEnabled: Boolean)

    /** Reorders providers in the order passed in (reassigns sortOrder and saves them as a whole). */
    suspend fun reorderProviders(providers: List<AIProviderConfig>)
}
