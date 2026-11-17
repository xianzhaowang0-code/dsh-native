package xin.dshaa.core.engine

import kotlinx.coroutines.CancellationException
import xin.dshaa.core.protocol.ApiRequest

/**
 * 请求/提示词中间件（M87-B，docs/181 §3.3）。
 *
 * 0.3.4 的插件只能新增提示词段、不能改「电器内部」；这里补上真实 DSH 的四个扩展点：
 *
 * | 方法 | 时机 | 能力 |
 * |---|---|---|
 * | [RequestMiddleware.assembleSystemPrompt] | 每次请求拼 `system` 之前 | 有序拼装/替换内置段 |
 * | [RequestMiddleware.rewriteRequest] | `ApiRequest` 构造之后、`stream` 之前 | 改 model/messages/tools/参数 |
 * | [RequestMiddleware.transformStreamDelta] | 每个文本/思考增量到达时 | 流式改写（UI 与落盘一致） |
 * | [RequestMiddleware.preStep] | 每个 step 起点、注入都落完之后 | 给本步注入 user 消息（等价 `agent.inject`） |
 *
 * 契约（同 docs/181 §3.1）：
 * - 顺序 = 注册顺序（插件启用顺序），不引入隐式优先级；
 * - 返回 null = 放弃改写、继续链；抛异常 = **本中间件跳过并记诊断**，不影响其它插件与主流程
 *   （CancellationException 例外：用户停止必须穿透，不能吞）；
 * - 链为空时零开销：`assemble`/`rewrite` 原样返回入参，`transform` 返回同一个对象；
 * - 中间件只改**这一次请求/这一次流**，不改 `ui.messages()` / 会话历史；改写后的请求仍走
 *   `onRequest` 落 run.jsonl（调试可回放）。
 */
interface RequestMiddleware {

    /** 诊断用的稳定 id（通常 `<pluginId>/request`）。 */
    val id: String

    /** system-prompt/assemble：返回改写后的整段 system；null = 不改。 */
    suspend fun assembleSystemPrompt(prompt: String, context: RequestContext): String? = null

    /** agent/request：返回本次请求的改写副本；null = 不改。 */
    suspend fun rewriteRequest(request: ApiRequest, context: RequestContext): ApiRequest? = null

    /** llm/stream：改写一个文本/思考增量；null = 不改。 */
    suspend fun transformStreamDelta(delta: StreamDelta, context: RequestContext): StreamDelta? = null

    /** agent/pre-step：给本步注入的 user 消息正文（按顺序落成 `plugin-inject`）；空 = 不注入。 */
    suspend fun preStep(step: StepView): List<String> = emptyList()
}

/** 一次请求的只读上下文（插件按 session 路由模型用）。 */
data class RequestContext(
    val sessionId: String,
    val provider: String,
    val model: String,
)

/** 本 step 的只读视图（`agent/pre-step`）。 */
data class StepView(
    val sessionId: String,
    val turn: Int,
    val step: Int,
    val messageCount: Int,
    val lastUserText: String,
)

/** 一个流式增量。[reasoning] = 思考内容（与正文分开，插件可只改其一）。 */
data class StreamDelta(
    val index: Int,
    val text: String,
    val reasoning: Boolean = false,
)

/**
 * 请求中间件链：不可变快照（插件启用/停用/重载后由宿主重建并换进 [EngineDeps]）。
 * 每次调用按构造时的顺序执行；空链不分配中间结果。
 */
class RequestMiddlewareChain(
    private val middlewares: List<RequestMiddleware> = emptyList(),
    private val onDiagnostic: (MiddlewareDiagnostic) -> Unit = {},
) {
    val size: Int get() = middlewares.size

    val isEmpty: Boolean get() = middlewares.isEmpty()

    val ids: List<String> get() = middlewares.map { it.id }

    /** system-prompt/assemble 瀑布：每个中间件看到前一个的结果。 */
    suspend fun assembleSystemPrompt(prompt: String, context: RequestContext): MiddlewareOutcome<String> {
        if (middlewares.isEmpty()) return MiddlewareOutcome(prompt)
        var current = prompt
        val appliedBy = ArrayList<String>()
        for (middleware in middlewares) {
            val next = runPhase(middleware, "system-prompt/assemble") {
                middleware.assembleSystemPrompt(current, context)
            } ?: continue
            if (next != current) {
                appliedBy += middleware.id
                current = next
            }
        }
        return MiddlewareOutcome(current, appliedBy)
    }

    /** agent/request 瀑布：顺序执行，每个中间件看到前一个的改写结果。 */
    suspend fun rewriteRequest(request: ApiRequest, context: RequestContext): MiddlewareOutcome<ApiRequest> {
        if (middlewares.isEmpty()) return MiddlewareOutcome(request)
        var current = request
        // 证据哈希按**改写前**的请求算（插件页/诊断要能对回原始请求）。
        val originalHash = sha256(request.toString())
        val appliedBy = ArrayList<String>()
        for (middleware in middlewares) {
            val next = runPhase(middleware, "agent/request") {
                middleware.rewriteRequest(current, context)
            } ?: continue
            // 强制保证这次请求仍走流式（SSE 解析路径不能换成非流式）。
            val normalized = if (next.stream) next else next.copy(stream = true)
            if (normalized != current) {
                appliedBy += middleware.id
                current = normalized
            }
        }
        return if (appliedBy.isEmpty()) {
            MiddlewareOutcome(current)
        } else {
            MiddlewareOutcome(current, appliedBy, originalHash)
        }
    }

    /** llm/stream：逐增量顺序改写；异常/超时由调用方（或这里）跳过并记诊断。 */
    suspend fun transformStreamDelta(delta: StreamDelta, context: RequestContext): StreamDelta {
        if (middlewares.isEmpty()) return delta
        var current = delta
        for (middleware in middlewares) {
            val next = runPhase(middleware, "llm/stream") {
                middleware.transformStreamDelta(current, context)
            } ?: continue
            current = next
        }
        return current
    }

    /** agent/pre-step：依次收集注入正文；单个中间件异常只跳过它自己。 */
    suspend fun preStep(step: StepView): List<String> {
        if (middlewares.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (middleware in middlewares) {
            val texts = runPhase(middleware, "agent/pre-step") { middleware.preStep(step) } ?: continue
            texts.forEach { text -> if (text.isNotBlank()) out += text }
        }
        return out
    }

    private inline fun <T> runPhase(middleware: RequestMiddleware, phase: String, block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onDiagnostic(
                MiddlewareDiagnostic(
                    middlewareId = middleware.id,
                    phase = phase,
                    error = "${e.javaClass.simpleName}: ${e.message}",
                ),
            )
            null
        }

    companion object {
        /** 没有插件注册中间件时的共享空链（零开销）。 */
        val EMPTY = RequestMiddlewareChain(emptyList())
    }
}
