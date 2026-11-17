package xin.dshaa.core.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * M88-G（docs/183 §7）：工具输入的完整性 + 长度上限。
 *
 * 背景：`parseArgs` 对非法/半截 JSON **静默返回 `{}`**，工具拿空参数执行——用户看到的是
 * 「调用工具后停了、没反应」。H1 只收口了 max_tokens 截断（TurnEngine 里 tool_use 整批不执行），
 * 没有覆盖「JSON 半截但没报 max_tokens」与「一次输入过长」两种情况。
 *
 * 拍板（docs/183 §10）：默认上限 64KB，`write` / `edit` 512KB；超限与半截 JSON 都返回
 * 显式 error tool_result，回合继续，由模型拆小重试。
 */
object ToolInputGuard {
    /** 普通工具（含插件工具）的默认输入上限。 */
    const val DEFAULT_MAX_BYTES = 64 * 1024

    /** 大内容写入工具的上限（`write` / `edit`）。 */
    const val LARGE_MAX_BYTES = 512 * 1024

    private val LARGE_INPUT_TOOLS = setOf("write", "edit")

    /** 按工具名取上限；未知工具走默认 64KB。 */
    fun limitFor(toolName: String): Int =
        if (toolName in LARGE_INPUT_TOOLS) LARGE_MAX_BYTES else DEFAULT_MAX_BYTES

    /** UTF-8 字节数（中文内容按真实字节算，不按字符数）。 */
    fun byteSize(raw: String): Int = raw.toByteArray(Charsets.UTF_8).size

    /**
     * 返回给模型的显式错误；null = 本次输入可以执行。
     *
     * 先查长度（不解析超大字符串），再严格解析成 JSON 对象；空串按 `{}` 处理（模型常给空参数）。
     */
    fun violation(toolName: String, raw: String): String? {
        tooLongMessage(toolName, raw)?.let { return it }
        if (parseStrict(raw) == null) return INVALID_JSON_MESSAGE
        return null
    }

    /** 超限错误文本；没超返回 null。 */
    fun tooLongMessage(toolName: String, raw: String): String? {
        val bytes = byteSize(raw)
        val limit = limitFor(toolName)
        if (bytes <= limit) return null
        return "工具输入过长：$bytes 字节 > 上限 $limit 字节（$toolName），本次调用未执行。" +
            "请把内容分段/分批重试（例如先写第一段，再用 edit 追加）。"
    }

    /** 严格解析：非法/半截 JSON、非对象 JSON 都返回 null；空白按 `{}`。 */
    fun parseStrict(raw: String): JsonObject? =
        runCatching { Json.parseToJsonElement(raw.ifBlank { "{}" }) as JsonObject }.getOrNull()

    const val INVALID_JSON_MESSAGE =
        "工具参数不是合法 JSON（可能被截断），本次调用未执行。请拆成多次调用，或缩短内容后重试。"
}
