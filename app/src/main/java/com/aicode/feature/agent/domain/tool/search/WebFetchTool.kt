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
        // 较新的桌面 Chrome 版本，搭配下方 sec-ch-ua / sec-fetch-* 请求头以贴近真实浏览器指纹
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
        const val MAX_LENGTH = 100_000 // 限制最大提取字符数，防止撑爆上下文
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
                FileLogger.i(TAG, "正在抓取网页: $url, format=$format")

                // 从顶层捕获非 HTTP 的连接异常（DNS、超时、SSL 等），直接把具体原因回传给 AI，不做兜底猜测
                val doc = try {
                    fetchDocument(url)
                } catch (e: FetchException) {
                    return@withContext ToolResult.Error(e.detailedMessage(url))
                }

                // 按要求提取
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
                FileLogger.e(TAG, "抓取网页时发生异常", e)
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
                // 真桌面 Chrome 一定会发送的 sec-ch-ua 系列客户端提示
                .header("Sec-Ch-Ua", "\"Chromium\";v=\"132\", \"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"132\"")
                .header("Sec-Ch-Ua-Mobile", "?0")
                .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                // Fetch Metadata 请求头，现代浏览器发页面请求时必带
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")
                // 3xx 重定向默认即跟随，显式声明；限制响应体大小避免超大页面吃满内存
                .followRedirects(true)
                .maxBodySize(5 * 1024 * 1024) // 5MB
                .timeout(15000) // 15秒超时
                .ignoreContentType(true)
                .get()
        } catch (e: org.jsoup.HttpStatusException) {
            FileLogger.e(TAG, "HTTP 状态码异常: ${e.statusCode} - ${e.getUrl()}", e)
            // 把真实状态码和原始异常描述回传给 AI，不做任何模糊化处理
            throw FetchException("HTTP ${e.statusCode}: ${e.message ?: "no status description"}")
        } catch (e: org.jsoup.UnsupportedMimeTypeException) {
            FileLogger.e(TAG, "不支持的响应类型: ${e.getMimeType()} - ${e.getUrl()}", e)
            throw FetchException("Unsupported response MIME type: ${e.getMimeType()}")
        } catch (e: java.net.SocketTimeoutException) {
            FileLogger.e(TAG, "请求超时: $url", e)
            throw FetchException("Request timed out (no response within 15 seconds)")
        } catch (e: java.net.UnknownHostException) {
            FileLogger.e(TAG, "DNS 解析失败: $url", e)
            throw FetchException("Unable to resolve hostname: ${url}")
        } catch (e: javax.net.ssl.SSLException) {
            FileLogger.e(TAG, "SSL 握手失败: $url", e)
            throw FetchException("SSL/TLS handshake failed: ${e.message ?: "unknown cause"}")
        } catch (e: java.io.IOException) {
            FileLogger.e(TAG, "网络 I/O 异常: ${e.message} - $url", e)
            throw FetchException("Network I/O error: ${e.message ?: "unknown cause"}")
        }
    }

    /** 把抓取过程中的失败包装成携带具体信息的异常，以便回传给 AI 而非模糊提示。 */
    private class FetchException(val detail: String) : RuntimeException(detail) {
        fun detailedMessage(url: String): String = "Failed to fetch $url: $detail"
    }

    /**
     * 提取干净正文，并在段落之间保留换行符。
     */
    private fun extractCleanText(doc: Document): String {
        // 移除多余的不可见内容
        doc.select("script, style, iframe, nav, footer, header, noscript, .ad, .advertisement").remove()
        
        // 为了避免 Jsoup 的 .text() 把所有行挤在一起，给块级元素加上换行符
        doc.select("p, h1, h2, h3, h4, h5, h6, li, div, br").append("\\n")
        
        val rawText = doc.body()?.text() ?: ""
        
        // 还原换行符，并清理多余的空行
        return rawText.replace("\\n", "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
