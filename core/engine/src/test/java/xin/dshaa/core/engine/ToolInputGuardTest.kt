package xin.dshaa.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M88-G（docs/183 §7 / §10）：工具输入上限。
 *
 * 默认 64KB；`write` / `edit` 512KB；UTF-8 字节计算；超限与半截 JSON 都返回显式错误，
 * 恰好等于上限放行。
 */
class ToolInputGuardTest {

    @Test
    fun defaultLimitIs64KbAndWriteEditAre512Kb() {
        assertEquals(64 * 1024, ToolInputGuard.limitFor("fake_tool"))
        assertEquals(64 * 1024, ToolInputGuard.limitFor("bash"))
        assertEquals(512 * 1024, ToolInputGuard.limitFor("write"))
        assertEquals(512 * 1024, ToolInputGuard.limitFor("edit"))
    }

    @Test
    fun oversizedInputReturnsActionableError() {
        val raw = "{\"q\":\"" + "a".repeat(64 * 1024) + "\"}"
        val message = assertNotNull(ToolInputGuard.violation("fake_tool", raw))
        assertTrue(message.contains("工具输入过长"), message)
        assertTrue(message.contains("${ToolInputGuard.byteSize(raw)} 字节"), message)
        assertTrue(message.contains("65536 字节"), message)
        assertTrue(message.contains("分段"), message)
    }

    @Test
    fun exactlyAtLimitIsAllowed() {
        val raw = "{\"q\":\"" + "a".repeat(64 * 1024 - 8) + "\"}"
        assertEquals(64 * 1024, ToolInputGuard.byteSize(raw))
        assertNull(ToolInputGuard.violation("fake_tool", raw))
        assertNotNull(ToolInputGuard.parseStrict(raw))
    }

    @Test
    fun writeUsesTheLargerLimit() {
        // 64KB 以上、512KB 以内：普通工具拒绝，write 放行。
        val raw = "{\"path\":\"a.txt\",\"content\":\"" + "a".repeat(128 * 1024) + "\"}"
        assertNotNull(ToolInputGuard.violation("fake_tool", raw))
        assertNull(ToolInputGuard.violation("write", raw))
        assertNull(ToolInputGuard.violation("edit", raw))

        // 超过 512KB：write 也拒绝。
        val tooBig = "{\"content\":\"" + "a".repeat(512 * 1024) + "\"}"
        val message = assertNotNull(ToolInputGuard.violation("write", tooBig))
        assertTrue(message.contains("524288 字节"), message)
    }

    @Test
    fun halfJsonAndNonObjectJsonAreRejected() {
        assertTrue(assertNotNull(ToolInputGuard.violation("fake_tool", "{\"q\":\"半截")).contains("不是合法 JSON"))
        assertTrue(assertNotNull(ToolInputGuard.violation("fake_tool", "[1,2,3]")).contains("不是合法 JSON"))
        assertNull(ToolInputGuard.violation("fake_tool", ""), "空参数按 {} 处理")
        assertNull(ToolInputGuard.violation("fake_tool", "{}"))
    }

    @Test
    fun utf8BytesAreCountedNotCharacters() {
        val raw = "{\"q\":\"" + "中".repeat(30) + "\"}"
        assertTrue(ToolInputGuard.byteSize(raw) > raw.length)
        assertEquals(raw.length + 2 * 30, ToolInputGuard.byteSize(raw))
    }
}
