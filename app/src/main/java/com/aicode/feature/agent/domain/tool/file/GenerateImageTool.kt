package com.aicode.feature.agent.domain.tool.file

import android.util.Base64
import com.aicode.core.util.AILogger
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.ImageGenerationRequest
import com.aicode.feature.agent.data.remote.openai.ImageGenerationResponse
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.provider.enrichWithHttpErrorBody
import com.aicode.feature.agent.domain.provider.isKeySwitchFailure
import com.aicode.feature.agent.domain.provider.joinUrl
import com.aicode.feature.agent.domain.provider.parseInteractionSteps
import com.aicode.feature.agent.domain.provider.resolveCustomHeaders
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.settings.data.repository.ImageGenModelSettingsRepository
import com.aicode.feature.settings.data.repository.ProviderKeyRotator
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/**
 * Image generation tool: calls the OpenAI Images API (POST /v1/images/generations) to generate a bitmap from a prompt.
 *
 * Uses the provider + model configured in "Settings -> Default model -> Image generation model" (such as gpt-image-1 / dall-e-3);
 * when not configured, returns an error with setup guidance. The generated images:
 * - are always returned to the workflow as [AgentImage], so the chat area can render them directly;
 * - when `output_path` is passed, they are also written to the workspace (with a numeric suffix added automatically for multiple images).
 *
 * The request body is compatible with the dall-e family (explicit `response_format=b64_json`) and the GPT image family
 * (which does not support that parameter and always returns base64); on failure, the error message is returned as-is as the tool result.
 */
