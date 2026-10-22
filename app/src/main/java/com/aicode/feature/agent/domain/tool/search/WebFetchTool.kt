package com.aicode.feature.agent.domain.tool.search

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject

class WebFetchTool @Inject constructor() : AgentTool() {

    private companion object {
        const val TAG = "WebFetchTool"
        // A newer desktop Chrome version, paired with the sec-ch-ua / sec-fetch-* headers below to closely match a real browser fingerprint
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
        const val MAX_LENGTH = 100_000 // Limits the maximum number of extracted characters, preventing the context from being blown up
    }

    override val name = "webfetch"
    override val description = "Fetch the content of a specified HTTP/HTTPS web page. Can extract the page body as plain text or return the raw HTML structure."
    override val capabilities = setOf(ToolCapability.NETWORK_READ)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "url" to ToolParameter(
            name = "url",
            type = ParameterType.STRING,
            description = "Full URL of the web page to fetch (must start with http:// or https://)",
            required = true
        ),
        "format" to ToolParameter(
            name = "format",
            type = ParameterType.STRING,
            description = "Return format: text (default; removes ads/scripts/styles and keeps only the body) or html (raw HTML source)",
            enum = listOf("text", "html"),
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("Missing required parameter: url")
        val format = args["format"]?.jsonPrimitive?.contentOrNull ?: "text"

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.Error("url must start with http:// or https://")
        }

        return withContext(Dispatchers.IO) {
            try {
                FileLogger.i(TAG, "Fetching web page: $url, format=$format")

                // Capture non-HTTP connection exceptions (DNS, timeout, SSL, etc.) at the top level and pass the specific reason back to the AI directly, without guessing a fallback
                val doc = try {
                    fetchDocument(url)
                } catch (e: FetchException) {
                    return@withContext ToolResult.Error(e.detailedMessage(url))
                }

                // Extract as requested
                val resultText = when (format) {
                    "html" -> doc.outerHtml()
                    else -> extractCleanText(doc)
                }
                
                val finalOutput = if (resultText.length > MAX_LENGTH) {
                    resultText.take(MAX_LENGTH) + "\n\n[Web page content too long; truncated]"
                } else {
                    resultText
                }

                ToolResult.Success(kotlinx.serialization.json.JsonPrimitive(finalOutput))
            } catch (e: Exception) {
                FileLogger.e(TAG, "Exception while fetching web page", e)
                ToolResult.Error("Fetch failed: ${e.message}")
            }
        }
    }

    private fun fetchDocument(url: String): Document {
        return try {
            Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                // The sec-ch-ua family of client hints that a real desktop Chrome always sends
                .header("Sec-Ch-Ua", "\"Chromium\";v=\"132\", \"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"132\"")
                .header("Sec-Ch-Ua-Mobile", "?0")
                .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                // Fetch Metadata headers, which modern browsers always send with page requests
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")
                // 3xx redirects are followed by default; declared explicitly; limits the response body size to avoid huge pages filling memory
                .followRedirects(true)
                .maxBodySize(5 * 1024 * 1024) // 5MB
                .timeout(15000) // 15 second timeout
                .ignoreContentType(true)
                .get()
        } catch (e: org.jsoup.HttpStatusException) {
            FileLogger.e(TAG, "Abnormal HTTP status code: ${e.statusCode} - ${e.getUrl()}", e)
            // Pass the real status code and the original exception description back to the AI, with no fuzzing
            throw FetchException("HTTP ${e.statusCode}: ${e.message ?: "no status description"}")
        } catch (e: org.jsoup.UnsupportedMimeTypeException) {
            FileLogger.e(TAG, "Unsupported response type: ${e.getMimeType()} - ${e.getUrl()}", e)
            throw FetchException("Unsupported response MIME type: ${e.getMimeType()}")
        } catch (e: java.net.SocketTimeoutException) {
            FileLogger.e(TAG, "Request timed out: $url", e)
            throw FetchException("Request timed out (no response within 15 seconds)")
        } catch (e: java.net.UnknownHostException) {
            FileLogger.e(TAG, "DNS resolution failed: $url", e)
            throw FetchException("Unable to resolve hostname: ${url}")
        } catch (e: javax.net.ssl.SSLException) {
            FileLogger.e(TAG, "SSL handshake failed: $url", e)
            throw FetchException("SSL/TLS handshake failed: ${e.message ?: "unknown cause"}")
        } catch (e: java.io.IOException) {
            FileLogger.e(TAG, "Network I/O error: ${e.message} - $url", e)
            throw FetchException("Network I/O error: ${e.message ?: "unknown cause"}")
        }
    }

    /** Wraps a failure during fetching into an exception carrying specific information, so it can be passed back to the AI rather than as a vague hint. */
    private class FetchException(val detail: String) : RuntimeException(detail) {
        fun detailedMessage(url: String): String = "Failed to fetch $url: $detail"
    }

    /**
     * Extracts clean body text, preserving newlines between paragraphs.
     */
    private fun extractCleanText(doc: Document): String {
        // Remove redundant invisible content
        doc.select("script, style, iframe, nav, footer, header, noscript, .ad, .advertisement").remove()
        
        // To avoid Jsoup's .text() squeezing all lines together, add newlines to block-level elements
        doc.select("p, h1, h2, h3, h4, h5, h6, li, div, br").append("\\n")
        
        val rawText = doc.body()?.text() ?: ""
        
        // Restore the newlines and clean up redundant blank lines
        return rawText.replace("\\n", "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
