package xin.dshaa.core.plugins

import kotlinx.serialization.json.*

/**
 * M87-C/D（docs/181 §3.4）：插件往宿主原生界面「加一块」的声明式插槽契约。
 *
 * 槽位：
 * - `chat.composer.dock`：输入条上方一行（Surface 子树 + 只读状态）；
 * - `chat.node.tool`：工具卡 body 附加行；
 * - `chat.node.assistant` / `chat.node.user`：助手回合 / 用户消息的附加行；
 * - `chat.message.actions`：消息长按/更多菜单里的操作项；
 * - `settings.rows`：设置首页追加行；
 * - `drawer.items`：侧边栏底部追加项；
 * - `shell.overlay`：全局浮层（原生壳最上层，最后注册的盖在上面）。
 *
 * 安全边界（解析时强制，渲染器不用再防御）：
 * - 只允许 [SLOTS] 里的槽位与 [PRIMITIVES] 里的原语；
 * - 每棵树最多 [MAX_NODES] 节点、[MAX_DEPTH] 层、单文本 [MAX_TEXT] 字符、声明 [MAX_BYTES]；
 * - `bind` 只允许只读路径（`state.*` = 插件动作返回的槽位状态；`view.*` = 插件自己
 *   session projection 的 `wire.view`），不允许任意表达式；
 * - 动作 id 只允许 `[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}`，点击只回插件 JS（不放执行命令）；
 * - 未知字段直接报错（不猜、不静默丢弃）。
 *
 * 与 loading/v1（[LoadingSpec]）同一形状：纯 JVM、安装时校验一次、读盘再校验一次。
 */
data class PluginSlot(
    val slot: String,
    val id: String,
    val order: Int,
    /** `any` / `hasMessages` / `empty`（v1 只按当前会话有没有消息过滤）。 */
    val whenSession: String,
    /** 规范化后的 Surface 子树 JSON。 */
    val treeJson: String,
) {
    /** 宿主内部用的稳定 key（同一插件同一槽位下唯一）。 */
    val key: String get() = "$slot/$id"
}

data class SlotParseResult(val slot: PluginSlot? = null, val error: String? = null)

object SlotSpec {

    const val VERSION = "slot/v1"
    const val MAX_BYTES = 16 * 1024
    const val MAX_DECLARATIONS = 16
    const val MAX_NODES = 64
    const val MAX_DEPTH = 12
    const val MAX_TEXT = 2_000

    /** 允许的槽位；未知槽位不装（M87-D 补齐 §3.4 清单）。 */
    val SLOTS: Set<String> = linkedSetOf(
        "chat.composer.dock",
        "chat.node.tool",
        "chat.node.assistant",
        "chat.node.user",
        "chat.message.actions",
        "settings.rows",
        "drawer.items",
        "shell.overlay",
    )

    /** 槽位里允许的原语（比整壳少：不放 webview/image/textfield/markdown/list）。 */
    val PRIMITIVES: Set<String> = linkedSetOf(
        "column", "row", "stack", "text", "button", "switch", "divider", "spacer", "progress", "icon",
    )

