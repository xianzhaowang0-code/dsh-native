package xin.dshaa.nativeapp.ui.shell.slots

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.ui.design.theme.DshColors
import xin.dshaa.ui.design.theme.DshTheme2
import xin.dshaa.ui.shellapi.SlotRegistry

/** 当前插槽注册表（App 在 NativeShellHost 上提供；null = 没有插件插槽，零开销）。 */
val LocalSlotRegistry = compositionLocalOf<SlotRegistry?> { null }

/** 当前会话有没有消息（`when.session` 过滤用；聊天页由 ChatScreen 提供，其它页默认 false）。 */
val LocalSlotHasMessages = compositionLocalOf { false }

/**
 * M87-C/D：在宿主原生界面里渲染某个槽位的全部插件条目。
 *
 * - 没有注册表/没有条目时**不占位**（不改变现有布局与性能）；
 * - 每个条目单独 `key`，单个树的解析失败只显示一行占位，不牵连其它插件；
 * - 数据只读：`state.*` 来自插件动作返回值，`view.*` 来自插件投影；点击只回插件 JS。
 */
@Composable
fun SlotDock(
    slot: String,
    modifier: Modifier = Modifier,
    horizontalPadding: Int = 10,
    verticalPadding: Int = 4,
    /** 铺满可用宽度（输入条/设置/菜单默认）；false = 按内容宽度，交给父布局对齐。 */
    fillWidth: Boolean = true,
) {
    val registry = LocalSlotRegistry.current ?: return
    val entries = rememberSlotEntries(registry, slot, LocalSlotHasMessages.current)
    if (entries.isEmpty()) return
    val widthModifier = if (fillWidth) modifier.fillMaxWidth() else modifier
    SlotEntriesColumn(registry, entries, widthModifier.padding(horizontal = horizontalPadding.dp, vertical = verticalPadding.dp))
}

/**
 * M87-D：`shell.overlay` 全局浮层。
 *
 * 画在原生壳最上层（抽屉/设置二级页之上），每个条目单独一层、贴右下角；条目按
 * 「插件清单顺序 → order → 注册先后」绘制，**后画的盖在上面**（最后注册的置顶）。
 * 没有条目时返回空，不产生任何布局与触摸节点；浮层的空白区域不拦截宿主点击。
 * [bottomInset] 由宿主按当前页面给（聊天页要让开输入条，别挡住发送）。
 */
@Composable
fun SlotOverlay(
    slot: String = "shell.overlay",
    horizontalPadding: Int = 12,
    verticalPadding: Int = 24,
    bottomInset: Int = 0,
) {
    val registry = LocalSlotRegistry.current ?: return
    val entries = rememberSlotEntries(registry, slot, LocalSlotHasMessages.current)
    if (entries.isEmpty()) return
    Box(Modifier.fillMaxSize()) {
        entries.forEach { entry ->
            key(entry.key) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = horizontalPadding.dp, vertical = verticalPadding.dp)
                        .padding(bottom = bottomInset.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    SlotEntry(registry, entry)
                }
            }
        }
    }
}

/** 订阅注册表变更并取出某个槽位的当前条目（listener 改的是 snapshot state）。 */
@Composable
private fun rememberSlotEntries(
    registry: SlotRegistry,
    slot: String,
    hasMessages: Boolean,
): List<SlotRegistry.Entry> {
    var tick by remember(registry) { mutableIntStateOf(0) }
    DisposableEffect(registry) {
        val listener = { tick += 1 }
        registry.addListener(listener)
        onDispose { registry.removeListener(listener) }
    }
    val revision = tick
    return remember(registry, slot, hasMessages, revision) { registry.entries(slot, hasMessages) }
}

@Composable
private fun SlotEntriesColumn(
    registry: SlotRegistry,
    entries: List<SlotRegistry.Entry>,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { entry ->
            key(entry.key) { SlotEntry(registry, entry) }
        }
    }
}

@Composable
private fun SlotEntry(registry: SlotRegistry, entry: SlotRegistry.Entry) {
    val obj = remember(entry.slot.treeJson) {
        runCatching { Json.parseToJsonElement(entry.slot.treeJson) as? JsonObject }.getOrNull()
    }
    if (obj == null) {
        Text(
            "插件「${entry.pluginName}」插槽无法解析（已跳过）",
            style = MaterialTheme.typography.labelSmall,
            color = DshTheme2.colors.danger,
        )
    } else {
        SlotNode(registry, entry, obj)
    }
}

