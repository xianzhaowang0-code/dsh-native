package xin.dshaa.nativeapp.device.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * H2（docs/182）：悬浮球回程落点。纯函数单测，设备离线也能跑。
 */
class FloatingBallReturnTest {

    private val screenW = 1080
    private val screenH = 2400
    private val statusBar = 64
    private val contentH = screenH - statusBar
    private val ball = 120 // 60dp @ 2x

    private fun target(
        oldMode: FloatingMode,
        lastBall: BallReturnPosition?,
        windowX: Int = 300,
        windowY: Int = 500,
        windowW: Int = 600,
        windowH: Int = 800,
    ) = ballTargetOnReturn(
        oldMode = oldMode,
        windowX = windowX,
        windowY = windowY,
        windowWidth = windowW,
        windowHeight = windowH,
        lastBall = lastBall,
        screenW = screenW,
        screenH = screenH,
        contentH = contentH,
        ball = ball,
    )

    @Test
    fun `window close returns ball to recorded position`() {
        val pos = target(FloatingMode.WINDOW, BallReturnPosition(40, 900))
        assertEquals(BallReturnPosition(40, 900), pos)
    }

    @Test
    fun `window close falls back to window center when never recorded`() {
        val pos = target(FloatingMode.WINDOW, lastBall = null)
        // 300 + 600/2 - 120/2 = 540；500 + 800/2 - 60 = 840
        assertEquals(BallReturnPosition(540, 840), pos)
    }

    @Test
    fun `fullscreen close returns ball to recorded position`() {
        val pos = target(FloatingMode.FULLSCREEN, BallReturnPosition(700, 300))
        assertEquals(BallReturnPosition(700, 300), pos)
    }

    @Test
    fun `fullscreen close falls back to right middle when never recorded`() {
        val pos = target(FloatingMode.FULLSCREEN, lastBall = null)
        assertEquals(BallReturnPosition(screenW - ball, (screenH - ball) / 2), pos)
    }

    @Test
    fun `screen ocr close also prefers recorded position`() {
        val pos = target(FloatingMode.SCREEN_OCR, BallReturnPosition(12, 34))
        assertEquals(BallReturnPosition(12, 34), pos)
    }

    @Test
    fun `result display close returns ball to recorded position`() {
        val pos = target(FloatingMode.RESULT_DISPLAY, BallReturnPosition(66, 77))
        assertEquals(BallReturnPosition(66, 77), pos)
    }

    @Test
    fun `recorded position is clamped to current screen`() {
        val pos = target(FloatingMode.WINDOW, BallReturnPosition(5000, 9000))
        assertEquals(screenW - ball / 2, pos.x)
        assertEquals(contentH - ball / 2, pos.y)
    }

    @Test
    fun `negative recorded position keeps half ball visible`() {
        val pos = target(FloatingMode.WINDOW, BallReturnPosition(-500, -50))
        assertEquals(-ball / 2, pos.x)
        assertEquals(0, pos.y)
    }

    @Test
    fun `bottom right recorded position is preserved`() {
        // 用户把球拖到右下角：位置记录原样返回（不因回程改成窗中心）
        val x = screenW - ball
        val y = contentH - ball
        assertEquals(BallReturnPosition(x, y), target(FloatingMode.WINDOW, BallReturnPosition(x, y)))
        assertEquals(BallReturnPosition(x, y), target(FloatingMode.FULLSCREEN, BallReturnPosition(x, y)))
    }

    // ---- 冷启/服务重启的初始落点（H2 真机验收发现的「球出现在窗口位置」） ----

    private fun initial(
        hasBall: Boolean,
        lastBall: BallReturnPosition?,
        fallbackX: Int = 260,
        fallbackY: Int = 896,
    ) = ballInitialPosition(
        hasBallPosition = hasBall,
        lastBall = lastBall,
        fallbackX = fallbackX,
        fallbackY = fallbackY,
        screenW = screenW,
        contentH = contentH,
        ball = ball,
        safePx = 32,
    )

    @Test
    fun `cold start prefers recorded ball over last window position`() {
        // 真机复现：拖到右下 (546,1257) → 开窗（窗口 260,896）→ 关回球 → 冷启。
        // 落盘的 floating_x/y 是窗口位置，球必须回到 lastBall。
        val pos = initial(hasBall = true, lastBall = BallReturnPosition(546, 1257))
        assertEquals(BallReturnPosition(546, 1257), pos)
        assertNotEquals(BallReturnPosition(260, 896), pos)
    }

    @Test
    fun `cold start falls back to saved current position when never recorded`() {
        assertEquals(BallReturnPosition(260, 896), initial(hasBall = false, lastBall = null))
        assertEquals(BallReturnPosition(260, 896), initial(hasBall = true, lastBall = null))
    }

    @Test
    fun `cold start clamps recorded ball to screen with safe inset`() {
        val pos = initial(hasBall = true, lastBall = BallReturnPosition(5000, 9000))
        assertEquals(screenW - ball / 2 - 32, pos.x)
        assertEquals(contentH - ball / 2 - 32, pos.y)
        val neg = initial(hasBall = true, lastBall = BallReturnPosition(-500, -50))
        assertEquals(-ball / 2 + 32, neg.x)
        assertEquals(32, neg.y)
    }
}
