package xin.dshaa.nativeapp.ui.shell.native

/**
 * 二级页（设置下挂页）的返回目标（H3，docs/182 §H3）。
 *
 * 0.3.4 的 `openSub` 不管从哪进都把 `backPage` 记成「设置首页」，于是：
 * 聊天页 →（输入条模型芯片）→ 模型配置 → 返回，落回设置首页；再按返回又原地不动，
 * 用户看到的就是「设置页按返回键没反应」。
 *
 * 规则：按**真实来路**记录——
 * - 从聊天（一级页为空，即 `originPage == null`）直进二级页：返回直接回聊天，`backPage = null`；
 * - 从设置首页进二级页：返回设置首页（0.3.4 语义不变）。
 *
 * `screenName` 仍要设成设置（渲染子页需要），这里只决定返回目标；
 * 抽屉路径走 `openSubFromDrawer`，有独立的来路快照，不经过这里。
 */
object NativeNavBackPolicy {

    /** 打开二级页时要写入的返回目标；[page] 为 null 表示回聊天。 */
    data class BackTarget(val page: String?, val sub: String?)

    fun forOpenSub(originPage: String?, settingsPageId: String): BackTarget =
        if (originPage == null) {
            BackTarget(page = null, sub = null)
        } else {
            BackTarget(page = settingsPageId, sub = null)
        }
}