class GenerateImageTool @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val imageGenModelSettingsRepository: ImageGenModelSettingsRepository,
    private val aiProviderRepository: AIProviderRepository,
    private val openAIApi: OpenAIApi,
    private val geminiApi: GeminiApi,
    private val httpClient: OkHttpClient,
    private val keyRotator: ProviderKeyRotator
) : AbstractContextualTool() {

    override val name = "generateImage"
    override val description = "Generate an image from a text description and display it to the user in the chat area. Supports specifying size and count, and optionally saving to a workspace file via output_path."
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.NETWORK_WRITE, ToolCapability.WRITE_WORKSPACE)
    override val parameters = mapOf(
        "prompt" to ToolParameter(
            name = "prompt",
            type = ParameterType.STRING,
            description = "Detailed description of the image content (subject, style, composition, color tone, mood, etc.).",
            required = true
        ),
        "size" to ToolParameter(
            name = "size",
            type = ParameterType.STRING,
            description = "Image size, e.g. 1024x1024 (default), 1024x1536, 1536x1024.",
            required = false
        ),
        "n" to ToolParameter(
            name = "n",
            type = ParameterType.INTEGER,
            description = "Number of images to generate, default 1, max 4. dall-e-3 supports only 1.",
            required = false
        ),
        "quality" to ToolParameter(
            name = "quality",
            type = ParameterType.STRING,
            description = "Quality: GPT Image series supports low / medium / high / auto (default auto); dall-e-3 supports standard / hd (default standard).",
            required = false,
            enum = listOf("low", "medium", "high", "auto", "standard", "hd")
        ),
        "background" to ToolParameter(
            name = "background",
            type = ParameterType.STRING,
            description = "Background: transparent / opaque / auto (default auto), supported only by GPT Image series models; transparent requires png or webp output format.",
            required = false,
            enum = listOf("transparent", "opaque", "auto")
        ),
        "moderation" to ToolParameter(
            name = "moderation",
            type = ParameterType.STRING,
            description = "Content moderation level: low / auto (default auto), supported only by GPT Image series models.",
            required = false,
            enum = listOf("low", "auto")
        ),
        "style" to ToolParameter(
            name = "style",
            type = ParameterType.STRING,
            description = "Style: vivid / natural, supported only by dall-e-3.",
            required = false,
            enum = listOf("vivid", "natural")
        ),
        "output_format" to ToolParameter(
            name = "output_format",
            type = ParameterType.STRING,
            description = "Output format: png / jpeg / webp (default png), supported only by GPT Image series models.",
            required = false,
            enum = listOf("png", "jpeg", "webp")
        ),
        "output_path" to ToolParameter(
            name = "output_path",
            type = ParameterType.STRING,
            description = "Save path (optional). If not provided, saves to ~/.aicode/generated-images/ by default; if provided, saves to the specified path, e.g. ~/workspace/assets/image.png.",
            required = false
        )
    )

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val outputPath = args["output_path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val count = args["n"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "Confirm image generation",
            summary = "AI requests to call the image generation model to generate an image",
            details = if (outputPath.isBlank()) {
                "Prompt: $prompt\nCount: $count\nSave to: $DEFAULT_OUTPUT_DIR/"
            } else {
                "Prompt: $prompt\nCount: $count\nSave to: $outputPath"
            },
            argsPreview = argsPreview
        )
    }

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult = withContext(Dispatchers.IO) {
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (prompt.isEmpty()) {
            return@withContext ToolResult.Error("Missing required parameter: prompt", "MISSING_PROMPT")
        }
        val size = args["size"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
            ?: DEFAULT_SIZE
        val n = args["n"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
        if (n !in 1..MAX_IMAGES) {
            return@withContext ToolResult.Error("n must be between 1 and $MAX_IMAGES, currently $n", "INVALID_PARAMS")
        }
        val quality = args["quality"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.ifBlank { null }
        val background = args["background"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.ifBlank { null }
        val moderation = args["moderation"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.ifBlank { null }
        val style = args["style"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.ifBlank { null }
        val outputFormat = args["output_format"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.ifBlank { null }
        val outputPath = args["output_path"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }

        var seq = 0
        var lastProviderId = ""
        var activeApiKey = ""
        return@withContext try {
            val provider = resolveImageGenProvider()
            lastProviderId = provider.id
            activeApiKey = keyRotator.activeKey(provider, context.sessionId) ?: provider.firstUsableApiKey
            val model = provider.effectiveModel
            // Official Gemini image models use the Interactions protocol (v1beta/interactions); OpenAI's
            // /v1/images/generations endpoint cannot reach them, so when a Gemini image-generation model is set, switch to a separate channel;
            // OpenAI-specific parameters (size/quality/background, etc.) are ignored in the Gemini branch.
            if (provider.type == ProviderType.GEMINI) {
                return@withContext generateViaGemini(provider, activeApiKey, prompt, n, outputPath, context.sessionId)
            }
            val isGptImage = model.startsWith("gpt-image", ignoreCase = true)
            val isDalle2 = model.equals("dall-e-2", ignoreCase = true)
            val isDalle3 = model.equals("dall-e-3", ignoreCase = true)
            GenerateImageTool.validateImageParams(
                model, isGptImage, isDalle2, isDalle3, n, quality,
                background, moderation, style, outputFormat
            )
?.let {
                return@withContext ToolResult.Error(it, "INVALID_PARAMS")
            }
            FileLogger.i(TAG, "generateImage provider=${provider.id} model=$model prompt=$prompt n=$n size=$size")

            val url = if (provider.useFullUrl) provider.baseUrl else joinUrl(provider.baseUrl, "v1/images/generations")
            // Compatibility rules:
            // 1. Only genuine official GPT Image family models (such as gpt-image-1 / gpt-image-1.5) support output_format, and they return base64 by default and do not support response_format;
            // 2. Other models (DALL-E, and various aggregation gateways such as agnes/OneAPI/NewAPI/Flux/SD) must be passed response_format=b64_json to return base64,
            //    and must absolutely not be passed output_format (passing it gets an HTTP 400 from the gateway: output_format is not supported).
            val request = ImageGenerationRequest(
                model = model,
                prompt = prompt,
                n = n,
                size = size,
                quality = quality,
                response_format = if (!isGptImage) "b64_json" else null,
                output_format = if (isGptImage) outputFormat else null,
                background = if (isGptImage) background else null,
                moderation = if (isGptImage) moderation else null,
                style = if (isDalle3) style else null
            )

            seq = AILogger.logRequest(context.sessionId, provider.id, model, "POST", url, request)

            val response = openAIApi.createImage(
                url = url,
                authorization = "Bearer $activeApiKey",
                extraHeaders = resolveCustomHeaders(provider.customHeaders, context.sessionId, activeApiKey),
                request = request
            )
            AILogger.logResponse(context.sessionId, provider.id, response, seq)
            buildSuccess(response, n, outputPath, model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            if (activeApiKey.isNotEmpty() && enriched.isKeySwitchFailure()) {
                keyRotator.reportFailure(lastProviderId, context.sessionId, activeApiKey)
            }
            FileLogger.e(TAG, "generateImage failed", enriched)
            AILogger.logError(context.sessionId, lastProviderId, enriched, seq)
            ToolResult.Error(enriched.message ?: "Image generation failed", "IMAGE_GEN_FAILED")
        }
    }

    private suspend fun resolveImageGenProvider(): com.aicode.feature.settings.domain.model.AIProviderConfig {
        val providerId = imageGenModelSettingsRepository.getImageGenProviderId().trim()
        val model = imageGenModelSettingsRepository.getImageGenModel().trim()
        if (providerId.isEmpty() || model.isEmpty()) {
            throw IllegalStateException("Image generation model not configured: please go to Settings → Default Model → Image Generation Model and select a model that supports image output (e.g. gpt-image-1 / dall-e-3).")
        }
        val config = aiProviderRepository.getProviderById(providerId)
            ?: throw IllegalStateException("The provider configured for the image generation model does not exist or has been deleted, please reselect in Settings → Default Model → Image Generation Model.")
        if (!config.isEnabled) throw IllegalStateException("The provider \"${config.name}\" configured for the image generation model is not enabled.")
        if (!config.hasUsableApiKey) throw IllegalStateException("The provider \"${config.name}\" configured for the image generation model has no API Key configured.")
        return config.copy(selectedModel = model)
    }

    private suspend fun buildSuccess(
        response: ImageGenerationResponse,
        requestedN: Int,
        outputPath: String?,
        model: String
    ): ToolResult {
        if (response.data.isEmpty()) {
            return ToolResult.Error("Image generation service did not return image data", "EMPTY_RESULT")
        }
        val effectiveBasePath = outputPath ?: createDefaultBasePath()
        val overwrite = outputPath != null

        val agentImages = mutableListOf<AgentImage>()
        val savedDisplayPaths = mutableListOf<String>()
        val filesList = mutableListOf<JsonObject>()
        var totalBytes = 0L
        var failedCount = 0
        response.data.forEachIndexed { index, item ->
            try {
                val bytes: ByteArray
                val base64: String
                when {
                    !item.b64_json.isNullOrBlank() -> {
                        base64 = item.b64_json
                        bytes = decodeImageBase64(base64)
                    }
                    !item.url.isNullOrBlank() -> {
                        bytes = downloadImageBytes(item.url)
                        base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
                    else -> throw IOException("Result is missing both base64 and URL")
                }
                totalBytes = checkedTotalBytes(totalBytes, bytes.size)
                persistImageBytes(
                    bytes, base64, effectiveBasePath, overwrite,
                    agentImages, savedDisplayPaths, filesList
                )
            } catch (e: Exception) {
                failedCount++
                FileLogger.w(TAG, "Failed to process generated image result ${index + 1}", e)
            }
        }

        if (agentImages.isEmpty()) {
            return ToolResult.Error("Failed to obtain the generated image content (image is invalid, too large, or download failed)", "EMPTY_RESULT")
        }
        return buildImageResult(
            agentImages, savedDisplayPaths, filesList, model, requestedN,
            outputPath, failedCount, usage = response.usage
        )
    }

    /**
     * Gemini image-generation channel: follows the provider's useResponseApi setting. When on, it uses the Interactions non-streaming endpoint
     * (parsing images from the `steps` timeline); when off (the default), it uses generateContent (natively supported by image models, parsing
     * from the inlineData in candidates[].parts). Multiple images use multiple calls; OpenAI-specific parameters are ignored.
     */
    private suspend fun generateViaGemini(
        provider: com.aicode.feature.settings.domain.model.AIProviderConfig,
        apiKey: String,
        prompt: String,
        n: Int,
        outputPath: String?,
        sessionId: String?
    ): ToolResult {
        val model = provider.effectiveModel
        FileLogger.i(TAG, "generateImage(Gemini) provider=${provider.id} model=$model prompt=$prompt n=$n")
        return if (provider.useResponseApi) {
            generateViaGeminiInteractions(provider, apiKey, model, prompt, n, outputPath, sessionId)
        } else {
            generateViaGeminiGenerateContent(provider, apiKey, model, prompt, n, outputPath, sessionId)
        }
    }

    /** Interactions channel: non-streaming createInteraction; extracts images from the response steps. */
    private suspend fun generateViaGeminiInteractions(
        provider: com.aicode.feature.settings.domain.model.AIProviderConfig,
        apiKey: String,
        model: String,
        prompt: String,
        n: Int,
        outputPath: String?,
        sessionId: String?
    ): ToolResult {
        val url = if (provider.useFullUrl) provider.baseUrl else joinUrl(provider.baseUrl, "v1beta/interactions")
        val effectiveBasePath = outputPath ?: createDefaultBasePath()
        val overwrite = outputPath != null

        val agentImages = mutableListOf<AgentImage>()
        val savedDisplayPaths = mutableListOf<String>()
        val filesList = mutableListOf<JsonObject>()
        var totalBytes = 0L
        var failedCount = 0
        var lastSeq = 0
        try {
            for (index in 0 until n) {
                val request = mapOf("model" to model, "input" to prompt, "store" to false)
                lastSeq = AILogger.logRequest(sessionId, provider.id, model, "POST", url, request)
                val response = geminiApi.createInteraction(
                    url = url,
                    apiKey = apiKey,
                    extraHeaders = resolveCustomHeaders(provider.customHeaders, sessionId, apiKey),
                    request = request
                )
                AILogger.logResponse(sessionId, provider.id, response, lastSeq)
                val status = response.get("status")?.takeIf { it.isJsonPrimitive }?.asString
                if (status == "failed" || status == "cancelled" || status == "budget_exceeded") {
                    val reason = response.get("errors")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: "Gemini image generation failed (status=$status)"
                    failedCount += n - index
                    return if (agentImages.isEmpty()) {
                        ToolResult.Error(reason, "IMAGE_GEN_FAILED")
                    } else {
                        buildGeminiResult(agentImages, savedDisplayPaths, filesList, model, n, outputPath, failedCount, reason)
                    }
                }
                val beforeCount = agentImages.size
                val parsed = parseInteractionSteps(response.get("steps")?.takeIf { it.isJsonArray }?.asJsonArray)
                parsed.images.forEach { image ->
                    runCatching {
                        totalBytes += persistImage(
                            image.base64Data, effectiveBasePath, overwrite, agentImages,
                            savedDisplayPaths, filesList, totalBytes
                        )
                    }.onFailure { FileLogger.w(TAG, "Failed to process Gemini image-generation result", it) }
                }
                if (agentImages.size == beforeCount) failedCount++
                if (index == 0 && agentImages.isEmpty() && n > 1) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            if (enriched.isKeySwitchFailure()) keyRotator.reportFailure(provider.id, sessionId, apiKey)
            FileLogger.e(TAG, "generateImage(Gemini) failed", enriched)
            AILogger.logError(sessionId, provider.id, enriched, lastSeq)
            if (agentImages.isNotEmpty()) {
                return buildGeminiResult(
                    agentImages, savedDisplayPaths, filesList, model, n, outputPath,
                    failedCount.coerceAtLeast(n - agentImages.size), enriched.message
                )
            }
            return ToolResult.Error(enriched.message ?: "Gemini image generation call failed", "IMAGE_GEN_FAILED")
        }

        return buildGeminiResult(agentImages, savedDisplayPaths, filesList, model, n, outputPath, failedCount)
    }

    /**
     * generateContent channel: image models (Nano Banana) natively support this endpoint, and the image is returned as a whole block in
     * the inlineData (camelCase) of candidates[].content.parts.
     */
    private suspend fun generateViaGeminiGenerateContent(
        provider: com.aicode.feature.settings.domain.model.AIProviderConfig,
        apiKey: String,
        model: String,
        prompt: String,
        n: Int,
        outputPath: String?,
        sessionId: String?
    ): ToolResult {
        val url = if (provider.useFullUrl) provider.baseUrl else joinUrl(provider.baseUrl, "v1beta/models/$model:generateContent")
        val effectiveBasePath = outputPath ?: createDefaultBasePath()
        val overwrite = outputPath != null

        val agentImages = mutableListOf<AgentImage>()
        val savedDisplayPaths = mutableListOf<String>()
        val filesList = mutableListOf<JsonObject>()
        var totalBytes = 0L
        var failedCount = 0
        var lastSeq = 0
        try {
            for (index in 0 until n) {
                val request = mapOf(
                    "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to prompt))))
                )
                lastSeq = AILogger.logRequest(sessionId, provider.id, model, "POST", url, request)
                val response = geminiApi.generateContent(
                    url = url,
                    apiKey = apiKey,
                    extraHeaders = resolveCustomHeaders(provider.customHeaders, sessionId, apiKey),
                    request = request
                )
                AILogger.logResponse(sessionId, provider.id, response, lastSeq)
                val beforeCount = agentImages.size
                extractGenerateContentImageData(response).forEach { base64 ->
                    runCatching {
                        totalBytes += persistImage(
                            base64, effectiveBasePath, overwrite, agentImages,
                            savedDisplayPaths, filesList, totalBytes
                        )
                    }.onFailure { FileLogger.w(TAG, "Failed to process Gemini image-generation result", it) }
                }
                if (agentImages.size == beforeCount) failedCount++
                if (index == 0 && agentImages.isEmpty() && n > 1) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            if (enriched.isKeySwitchFailure()) keyRotator.reportFailure(provider.id, sessionId, apiKey)
            FileLogger.e(TAG, "generateImage(Gemini) failed", enriched)
            AILogger.logError(sessionId, provider.id, enriched, lastSeq)
            if (agentImages.isNotEmpty()) {
                return buildGeminiResult(
                    agentImages, savedDisplayPaths, filesList, model, n, outputPath,
                    failedCount.coerceAtLeast(n - agentImages.size), enriched.message
                )
            }
            return ToolResult.Error(enriched.message ?: "Gemini image generation call failed", "IMAGE_GEN_FAILED")
        }

        return buildGeminiResult(agentImages, savedDisplayPaths, filesList, model, n, outputPath, failedCount)
    }

    /** Extracts the list of image base64 strings from a generateContent response (accepts both inlineData / inline_data). */
    private fun extractGenerateContentImageData(response: com.google.gson.JsonObject): List<String> {
        val parts = response.get("candidates")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("content")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("parts")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        return parts.mapNotNull { partEl ->
            val part = partEl.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val inline = part.get("inlineData") ?: part.get("inline_data")
            (inline?.takeIf { it.isJsonObject }?.asJsonObject)?.get("data")
                ?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() }
        }
    }

    /** Writes one image to disk: base64 -> writes the file in its real format -> assembles the AgentImage and files attachments. */
    private fun persistImage(
        base64: String,
        effectiveBasePath: String,
        overwrite: Boolean,
        agentImages: MutableList<AgentImage>,
        savedDisplayPaths: MutableList<String>,
        filesList: MutableList<JsonObject>,
        currentTotalBytes: Long
    ): Long {
        val bytes = decodeImageBase64(base64)
        checkedTotalBytes(currentTotalBytes, bytes.size)
        persistImageBytes(
            bytes, base64, effectiveBasePath, overwrite,
            agentImages, savedDisplayPaths, filesList
        )
        return bytes.size.toLong()
    }

    private fun persistImageBytes(
        bytes: ByteArray,
        base64: String,
        effectiveBasePath: String,
        overwrite: Boolean,
        agentImages: MutableList<AgentImage>,
        savedDisplayPaths: MutableList<String>,
        filesList: MutableList<JsonObject>
    ) {
        if (bytes.isEmpty()) throw IOException("Image content is empty")
        val format = detectImageFormat(bytes)
        val targetPath = buildTargetPath(effectiveBasePath, agentImages.size, format)
        val realMime = mimeForFormat(format)
        fileAccess.writeBytes(targetPath, bytes, overwrite = overwrite)
        val displayPath = fileAccess.toDisplayPath(targetPath)
        val localFile = fileAccess.copyToLocal(targetPath)
        val fileName = targetPath.substringAfterLast('/')

        agentImages.add(AgentImage(mimeType = realMime, base64Data = base64, path = displayPath))
        savedDisplayPaths.add(displayPath)
        filesList.add(
            JsonObject(
                mapOf(
                    "path" to JsonPrimitive(displayPath),
                    "local_path" to JsonPrimitive(localFile.absolutePath),
                    "name" to JsonPrimitive(fileName),
                    "mime_type" to JsonPrimitive(realMime),
                    "size_bytes" to JsonPrimitive(bytes.size.toLong()),
                    "is_image" to JsonPrimitive(true)
                )
            )
        )
    }

    /** Assembles the unified result for Gemini image generation (shared by both channels). */
    private fun buildGeminiResult(
        agentImages: List<AgentImage>,
        savedDisplayPaths: List<String>,
        filesList: List<JsonObject>,
        model: String,
        n: Int,
        outputPath: String?,
        failedCount: Int,
        failureReason: String? = null
    ): ToolResult {
        if (agentImages.isEmpty()) {
            return ToolResult.Error("Gemini image generation did not return image data (the model may have replied with text only, or the image is invalid or too large)", "EMPTY_RESULT")
        }
        return buildImageResult(
            agentImages, savedDisplayPaths, filesList, model, n,
            outputPath, failedCount, failureReason
        )
    }

    private fun buildImageResult(
        agentImages: List<AgentImage>,
        savedDisplayPaths: List<String>,
        filesList: List<JsonObject>,
        model: String,
        requestedCount: Int,
        outputPath: String?,
        failedCount: Int,
        failureReason: String? = null,
        usage: com.aicode.feature.agent.data.remote.openai.ImageGenerationUsage? = null
    ): ToolResult {
        val effectiveFailedCount = failedCount.coerceAtLeast(0)
        val content = buildString {
            append("Generated ${agentImages.size} images")
            if (effectiveFailedCount > 0) append(", $effectiveFailedCount more failed")
            if (!failureReason.isNullOrBlank()) append("(").append(failureReason).append(")")
            if (outputPath == null) append(", saved to $DEFAULT_OUTPUT_DIR/:")
            else append(", saved to the specified path:")
            savedDisplayPaths.forEach { p -> append("\n- ").append(p) }
        }
        val data = mutableMapOf<String, JsonElement>(
            "status" to JsonPrimitive(if (effectiveFailedCount > 0) "partial" else "generated"),
            "content" to JsonPrimitive(content),
            "image_count" to JsonPrimitive(agentImages.size),
            "failed_count" to JsonPrimitive(effectiveFailedCount),
            "model" to JsonPrimitive(model),
            "requested_count" to JsonPrimitive(requestedCount),
            "files" to JsonArray(filesList)
        )
        data["usage"] = usage?.let { value ->
            JsonObject(
                mapOf(
                    "input_tokens" to JsonPrimitive(value.input_tokens),
                    "output_tokens" to JsonPrimitive(value.output_tokens),
                    "total_tokens" to JsonPrimitive(value.total_tokens)
                )
            )
        } ?: JsonNull
        return ToolResult.Success(JsonObject(data), images = agentImages)
    }

    private fun downloadImageBytes(url: String): ByteArray {
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Failed to download image HTTP ${resp.code}: $url")
            val body = resp.body ?: throw IOException("Image response body is empty: $url")
            val contentLength = body.contentLength()
            if (contentLength > MAX_IMAGE_BYTES) {
                throw IOException("Image exceeds the ${MAX_IMAGE_BYTES / 1024 / 1024}MB limit")
            }
            val initialSize = contentLength.takeIf { it in 1..MAX_IMAGE_BYTES }?.toInt() ?: DEFAULT_BUFFER_SIZE
            val output = ByteArrayOutputStream(initialSize)
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_IMAGE_BYTES) {
                        throw IOException("Image exceeds the ${MAX_IMAGE_BYTES / 1024 / 1024}MB limit")
                    }
                    output.write(buffer, 0, read)
                }
            }
            return output.toByteArray()
        }
    }

    private fun decodeImageBase64(base64: String): ByteArray {
        if (estimatedDecodedBytes(base64.length) > MAX_IMAGE_BYTES) {
            throw IOException("Image exceeds the ${MAX_IMAGE_BYTES / 1024 / 1024}MB limit")
        }
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        if (bytes.size > MAX_IMAGE_BYTES) {
            throw IOException("Image exceeds the ${MAX_IMAGE_BYTES / 1024 / 1024}MB limit")
        }
        return bytes
    }

    private fun checkedTotalBytes(current: Long, additional: Int): Long {
        val total = current + additional
        if (total > MAX_TOTAL_IMAGE_BYTES) {
            throw IOException("Total image size for this request exceeds the ${MAX_TOTAL_IMAGE_BYTES / 1024 / 1024}MB limit")
        }
        return total
    }

    private fun createDefaultBasePath(): String =
        "$DEFAULT_OUTPUT_DIR/gen_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"

    /** Derives the output path: strips the caller's suffix, writes to disk in the real format, and adds _1/_2 suffixes to the file name for multiple images.
     * The extension dot is only looked for after the last path separator: dots in paths such as `~/.aicode/...` themselves cannot be treated as extension separators. */
    private fun buildTargetPath(basePath: String, index: Int, format: String): String {
        val slash = basePath.lastIndexOf('/')
        val dot = basePath.lastIndexOf('.', basePath.length - 1).takeIf { it > slash } ?: -1
        val base = if (dot > 0) basePath.substring(0, dot) else basePath
        val suffix = if (index == 0) "" else "_${index + 1}"
        return "$base$suffix.${extForFormat(format)}"
    }

    /** Determines the real image format from the byte stream's magic number (a gateway may ignore output_format; looking at the content directly is most reliable). */
    private fun detectImageFormat(bytes: ByteArray): String {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        ) return FORMAT_PNG
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        ) return FORMAT_JPEG
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) return FORMAT_WEBP
        return FORMAT_PNG
    }

    private fun mimeForFormat(format: String): String = when (format) {
        FORMAT_JPEG -> "image/jpeg"
        FORMAT_WEBP -> "image/webp"
        else -> "image/png"
    }

    private fun extForFormat(format: String): String = if (format == FORMAT_JPEG) "jpg" else format

    internal companion object {
        const val TAG = "GenerateImageTool"
        const val DEFAULT_OUTPUT_DIR = "~/.aicode/generated-images"
        const val DEFAULT_SIZE = "1024x1024"
        const val MAX_IMAGES = 4
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        const val MAX_TOTAL_IMAGE_BYTES = 48L * 1024 * 1024
        const val FORMAT_PNG = "png"
        const val FORMAT_JPEG = "jpeg"
        const val FORMAT_WEBP = "webp"
        val QUALITY_GPT_IMAGE = setOf("low", "medium", "high", "auto")
        val QUALITY_DALLE3 = setOf("standard", "hd")
        val QUALITY_DALLE2 = setOf("standard")
        val BACKGROUNDS = setOf("transparent", "opaque", "auto")
        val MODERATIONS = setOf("low", "auto")
        val STYLES = setOf("vivid", "natural")
        val OUTPUT_FORMATS = setOf("png", "jpeg", "webp")

        internal fun validateImageParams(
            model: String,
            isGptImage: Boolean,
            isDalle2: Boolean,
            isDalle3: Boolean,
            n: Int,
            quality: String?,
            background: String?,
            moderation: String?,
            style: String?,
            outputFormat: String?
        ): String? {
            if (background != null && background !in BACKGROUNDS) return "background value $background is not supported, options: ${BACKGROUNDS.joinToString(" / ")}."
            if (moderation != null && moderation !in MODERATIONS) return "moderation value $moderation is not supported, options: ${MODERATIONS.joinToString(" / ")}."
            if (style != null && style !in STYLES) return "style value $style is not supported, options: ${STYLES.joinToString(" / ")}."
            if (outputFormat != null && outputFormat !in OUTPUT_FORMATS) return "output_format value $outputFormat is not supported, options: ${OUTPUT_FORMATS.joinToString(" / ")}."
            if (!isGptImage && background != null) return "The background parameter is supported only by GPT Image series models, the current model $model does not support it."
            if (!isGptImage && moderation != null) return "The moderation parameter is supported only by GPT Image series models, the current model $model does not support it."
            if (!isGptImage && outputFormat != null) return "The output_format parameter is supported only by GPT Image series models, the current model $model does not support it."
            if (!isDalle3 && style != null) return "The style parameter is supported only by dall-e-3, the current model $model does not support it."
            if (isDalle3 && n > 1) return "dall-e-3 can only generate 1 image at a time (n=1); for multiple images, use a GPT Image series model."
            if (quality != null) {
                val allowed = when {
                    isGptImage -> QUALITY_GPT_IMAGE
                    isDalle3 -> QUALITY_DALLE3
                    isDalle2 -> QUALITY_DALLE2
                    else -> return "The quality parameter is supported only by official OpenAI image generation models (gpt-image series / dall-e-2 / dall-e-3), the current model $model does not support it."
                }
                if (quality !in allowed) return "quality value $quality is not supported by the current model $model, options: ${allowed.joinToString(" / ")}."
            }
            return null
        }

        internal fun estimatedDecodedBytes(base64Length: Int): Long = base64Length.toLong() * 3 / 4
    }
}
