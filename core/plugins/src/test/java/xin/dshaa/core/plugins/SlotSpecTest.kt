package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M87-C（docs/181 §3.4）：声明式插槽契约的 JVM 锁定。
 *
 * 覆盖节点预算、未知槽位/原语/字段、只读绑定、动作 id、规范化往返与条件过滤；
 * 真实 JS 注册与渲染在真机 fixture 验（QuickJS 只有 arm64 JNI）。
 */
class SlotSpecTest {

    private fun declaration(
        slot: String = "chat.composer.dock",
        id: String = "mirror",
        order: Int = 100,
        whenSession: String = "any",
        tree: kotlinx.serialization.json.JsonObject,
    ) = buildJsonObject {
        put("slot", slot)
        put("id", id)
        put("order", order)
        put("when", buildJsonObject { put("session", whenSession) })
        put("tree", tree)
    }

    private fun text(value: String = "hi") = buildJsonObject {
        put("type", "text")
        put("text", value)
    }

    @Test
    fun `valid slot is canonicalized and round trips`() {
        val parsed = SlotSpec.parse(
            declaration(
                tree = buildJsonObject {
                    put("type", "row")
                    put("gap", 6)
                    put(
                        "children",
                        buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", "镜像")
                                put("color", "brand")
                            })
                            add(buildJsonObject {
                                put("type", "button")
                                put("text", "开关")
                                put("onTap", "mirror/toggle")
                                put("payload", buildJsonObject { put("k", "v") })
                            })
                        },
                    )
                },
            ),
        )
        val slot = assertNotNull(parsed.slot, parsed.error)
        assertEquals("chat.composer.dock", slot.slot)
        assertEquals("mirror", slot.id)
        assertEquals("any", slot.whenSession)
        assertEquals("chat.composer.dock/mirror", slot.key)
        // 规范化后的声明能再次解析且相等（plugins.json 往返）
        val again = assertNotNull(SlotSpec.parse(SlotSpec.declaration(slot)).slot)
        assertEquals(slot, again)
        assertEquals(slot.treeJson, again.treeJson)
    }

    @Test
    fun `unknown slot and primitive are rejected`() {
        assertTrue(SlotSpec.parse(declaration(slot = "chat.banner", tree = text())).error!!.contains("未知插槽"))
        assertTrue(
            SlotSpec.parse(declaration(tree = buildJsonObject { put("type", "webview") })).error!!
                .contains("不允许原语"),
        )
        assertNull(SlotSpec.parse(declaration(slot = "shell.overlay", tree = text())).error)
    }

    @Test
    fun `all documented slots are accepted`() {
        // docs/181 §3.4 清单在 M87-D 全部开放；新增槽位不需要改 JS 侧注册 API。
        for (name in SlotSpec.SLOTS) {
            val parsed = SlotSpec.parse(declaration(slot = name, id = "probe", tree = text("x")))
            val slot = assertNotNull(parsed.slot, "$name 应被接受：${parsed.error}")
            assertEquals(name, slot.slot)
        }
        assertEquals(8, SlotSpec.SLOTS.size)
        assertNull(SlotSpec.parse(declaration(slot = "chat.banner", tree = text())).slot)
    }

    @Test
    fun `overlay keeps webview and image disabled`() {
        // shell.overlay 仍然只能是受限 Surface 子树：不放 webview/image（docs/181 §3.4 安全边界）。
        for (type in listOf("webview", "image")) {
            val bad = buildJsonObject {
                put("type", type)
                put("url", "https://example.com")
            }
            val error = SlotSpec.parse(declaration(slot = "shell.overlay", tree = bad)).error
            assertNotNull(error)
            assertTrue(error!!.contains("不允许原语"), "$type 应被拒绝")
        }
    }

    @Test
    fun `node budget depth and text limits are enforced`() {
        val many = buildJsonObject {
            put("type", "column")
            put("children", buildJsonArray { repeat(SlotSpec.MAX_NODES + 1) { add(text()) } })
        }
        assertTrue(SlotSpec.parse(declaration(tree = many)).error!!.contains("过多"))

        fun nested(depth: Int): kotlinx.serialization.json.JsonObject =
            if (depth <= 0) text() else buildJsonObject {
                put("type", "column")
                put("children", buildJsonArray { add(nested(depth - 1)) })
            }
        assertTrue(SlotSpec.parse(declaration(tree = nested(SlotSpec.MAX_DEPTH + 1))).error!!.contains("过深"))

        val longText = text("x".repeat(SlotSpec.MAX_TEXT + 1))
        assertTrue(SlotSpec.parse(declaration(tree = longText)).error!!.contains("超过"))
    }

    @Test
    fun `only read only state and view bindings are allowed`() {
        fun bound(path: String) = buildJsonObject {
            put("type", "text")
            put("bind", path)
        }
        assertNotNull(SlotSpec.parse(declaration(tree = bound("state.mirrored"))).slot)
        assertNotNull(SlotSpec.parse(declaration(tree = bound("view.turns"))).slot)
        assertNotNull(SlotSpec.parse(declaration(tree = bound("state"))).slot)
        for (bad in listOf("chat.model", "state.a.b.c.d", "state[0]", "process.env", "state..x")) {
            assertNull(SlotSpec.parse(declaration(tree = bound(bad))).slot, "不应允许绑定：$bad")
        }
    }

    @Test
    fun `action ids and payload are constrained`() {
        fun button(action: String) = buildJsonObject {
            put("type", "button")
            put("text", "x")
            put("onTap", action)
        }
        assertNotNull(SlotSpec.parse(declaration(tree = button("mirror/toggle"))).slot)
        assertNotNull(SlotSpec.parse(declaration(tree = button("a.b:c-d_e"))).slot)
        assertNull(SlotSpec.parse(declaration(tree = button("rm -rf"))).slot)
        assertNull(SlotSpec.parse(declaration(tree = button(""))).slot)
    }

    @Test
    fun `unknown fields colors and values are rejected`() {
        val unknownProp = buildJsonObject {
            put("type", "text")
            put("text", "x")
            put("onLongPress", "nope")
        }
        assertTrue(SlotSpec.parse(declaration(tree = unknownProp)).error!!.contains("未知或不允许的字段"))

        val badColor = buildJsonObject {
            put("type", "text")
            put("text", "x")
            put("color", "#ff0000")
        }
        assertTrue(SlotSpec.parse(declaration(tree = badColor)).error!!.contains("主题 token"))

        val badProgress = buildJsonObject {
            put("type", "progress")
            put("value", 2)
        }
        assertTrue(SlotSpec.parse(declaration(tree = badProgress)).error!!.contains("0–1"))
    }

    @Test
    fun `parse all drops invalid duplicates and caps declarations`() {
        val good = declaration(id = "a", tree = text())
        val duplicate = declaration(id = "a", tree = text())
        val bad = declaration(slot = "nope", tree = text())
        val all = buildJsonArray {
            add(good)
            add(duplicate)
            add(bad)
            repeat(SlotSpec.MAX_DECLARATIONS + 2) { index -> add(declaration(id = "id$index", tree = text())) }
        }
        val parsed = SlotSpec.parseAll(all)
        // take(MAX_DECLARATIONS) 先截断：16 条里 1 条重复 + 1 条非法 → 14 条生效
        assertEquals(14, parsed.size)
        assertTrue(parsed.size <= SlotSpec.MAX_DECLARATIONS)
        assertEquals(parsed.map { it.id }.distinct().size, parsed.size)
        assertFalse(parsed.any { it.slot == "nope" })

        // 全合法时上限就是 MAX_DECLARATIONS
        val manyValid = buildJsonArray {
            repeat(SlotSpec.MAX_DECLARATIONS + 3) { index -> add(declaration(id = "v$index", tree = text())) }
        }
        assertEquals(SlotSpec.MAX_DECLARATIONS, SlotSpec.parseAll(manyValid).size)
    }

    @Test
    fun `when session condition filters`() {
        val any = assertNotNull(SlotSpec.parse(declaration(tree = text())).slot)
        val has = assertNotNull(SlotSpec.parse(declaration(whenSession = "hasMessages", tree = text())).slot)
        val empty = assertNotNull(SlotSpec.parse(declaration(whenSession = "empty", tree = text())).slot)
        assertTrue(SlotSpec.matches(any, hasMessages = false))
        assertTrue(SlotSpec.matches(has, hasMessages = true))
        assertFalse(SlotSpec.matches(has, hasMessages = false))
        assertTrue(SlotSpec.matches(empty, hasMessages = false))
        assertFalse(SlotSpec.matches(empty, hasMessages = true))
        assertTrue(SlotSpec.parse(declaration(whenSession = "sometimes", tree = text())).error != null)
    }

    @Test
    fun `declaration rejects non object trees`() {
        val raw = Json.parseToJsonElement("""{"slot":"settings.rows","id":"x","tree":[]}""").jsonObject
        assertTrue(SlotSpec.parse(raw).error!!.contains("必须是对象"))
    }
}
