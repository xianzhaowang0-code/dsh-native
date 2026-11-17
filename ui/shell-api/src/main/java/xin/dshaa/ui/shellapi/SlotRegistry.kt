package xin.dshaa.ui.shellapi

import xin.dshaa.core.plugins.PluginSlot
import xin.dshaa.core.plugins.SlotSpec

/**
 * M87-C（docs/181 §3.4）：宿主原生界面的插件插槽注册表。
 *
 * 插件在 `apply` 里用 `ctx.ui.slots.register({slot,id,order,when,tree})` 声明一棵 Surface 子树，
 * 安装时由 [SlotSpec] 校验并落进 `plugins.json`；宿主按「启用 + ui:slot 已授权」把声明同步到这里，
 * 原生页面在固定插点读 [entries] 渲染。
 *
 * 契约：
 * - **顺序** = 插件清单顺序，同一槽位内再按 `order`（小在前）与注册先后；不引入隐式优先级；
 * - **停用/删除/重载即清**：[syncPlugin] 先摘掉该插件的全部旧条目，再按新清单注册；
 * - **异常隔离**：读模型只返回已校验的 [PluginSlot]（treeJson 是 SlotSpec 规范化后的 JSON），
 *   渲染层的解析失败只影响该条目（由 UI 显示占位）；
 * - **只读状态 + 动作回插件**：`stateJson` 是插件动作返回的槽位状态；点击只调 [actionHandler]，
 *   宿主不执行任何命令、不把树里的值当代码。
 *
 * 本类刻意不依赖 Compose（纯 JVM 可测）；UI 用 [addListener] 订阅变更并触发重组。
 */
class SlotRegistry {

    /** 一个已生效的插槽条目。 */
    data class Entry(
        val pluginId: String,
        val pluginName: String,
        val slot: PluginSlot,
        /** 插件动作返回的槽位状态（read-only 绑定 `state.*` 的来源）。 */
        val stateJson: String = "{}",
        /** 注册先后（同 order 时的稳定次序）；不随状态更新变化。 */
        val sequence: Long = 0,
    ) {
        /** 跨插件唯一 key（同插件同槽位同 id 只保留一条）。 */
        val key: String get() = "$pluginId\u0000${slot.key}"

        /** 给 UI/诊断用的短名：`<slot>/<id>`。 */
        val displayId: String get() = slot.key
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private val listeners = LinkedHashSet<() -> Unit>()
    private var sequence = 0L

    /** 点击动作：由宿主接到插件常驻运行时（`ui.slot.action` dispatch）；null = 动作无效。 */
    @Volatile
    var actionHandler: ((Entry, actionId: String, payloadJson: String?) -> Unit)? = null

    /** `view.*` 只读绑定：返回该插件在当前会话的 projection `wire.view` 文本；null = 没有。 */
    @Volatile
    var viewResolver: ((pluginId: String, path: String) -> String?)? = null

    /** 变更通知（UI 订阅；纯 JVM 侧不依赖 Compose）。 */
    fun addListener(listener: () -> Unit) {
        synchronized(lock) { listeners += listener }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(lock) { listeners -= listener }
    }

    /** 按插件清单重建该插件的全部插槽（停用/删除时传空列表）。 */
    fun syncPlugin(pluginId: String, pluginName: String, slots: List<PluginSlot>) {
        synchronized(lock) {
            entries.entries.removeIf { it.value.pluginId == pluginId }
            slots.forEach { slot ->
                val entry = Entry(pluginId, pluginName, slot, stateJson = "{}", sequence = sequence++)
                entries[entry.key] = entry
            }
        }
        changed()
    }

    fun removePlugin(pluginId: String) {
        val removed = synchronized(lock) {
            val keys = entries.filterValues { it.pluginId == pluginId }.keys.toList()
            keys.forEach { entries.remove(it) }
            keys.isNotEmpty()
        }
        if (removed) changed()
    }

    fun clear() {
        val had = synchronized(lock) {
            val had = entries.isNotEmpty()
            entries.clear()
            had
        }
        if (had) changed()
    }

    /** 某个槽位当前应渲染的条目（已按条件 `when` 过滤、按 order + 注册先后排序）。 */
    fun entries(slot: String, hasMessages: Boolean): List<Entry> = synchronized(lock) {
        entries.values
            .filter { it.slot.slot == slot && SlotSpec.matches(it.slot, hasMessages) }
            .sortedWith(compareBy({ it.slot.order }, { it.sequence }))
    }

    /** 当前全部条目（诊断/验收用）。 */
    fun all(): List<Entry> = synchronized(lock) { entries.values.toList() }

    fun isEmpty(): Boolean = synchronized(lock) { entries.isEmpty() }

    /** 插件动作返回新状态后替换条目；返回 false = 条目已经不在了（插件停用/重载）。 */
    fun updateState(pluginId: String, slotKey: String, stateJson: String): Boolean {
        val updated = synchronized(lock) {
            val key = "$pluginId\u0000$slotKey"
            val current = entries[key] ?: return false
            entries[key] = current.copy(stateJson = stateJson)
            true
        }
        if (updated) changed()
        return updated
    }

    /** 触发一次槽位动作（UI 点击/开关）。payload 是 JSON 文本或 null。 */
    fun dispatch(entry: Entry, actionId: String, payloadJson: String?) {
        actionHandler?.invoke(entry, actionId, payloadJson)
    }

    /** `view.*` 绑定的宿主解析（没有投影/没有会话时返回 null，UI 显示空/占位）。 */
    fun view(pluginId: String, path: String): String? = viewResolver?.invoke(pluginId, path)

    /** 取当前最新的条目（动作期间插件可能被停用）。 */
    fun fresh(entry: Entry): Entry? = synchronized(lock) { entries[entry.key] }

    private fun changed() {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { listener -> runCatching { listener() } }
    }
}
