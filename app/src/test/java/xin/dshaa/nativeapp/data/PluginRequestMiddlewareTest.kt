package xin.dshaa.nativeapp.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dshaa.core.engine.RequestContext
import xin.dshaa.core.engine.StepView
import xin.dshaa.core.engine.StreamDelta
import xin.dshaa.core.protocol.ApiMessage
import xin.dshaa.core.protocol.ApiRequest
import xin.dshaa.core.protocol.ApiTool
import xin.dshaa.core.protocol.ContentBlock
import xin.dshaa.core.protocol.ProtocolJson

/**
 * M87-B（docs/181 §3.3）：插件请求/提示词中间件的宿主侧适配。
 * 纯 JVM：假 dispatch 返回 JSON，不需要 QuickJS / 设备（真实 JS 往返在真机 fixture 验）。
 */
class PluginRequestMiddlewareTest {

    private val context = RequestContext("session-9", "deepseek", "deepseek-flash")

    private val request = ApiRequest(
        model = "deepseek-flash",
        messages = listOf(ApiMessage("user", listOf(ContentBlock.Text("hi")))),
        maxTokens = 2048,
        system = "SYS",
        tools = listOf(ApiTool("fake_tool", "fake", buildJsonObject { put("type", "object") })),
    )

    private fun middleware(
        meta: LlmMiddlewareMeta,
        granted: Boolean = true,
        log: (String) -> Unit = {},
        dispatch: (String, String) -> String? = { _, _ -> null },
    ) = PluginRequestMiddleware(
        pluginId = "demo.plugin",
        pluginName = "演示插件",
        meta = meta,
        granted = granted,
        dispatch = dispatch,
        log = log,
    )

    @Test
    fun `assemble sends prompt and context then takes the reply`() = runBlocking {
        var payload = ""
        val m = middleware(LlmMiddlewareMeta(active = true, assemble = 1)) { method, body ->
            assertEquals("ext.assemble", method)
            payload = body
            """{"ok":true,"value":"SYS+EXT"}"""
        }
        assertEquals("SYS+EXT", m.assembleSystemPrompt("SYS", context))
        assertTrue("要带原始 system：$payload", payload.contains("SYS"))
        assertTrue("要带 session（按会话改写）：$payload", payload.contains("session-9"))
        assertTrue(payload.contains("deepseek-flash"))
    }

    @Test
    fun `assemble is gated and skipped when not registered`() = runBlocking {
        var called = 0
        val dispatch: (String, String) -> String? = { _, _ -> called++; """{"ok":true,"value":"X"}""" }
        // 未授权：即使注册了也不往返
        assertNull(middleware(LlmMiddlewareMeta(active = true, assemble = 1), granted = false, dispatch = dispatch)
            .assembleSystemPrompt("SYS", context))
        // 没注册这个点：不往返
        assertNull(middleware(LlmMiddlewareMeta(active = true), dispatch = dispatch)
            .assembleSystemPrompt("SYS", context))
        assertEquals(0, called)
    }

    @Test
    fun `request rewrite round trips through protocol json`() = runBlocking {
        var payload = ""
        val rewrittenJson = ProtocolJson.encodeRequest(
            request.copy(
                model = "deepseek-router",
                messages = request.messages + ApiMessage("user", listOf(ContentBlock.Text("ROUTED"))),
            ),
        )
        val m = middleware(LlmMiddlewareMeta(active = true, request = 1)) { method, body ->
            assertEquals("ext.request", method)
            payload = body
            """{"ok":true,"value":$rewrittenJson}"""
        }
        val rewritten = m.rewriteRequest(request, context)
        assertNotNull(rewritten)
        assertEquals("deepseek-router", rewritten!!.model)
        assertTrue(rewritten.messages.last().content.filterIsInstance<ContentBlock.Text>().single().text == "ROUTED")
        assertTrue("JS 要看到原标题/原正文：$payload", payload.contains("deepseek-flash"))
        assertTrue(payload.contains("session-9"))
    }

    @Test
    fun `request rewrite tolerates garbage and bad json`() = runBlocking {
        val notObject = middleware(LlmMiddlewareMeta(active = true, request = 1)) { _, _ -> """{"ok":true,"value":"nope"}""" }
        assertNull(notObject.rewriteRequest(request, context))
        val badJson = middleware(LlmMiddlewareMeta(active = true, request = 1)) { _, _ -> "not json" }
        assertNull(badJson.rewriteRequest(request, context))
        val notOk = middleware(LlmMiddlewareMeta(active = true, request = 1)) { _, _ ->
            """{"ok":false,"error":"插件拒绝"}"""
        }
        assertNull(notOk.rewriteRequest(request, context))
        // 超时/常驻运行时不在 = null
        assertNull(middleware(LlmMiddlewareMeta(active = true, request = 1)) { _, _ -> null }
            .rewriteRequest(request, context))
    }

    @Test
    fun `stream delta rewrite takes text only`() = runBlocking {
        var payload = ""
        val m = middleware(LlmMiddlewareMeta(active = true, stream = 1)) { method, body ->
            assertEquals("ext.stream", method)
            payload = body
            """{"ok":true,"text":"bar"}"""
        }
        val next = m.transformStreamDelta(StreamDelta(4, "foo", reasoning = true), context)
        assertNotNull(next)
        assertEquals("bar", next!!.text)
        assertEquals(4, next.index)
        assertTrue(next.reasoning)
        assertTrue("要带 reasoning 标记：$payload", payload.contains("\"reasoning\":true"))
        assertTrue("要带原文本：$payload", payload.contains("foo"))
        // 文本没变 = 不改（引擎据此零拷贝透传）
        val same = middleware(LlmMiddlewareMeta(active = true, stream = 1)) { _, _ -> """{"ok":true,"text":"foo"}""" }
        assertNull(same.transformStreamDelta(StreamDelta(4, "foo"), context))
    }

    @Test
    fun `pre step collects inject texts and drops blanks`() = runBlocking {
        var payload = ""
        val m = middleware(LlmMiddlewareMeta(active = true, preStep = 1)) { method, body ->
            assertEquals("ext.preStep", method)
            payload = body
            """{"ok":true,"texts":["NOTE-A","  ","NOTE-B"]}"""
        }
        assertEquals(listOf("NOTE-A", "NOTE-B"), m.preStep(StepView("session-9", 2, 3, 5, "hi")))
        assertTrue("要带 step 上下文：$payload", payload.contains("\"turn\":2"))
        assertTrue(payload.contains("\"step\":3"))
        assertTrue(payload.contains("\"lastUserText\":\"hi\""))
    }

    @Test
    fun `payload is valid json with the expected shape`() = runBlocking {
        var seen = ""
        val m = middleware(LlmMiddlewareMeta(active = true, stream = 1)) { _, body ->
            seen = body
            """{"ok":true,"text":"x"}"""
        }
        m.transformStreamDelta(StreamDelta(0, "a\"b"), context)
        val obj = Json.parseToJsonElement(seen).jsonObject
        assertEquals("a\"b", obj["delta"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("session-9", obj["sessionId"]!!.jsonPrimitive.content)
    }
}
