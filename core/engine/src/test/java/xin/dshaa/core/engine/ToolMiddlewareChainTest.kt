package xin.dshaa.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.protocol.ApiTool
import xin.dshaa.core.tools.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M87-A（docs/181 §3.2）工具中间件链的契约测试：顺序、restrict/describe、guard、
 * pre/post 改写、异常跳过与诊断、空链零开销、CancellationException 穿透。
 */
class ToolMiddlewareChainTest {

    private val call = ToolCallView(
        id = "call-1",
        name = "bash",
        args = buildJsonObject { put("command", "echo hi") },
        rawArguments = """{"command":"echo hi"}""",
    )

    private fun tool(name: String, description: String = name) =
        ApiTool(name = name, description = description, inputSchema = buildJsonObject {})

    private class Fake(
        override val id: String,
        private val onAllow: (String) -> Boolean = { true },
        private val onDescribe: (ApiTool) -> ApiTool? = { null },
        private val onGuard: (ToolCallView) -> String? = { null },
        private val onPre: suspend (ToolCallView) -> ToolCallView? = { null },
        private val onPost: suspend (ToolCallView, ToolResult) -> ToolResult? = { _, _ -> null },
        private val onObserve: (ToolCallView, ToolResult) -> Unit = { _, _ -> },
    ) : ToolMiddleware {
        override fun allowTool(name: String): Boolean = onAllow(name)
        override fun describe(tool: ApiTool): ApiTool? = onDescribe.invoke(tool)
        override suspend fun guard(call: ToolCallView): String? = onGuard.invoke(call)
        override suspend fun preExecute(call: ToolCallView): ToolCallView? = onPre.invoke(call)
        override suspend fun postExecute(call: ToolCallView, result: ToolResult): ToolResult? =
            onPost.invoke(call, result)
        override fun observe(call: ToolCallView, result: ToolResult) = onObserve.invoke(call, result)
    }

    @Test
    fun `empty chain is zero overhead`() = runBlocking {
        val tools = listOf(tool("bash"), tool("read"))
        assertSame(tools, ToolMiddlewareChain.EMPTY.apply(tools))
        assertNull(ToolMiddlewareChain.EMPTY.guard(call))
        assertSame(call, ToolMiddlewareChain.EMPTY.preExecute(call).value)
        val result = ToolResult.ok("ok")
        assertSame(result, ToolMiddlewareChain.EMPTY.postExecute(call, result).value)
        assertTrue(ToolMiddlewareChain.EMPTY.isEmpty)
    }

    @Test
    fun `restrict removes tools and keeps registry order`() {
        val chain = ToolMiddlewareChain(
            listOf(
                Fake(id = "a/restrict", onAllow = { it != "bash" }),
                Fake(id = "b/restrict", onAllow = { it != "read" }),
            ),
        )
        assertEquals(
            listOf("write"),
            chain.apply(listOf(tool("bash"), tool("read"), tool("write"))).map { it.name },
        )
    }

    @Test
    fun `restrict can only delete never add`() {
        val chain = ToolMiddlewareChain(listOf(Fake(id = "a/restrict", onAllow = { true })))
        assertEquals(listOf("bash"), chain.apply(listOf(tool("bash"))).map { it.name })
    }

    @Test
    fun `describe runs in registration order and later sees earlier output`() {
        val chain = ToolMiddlewareChain(
            listOf(
                Fake(id = "a/describe", onDescribe = { it.copy(description = "A:${it.description}") }),
                Fake(id = "b/describe", onDescribe = { it.copy(description = "B:${it.description}") }),
            ),
        )
        val described = chain.apply(listOf(tool("bash", "base"))).single()
        assertEquals("B:A:base", described.description)
    }

    @Test
    fun `restrict and describe failures are diagnosed and skipped`() {
        val diagnostics = mutableListOf<MiddlewareDiagnostic>()
        val chain = ToolMiddlewareChain(
            listOf(
                Fake(id = "broken/restrict", onAllow = { error("boom-restrict") }),
                Fake(id = "broken/describe", onDescribe = { error("boom-describe") }),
                Fake(id = "ok/describe", onDescribe = { it.copy(description = "kept") }),
            ),
            onDiagnostic = { diagnostics += it },
        )
        val described = chain.apply(listOf(tool("bash", "base"))).single()
        assertEquals("kept", described.description)
        assertEquals(listOf("broken/restrict", "broken/describe"), diagnostics.map { it.middlewareId })
        assertEquals(listOf("restrict", "describe"), diagnostics.map { it.phase })
        assertTrue(diagnostics.all { it.error.contains("IllegalStateException") })
    }

    @Test
    fun `guard first denial wins and later guards do not run`() = runBlocking {
        val seen = mutableListOf<String>()
        val chain = ToolMiddlewareChain(
            listOf(
                Fake(id = "first/guard", onGuard = { seen += "first"; "blocked by first" }),
                Fake(id = "second/guard", onGuard = { seen += "second"; "blocked by second" }),
            ),
        )
        val decision = chain.guard(call)
        assertNotNull(decision)
        assertEquals("first/guard", decision.middlewareId)
        assertEquals("blocked by first", decision.message)
        assertEquals(listOf("first"), seen)
    }

