package com.aicode.feature.agent.data.remote.anthropic

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url

interface AnthropicApi {
    @POST
    suspend fun createMessage(
        @Url url: String,
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: AnthropicMessageRequest
    ): AnthropicMessageResponse

    /** Streaming (SSE) completion: returns the raw response body, which the caller parses line by line as an event-stream. The request must carry stream=true. */
    @Streaming
    @POST
    fun streamMessage(
        @Url url: String,
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: AnthropicMessageRequest
    ): retrofit2.Call<ResponseBody>
}
