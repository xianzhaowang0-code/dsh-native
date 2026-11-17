package xin.dshaa.nativeapp.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.engine.ToolCallView
import xin.dshaa.core.engine.ToolMiddleware
import xin.dshaa.core.protocol.ApiTool
import xin.dshaa.core.tools.ToolResult

/**
 * M87-A（docs/181 §3.2）：把一个插件注册的工具中间件接进 `core:engine` 的 [ToolMiddleware]。
 *
 * 职责分工（为什么 JS 只拿一半）：
 * - **同步快速路径在 Kotlin 做**：`restrict` 的 glob 模式、`describe` 的静态 patch 在常驻运行时启动时
 *   就已经作为元数据交给宿主，拼 `tools[]` 时不往返 JS（零阻塞、链为空零开销）；
 * - **需要闭包的三个钩子才 dispatch 进 JS**：`guard`（同步拒绝）、`pre-execute`、`post-execute`、
 *   `result`（只读）。每次往返由宿主限时 50ms；超时/运行时不在 = 本中间件跳过并记诊断。
 *
 * 权限：`restrict/guard` 只能收紧，不需要 `tools:intercept`；
 * `describe/pre-execute/post-execute/result` 必须拿到 `tools:intercept` 授权（[interceptGranted]），
 * 没授权时静默跳过（注册本身不报错，插件页仍可看到未授权）。
 *
 * 边界（v1）：
 * - `post-execute` 只改 `text` / `isError`，图片列表保持原样（ImageRef 需要附件存储，不能在 JS 里造）；
 * - 中间件只改「这一次」的工具调用/结果；会话日志与原始结果哈希由引擎侧诊断留痕；
 * - 插件停用/重载后链由宿主重建，旧链快照最多影响正在收尾的那一次调用。
 */
