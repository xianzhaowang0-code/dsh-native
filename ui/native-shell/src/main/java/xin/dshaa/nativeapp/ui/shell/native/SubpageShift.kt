package xin.dshaa.nativeapp.ui.shell.native

/**
 * M88-F（docs/183 §6）：设置二级页的轻滑入/反向滑出。
 *
 * 背景：H4 候选 E（docs/182 §H4）为了保住性能，让设置首页与二级页**一直保留组合**，
 * 二级页只有 160ms alpha；用户观感是「进二级页动画没了」。
 *
 * 拍板（docs/183 §10）：保留组合，只给二级页层加位移 + alpha：
 * - 进入：从 +1/10 屏宽滑到 0，180ms 淡入；
 * - 退出：反向滑回右侧，140ms 淡出；
 * - 二级页之间切换：6% 位移 + 160ms，避免硬切。
 *
 * 这里只放纯计算（方向/比例/时长），Compose 侧只消费它，JVM 可测。
 */
object SubpageShift {
    /** 进入/退出的位移（占屏宽比例）。 */
    const val EDGE_FRACTION = 0.10f

    /** 二级页之间切换的位移（占屏宽比例）。 */
    const val SIBLING_FRACTION = 0.06f

    const val ENTER_MS = 180
    const val EXIT_MS = 140
    const val SIBLING_MS = 160

    /**
     * 方向：+1 = 进入/同级切换（从右侧滑入），-1 = 退出（滑回右侧），0 = 不动。
     *
     * [from] / [to] 是二级页 id（null = 没有二级页）。同一页重复赋值不算切换。
     */
    fun dir(from: String?, to: String?): Int = when {
        from == null && to != null -> 1
        from != null && to == null -> -1
        from != null && to != null && from != to -> 1
        else -> 0
    }

    /** 本次过渡的位移比例：同级切换用 6%，进入/退出用 1/10 屏宽。 */
    fun fraction(from: String?, to: String?): Float =
        if (from != null && to != null && from != to) SIBLING_FRACTION else EDGE_FRACTION

    /** 本次过渡的时长：退出 140ms，同级切换 160ms，进入 180ms。 */
    fun durationMs(from: String?, to: String?): Int = when {
        to == null && from != null -> EXIT_MS
        from != null && to != null && from != to -> SIBLING_MS
        else -> ENTER_MS
    }
}
