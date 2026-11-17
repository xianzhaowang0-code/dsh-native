package xin.dshaa.core.engine

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.protocol.ApiMessage
import xin.dshaa.core.protocol.ApiRequest
import xin.dshaa.core.protocol.ApiTool
import xin.dshaa.core.protocol.ContentBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M87-B（docs/181 §3.3）：请求/提示词中间件链的契约锁定。
 *
 * 覆盖 docs/181 §五.2 要求的新增契约：顺序、`next()`、异常跳过、链为空零改写/同一对象，
 * 以及 pre-step 注入的顺序与空串过滤。真实 JS 往返在真机 fixture 验（QuickJS 只有 arm64 JNI）。
 */
class RequestMiddlewareChainTest {

    private val context = RequestContext(sessionId = "session-1", provider = "deepseek", model = "deepseek-flash")

    private val request = ApiRequest(
        model = "deepseek-flash",
        messages = listOf(ApiMessage("user", listOf(ContentBlock.Text("hi")))),
        maxTokens = 1024,
        system = "SYS",
        tools = listOf(ApiTool("fake_tool", "fake", buildJsonObject { put("type", "object") })),
    )

    private fun middleware(
        id: String,
        assemble: (suspend (String, RequestContext) -> String?)? = null,
        rewrite: (suspend (ApiRequest, RequestContext) -> ApiRequest?)? = null,
        deltaFn: (suspend (StreamDelta, RequestContext) -> StreamDelta?)? = null,
        preStepFn: (suspend (StepView) -> List<String>)? = null,
    ): RequestMiddleware = object : RequestMiddleware {
        override val id: String = id
        override suspend fun assembleSystemPrompt(prompt: String, context: RequestContext): String? =
            assemble?.invoke(prompt, context)
        override suspend fun rewriteRequest(request: ApiRequest, context: RequestContext): ApiRequest? =
            rewrite?.invoke(request, context)
        override suspend fun transformStreamDelta(delta: StreamDelta, context: RequestContext): StreamDelta? =
            deltaFn?.invoke(delta, context)
        override suspend fun preStep(step: StepView): List<String> = preStepFn?.invoke(step).orEmpty()
    }

    @Test
    fun `empty chain is zero work and returns the same objects`() = runBlocking {
        val chain = RequestMiddlewareChain.EMPTY
        assertTrue(chain.isEmpty)
        assertEquals(0, chain.size)
        val assembled = chain.assembleSystemPrompt("SYS", context)
        assertSame("SYS", assembled.value)
        assertFalse(assembled.rewritten)
        val rewritten = chain.rewriteRequest(request, context)
        assertSame(request, rewritten.value)
        assertFalse(rewritten.rewritten)
        val delta = StreamDelta(0, "foo")
        assertSame(delta, chain.transformStreamDelta(delta, context))
        assertTrue(chain.preStep(StepView("s", 1, 1, 1, "hi")).isEmpty())
    }

    @Test
    fun `waterfall order follows registration`() = runBlocking {
        val calls = ArrayList<String>()
        val chain = RequestMiddlewareChain(
            listOf(
                middleware("a", assemble = { prompt, _ -> calls += "a"; "$prompt+A" }),
                middleware("b", assemble = { prompt, _ -> calls += "b"; "$prompt+B" }),
            ),
        )
        val outcome = chain.assembleSystemPrompt("SYS", context)
        assertEquals(listOf("a", "b"), calls)
        assertEquals("SYS+A+B", outcome.value)
        assertEquals(listOf("a", "b"), outcome.appliedBy)
    }

    @Test
    fun `null means give up and the chain keeps going`() = runBlocking {
        val seen = ArrayList<String>()
        val chain = RequestMiddlewareChain(
            listOf(
                middleware("a", rewrite = { _, _ -> null }),
                middleware("b", rewrite = { req, _ -> seen += req.model; req.copy(model = "routed") }),
                middleware("c", rewrite = { req, _ -> seen += req.model; null }),
            ),
        )
        val outcome = chain.rewriteRequest(request, context)
        assertEquals("routed", outcome.value.model)
        // b 看到原值，c 看到 b 改写后的值（每个中间件看到前一个的结果）
        assertEquals(listOf("deepseek-flash", "routed"), seen)
        assertEquals(listOf("b"), outcome.appliedBy)
    }

    @Test
    fun `exception skips only that middleware and reports a diagnostic`() = runBlocking {
        val diagnostics = ArrayList<MiddlewareDiagnostic>()
        val chain = RequestMiddlewareChain(
            listOf(
                middleware("ok-1", assemble = { prompt, _ -> "$prompt-1" }),
                middleware(
                    "boom",
                    assemble = { _, _ -> throw IllegalStateException("插件炸了") },
                ),
                middleware("ok-2", assemble = { prompt, _ -> "$prompt-2" }),
            ),
            onDiagnostic = { diagnostics += it },
        )
        val outcome = chain.assembleSystemPrompt("SYS", context)
        assertEquals("SYS-1-2", outcome.value)
        assertEquals(listOf("ok-1", "ok-2"), outcome.appliedBy)
        assertEquals(1, diagnostics.size)
        assertEquals("boom", diagnostics[0].middlewareId)
        assertEquals("system-prompt/assemble", diagnostics[0].phase)
        assertTrue(diagnostics[0].error.contains("插件炸了"))
    }

    @Test
    fun `stream delta rewrites sequentially and keeps the last value`() = runBlocking {
        val chain = RequestMiddlewareChain(
            listOf(
                middleware("a", deltaFn = { delta, _ -> delta.copy(text = delta.text.replace("foo", "bar")) }),
                middleware("b", deltaFn = { delta, _ -> delta.copy(text = "${delta.text}!") }),
            ),
        )
        val out = chain.transformStreamDelta(StreamDelta(3, "foo baz", reasoning = true), context)
        assertEquals("bar baz!", out.text)
        assertEquals(3, out.index)
        assertTrue(out.reasoning)
    }

    @Test
    fun `pre step concatenates injections in order`() = runBlocking {
        val chain = RequestMiddlewareChain(
            listOf(
                middleware("a", preStepFn = { step -> listOf("A-${step.sessionId}", "  ") }),
                middleware("b", preStepFn = { _ -> throw RuntimeException("x") }),
                middleware("c", preStepFn = { _ -> listOf("C") }),
            ),
            onDiagnostic = { },
        )
        assertEquals(listOf("A-session-1", "C"), chain.preStep(StepView("session-1", 2, 3, 4, "hi")))
    }

    @Test
    fun `request rewrite forces streaming and records the original hash`() = runBlocking {
        val chain = RequestMiddlewareChain(
            listOf(middleware("router", rewrite = { req, _ -> req.copy(model = "routed", stream = false) })),
        )
        val outcome = chain.rewriteRequest(request, context)
        assertEquals("routed", outcome.value.model)
        assertTrue(outcome.value.stream, "插件不能把 SSE 通道改成非流式")
        assertTrue(outcome.rewritten)
        assertTrue(!outcome.originalHash.isNullOrBlank())
    }
}
