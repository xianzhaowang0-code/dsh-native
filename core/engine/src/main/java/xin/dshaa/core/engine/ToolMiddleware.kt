package xin.dshaa.core.engine

import kotlinx.coroutines.CancellationException
import xin.dshaa.core.model.ToolCall
import xin.dshaa.core.protocol.ApiTool
import xin.dshaa.core.tools.ToolResult
import java.security.MessageDigest

/**
 * 工具中间件（M87-A，docs/181 §3.2）。
 *
 * 0.3.4 的插件只能**新增**工具；这里补上「工具内部」的四个扩展点，对齐真实 DSH 的
 * `tools/pre-execute` / `tools/execute` / `tools/post-execute` / `tools/result` 瀑布：
 *
 * | 方法 | 时机 | 能力 |
 * |---|---|---|
 * | [ToolMiddleware.allowTool] | 拼请求 tools[] 时 | 只能删（restrict） |
 * | [ToolMiddleware.describe] | 拼请求 tools[] 时 | 改描述/input_schema（describe） |
 * | [ToolMiddleware.guard] | 审批之前 | 同步拒绝（guard），拒绝文案落 tool_result |
 * | [ToolMiddleware.preExecute] | 审批通过、执行之前 | 改参数 |
 * | [ToolMiddleware.postExecute] | 执行之后、落库之前 | 改结果 text/images/isError |
 * | [ToolMiddleware.observe] | 结果落库之后 | 只读观测 |
 *
 * 契约（同 docs/181 §3.1）：
 * - 顺序 = 注册顺序（插件启用顺序），不引入隐式优先级；
 * - 返回 null / 默认实现 = 不改；抛异常 = **本中间件跳过并记诊断**，不影响其它插件与主流程
 *   （CancellationException 例外：用户停止必须穿透，不能吞）；
 * - 链为空时零开销：`apply` 原样返回同一个 List，执行路径不做任何复制/比较；
 * - 中间件只改「这一次」的请求/结果，不改会话历史；改写者与原始内容哈希进诊断（证据可查）。
 */
interface ToolMiddleware {

    /** 诊断/「已由 <插件> 改写」标记用的稳定 id（通常 `<pluginId>/<name>`）。 */
    val id: String

    /** restrict：false = 本次请求不向模型暴露该工具。只能删，不能加。 */
    fun allowTool(name: String): Boolean = true

    /** describe：改写工具描述/input_schema；null = 不改。 */
    fun describe(tool: ApiTool): ApiTool? = null

    /**
     * guard：审批之前的拒绝；非空文案 = 拒绝并原样落 tool_result；null = 放行。
     *
     * suspend 只为了宿主能把 JS 往返放到 IO 线程（插件 handler 本身必须同步）：
     * 空链时引擎不会多一次调度。
     */
    suspend fun guard(call: ToolCallView): String? = null

    /** pre-execute：改写参数；null = 不改。 */
    suspend fun preExecute(call: ToolCallView): ToolCallView? = null

    /** post-execute：改写结果；null = 不改。 */
    suspend fun postExecute(call: ToolCallView, result: ToolResult): ToolResult? = null

    /** result：只读观测，不能改结果。 */
    fun observe(call: ToolCallView, result: ToolResult) {}
}

/**
 * 一次工具调用的视图。[args] 已解析；[rawArguments] 是模型给出的原始参数串
 * （改写 [args] 时调用方应同步改写 [rawArguments]，诊断里的哈希按它算）。
 */
data class ToolCallView(
    val id: String,
    val name: String,
    val args: kotlinx.serialization.json.JsonObject,
    val rawArguments: String,
) {
    companion object {
        fun of(call: ToolCall, args: kotlinx.serialization.json.JsonObject): ToolCallView =
            ToolCallView(call.id, call.name, args, call.arguments)
    }
}

/** guard 拒绝：哪个中间件拒的 + 给模型/UI 的文案。 */
data class GuardDecision(val middlewareId: String, val message: String)

/** 一条中间件诊断（插件页「扩展点」与 logcat `DshExt` 用）。 */
data class MiddlewareDiagnostic(val middlewareId: String, val phase: String, val error: String)

/**
 * 瀑布的结果：改写后的值 + 实际改写者（按顺序）+ 原始内容哈希。[appliedBy] 为空 = 没被改。
 */
data class MiddlewareOutcome<T>(
    val value: T,
    val appliedBy: List<String> = emptyList(),
    val originalHash: String? = null,
) {
    val rewritten: Boolean get() = appliedBy.isNotEmpty()
}

