package com.aicode.feature.settings.data.remote

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.provider.joinUrl
import com.aicode.feature.settings.domain.model.ProviderType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** Result of fetching the model list, containing the parsed model list and full debug information. */
data class FetchModelsResult(
    val models: List<String>,
    val debugInfo: ModelTestResult
)

/** Exception when fetching models, carrying debug information. */
class FetchModelsException(
    override val message: String,
    val debugInfo: ModelTestResult?
) : RuntimeException(message)

/** Result of testing model connectivity, containing full request/response debug information. */
data class ModelTestResult(
    val success: Boolean,
    val latencyMs: Long,
    val message: String,
    val requestUrl: String = "",
    val requestHeaders: Map<String, String> = emptyMap(),
    val requestBody: String = "",
    val responseCode: Int = 0,
    val responseHeaders: Map<String, String> = emptyMap(),
    val responseBody: String = "",
    val errorDetail: String = ""
)

/**
 * Calls the provider's REST API directly via OkHttp to fetch the model list and test connectivity,
 * reusing the global OkHttpClient, independent of the Retrofit adapter used for chat.
 */
@Singleton
class ModelApiService @Inject constructor(
    private val client: OkHttpClient,
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Fetches the provider's available model list (OpenAI-compatible / Anthropic both use GET /v1/models, Gemini uses GET /v1beta/models). */
    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String,
        type: ProviderType,
        useFullUrl: Boolean = false,
        customHeaders: Map<String, String> = emptyMap()
    ): Result<FetchModelsResult> = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        var capturedUrl = ""
        var capturedHeaders: Map<String, String> = emptyMap()
        runCatching {
            if (apiKey.isBlank()) error(context.getString(R.string.provider_api_key_required))

            val modelsPath = if (type == ProviderType.GEMINI) "v1beta/models" else "v1/models"
            val url = if (useFullUrl) baseUrl else joinUrl(baseUrl, modelsPath)
            capturedUrl = url
            val request = Request.Builder()
                .url(url)
                .applyAuth(apiKey, type)
                .applyCustomHeaders(customHeaders)
                .get()
                .build()

            val reqHeadersMap = request.headers.names().associateWith { name ->
                val raw = request.header(name).orEmpty()
                if (name.equals("Authorization", ignoreCase = true) || name.equals("x-api-key", ignoreCase = true)) {
                    if (raw.length > 12) raw.take(7) + "..." + raw.takeLast(4) else "***"
                } else {
                    raw
                }
            }
            capturedHeaders = reqHeadersMap

            client.newCall(request).execute().use { response ->
                val latency = (System.nanoTime() - start) / 1_000_000
                val body = response.body?.string().orEmpty()
                val respHeadersMap = response.headers.names().associateWith { response.header(it).orEmpty() }

                if (!response.isSuccessful) {
                    val debug = ModelTestResult(
                        success = false,
                        latencyMs = latency,
                        message = "HTTP ${response.code}: ${body.take(160)}",
                        requestUrl = url,
                        requestHeaders = reqHeadersMap,
                        requestBody = "",
                        responseCode = response.code,
                        responseHeaders = respHeadersMap,
                        responseBody = body,
                        errorDetail = body
                    )
                    throw FetchModelsException("HTTP ${response.code}: ${body.take(200)}", debug)
                }

                val jsonObj = json.parseToJsonElement(body).jsonObject
                val data = if (type == ProviderType.GEMINI) {
                    jsonObj["models"]?.jsonArray
                } else {
                    jsonObj["data"]?.jsonArray
                } ?: run {
                    val debug = ModelTestResult(
                        success = false,
                        latencyMs = latency,
                        message = context.getString(R.string.provider_fetch_missing_fields),
                        requestUrl = url,
                        requestHeaders = reqHeadersMap,
                        requestBody = "",
                        responseCode = response.code,
                        responseHeaders = respHeadersMap,
                        responseBody = body,
                        errorDetail = context.getString(R.string.provider_fetch_missing_fields_detail, body)
                    )
                    throw FetchModelsException(context.getString(R.string.provider_fetch_missing_fields), debug)
                }

                val modelList = data.mapNotNull { 
                        it.jsonObject[if (type == ProviderType.GEMINI) "name" else "id"]?.jsonPrimitive?.contentOrNull 
                    }
                    .map { if (type == ProviderType.GEMINI) it.removePrefix("models/") else it }
                    .filter { it.isNotBlank() }
                    .sorted()

                val debug = ModelTestResult(
                    success = true,
                    latencyMs = latency,
                    message = context.getString(R.string.provider_fetch_success, modelList.size, latency),
                    requestUrl = url,
                    requestHeaders = reqHeadersMap,
                    requestBody = "",
                    responseCode = response.code,
                    responseHeaders = respHeadersMap,
                    responseBody = body
                )
                FetchModelsResult(modelList, debug)
            }
        }.recoverCatching { e ->
            if (e is FetchModelsException) {
                throw e
            }
            val latency = (System.nanoTime() - start) / 1_000_000
            val fallback = context.getString(R.string.settings_models_fetch_failed)
            val debug = ModelTestResult(
                success = false,
                latencyMs = latency,
                message = e.message ?: fallback,
                requestUrl = capturedUrl,
                requestHeaders = capturedHeaders,
                errorDetail = e.stackTraceToString()
            )
            throw FetchModelsException(e.message ?: fallback, debug)
        }
    }

    /** Sends a very short request to verify that the key + model + endpoint are usable, and returns the latency. */
    suspend fun testModel(
        baseUrl: String,
        apiKey: String,
        type: ProviderType,
        useFullUrl: Boolean,
        useResponseApi: Boolean,
        model: String,
        customHeaders: Map<String, String> = emptyMap()
    ): ModelTestResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            if (apiKey.isBlank()) error(context.getString(R.string.provider_api_key_required))

            val (url, payload) = when (type) {
                ProviderType.ANTHROPIC -> {
                    val u = if (useFullUrl) baseUrl else joinUrl(baseUrl, "v1/messages")
                    u to """{"model":${model.jsonStr()},"max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""
                }
                ProviderType.GEMINI -> {
                    // The Interactions request body is `input` (a sequence of steps or a plain string), so it must also switch to the
                    // v1beta/interactions endpoint; still hitting generateContent will be rejected for missing `contents`.
                    // Image models (the Nano Banana family) only support Interactions, so the switch is forced regardless of whether the toggle is on.
                    if (useResponseApi || model.endsWith("-image", ignoreCase = true)) {
                        val u = if (useFullUrl) baseUrl else joinUrl(baseUrl, "v1beta/interactions")
                        u to """{"model":${model.jsonStr()},"input":"hi","store":false}"""
                    } else {
                        val u = if (useFullUrl) {
                            baseUrl
                        } else {
                            val path = if (baseUrl.trimEnd('/').endsWith(model)) {
                                baseUrl.trimEnd('/') + ":generateContent"
                            } else {
                                joinUrl(baseUrl, "v1beta/models/$model:generateContent")
                            }
                            path
                        }
                        u to """{"contents":[{"role":"user","parts":[{"text":"hi"}]}]}"""
                    }
                }
                else -> {
                    // The Responses API request body uses the `input` format, so it must also switch to the v1/responses endpoint;
                    // still hitting chat/completions will be rejected by the server with "missing field `messages`".
                    val path = if (useResponseApi) "v1/responses" else "v1/chat/completions"
                    val u = if (useFullUrl) baseUrl else joinUrl(baseUrl, path)
                    if (useResponseApi) {
                        u to """{"model":${model.jsonStr()},"input":[{"role":"user","content":"hi"}]}"""
                    } else {
                        u to """{"model":${model.jsonStr()},"max_completion_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""
                    }
                }
            }

            val request = Request.Builder()
                .url(url)
                .applyAuth(apiKey, type)
                .applyCustomHeaders(customHeaders)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            val reqHeadersMap = request.headers.names().associateWith { name ->
                val raw = request.header(name).orEmpty()
                if (name.equals("Authorization", ignoreCase = true) || name.equals("x-api-key", ignoreCase = true)) {
                    if (raw.length > 12) raw.take(7) + "..." + raw.takeLast(4) else "***"
                } else {
                    raw
                }
            }

            client.newCall(request).execute().use { response ->
                val latency = (System.nanoTime() - start) / 1_000_000
                val body = response.body?.string().orEmpty()
                val respHeadersMap = response.headers.names().associateWith { response.header(it).orEmpty() }

                if (response.isSuccessful) {
                    ModelTestResult(
                        success = true,
                        latencyMs = latency,
                        message = context.getString(R.string.provider_test_success, latency),
                        requestUrl = url,
                        requestHeaders = reqHeadersMap,
                        requestBody = payload,
                        responseCode = response.code,
                        responseHeaders = respHeadersMap,
                        responseBody = body
                    )
                } else {
                    ModelTestResult(
                        success = false,
                        latencyMs = latency,
                        message = "HTTP ${response.code}: ${body.take(160)}",
                        requestUrl = url,
                        requestHeaders = reqHeadersMap,
                        requestBody = payload,
                        responseCode = response.code,
                        responseHeaders = respHeadersMap,
                        responseBody = body,
                        errorDetail = body
                    )
                }
            }
        } catch (e: Exception) {
            val latency = (System.nanoTime() - start) / 1_000_000
            ModelTestResult(
                success = false,
                latencyMs = latency,
                message = e.message ?: context.getString(R.string.chat_error_title),
                errorDetail = e.stackTraceToString()
            )
        }
    }

    private fun Request.Builder.applyAuth(apiKey: String, type: ProviderType): Request.Builder =
        when (type) {
            ProviderType.ANTHROPIC -> this
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
            ProviderType.GEMINI -> this
                .header("x-goog-api-key", apiKey)
            else -> this.header("Authorization", "Bearer $apiKey")
        }

    private fun Request.Builder.applyCustomHeaders(customHeaders: Map<String, String>): Request.Builder =
        this.apply { customHeaders.forEach { (name, value) -> if (name.isNotBlank()) header(name, value) } }

    /** Converts to a safe JSON string literal (including quotes and correct escaping). */
    private fun String.jsonStr(): String = JsonPrimitive(this).toString()
}
