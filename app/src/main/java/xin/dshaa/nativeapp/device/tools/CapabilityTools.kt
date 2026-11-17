package xin.dshaa.nativeapp.device.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import xin.dshaa.nativeapp.data.CapabilityHost

/**
 * M88-C（docs/183 §3）：把「能力」送进模型工具表。
 *
 * 根因：能力一直只在 [CapabilityHost]（插件页/调试入口），模型工具表只有
 * `promptPack.androidApiTools() + pluginApiTools + pluginDevTools`，所以 AI 从来「看不到」
 * notify/clipboard 这些能力——不是模型不听话。
 *
 * 这两个工具归内置组 `builtin.capability`（「能力与通知」，可整体关闭）：
 * - `capability {id, args?}`：调任意内置/插件能力；未知 id 会列出本会话可用 id；
 * - `notify {title?, body}`：发系统通知；没系统权限时返回可行动错误（沿用 H5 的检查）。
 *
 * 依赖用函数注入（不直接持有 Android Context），JVM 测试可给假 host。
 */
class CapabilityTools(
    private val invoke: suspend (id: String, argsJson: String?) -> CapabilityHost.InvokeResult,
    /** 本会话「可用」的能力 id（内置 + 已启用且授权 capability 的插件能力）。 */
    private val availableIds: () -> List<String>,
    /** 现在能不能发系统通知（`CapabilityHost.notificationsAllowed`）。 */
    private val notificationsAllowed: () -> Boolean,
) {

    /** 内置组 `builtin.capability` 的两个工具名（与 [xin.dshaa.nativeapp.data.BuiltinPlugins] 一致）。 */
    companion object {
        const val GROUP_ID = "builtin.capability"
        const val CAPABILITY_TOOL = "capability"
        const val NOTIFY_TOOL = "notify"
    }

    val tools: List<DshTool> = listOf(CapabilityTool(), NotifyTool())

    private inner class CapabilityTool : DshTool {
        override val name: String = CAPABILITY_TOOL

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val id = args.text("id").trim()
            val available = availableIds()
            if (id.isEmpty()) {
                return ToolResult.err("capability 需要 id。可用能力（${available.size}）：${idList(available)}")
            }
            if (id.equals("root.exec", ignoreCase = true)) {
                // M88-B：root.exec 已并入 shell.exec。不是「未知 id」，直接给合并说明。
                return ToolResult.err(
                    "${xin.dshaa.nativeapp.device.ShellCapability.MERGED_ROOT_EXEC_REASON}：" +
                        CapabilityHost.mergedRootExecMessage(),
                )
            }
            if (id !in available) {
                return ToolResult.err(
                    "未知能力 id：$id。可用能力（${available.size}）：${idList(available)}。" +
                        "插件能力需要插件已启用且拿到 capability 授权。",
                )
            }
            val extra = when (val raw = args["args"]) {
                null -> "{}"
                is JsonObject -> raw.toString()
                else -> return ToolResult.err("capability 的 args 必须是 JSON 对象，收到：${typeName(raw)}")
            }
            val result = invoke(id, extra)
            return if (result.ok) {
                ToolResult.ok(result.valueJson)
            } else {
                ToolResult.err("能力 $id 调用失败：${result.error ?: result.summary.ifBlank { "未知原因" }}")
            }
        }
    }

    private inner class NotifyTool : DshTool {
        override val name: String = NOTIFY_TOOL

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val title = args.text("title").trim()
            val body = args.text("body").trim()
            if (title.isEmpty() && body.isEmpty()) {
                return ToolResult.err("notify 需要 title 或 body 至少一个")
            }
            if (!notificationsAllowed()) {
                return ToolResult.err(
                    "系统通知权限未开：去 设置 → 权限与能力 → 通知 打开鲸歌的通知权限，" +
                        "或改用 capability {id:\"notify.toast\"} 只弹 Toast。本次未发送。",
                )
            }
            val payload = buildJsonObject {
                if (title.isNotEmpty()) put("title", title)
                put("body", body)
            }
            val result = invoke("notify", payload.toString())
            if (!result.ok) {
                return ToolResult.err("通知发送失败：${result.error ?: result.summary.ifBlank { "未知原因" }}")
            }
            val text = if (title.isEmpty()) body else "$title：$body"
            return ToolResult.ok("通知已发送：$text")
        }
    }

    private fun JsonObject.text(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()

    private fun idList(ids: List<String>): String =
        if (ids.isEmpty()) "（无；请先在插件页启用并授权能力）" else ids.joinToString("、")

    private fun typeName(value: JsonElement): String = value::class.simpleName ?: "非对象"
}