@Composable
private fun SlotNode(registry: SlotRegistry, entry: SlotRegistry.Entry, node: JsonObject) {
    val c = DshTheme2.colors
    val type = node.string("type").orEmpty()
    val mod = node.nodeModifier(node, c)
    val children = (node["children"] as? JsonArray).orEmpty()
    when (type) {
        "column" -> Column(mod, verticalArrangement = Arrangement.spacedBy(node.gap().dp)) {
            children.forEach { child -> (child as? JsonObject)?.let { SlotNode(registry, entry, it) } }
        }
        "row" -> Row(mod, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = node.arrangement()) {
            children.forEachIndexed { index, child ->
                if (index > 0) Spacer(Modifier.width(node.gap().dp))
                (child as? JsonObject)?.let { SlotNode(registry, entry, it) }
            }
        }
        "stack" -> Box(mod) {
            children.forEach { child -> (child as? JsonObject)?.let { SlotNode(registry, entry, it) } }
        }
        "text" -> Text(
            text = node.text(registry, entry).orEmpty(),
            modifier = mod,
            color = node.color(c) ?: c.text,
            fontSize = (node.number("size") ?: 13.0).sp,
            fontWeight = node.fontWeight(),
            maxLines = node.int("maxLines") ?: Int.MAX_VALUE,
        )
        "button" -> Surface(
            modifier = mod,
            shape = RoundedCornerShape((node.number("corner") ?: 10.0).dp),
            color = node.color(c, "color") ?: c.brandSoft,
            border = androidx.compose.foundation.BorderStroke(1.dp, c.line2),
        ) {
            Text(
                text = node.text(registry, entry).orEmpty(),
                color = node.color(c, "color") ?: c.brand,
                fontSize = (node.number("size") ?: 13.0).sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable(enabled = node.boolean("disabled") != true) {
                        node.string("onTap")?.let { action ->
                            registry.fresh(entry)?.let { registry.dispatch(it, action, node.payloadJson()) }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        "switch" -> {
            val checked = node.boolean("checked")
                ?: node.bind(registry, entry)?.toBooleanStrictOrNull()
                ?: false
            Switch(
                checked = checked,
                onCheckedChange = { next ->
                    node.string("onChange")?.let { action ->
                        val payload = buildJsonObject {
                            node.payloadObject()?.forEach { (key, value) -> put(key, value) }
                            put("checked", next)
                        }
                        registry.fresh(entry)?.let { registry.dispatch(it, action, payload.toString()) }
                    }
                },
                modifier = mod,
                enabled = node.boolean("disabled") != true,
            )
        }
        "progress" -> LinearProgressIndicator(
            progress = { (node.number("value") ?: 0.0).toFloat().coerceIn(0f, 1f) },
            modifier = mod.fillMaxWidth().height(4.dp),
            color = c.brand,
        )
        "divider" -> HorizontalDivider(modifier = mod, color = c.line2)
        "spacer" -> Spacer(Modifier.height((node.number("size") ?: 6.0).dp).then(mod))
        "icon" -> Icon(
            imageVector = when (node.string("name")) {
                "whale" -> Icons.Rounded.Pets
                "extension" -> Icons.Rounded.Extension
                "info" -> Icons.Rounded.Info
                "star" -> Icons.Rounded.Star
                "bolt" -> Icons.Rounded.Bolt
                else -> Icons.Rounded.Check
            },
            contentDescription = null,
            tint = node.color(c) ?: c.text,
            modifier = mod.size((node.number("size") ?: 16.0).dp),
        )
    }
}

/** 绑定与只读文本：`state.*` 取插件动作返回的状态，`view.*` 取插件投影（null = 空串）。 */
private fun JsonObject.text(registry: SlotRegistry, entry: SlotRegistry.Entry): String? =
    bind(registry, entry) ?: string("text")

private fun JsonObject.bind(registry: SlotRegistry, entry: SlotRegistry.Entry): String? {
    val path = string("bind") ?: return null
    val parts = path.split('.')
    return when (parts.firstOrNull()) {
        "state" -> readPath(entry.stateJson, parts.drop(1))
        "view" -> registry.view(entry.pluginId, parts.drop(1).joinToString("."))
        else -> null
    }
}

private fun readPath(json: String, path: List<String>): String? {
    var current: JsonElement = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return null
    path.forEach { key ->
        current = (current as? JsonObject)?.get(key) ?: return null
    }
    return when (current) {
        is JsonPrimitive -> current.contentOrNull ?: current.toString()
        else -> current.toString()
    }
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

private fun JsonObject.number(name: String): Double? =
    (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull

private fun JsonObject.boolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.gap(): Int = (number("gap") ?: 6.0).toInt().coerceIn(0, 48)

private fun JsonObject.arrangement(): Arrangement.Horizontal = when (string("justify")) {
    "center" -> Arrangement.Center
    "end" -> Arrangement.End
    "between" -> Arrangement.SpaceBetween
    else -> Arrangement.Start
}

private fun JsonObject.fontWeight(): FontWeight = when (string("weight")) {
    "medium" -> FontWeight.Medium
    "bold" -> FontWeight.Bold
    else -> FontWeight.Normal
}

private fun JsonObject.color(c: DshColors, name: String = "color"): Color? {
    val token = string(name) ?: return null
    return colorOf(c, token)
}

private fun colorOf(c: DshColors, token: String): Color? = when (token) {
    "brand" -> c.brand
    "sub" -> c.sub
    "faint" -> c.faint
    "warn" -> c.warn
    "error" -> c.danger
    "success" -> c.success
    "surface" -> c.surface
    "background" -> c.background
    "text" -> c.text
    else -> null
}

private fun JsonObject.payloadJson(): String? {
    val payload = this["payload"] ?: return null
    return if (payload is JsonObject || payload is JsonArray) payload.toString() else null
}

private fun JsonObject.payloadObject(): JsonObject? = this["payload"] as? JsonObject

/** 布局修饰：padding / background / corner / 可见性。 */
private fun JsonObject.nodeModifier(node: JsonObject, c: DshColors): Modifier {
    var mod: Modifier = Modifier
    val padding = node.number("padding")?.toInt()?.coerceIn(0, 48) ?: 0
    val paddingH = node.number("paddingH")?.toInt()?.coerceIn(0, 48) ?: 0
    val paddingV = node.number("paddingV")?.toInt()?.coerceIn(0, 48) ?: 0
    if (padding > 0) mod = mod.padding(padding.dp)
    if (paddingH > 0 || paddingV > 0) mod = mod.padding(horizontal = paddingH.dp, vertical = paddingV.dp)
    val corner = node.number("corner")?.toInt()?.coerceIn(0, 32)
    val background = node.string("background")?.let { colorOf(c, it) }
    if (background != null) {
        mod = mod
            .background(background, RoundedCornerShape((corner ?: 0).dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    } else if (corner != null && corner > 0) {
        mod = mod.background(Color.Transparent, RoundedCornerShape(corner.dp))
    }
    return mod
}
