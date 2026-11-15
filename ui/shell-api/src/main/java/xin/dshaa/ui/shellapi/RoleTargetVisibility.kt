package xin.dshaa.ui.shellapi

/**
 * 0.3.4（用户 2026-11-15 第 4 条）：插件页「角色」只列真正可选的实现。
 *
 * 之前停用/卸载的插件仍以「不可用 · 插件已停用」占位，计数也算进去，看起来像还能选。
 * 现在：
 * - **宿主内置实现**（`builtin && pluginId == null`，如 native 界面壳 / 内置存储/提示词/
 *   模型/加载页）始终显示——它的可用性由内置插件开关决定，行内保留原因说明与「恢复」入口；
 * - **插件形式的实现**（第三方，以及随包的 `builtin.shell.whale` 可选壳这类自带开关的
 *   bundled 插件）只列「已启用 + 已授权」的；停用后立刻消失，宿主同时会清掉指向它的
 *   角色选择并回退内置。
 *
 * 2026-11-15 用户反馈：内置里关掉「鲸歌可选壳」后角色页仍显示「鲸歌默认壳」——
 * whale 是带开关的 bundled 插件，不是宿主内置实现，必须跟 [RoleTargetUi.enabled] 走。
 */
fun RoleTargetUi.visibleInRoleList(): Boolean =
    (builtin && pluginId == null) || (enabled && permitted)

/** [AgentTargetUi] 版本的同一规则（回合角色）。 */
fun AgentTargetUi.visibleInRoleList(): Boolean =
    (builtin && pluginId == null) || (enabled && permitted)
