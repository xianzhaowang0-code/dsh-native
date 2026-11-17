package xin.dshaa.nativeapp.device.floating

/**
 * 悬浮球落点（H2，docs/182 §H2）。
 *
 * 0.3.4 的 bug：从悬浮窗/全屏关闭回悬浮球时，位置算的是「当前窗口矩形的中心」
 * （全屏则固定右侧居中），而不是打开窗口前球所在的位置——用户点了悬浮球再关窗，
 * 球凭空跑到屏幕中间。`state.lastBallPositionX/Y` 在 BALL→WINDOW 时其实已经存了，
 * 只是回程没用。
 *
 * 规则：
 * - **有球位置记录**（[lastBall] 非空）：任何模式都回到这个位置，再按当前屏幕/状态栏可见区 clamp；
 * - **从未记录**（首次启动/旧数据）：WINDOW / RESULT_DISPLAY 回退到窗口中心，
 *   FULLSCREEN / SCREEN_OCR 回退到右侧居中，与 0.3.4 行为一致。
 *
 * 纯函数（不碰 Android 类），JVM 单测覆盖四个分支与 clamp。
 * 注意：这里只算「球」的落点；窗口自身位置由 WINDOW 分支单独处理，回程不影响它。
 */

/** 悬浮球左上角坐标（px）。 */
data class BallReturnPosition(val x: Int, val y: Int)

/**
 * @param windowX/windowY/windowWidth/windowHeight 旧窗口的布局参数（MATCH_PARENT 需调用方先换成屏幕尺寸）
 * @param lastBall 悬浮球位置记录；null = 从未记录，允许回退窗口中心
 * @param screenW/screenH 物理屏尺寸（px，DEFAULT_DISPLAY）
 * @param contentH 可用高度（屏幕减状态栏），球贴底夹取按它算
 * @param ball 球边长（px）
 */
fun ballTargetOnReturn(
    oldMode: FloatingMode,
    windowX: Int,
    windowY: Int,
    windowWidth: Int,
    windowHeight: Int,
    lastBall: BallReturnPosition?,
    screenW: Int,
    screenH: Int,
    contentH: Int,
    ball: Int,
): BallReturnPosition {
    val minVisible = ball / 2
    val fallback = when (oldMode) {
        FloatingMode.FULLSCREEN, FloatingMode.SCREEN_OCR ->
            BallReturnPosition(screenW - ball, (screenH - ball) / 2)

        else -> BallReturnPosition(
            windowX + windowWidth / 2 - ball / 2,
            windowY + windowHeight / 2 - ball / 2,
        )
    }
    val raw = lastBall ?: fallback
    // 与 0.3.4 相同的可见性夹取：横向最多出屏半个球，纵向不越过状态栏下沿。
    val maxX = (screenW - minVisible).coerceAtLeast(-ball + minVisible)
    val maxY = (contentH - minVisible).coerceAtLeast(0)
    return BallReturnPosition(
        raw.x.coerceIn(-ball + minVisible, maxX),
        raw.y.coerceIn(0, maxY),
    )
}

/**
 * 冷启 / 服务重启时悬浮球的初始落点（H2 真机验收发现的第二个问题）。
 *
 * 回程只把 `state.x/y` 改到球位，没来得及落盘；`saveState()` 存的 `floating_x/y` 还是
 * 最后那个**窗口**的位置。下一次冷启如果直接拿 `state.x/y` 摆球，球就出现在窗口的位置。
 * 所以有球位置记录时优先用 `lastBall`，从未记录才退回 `state.x/y`（旧数据/全新安装）。
 *
 * @param hasBallPosition 是否真的记录过球位置（`FloatingWindowState.hasBallPosition`）
 * @param lastBall 记录过的球位置；null = 没有
 * @param fallbackX/fallbackY 没记录时用的当前位置（老行为）
 * @param safePx 贴边安全边距（16dp 换算成 px）
 */
fun ballInitialPosition(
    hasBallPosition: Boolean,
    lastBall: BallReturnPosition?,
    fallbackX: Int,
    fallbackY: Int,
    screenW: Int,
    contentH: Int,
    ball: Int,
    safePx: Int,
): BallReturnPosition {
    val raw = if (hasBallPosition && lastBall != null) lastBall else BallReturnPosition(fallbackX, fallbackY)
    val minVisible = ball / 2
    val minX = -ball + minVisible + safePx
    val maxX = (screenW - minVisible - safePx).coerceAtLeast(minX)
    val minY = safePx
    val maxY = (contentH - minVisible - safePx).coerceAtLeast(minY)
    return BallReturnPosition(
        raw.x.coerceIn(minX, maxX),
        raw.y.coerceIn(minY, maxY),
    )
}
