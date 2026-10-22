package com.aicode.feature.agent.data.remote.openai

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url

interface OpenAIApi {
    @POST
    suspend fun createChatCompletion(
        @Url url: String,
        @retrofit2.http.Header("Authorization") authorization: String,
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse

    /** Streaming (SSE) completion: returns the raw response body, which the caller parses line by line as an event-stream. The request must carry stream=true. */
    @Streaming
    @POST
    fun streamChatCompletion(
        @Url url: String,
        @retrofit2.http.Header("Authorization") authorization: String,
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: ChatCompletionRequest
    ): retrofit2.Call<ResponseBody>
    
    @POST
    suspend fun createResponses(
        @Url url: String,
        @retrofit2.http.Header("Authorization") authorization: String,
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: Any
    ): com.google.gson.JsonObject

    @Streaming
    @POST
    fun streamResponses(
        @Url url: String,
        @retrofit2.http.Header("Authorization") authorization: String,
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: Any
    ): retrofit2.Call<ResponseBody>

    /** Images API: generates images (POST /v1/images/generations). */
    @POST
    suspend fun createImage(
        @Url url: String,
        @retrofit2.http.Header("Authorization") authorization: String,
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: ImageGenerationRequest
    ): ImageGenerationResponse
}