/**
 * 中间件链：不可变快照（插件启用/停用后由宿主重建并换进 [EngineDeps]），
 * 每次调用按构造时的顺序执行。
 */
class ToolMiddlewareChain(
    private val middlewares: List<ToolMiddleware> = emptyList(),
    private val onDiagnostic: (MiddlewareDiagnostic) -> Unit = {},
) {
    val size: Int get() = middlewares.size

    val isEmpty: Boolean get() = middlewares.isEmpty()

    val ids: List<String> get() = middlewares.map { it.id }

    /** 先按 restrict 过滤（保持原顺序），再逐项套 describe。链为空时原样返回。 */
    fun apply(tools: List<ApiTool>): List<ApiTool> {
        if (middlewares.isEmpty()) return tools
        val allowed = tools.filter { tool -> allowsTool(tool.name) }
        return allowed.map { tool ->
            var current = tool
            for (middleware in middlewares) {
                val next = runPhase(middleware, "describe") { middleware.describe(current) }
                if (next != null) current = next
            }
            current
        }
    }

    /**
     * M87-A：工具名是否被 restrict 允许。
     *
     * 除了拼 `tools[]` 时过滤，**执行前也要查一次**：模型可能凭空 emit 一个被隐藏的工具名
     * （幻觉/提示注入），不能因为「清单里没有」就绕过 restrict 直接执行。
     */
    fun allowsTool(name: String): Boolean {
        if (middlewares.isEmpty()) return true
        return middlewares.all { middleware ->
            runPhase(middleware, "restrict") { middleware.allowTool(name) } != false
        }
    }

    /** guard：第一个非空拒绝生效，后面的不再执行（只能拒，不能放行已拒的）。 */
    suspend fun guard(call: ToolCallView): GuardDecision? {
        if (middlewares.isEmpty()) return null
        for (middleware in middlewares) {
            val message = runPhase(middleware, "guard") { middleware.guard(call) }
            if (!message.isNullOrBlank()) return GuardDecision(middleware.id, message)
        }
        return null
    }

    /** pre-execute 瀑布：顺序执行，每个中间件看到前一个的结果。 */
    suspend fun preExecute(call: ToolCallView): MiddlewareOutcome<ToolCallView> {
        if (middlewares.isEmpty()) return MiddlewareOutcome(call)
        var current = call
        val appliedBy = ArrayList<String>()
        for (middleware in middlewares) {
            val next = try {
                middleware.preExecute(current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(middleware, "pre-execute", e)
                null
            } ?: continue
            if (next != current) {
                appliedBy += middleware.id
                current = next
            }
        }
        return if (appliedBy.isEmpty()) {
            MiddlewareOutcome(current)
        } else {
            MiddlewareOutcome(current, appliedBy, sha256(call.rawArguments))
        }
    }

    /** post-execute 瀑布：顺序执行，每个中间件看到前一个的结果。 */
    suspend fun postExecute(call: ToolCallView, result: ToolResult): MiddlewareOutcome<ToolResult> {
        if (middlewares.isEmpty()) return MiddlewareOutcome(result)
        var current = result
        val appliedBy = ArrayList<String>()
        for (middleware in middlewares) {
            val next = try {
                middleware.postExecute(call, current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(middleware, "post-execute", e)
                null
            } ?: continue
            if (next != current) {
                appliedBy += middleware.id
                current = next
            }
        }
        return if (appliedBy.isEmpty()) {
            MiddlewareOutcome(current)
        } else {
            MiddlewareOutcome(current, appliedBy, sha256(result.text))
        }
    }

    /** result 观测：全部执行、只读；异常记诊断后继续。 */
    fun observe(call: ToolCallView, result: ToolResult) {
        if (middlewares.isEmpty()) return
        for (middleware in middlewares) {
            try {
                middleware.observe(call, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(middleware, "result", e)
            }
        }
    }

    private inline fun <T> runPhase(middleware: ToolMiddleware, phase: String, block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(middleware, phase, e)
            null
        }

    private fun report(middleware: ToolMiddleware, phase: String, e: Exception) {
        onDiagnostic(
            MiddlewareDiagnostic(
                middlewareId = middleware.id,
                phase = phase,
                error = "${e.javaClass.simpleName}: ${e.message}",
            ),
        )
    }

    companion object {
        /** 没有插件注册中间件时的共享空链（零开销，不会分配）。 */
        val EMPTY = ToolMiddlewareChain(emptyList())
    }
}

/** 证据用短哈希（sha256 前 16 个 hex 字符）。 */
internal fun sha256(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.take(8).joinToString("") { byte -> "%02x".format(byte) }
}
