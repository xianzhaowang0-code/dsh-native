package xin.dshaa.core.session

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * M87-E（docs/181 §3.5）：写入钩子的 JVM 锁定。
 *
 * 覆盖「拒绝 → 不落盘/不占 seq」「可信补写绕过钩子」「只对 user/tool 两类事件问钩子」
 * 与插件记录标记（`source.kind=plugin-*`），真机上的插件 dispatch 在 fixture 验。
 */
class SessionWriteHooksTest {

    @AfterTest
    fun reset() {
        SessionWriteHooks.resetForTest()
    }

    private fun session(prefix: String): Pair<SessionStore, SessionSession> {
        val root = File(Files.createTempDirectory(prefix).toFile(), "sessions")
        val store = SessionStores.create(root)
        return store to store.create(cwd = "/tmp/ws", model = "deepseek-flash")
    }

    @Test
    fun `vetoed user message is not persisted and does not take a seq`() {
        val (store, session) = session("dsh-write-veto")
        var seen: PendingSessionEvent? = null
        SessionWriteHooks.gate = object : SessionAppendGate {
            override fun beforeAppend(event: PendingSessionEvent): SessionAppendDecision {
                seen = event
                return if (event.type == "user/message") {
                    SessionAppendDecision(allow = false, reason = "测试拒绝", pluginId = "p1")
                } else {
                    SessionAppendDecision()
                }
            }
        }
        val before = session.events.size
        val failure = assertFailsWith<SessionAppendVetoed> { session.appendUserMessage("VETO") }
        assertEquals("user/message", failure.type)
        assertEquals("p1", failure.pluginId)
        assertEquals("user", seen?.sourceKind)
        assertEquals(before, session.events.size)
        // 重新读盘也不该有这条消息（落盘是唯一真相）
        val loaded = store.load(session.header.id)
        assertTrue(loaded != null)
        assertTrue(loaded.events.none { it.type == "user/message" })
        // 拒绝不占用 seq：下一条事件的 seq 接在旧值后面
        val next = session.appendRaw("plan/mode", kotlinx.serialization.json.buildJsonObject { put("active", true) })
        assertEquals(0L, next)
    }

    @Test
    fun `trusted host tool result bypasses the gate`() {
        val (store, session) = session("dsh-write-trusted")
        var calls = 0
        SessionWriteHooks.gate = object : SessionAppendGate {
            override fun beforeAppend(event: PendingSessionEvent): SessionAppendDecision {
                calls++
                return SessionAppendDecision(allow = false, reason = "拒绝所有工具结果", pluginId = "p1")
            }
        }
        val seq = session.appendToolResultTrusted("call-1", "宿主补写的错误结果", isError = true)
        assertTrue(seq >= 0)
        assertEquals(0, calls, "trusted 补写不能再问钩子")
        val loaded = store.load(session.header.id)
        val text = loaded?.events?.filter { it.type == "tool/result" }?.size
        assertEquals(1, text)
    }

    @Test
    fun `only user and tool events are vetoable`() {
        val (_, session) = session("dsh-write-types")
        val seenTypes = ArrayList<String>()
        SessionWriteHooks.gate = object : SessionAppendGate {
            override fun beforeAppend(event: PendingSessionEvent): SessionAppendDecision {
                seenTypes += event.type
                return SessionAppendDecision()
            }
        }
        session.startTurn()
        session.appendRaw("plan/mode", kotlinx.serialization.json.buildJsonObject { put("active", true) })
        session.appendUserMessage("hello")
        session.appendToolResult("call-1", "ok", isError = false)
        assertEquals(listOf("user/message", "tool/result"), seenTypes)
    }

    @Test
    fun `plugin records are marked by source kind`() {
        val (_, session) = session("dsh-write-plugin-record")
        val seen = ArrayList<PendingSessionEvent>()
        SessionWriteHooks.gate = object : SessionAppendGate {
            override fun beforeAppend(event: PendingSessionEvent): SessionAppendDecision {
                seen += event
                return SessionAppendDecision()
            }
        }
        session.appendUserMessageTexts(
            listOf("[plugin] injected"),
            source = kotlinx.serialization.json.buildJsonObject { put("kind", "plugin-inject") },
        )
        assertEquals(1, seen.size)
        assertEquals("plugin-inject", seen.single().sourceKind)
        assertTrue(seen.single().pluginRecord)
        assertTrue(seen.single().dataJson.contains("injected"))
    }

    @Test
    fun `host bypass skips the gate entirely`() {
        // M87-F：插件中间件总开关关闭时，宿主置 SessionWriteHooks.bypass=true；
        // 事件正常落盘、gate 一次都不问。
        val (store, session) = session("dsh-write-bypass")
        var calls = 0
        SessionWriteHooks.gate = object : SessionAppendGate {
            override fun beforeAppend(event: PendingSessionEvent): SessionAppendDecision {
                calls++
                return SessionAppendDecision(allow = false, reason = "不该被问到", pluginId = "p1")
            }
        }
        SessionWriteHooks.bypass = true
        assertEquals(0L, session.appendUserMessage("BYPASS-OK"))
        assertEquals(0, calls, "bypass 时不能进 gate")
        val loaded = store.load(session.header.id)
        assertTrue(loaded != null)
        assertEquals("user/message", loaded.events.last().type)
        assertTrue(loaded.events.last().data.toString().contains("BYPASS-OK"))
    }
}
