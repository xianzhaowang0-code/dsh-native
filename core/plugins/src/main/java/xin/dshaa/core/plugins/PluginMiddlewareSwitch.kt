package xin.dshaa.core.plugins

/**
 * M87-F（docs/181 §3.1 第 7 条）：插件中间件总开关。
 *
 * 设置里关掉后，宿主把工具中间件链、请求/提示词中间件链、存储写入钩子、会话事件投递
 * **全部旁路**（排障用：怀疑插件链搞坏了行为时一键回到「零插件」语义）。
 * 插槽（`ui:slot`）与工具/页面/壳等注册不在此列——它们不是中间件链，关掉会让 UI 缺块更难排查。
 *
 * 单例是进程级状态：设置页写一次，`AppViewModel` 重建链时读它；常驻运行时按
 * [enabled] 决定是否投递事件/after-load。JVM 可直接测 [bypass]。
 */
object PluginMiddlewareSwitch {
    @Volatile
    private var value: Boolean = true

    val enabled: Boolean get() = value

    fun set(enabled: Boolean) {
        value = enabled
    }

    /** 开关打开返回 [chain]，关闭返回 [empty]（调用方传 `ToolMiddlewareChain.EMPTY` 等）。 */
    fun <T> bypass(chain: T, empty: T): T = if (value) chain else empty

    fun resetForTest() {
        value = true
    }
}
