package xin.dshaa.nativeapp.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.engine.ToolCallView
import xin.dshaa.core.protocol.ApiTool
import xin.dshaa.core.tools.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M87-A（docs/181 §3.2）：插件工具中间件的宿主侧适配。
 * 纯 JVM：假 dispatch 返回 JSON，不需要 QuickJS / 设备。
 */
class PluginToolMiddlewareTest {

    private val call = ToolCallView(
        id = "call-1",
        name = "bash",
        args = buildJsonObject { put("command", "echo hi") },
        rawArguments = """{"command":"echo hi"}""",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun middleware(
        meta: ToolMiddlewareMeta,
        granted: Boolean = true,
        dispatch: (String, String) -> String? = { _, _ -> null },
    ) = PluginToolMiddleware(
        pluginId = "demo.plugin",
        pluginName = "演示插件",
        meta = meta,
        interceptGranted = granted,
        dispatch = dispatch,
        scope = scope,
        log = {},
    )

    @Test
    fun `tool patterns support only star`() {
        assertTrue(toolPatternMatches("*", "bash"))
        assertTrue(toolPatternMatches("bash", "bash"))
        assertFalse(toolPatternMatches("bash", "bash_extra"))
        assertTrue(toolPatternMatches("shower_*", "shower_tap"))
        assertTrue(toolPatternMatches("*_tool", "fake_tool"))
        assertTrue(toolPatternMatches("fake*tool", "fake_tool"))
        assertFalse(toolPatternMatches("fake*tool", "fake_tool_x"))
        assertFalse(toolPatternMatches("shower_*", "download"))
    }

    @Test
    fun `restrict removes tools without intercept permission`() {
        val m = middleware(
            ToolMiddlewareMeta(active = true, restrict = listOf("bash", "shower_*")),
            granted = false,
        )
        assertFalse(m.allowTool("bash"))
        assertFalse(m.allowTool("shower_tap"))
        assertTrue(m.allowTool("read"))
    }

    @Test
    fun `describe only applies with intercept permission`() {
        val meta = ToolMiddlewareMeta(
            active = true,
            describe = listOf(
                ToolDescribe("bash", buildJsonObject {
                    put("description", "rewritten")
                    put("parameters", buildJsonObject { put("type", "object") })
                }),
            ),
        )
        val tool = ApiTool("bash", "original", buildJsonObject { })
        assertEquals("rewritten", middleware(meta).describe(tool)!!.description)
        assertNull(middleware(meta, granted = false).describe(tool))
        assertNull(middleware(ToolMiddlewareMeta(active = true)).describe(tool))
    }

    @Test
    fun `guard denial is structured and blank reply allows`() = runBlocking {
        var asked = 0
        var guardPayload = ""
        val deny = middleware(
            ToolMiddlewareMeta(active = true, guard = listOf("*")),
        ) { method, body ->
            asked++
            guardPayload = body
            assertEquals("tools.guard", method)
            """{"ok":true,"deny":true,"message":"rm -rf 被禁止"}"""
        }
        val message = deny.guard(call)
        assertNotNull(message)
        val text = message!!
        assertTrue("拒绝文案要标插件：$text", text.contains("演示插件"))
        assertTrue(text.contains("rm -rf 被禁止"))
        assertEquals(1, asked)
        // JS 侧读 payload.call：宿主要把调用包在 `call` 里（vivo 真机 fixture 才发现过这个 bug）。
        assertTrue("guard payload 必须有 call 包装：$guardPayload", guardPayload.contains("\"call\""))
        assertTrue("guard payload 要带工具名：$guardPayload", guardPayload.contains("\"bash\""))

        val allow = middleware(ToolMiddlewareMeta(active = true, guard = listOf("bash"))) { _, _ ->
            """{"ok":true,"deny":false}"""
        }
        assertNull(allow.guard(call))
        // 没有 guard 注册时不往返
        var called = 0
        assertNull(
            middleware(ToolMiddlewareMeta(active = true)) { _, _ -> called++; "{}" }.guard(call),
        )
        // guard 只收紧，未授权 tools:intercept 也照常生效
        val tightenOnly = middleware(ToolMiddlewareMeta(active = true, guard = listOf("*")), granted = false) { _, _ ->
            called++
            """{"ok":true,"deny":true,"message":"no"}"""
        }
        assertNotNull(tightenOnly.guard(call))
        assertEquals(1, called)
    }

    @Test
    fun `preExecute rewrites args from js reply`() = runBlocking {
        var payload = ""
        val m = middleware(
            ToolMiddlewareMeta(active = true, preExecute = 1),
        ) { method, body ->
            assertEquals("tools.preExecute", method)
            payload = body
            """{"ok":true,"call":{"id":"call-1","name":"bash","args":{"command":"ls"},"rawArguments":"{\"command\":\"ls\"}"}}"""
        }
        val rewritten = m.preExecute(call)
        assertNotNull(rewritten)
        assertEquals("ls", rewritten!!.args["command"]!!.jsonPrimitive.content)
        assertEquals("""{"command":"ls"}""", rewritten.rawArguments)
        assertTrue("JS 要拿到原始参数：$payload", payload.contains("echo hi"))
        assertTrue("pre-execute payload 必须有 call 包装：$payload", payload.contains("\"call\""))
    }

    @Test
    fun `postExecute rewrites text and isError but keeps images`() = runBlocking {
        val original = ToolResult.ok("see example.com", meta = mapOf("k" to "v"))
        val m = middleware(
            ToolMiddlewareMeta(active = true, postExecute = 1),
        ) { method, body ->
            assertEquals("tools.postExecute", method)
            assertTrue("JS 要拿到原始结果：$body", body.contains("see example.com"))
            """{"ok":true,"result":{"text":"see mirror.local","isError":true}}"""
        }
        val rewritten = m.postExecute(call, original)
        assertNotNull(rewritten)
        assertEquals("see mirror.local", rewritten!!.text)
        assertTrue(rewritten.isError)
        assertEquals("meta/图片等未声明字段保持原样", original.meta, rewritten.meta)
        assertEquals(original.images, rewritten.images)
        // 没有注册 post 钩子（或未授权）不往返
        assertNull(middleware(ToolMiddlewareMeta(active = true)).postExecute(call, original))
        assertNull(
            middleware(ToolMiddlewareMeta(active = true, postExecute = 1), granted = false)
                .postExecute(call, original),
        )
    }

    @Test
    fun `result observer is fire and forget`() = runBlocking {
        val seen = mutableListOf<String>()
        val delivered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val m = middleware(
            ToolMiddlewareMeta(active = true, result = 1),
        ) { method, _ ->
            seen += method
            delivered.complete(Unit)
            """{"ok":true}"""
        }
        m.observe(call, ToolResult.ok("value"))
        // 不 sleep：等 dispatch 真正完成（IO 线程），再断言。
        kotlinx.coroutines.withTimeout(2_000) { delivered.await() }
        assertEquals(listOf("tools.result"), seen)
        // 未注册/未授权不观测
        middleware(ToolMiddlewareMeta(active = true)).observe(call, ToolResult.ok("x"))
        middleware(ToolMiddlewareMeta(active = true, result = 1), granted = false).observe(call, ToolResult.ok("x"))
        assertEquals(listOf("tools.result"), seen)
    }

    @Test
    fun `dispatch json parsing tolerates garbage`() = runBlocking {
        val m = middleware(ToolMiddlewareMeta(active = true, guard = listOf("*"))) { _, _ -> "not json" }
        assertNull(m.guard(call))
        // 坏 JSON 的 pre 也不崩
        val pre = middleware(ToolMiddlewareMeta(active = true, preExecute = 1)) { _, _ -> "{" }
        assertNull(pre.preExecute(call))
    }
}