    private val containers = setOf("column", "row", "stack")
    private val common = setOf("type", "key", "visible")
    private val layout = setOf("padding", "paddingH", "paddingV", "gap", "align", "justify", "background", "corner", "grow")
    private val palette = setOf("background", "surface", "brand", "text", "sub", "faint", "warn", "error", "success")
    private val icons = setOf("whale", "extension", "info", "star", "bolt", "check")
    private val idPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")
    private val actionPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}$")
    private val bindPattern = Regex("^(state|view)(\\.[A-Za-z][A-Za-z0-9_]*){0,3}$")
    private val sessionConditions = setOf("any", "hasMessages", "empty")

    fun parse(element: JsonElement): SlotParseResult = runCatching {
        require(element.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "插槽声明超过 16 KiB" }
        val obj = element as? JsonObject ?: error("插槽声明必须是对象")
        require(obj.keys.all { it in setOf("slot", "id", "order", "when", "tree") }) { "插槽声明有未知字段" }
        val slotName = string(obj["slot"])?.trim().orEmpty()
        require(slotName in SLOTS) { "未知插槽：${slotName.ifBlank { "(空)" }}" }
        val id = string(obj["id"])?.trim().orEmpty()
        require(idPattern.matches(id)) { "插槽 id 不合法（1–64 位字母数字 ._:-）" }
        val order = (obj["order"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: 100
        require(order in 0..10_000) { "插槽 order 超出 0–10000" }
        val whenSession = parseWhen(obj["when"])
        var nodes = 0
        fun node(value: JsonElement, depth: Int): JsonObject {
            require(depth <= MAX_DEPTH && ++nodes <= MAX_NODES) { "插槽树过深或节点过多（≤$MAX_NODES 节点 / $MAX_DEPTH 层）" }
            val raw = value as? JsonObject ?: error("插槽节点必须是对象")
            val type = string(raw["type"]).orEmpty()
            require(type in PRIMITIVES) { "插槽不允许原语：${type.ifBlank { "(空)" }}" }
            val allowed = common + layout + when (type) {
                "column", "row", "stack" -> setOf("children")
                "text" -> setOf("text", "bind", "size", "color", "weight", "maxLines")
                "button" -> setOf("text", "onTap", "payload", "disabled", "color")
                "switch" -> setOf("checked", "bind", "onChange", "payload", "disabled")
                "progress" -> setOf("value")
                "icon" -> setOf("name", "size", "color")
                "spacer" -> setOf("size")
                else -> emptySet()
            }
            require(raw.keys.all { it in allowed }) {
                "插槽 $type 有未知或不允许的字段：${raw.keys.filter { it !in allowed }.joinToString("、")}"
            }
            return buildJsonObject {
                put("type", type)
                raw.forEach { (key, item) ->
                    if (key == "type") return@forEach
                    when (key) {
                        "children" -> {
                            val children = item as? JsonArray ?: error("children 必须是数组")
                            require(children.size <= MAX_NODES) { "children 过多" }
                            put(key, JsonArray(children.map { node(it, depth + 1) }))
                        }
                        "bind" -> {
                            val text = string(item) ?: error("bind 必须是字符串")
                            require(bindPattern.matches(text)) { "插槽只允许 state.* / view.* 只读绑定：$text" }
                            put(key, JsonPrimitive(text))
                        }
                        "onTap", "onChange" -> {
                            val text = string(item) ?: error("$key 必须是动作 id")
                            require(actionPattern.matches(text)) { "$key 动作 id 不合法：$text" }
                            put(key, JsonPrimitive(text))
                        }
                        "text" -> {
                            val text = string(item) ?: error("text 必须是字符串")
                            require(text.length <= MAX_TEXT) { "插槽文本超过 $MAX_TEXT 字符" }
                            put(key, JsonPrimitive(text))
                        }
                        "color", "background" -> {
                            val text = string(item) ?: error("$key 必须是主题 token")
                            require(text in palette) { "插槽颜色必须使用主题 token：$text" }
                            put(key, JsonPrimitive(text))
                        }
                        "name" -> {
                            val text = string(item) ?: error("icon name 必须是字符串")
                            require(text in icons) { "插槽图标不支持：$text" }
                            put(key, JsonPrimitive(text))
                        }
                        "align" -> {
                            val text = string(item) ?: error("align 必须是字符串")
                            require(text in setOf("start", "center", "end")) { "插槽 align 不合法：$text" }
                            put(key, JsonPrimitive(text))
                        }
                        "justify" -> {
                            val text = string(item) ?: error("justify 必须是字符串")
                            require(text in setOf("start", "center", "end", "between")) { "插槽 justify 不合法：$text" }
                            put(key, JsonPrimitive(text))
                        }
                        "weight" -> {
                            val text = string(item) ?: error("weight 必须是字符串")
                            require(text in setOf("normal", "medium", "bold")) { "插槽 weight 不合法：$text" }
                            put(key, JsonPrimitive(text))
                        }
                        "maxLines" -> {
                            val number = intValue(item, "maxLines")
                            require(number in 1..20) { "插槽 maxLines 超出 1–20" }
                            put(key, JsonPrimitive(number))
                        }
                        "checked", "disabled", "visible" -> {
                            require(item is JsonPrimitive && item.booleanOrNull != null) { "插槽 $key 必须是布尔" }
                            put(key, item)
                        }
                        "payload" -> {
                            require(item is JsonObject || item is JsonArray || item is JsonPrimitive) { "插槽 payload 必须是 JSON 值" }
                            put(key, item)
                        }
                        "key" -> {
                            val text = string(item) ?: error("key 必须是字符串")
                            require(text.length <= 80) { "插槽 key 过长" }
                            put(key, JsonPrimitive(text))
                        }
                        "value" -> {
                            val number = doubleValue(item, "value")
                            require(number in 0.0..1.0) { "插槽 progress.value 超出 0–1" }
                            put(key, JsonPrimitive(number))
                        }
                        "size" -> {
                            val number = doubleValue(item, "size")
                            val range = if (type == "text") 10.0..32.0 else 0.0..96.0
                            require(number in range) { "插槽 $type.size 超出 ${range.start}–${range.endInclusive}" }
                            put(key, JsonPrimitive(number))
                        }
                        "padding", "paddingH", "paddingV", "gap", "corner", "grow" -> {
                            val number = doubleValue(item, key)
                            require(number in 0.0..96.0) { "插槽 $key 超出 0–96" }
                            put(key, JsonPrimitive(number))
                        }
                    }
                }
                if (type in containers && !raw.containsKey("children")) put("children", JsonArray(emptyList()))
            }
        }
        val tree = node(obj["tree"] ?: error("插槽缺少 tree"), 0)
        val canonical = tree.toString()
        require(canonical.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "插槽规范声明超过 16 KiB" }
        SlotParseResult(PluginSlot(slotName, id, order, whenSession, canonical))
    }.getOrElse { SlotParseResult(error = it.message ?: "插槽声明不合法") }

    /** 读盘/快照时按最多 [MAX_DECLARATIONS] 条重新校验（坏条目丢弃，不影响其它）。 */
    fun parseAll(element: JsonElement?): List<PluginSlot> = (element as? JsonArray).orEmpty()
        .take(MAX_DECLARATIONS).mapNotNull { parse(it).slot }.distinctBy { it.slot to it.id }

    /** 写盘形状（install 后 plugins.json 用）。 */
    fun declaration(slot: PluginSlot): JsonObject = buildJsonObject {
        put("slot", slot.slot)
        put("id", slot.id)
        put("order", slot.order)
        put("when", buildJsonObject { put("session", slot.whenSession) })
        put("tree", Json.parseToJsonElement(slot.treeJson))
    }

    /** 条件过滤：v1 只看「当前会话有没有消息」。 */
    fun matches(slot: PluginSlot, hasMessages: Boolean): Boolean = when (slot.whenSession) {
        "hasMessages" -> hasMessages
        "empty" -> !hasMessages
        else -> true
    }

    private fun parseWhen(element: JsonElement?): String {
        if (element == null || element is JsonNull) return "any"
        val obj = element as? JsonObject ?: error("when 必须是对象")
        require(obj.keys.all { it == "session" }) { "when 有未知字段" }
        val session = obj["session"]?.let { string(it) } ?: "any"
        require(session in sessionConditions) { "when.session 只支持 any/hasMessages/empty：$session" }
        return session
    }

    private fun string(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun intValue(element: JsonElement, name: String): Int {
        val number = (element as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        require(number != null) { "插槽 $name 必须是整数" }
        return number
    }

    private fun doubleValue(element: JsonElement, name: String): Double {
        val number = (element as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        require(number != null && number.isFinite()) { "插槽 $name 必须是有限数值" }
        return number
    }
}