class PluginToolMiddleware(
    private val pluginId: String,
    private val pluginName: String,
    private val meta: ToolMiddlewareMeta,
    private val interceptGranted: Boolean,
    /** 宿主 → 常驻运行时的一次 dispatch；返回 JSON 文本，null = 超时/运行时不可用。 */
    private val dispatch: (method: String, payloadJson: String) -> String?,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) : ToolMiddleware {

    override val id: String = "$pluginId/tools"

    /** restrict：只能删；模式支持 `*`（如 `shower_*`）。 */
    override fun allowTool(name: String): Boolean =
        meta.restrict.none { globMatches(it, name) }

    /** describe：只套静态 patch；未授权 `tools:intercept` 时什么都不改。 */
    override fun describe(tool: ApiTool): ApiTool? {
        if (!interceptGranted) return null
        val patch = meta.describe.lastOrNull { globMatches(it.tool, tool.name) }?.patch ?: return null
        var next = tool
        runCatching { patch["description"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?.let { next = next.copy(description = it) }
        (patch["parameters"] as? JsonObject)?.let { schema -> next = next.copy(inputSchema = schema) }
        return next.takeIf { it != tool }
    }

    override suspend fun guard(call: ToolCallView): String? {
        // 只收紧：不需要 tools:intercept。
        if (meta.guard.none { globMatches(it, call.name) }) return null
        log("guard dispatch plugin=$pluginId tool=${call.name}")
        // JS 侧 `tools.guard` 读的是 `payload.call`（和 pre/post 一致的形状）；
        // 直接发 callJson 会让 JS 拿到空调用，guard 永远不拒绝（H7 真机 fixture 发现）。
        val reply = dispatchJson("tools.guard", callPayload(call))
        if (reply == null) {
            log("guard no reply plugin=$pluginId tool=${call.name}（超时/运行时不在）")
            return null
        }
        if (reply["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == false) {
            val error = reply["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
            log("guard failed plugin=$pluginId tool=${call.name}: $error")
            return null
        }
        log("guard reply plugin=$pluginId tool=${call.name}: ${reply["deny"]}")
        if (reply["deny"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() != true) return null
        val message = reply["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "插件规则拒绝了这次工具调用"
        log("guard denied plugin=$pluginId tool=${call.name}: $message")
        return "[插件「$pluginName」拒绝] $message"
    }

    override suspend fun preExecute(call: ToolCallView): ToolCallView? {
        if (!interceptGranted || meta.preExecute <= 0) return null
        val reply = dispatchJson("tools.preExecute", callPayload(call)) ?: return null
        if (reply["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == false) return null
        val next = reply["call"] as? JsonObject ?: return null
        val args = next["args"] as? JsonObject ?: return null
        val raw = next["rawArguments"]?.jsonPrimitive?.contentOrNull ?: args.toString()
        val view = ToolCallView(call.id, call.name, args, raw)
        return view.takeIf { it != call }
    }

    override suspend fun postExecute(call: ToolCallView, result: ToolResult): ToolResult? {
        if (!interceptGranted || meta.postExecute <= 0) return null
        val reply = dispatchJson("tools.postExecute", resultPayload(call, result)) ?: return null
        if (reply["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == false) return null
        val next = reply["result"] as? JsonObject ?: return null
        val text = next["text"]?.jsonPrimitive?.contentOrNull ?: return null
        val isError = next["isError"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: result.isError
        val rewritten = result.copy(text = text, isError = isError)
        return rewritten.takeIf { it != result }
    }

    override fun observe(call: ToolCallView, result: ToolResult) {
        if (!interceptGranted || meta.result <= 0) return
        val payload = resultPayload(call, result)
        // 只观测：不阻塞工具回合，异常/超时只进 DshExt 诊断。
        scope.launch(Dispatchers.IO) { dispatchJson("tools.result", payload) }
    }

    /** `{call}`：guard / pre-execute 的 payload 形状（JS 侧统一读 `payload.call`）。 */
    private fun callPayload(call: ToolCallView): JsonObject = buildJsonObject {
        put("call", callJson(call))
    }

    /** `{call, result:{text,isError}}`：pre/post/result 共用的 payload 形状（图片保持原样，不进 JS）。 */
    private fun resultPayload(call: ToolCallView, result: ToolResult): JsonObject = buildJsonObject {
        put("call", callJson(call))
        put(
            "result",
            buildJsonObject {
                put("text", result.text)
                put("isError", result.isError)
            },
        )
    }

    private fun callJson(call: ToolCallView): JsonObject = Json.parseToJsonElement(
        buildString {
            append("{\"id\":").append(kotlinx.serialization.json.JsonPrimitive(call.id))
            append(",\"name\":").append(kotlinx.serialization.json.JsonPrimitive(call.name))
            append(",\"args\":").append(call.args.toString())
            append(",\"rawArguments\":").append(kotlinx.serialization.json.JsonPrimitive(call.rawArguments))
            append("}")
        },
    ).jsonObject

    private fun dispatchJson(method: String, payload: JsonObject): JsonObject? {
        val reply = dispatch(method, payload.toString()) ?: return null
        return runCatching { Json.parseToJsonElement(reply).jsonObject }.getOrNull()
    }

    /** 与 JS 侧 `__dshToolMatch` 同一语义（只支持 `*`）。 */
    private fun globMatches(pattern: String, name: String): Boolean = toolPatternMatches(pattern, name)
}

/** docs/181 §3.1：单次工具中间件 JS 往返的超时预算（超时 = 本中间件跳过并记诊断）。 */
internal const val TOOL_MIDDLEWARE_TIMEOUT_MS = 50L

/**
 * M87-A：`restrict` / `guard` / `describe` 的工具名匹配（只支持 `*` 通配）。
 * 与 `PluginModule.kt` 的 JS `__dshToolMatch` 保持同一语义；JVM 单测直接覆盖。
 */
internal fun toolPatternMatches(pattern: String, name: String): Boolean {
    if (pattern == "*" || pattern == name) return true
    if (!pattern.contains('*')) return false
    val parts = pattern.split('*').filter { it.isNotEmpty() }
    var index = 0
    parts.forEachIndexed { i, part ->
        val found = name.indexOf(part, index)
        if (found < 0) return false
        if (i == 0 && pattern.first() != '*' && found != 0) return false
        index = found + part.length
    }
    return pattern.endsWith("*") || index == name.length
}
