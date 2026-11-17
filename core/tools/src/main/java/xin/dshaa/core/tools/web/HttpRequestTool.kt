package xin.dshaa.core.tools.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * M88-D（docs/183 §4）：`http_request` —— 给 AI 一个 curl 等价物（不是把终端 curl 插件化）。
 *
 * 只做一件事：发一次 HTTP 请求并把**结构化结果**交回模型。边界：
 * - 只允许 http/https；重定向最多 5 跳，跳到其它 scheme 直接拒绝；
 * - 不回显请求头（可能含 token），只回响应头，且去掉 `set-cookie`；
 * - 响应体按 `max_bytes` 截断（`truncated=true` 不算错误）；非文本 content-type 用 base64；
 * - 不读本地文件、不自动重试 POST。
 *
 * 归内置组 `builtin.web`（与 web_search/web_fetch 一起可整体关闭）。
 */
class HttpRequestTool(private val http: OkHttpClient = WebFetchTool.defaultClient()) : DshTool {
    override val name = "http_request"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val method = (args.text("method") ?: "GET").trim().uppercase(Locale.ROOT)
        if (method !in METHODS) {
            return ToolResult.err("不支持的 method：$method（可用：${METHODS.joinToString(" / ")}）")
        }
        val rawUrl = args.text("url").orEmpty().trim()
        if (rawUrl.isEmpty()) return ToolResult.err("http_request 需要 url")
        val scheme = rawUrl.substringBefore(':', "").lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            return ToolResult.err("只允许 http/https URL，收到 scheme=${scheme.ifBlank { "（无）" }}")
        }
        val url = rawUrl.toHttpUrlOrNull()
            ?: return ToolResult.err("非法 URL：$rawUrl")

        val timeoutMs = (args.int("timeout_ms") ?: DEFAULT_TIMEOUT_MS).coerceIn(1, MAX_TIMEOUT_MS)
        val maxBytes = (args.int("max_bytes") ?: DEFAULT_MAX_BYTES).coerceIn(1, MAX_MAX_BYTES)
        val followRedirects = args.boolean("follow_redirects") ?: true
        val requestHeaders = args["headers"] as? JsonObject
        if (args["headers"] != null && requestHeaders == null) {
            return ToolResult.err("headers 必须是 JSON 对象（string → string）")
        }
        val bodyText = args.text("body")
        val client = http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val started = System.currentTimeMillis()
                var currentUrl = url
                var currentMethod = method
                var currentBody = requestBody(method, bodyText, requestHeaders)
                var hops = 0
                var response: Response
                while (true) {
                    val callHeaders = ArrayList<Pair<String, String>>()
                    requestHeaders?.forEach { (key, value) ->
                        val text = runCatching { (value as JsonPrimitive).contentOrNull }.getOrNull()
                        if (!text.isNullOrEmpty()) callHeaders += key to text
                    }
                    val request = Request.Builder()
                        .url(currentUrl)
                        .method(currentMethod, currentBody)
                        .apply { callHeaders.forEach { (k, v) -> header(k, v) } }
                        .build()
                    response = client.newCall(request).execute()
                    if (!followRedirects) break
                    if (response.code !in REDIRECT_CODES) break
                    val location = response.header("Location")
                    if (location.isNullOrBlank()) break
                    response.close()
                    if (hops >= MAX_REDIRECT_HOPS) {
                        throw IllegalArgumentException("重定向超过 $MAX_REDIRECT_HOPS 跳，已停止")
                    }
                    // 先看 Location 自己的 scheme：HttpUrl.resolve 对非 http(s) 返回 null，
                    // 那样只能报「非法地址」，说不清是跳到别的 scheme 被拒。
                    val locationText = location.trim()
                    if (!locationText.startsWith("//")) {
                        val locationScheme = locationText.substringBefore(':', "").lowercase(Locale.ROOT)
                        if (locationScheme.isNotEmpty() && locationScheme != "http" && locationScheme != "https") {
                            throw IllegalArgumentException("重定向到不支持的 scheme：$locationScheme")
                        }
                    }
                    val next = currentUrl.resolve(locationText)
                        ?: throw IllegalArgumentException("非法重定向地址：$location")
                    if (next.scheme != "http" && next.scheme != "https") {
                        throw IllegalArgumentException("重定向到不支持的 scheme：${next.scheme}")
                    }
                    hops++
                    if (response.code == 303 ||
                        ((response.code == 301 || response.code == 302) &&
                            currentMethod.equals("POST", ignoreCase = true))
                    ) {
                        currentMethod = "GET"
                        currentBody = null
                    }
                    currentUrl = next
                }
                response.use { resp -> buildResult(resp, currentUrl, started, maxBytes) }
            } catch (e: Exception) {
                ToolResult.err("HTTP 请求失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun requestBody(method: String, bodyText: String?, headers: JsonObject?): RequestBody? {
        if (method == "GET" || method == "HEAD") return null
        if (bodyText == null) return null
        val contentType = headers?.entries
            ?.firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value
            ?.let { runCatching { (it as JsonPrimitive).contentOrNull }.getOrNull() }
            ?: "text/plain; charset=utf-8"
        return bodyText.toRequestBody(contentType.toMediaTypeOrNull())
    }

    private fun buildResult(
        response: Response,
        finalUrl: HttpUrl,
        started: Long,
        maxBytes: Int,
    ): ToolResult {
        val contentType = response.header("content-type").orEmpty()
        val raw = if (response.request.method == "HEAD") {
            ByteArray(0)
        } else {
            response.body?.let { readLimited(it.source(), maxBytes) } ?: ByteArray(0)
        }
        val truncated = raw.size > maxBytes
        val bytes = if (truncated) raw.copyOf(maxBytes) else raw
        val textual = isTextual(contentType)
        val body = if (textual) {
            bytes.toString(Charsets.UTF_8)
        } else {
            Base64.getEncoder().encodeToString(bytes)
        }
        val headersJson = buildJsonObject {
            val names = response.headers.names().take(MAX_RESPONSE_HEADERS)
            names.forEach { name ->
                if (name.equals("set-cookie", ignoreCase = true)) return@forEach
                val values = response.headers.values(name)
                    .joinToString(", ")
                    .let { if (it.length > MAX_HEADER_CHARS) it.take(MAX_HEADER_CHARS) + "…" else it }
                put(name, values)
            }
        }
        val elapsedMs = System.currentTimeMillis() - started
        val json = buildJsonObject {
            put("ok", true)
            put("status", response.code)
            put("url", finalUrl.toString())
            put("headers", headersJson)
            put("body", body)
            put("encoding", if (textual) "utf-8" else "base64")
            put("bytes", bytes.size)
            put("truncated", truncated)
            put("contentType", contentType)
            put("elapsedMs", elapsedMs)
        }
        return ToolResult.ok(json.toString())
    }

    /** 最多读 [maxBytes]+1 字节（多读 1 字节只用来判 `truncated`），不把大响应整个吃进内存。 */
    private fun readLimited(source: BufferedSource, maxBytes: Int): ByteArray {
        val buffer = okio.Buffer()
        val cap = maxBytes.toLong() + 1L
        while (buffer.size < cap) {
            val read = source.read(buffer, cap - buffer.size)
            if (read == -1L) break
        }
        return buffer.readByteArray()
    }

    private fun isTextual(contentType: String): Boolean {
        val ct = contentType.lowercase(Locale.ROOT)
        if (ct.isBlank()) return true
        return ct.startsWith("text/") ||
            ct.contains("json") ||
            ct.contains("xml") ||
            ct.contains("javascript") ||
            ct.contains("x-www-form-urlencoded") ||
            ct.contains("graphql")
    }

    private fun JsonObject.text(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.int(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()

    private fun JsonObject.boolean(key: String): Boolean? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() }.getOrNull()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000
        const val MAX_TIMEOUT_MS = 60_000
        const val DEFAULT_MAX_BYTES = 262_144
        const val MAX_MAX_BYTES = 1_048_576
        const val MAX_REDIRECT_HOPS = 5
        const val MAX_RESPONSE_HEADERS = 100
        const val MAX_HEADER_CHARS = 8_192

        val METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
