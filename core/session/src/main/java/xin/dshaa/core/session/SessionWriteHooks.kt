package xin.dshaa.core.session

/**
 * M87-E（docs/181 §3.5）：会话写入钩子的宿主侧契约。
 *
 * 设计边界（v1，不假装）：
 * - 只对 `user/message` / `tool/result` 两类事件问钩子（真机上「拒绝写入」有可观察语义）；
 * - 钩子只做**观测 + 拒绝**，返回的改写内容一律忽略；改写写路径留到 0.3.6；
 * - 钩子返回拒绝 → 该事件不落盘、不占 seq，抛 [SessionAppendVetoed]；宿主负责收口回合与 UI 提示；
 * - 没有安装钩子（[SessionWriteHooks.gate] == null）时零开销，事件字节与 0.3.4 完全一致。
 */
data class PendingSessionEvent(
    val sessionId: String,
    val type: String,
    /** `data` 的 JSON 文本（插件钩子直接读，不经过会话模型）。 */
    val dataJson: String,
    /** `surfaceOp` 的 JSON 文本（没有就 null）。 */
    val surfaceOpJson: String?,
    /** `user/message` 的 `data.source.kind`（例如 `user` / `plugin-inject`）；其它事件为 null。 */
    val sourceKind: String?,
) {
    /** 插件记录：带 `source.kind` 的 user/message（当前只有插件注入）；只有这类事件可走 `session/event` veto。 */
    val pluginRecord: Boolean get() = sourceKind?.startsWith("plugin") == true
}

data class SessionAppendDecision(
    val allow: Boolean = true,
    /** 拒绝原因（进 UI/诊断）。 */
    val reason: String? = null,
    /** 做出拒绝的插件 id（多插件链时便于定位）。 */
    val pluginId: String? = null,
)

/**
 * 宿主安装的写入钩子。实现方负责把 [PendingSessionEvent] 分发给启用且获授权的插件，
 * 并在自身内部把超时/异常降级成「放行 + 诊断」——**不能**让插件故障阻塞落库。
 */
interface SessionAppendGate {
    fun beforeAppend(event: PendingSessionEvent): SessionAppendDecision
}

/** 进程级安装点（App 启动时装一次；插件启停由实现方现算，不需要重装）。 */
object SessionWriteHooks {
    /** 可被钩子拒绝的事件类型（v1 固定；其它事件直接落盘）。 */
    val VETOABLE_TYPES: Set<String> = linkedSetOf("user/message", "tool/result")

    @Volatile
    var gate: SessionAppendGate? = null

    /**
     * M87-F 宿主的「插件中间件」总开关关闭时置 true：写入钩子整条旁路，
     * [check] 不再碰 gate（排障用）。由 App 在设置变化时写，进程内全局。
     */
    @Volatile
    var bypass: Boolean = false

    /**
     * 问一次写入钩子。[bypass] 为进程级总开关（[SessionWriteHooks.bypass]）。
     */
    fun check(event: PendingSessionEvent): SessionAppendDecision {
        if (bypass) return SessionAppendDecision()
        val gate = gate ?: return SessionAppendDecision()
        if (event.type !in VETOABLE_TYPES) return SessionAppendDecision()
        return try {
            gate.beforeAppend(event)
        } catch (t: Throwable) {
            // 钩子自身出问题不能阻塞会话落库；诊断由实现方记（这里只放行）。
            SessionAppendDecision()
        }
    }

    fun resetForTest() {
        gate = null
        bypass = false
    }
}

/** 钩子拒绝一条会话事件（不落盘、不占 seq）。 */
class SessionAppendVetoed(
    val type: String,
    val pluginId: String?,
    val reason: String,
) : IllegalStateException("插件${pluginId?.let { " $it" } ?: ""}拒绝写入 $type：$reason")