    @Test
    fun `guard null means allow and blank message is ignored`() = runBlocking {
        val chain = ToolMiddlewareChain(
            listOf(
                Fake(id = "a/guard", onGuard = { "  " }),
                Fake(id = "b/guard", onGuard = { null }),
            ),
        )
        assertNull(chain.guard(call))
    }

    @Test
    fun `preExecute rewrites sequentially and records appliedBy plus original hash`() = runBlocking {
        val chain = ToolMiddlewareChain(
            listOf(
                Fake(
                    id = "a/pre",
                    onPre = { view ->
                        view.copy(
                            args = buildJsonObject { put("command", "echo hi > a.txt") },
                            rawArguments = """{"command":"echo hi > a.txt"}""",
                        )
                    },
                ),
                Fake(
                    id = "b/pre",
                    onPre = { view ->
                        // 看到的是上一个中间件改写后的参数
                        assertEquals("echo hi > a.txt", view.args["command"].toString().trim('"'))
                        null
                    },
                ),
            ),
        )
        val outcome = chain.preExecute(call)
        assertTrue(outcome.rewritten)
        assertEquals(listOf("a/pre"), outcome.appliedBy)
        assertNotNull(outcome.originalHash)
        assertEquals("echo hi > a.txt", outcome.value.args["command"].toString().trim('"'))
    }

    @Test
    fun `preExecute failure skips only that middleware`() = runBlocking {
        val diagnostics = mutableListOf<MiddlewareDiagnostic>()
        val chain = ToolMiddlewareChain(
            listOf(
                Fake(id = "broken/pre", onPre = { error("pre boom") }),
                Fake(id = "ok/pre", onPre = { it.copy(args = buildJsonObject { put("command", "ls") }) }),
            ),
            onDiagnostic = { diagnostics += it },
        )
        val outcome = chain.preExecute(call)
        assertEquals("ls", outcome.value.args["command"].toString().trim('"'))
        assertEquals(listOf("broken/pre"), diagnostics.map { it.middlewareId })
    }

    @Test
    fun `cancellation from preExecute propagates`() {
        val chain = ToolMiddlewareChain(
            listOf(Fake(id = "stop/pre", onPre = { throw CancellationException("stopped") })),
        )
        assertFailsWith<CancellationException> {
            runBlocking { chain.preExecute(call) }
        }
    }

    @Test
    fun `postExecute rewrites result and keeps original hash`() = runBlocking {
        val chain = ToolMiddlewareChain(
            listOf(
                Fake(
                    id = "a/post",
                    onPost = { _, result ->
                        result.copy(text = result.text.replace("example.com", "mirror.local"))
                    },
                ),
            ),
        )
        val original = "see example.com/a"
        val outcome = chain.postExecute(call, ToolResult.ok(original))
        assertEquals("see mirror.local/a", outcome.value.text)
        assertEquals(listOf("a/post"), outcome.appliedBy)
        assertNotNull(outcome.originalHash)
        // 原始文本没有被就地改（会话/证据仍拿得到原值）
        assertEquals("see example.com/a", original)
    }

    @Test
    fun `postExecute failure skips and is diagnosed`() = runBlocking {
        val diagnostics = mutableListOf<MiddlewareDiagnostic>()
        val chain = ToolMiddlewareChain(
            listOf(
                Fake(id = "broken/post", onPost = { _, _ -> error("post boom") }),
                Fake(id = "ok/post", onPost = { _, _ -> ToolResult.ok("final") }),
            ),
            onDiagnostic = { diagnostics += it },
        )
        val outcome = chain.postExecute(call, ToolResult.ok("raw"))
        assertEquals("final", outcome.value.text)
        assertEquals(listOf("broken/post"), diagnostics.map { it.middlewareId })
    }

    @Test
    fun `observe is read only and failures are diagnosed`() {
        val seen = mutableListOf<String>()
        val diagnostics = mutableListOf<MiddlewareDiagnostic>()
        val chain = ToolMiddlewareChain(
            listOf(
                Fake(
                    id = "a/observe",
                    onObserve = { _, result ->
                        seen += result.text
                        throw IllegalStateException("observe boom")
                    },
                ),
                Fake(id = "b/observe", onObserve = { _, result -> seen += "second:${result.text}" }),
            ),
            onDiagnostic = { diagnostics += it },
        )
        chain.observe(call, ToolResult.ok("value"))
        assertEquals(listOf("value", "second:value"), seen)
        assertEquals(listOf("a/observe"), diagnostics.map { it.middlewareId })
        assertEquals("result", diagnostics.single().phase)
    }

    @Test
    fun `chain ids expose plugin registration order for diagnostics`() {
        val chain = ToolMiddlewareChain(listOf(Fake(id = "x"), Fake(id = "y"), Fake(id = "z")))
        assertEquals(listOf("x", "y", "z"), chain.ids)
        assertEquals(3, chain.size)
    }

    @Test
    fun `tools and args are untouched when middleware returns null`() = runBlocking {
        val chain = ToolMiddlewareChain(listOf(Fake(id = "noop"), Fake(id = "noop2")))
        val tools = listOf(tool("bash"))
        assertEquals(tools, chain.apply(tools))
        val outcome = chain.preExecute(call)
        assertEquals(call, outcome.value)
        assertTrue(!outcome.rewritten)
        assertNull(outcome.originalHash)
    }
}
