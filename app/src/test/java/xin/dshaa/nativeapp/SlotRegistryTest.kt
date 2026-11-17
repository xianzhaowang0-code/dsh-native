package xin.dshaa.nativeapp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dshaa.core.plugins.PluginSlot
import xin.dshaa.ui.shellapi.SlotRegistry

/**
 * M87-C/D：插槽注册表的宿主侧行为（纯 JVM，不依赖 Compose/QuickJS）。
 *
 * 覆盖顺序（插件清单顺序 → order → 注册先后）、条件过滤、停用清理、状态往返与动作回调，
 * 以及 M87-D 新增槽位（消息/抽屉/浮层）的隔离与浮层叠放顺序。
 */
class SlotRegistryTest {

    private fun slot(
        slot: String = "chat.composer.dock",
        id: String = "dock",
        order: Int = 100,
        whenSession: String = "any",
        text: String = "text",
    ) = PluginSlot(
        slot = slot,
        id = id,
        order = order,
        whenSession = whenSession,
        treeJson = buildJsonObject {
            put("type", "text")
            put("text", text)
        }.toString(),
    )

    @Test
    fun `entries are ordered by plugin order then order then registration`() {
        val registry = SlotRegistry()
        registry.syncPlugin("p1", "插件一", listOf(slot(id = "b", order = 50), slot(id = "a", order = 50)))
        registry.syncPlugin("p2", "插件二", listOf(slot(id = "c", order = 10)))
        val entries = registry.entries("chat.composer.dock", hasMessages = true)
        assertEquals(listOf("c", "b", "a"), entries.map { it.slot.id })
        // 同插件内 order 小的在前；同 order 保持注册先后
        assertEquals(listOf("p2", "p1", "p1"), entries.map { it.pluginId })
    }

    @Test
    fun `sync replaces a plugin's slots and remove clears them`() {
        val registry = SlotRegistry()
        registry.syncPlugin("p1", "插件一", listOf(slot(id = "a"), slot(id = "b")))
        assertEquals(2, registry.all().size)
        registry.syncPlugin("p1", "插件一", listOf(slot(id = "c")))
        assertEquals(listOf("c"), registry.all().map { it.slot.id })
        registry.removePlugin("p1")
        assertTrue(registry.isEmpty())
        // 移除不存在的插件不报错
        registry.removePlugin("nope")
    }

    @Test
    fun `conditions filter by session messages`() {
        val registry = SlotRegistry()
        registry.syncPlugin(
            "p1",
            "插件一",
            listOf(slot(id = "any"), slot(id = "has", whenSession = "hasMessages"), slot(id = "empty", whenSession = "empty")),
        )
        assertEquals(listOf("any", "has"), registry.entries("chat.composer.dock", true).map { it.slot.id })
        assertEquals(listOf("any", "empty"), registry.entries("chat.composer.dock", false).map { it.slot.id })
        assertTrue(registry.entries("settings.rows", true).isEmpty())
    }

    @Test
    fun `state updates survive and are exposed to listeners`() {
        val registry = SlotRegistry()
        var notifications = 0
        val listener: () -> Unit = { notifications += 1 }
        registry.addListener(listener)
        registry.syncPlugin("p1", "插件一", listOf(slot(id = "a")))
        assertEquals(1, notifications)
        val entry = registry.entries("chat.composer.dock", true).single()
        assertEquals("{}", entry.stateJson)
        assertTrue(registry.updateState("p1", "chat.composer.dock/a", """{"mirrored":true}"""))
        val updated = registry.entries("chat.composer.dock", true).single()
        assertEquals("""{"mirrored":true}""", updated.stateJson)
        assertEquals(2, notifications)
        // 不存在的条目 / 已停用插件更新失败
        assertFalse(registry.updateState("p1", "chat.composer.dock/nope", "{}"))
        registry.removeListener(listener)
        registry.syncPlugin("p1", "插件一", listOf(slot(id = "a")))
        assertEquals(2, notifications)
    }

    @Test
    fun `action handler receives the entry and payload`() {
        val registry = SlotRegistry()
        registry.syncPlugin("p1", "插件一", listOf(slot(id = "a")))
        var seen: Triple<String, String, String?>? = null
        registry.actionHandler = { entry, action, payload -> seen = Triple(entry.pluginId, action, payload) }
        registry.dispatch(registry.entries("chat.composer.dock", true).single(), "mirror/toggle", """{"checked":true}""")
        assertEquals(Triple("p1", "mirror/toggle", """{"checked":true}"""), seen)
        // 没有 handler 时点击是 no-op（不崩）
        registry.actionHandler = null
        registry.dispatch(registry.entries("chat.composer.dock", true).single(), "mirror/toggle", null)
    }

    @Test
    fun `extended slots stay isolated and overlay orders last on top`() {
        val registry = SlotRegistry()
        registry.syncPlugin(
            "p1",
            "插件一",
            listOf(
                slot(slot = "chat.node.user", id = "u"),
                slot(slot = "chat.node.assistant", id = "a"),
                slot(slot = "chat.message.actions", id = "m"),
                slot(slot = "drawer.items", id = "d"),
                slot(slot = "shell.overlay", id = "low", order = 10),
            ),
        )
        registry.syncPlugin("p2", "插件二", listOf(slot(slot = "shell.overlay", id = "high", order = 20)))
        // 每个槽位只返回自己的条目
        assertEquals(listOf("u"), registry.entries("chat.node.user", true).map { it.slot.id })
        assertEquals(listOf("a"), registry.entries("chat.node.assistant", true).map { it.slot.id })
        assertEquals(listOf("m"), registry.entries("chat.message.actions", true).map { it.slot.id })
        assertEquals(listOf("d"), registry.entries("drawer.items", true).map { it.slot.id })
        // 浮层按 order 小→大返回；渲染层后画的盖在上面（最后注册/大 order 置顶）
        assertEquals(listOf("low", "high"), registry.entries("shell.overlay", true).map { it.slot.id })
    }

    @Test
    fun `view resolver is delegated per plugin`() {
        val registry = SlotRegistry()
        registry.viewResolver = { pluginId, path -> if (pluginId == "p1") "view:$path" else null }
        assertEquals("view:turns", registry.view("p1", "turns"))
        assertEquals(null, registry.view("p2", "turns"))
    }
}
