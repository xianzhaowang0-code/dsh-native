package xin.dshaa.nativeapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.engine.RequestContext
import xin.dshaa.core.engine.RequestMiddleware
import xin.dshaa.core.engine.StepView
import xin.dshaa.core.engine.StreamDelta
import xin.dshaa.core.protocol.ApiRequest
import xin.dshaa.core.protocol.ProtocolJson

/**
 * M87-B（docs/181 §3.3）：把一个插件注册的请求/提示词中间件接进 `core:engine` 的 [RequestMiddleware]。
 *
 * 与 M87-A 的工具中间件不同：这四个扩展点没有「同步元数据快速路径」——插件必须看到**本次**
 * system/请求/增量才能决定怎么改，所以每一步都 dispatch 进常驻 runtime（单次 50ms 预算）。
 * 链为空时引擎根本不调这里（零开销、请求体与 0.3.4 逐字节相同）。
 *
 * 权限：四个扩展点都要求 `llm:request`（清单申请 + 授权表都有，[granted]）。未授权时注册仍成功，
 * 但宿主不挂链——插件页/诊断能看出「申请了没授权」，而不是偷偷生效。
 *
 * 边界（v1）：
 * - `agent/request` 只改本次请求副本（model/messages/tools/参数/system）；强制 `stream=true`，
 *   不允许把 SSE 通道改成非流式；
 * - `llm/stream` 只改文本/思考增量；index 由宿主维护，插件改了就忽略；
 * - `agent/pre-step` 只注入、不裁剪（删除本步消息会让会话日志与 UI 不一致）；
 * - 任何超时/异常 = 本中间件跳过并记 `DshExt` 诊断，不影响其他插件与主流程。
 */
class PluginRequestMiddleware(
    private val pluginId: String,
    private val pluginName: String,
    private val meta: LlmMiddlewareMeta,
    private val granted: Boolean,
    /** 宿主 → 常驻运行时的一次 dispatch；返回 JSON 文本，null = 超时/运行时不可用。 */
    private val dispatch: (method: String, payloadJson: String) -> String?,
    private val log: (String) -> Unit,
) : RequestMiddleware {

    override val id: String = "$pluginId/request"

    override suspend fun assembleSystemPrompt(prompt: String, context: RequestContext): String? {
        if (!enabled(meta.assemble)) return null
        val reply = dispatchJson(
            "ext.assemble",
            buildJsonObject {
                put("prompt", prompt)
                putContext(context)
            },
        ) ?: return null
        val value = reply["value"]?.jsonPrimitive?.contentOrNull ?: return null
        return value.takeIf { it != prompt }
    }

    override suspend fun rewriteRequest(request: ApiRequest, context: RequestContext): ApiRequest? {
        if (!enabled(meta.request)) return null
        val payload = buildJsonObject {
            put("request", Json.parseToJsonElement(ProtocolJson.encodeRequest(request)))
            putContext(context)
        }
        val reply = dispatchJson("ext.request", payload) ?: return null
        val value = reply["value"] as? JsonObject ?: return null
        val rewritten = runCatching {
            ProtocolJson.json.decodeFromString(ApiRequest.serializer(), value.toString())
        }.getOrElse {
            log("agent/request 返回的请求体无法解析 plugin=$pluginId: ${it.message}")
            return null
        }
        return rewritten.takeIf { it != request }
    }

    override suspend fun transformStreamDelta(delta: StreamDelta, context: RequestContext): StreamDelta? {
        if (!enabled(meta.stream)) return null
        val reply = dispatchJson(
            "ext.stream",
            buildJsonObject {
                put(
                    "delta",
                    buildJsonObject {
                        put("index", delta.index)
                        put("text", delta.text)
                        put("reasoning", delta.reasoning)
                    },
                )
                putContext(context)
            },
        ) ?: return null
        val text = reply["text"]?.jsonPrimitive?.contentOrNull ?: return null
        return delta.copy(text = text).takeIf { it.text != delta.text }
    }

    override suspend fun preStep(step: StepView): List<String> {
        if (!enabled(meta.preStep)) return emptyList()
        val reply = dispatchJson(
            "ext.preStep",
            buildJsonObject {
                put(
                    "step",
                    buildJsonObject {
                        put("sessionId", step.sessionId)
                        put("turn", step.turn)
                        put("step", step.step)
                        put("messageCount", step.messageCount)
                        put("lastUserText", step.lastUserText)
                    },
                )
            },
        ) ?: return emptyList()
        val texts = (reply["texts"] as? JsonArray)?.mapNotNull { element ->
            runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }
        }.orEmpty()
        if (texts.isNotEmpty()) {
            log("agent/pre-step injected plugin=$pluginId count=${texts.size} session=${step.sessionId}")
        }
        return texts
    }

    private fun enabled(count: Int): Boolean = granted && count > 0

    private fun JsonObjectBuilder.putContext(context: RequestContext) {
        put("sessionId", context.sessionId)
        put("provider", context.provider)
        put("model", context.model)
    }

    private fun dispatchJson(method: String, payload: JsonObject): JsonObject? {
        val reply = dispatch(method, payload.toString()) ?: run {
            log("$method 没有回包 plugin=$pluginId（超时/运行时不在）")
            return null
        }
        val obj = runCatching { Json.parseToJsonElement(reply).jsonObject }.getOrNull() ?: run {
            log("$method 回包不是 JSON plugin=$pluginId: ${reply.take(200)}")
            return null
        }
        if (obj["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == false) {
            val error = obj["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
            log("$method 失败 plugin=$pluginId: $error")
            return null
        }
        return obj
    }

    companion object {
        /** docs/181 §3.1：单次请求中间件 JS 往返的超时预算（超时 = 本中间件跳过并记诊断）。 */
        const val TIMEOUT_MS = 50L

        /** 未授权时给插件页/诊断用的一句话（App 侧日志与插件页共用，文案不漂移）。 */
        fun deniedNote(pluginName: String): String =
            "插件「$pluginName」申请了 llm:request 但未授权：请求/提示词中间件不会生效"
    }
}
