package xin.dshaa.nativeapp.ui.shell.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * H3（docs/182）：二级页返回目标按真实来路。设备离线时先锁住决策逻辑，
 * 真机逐路径验收（182 的表）等端口恢复后补。
 */
class NativeNavBackPolicyTest {

    private val settings = "settings"

    @Test
    fun `open sub from chat returns to chat`() {
        val back = NativeNavBackPolicy.forOpenSub(originPage = null, settingsPageId = settings)
        assertNull(back.page)
        assertNull(back.sub)
    }

    @Test
    fun `open sub from settings returns to settings`() {
        val back = NativeNavBackPolicy.forOpenSub(originPage = settings, settingsPageId = settings)
        assertEquals(settings, back.page)
        assertNull(back.sub)
    }

    @Test
    fun `open sub from another primary page returns to settings`() {
        // 0.3.4 语义：只有「从聊天直进」这一条路径改；其它页面进来仍回设置首页。
        val back = NativeNavBackPolicy.forOpenSub(originPage = "tools", settingsPageId = settings)
        assertEquals(settings, back.page)
    }

    @Test
    fun `chat direct model config path goes back to chat`() {
        // 聊天 → 模型芯片 → 模型配置 → 返回：屏幕名从 null 变 settings+sub，返回目标是 null（聊天）。
        val back = NativeNavBackPolicy.forOpenSub(originPage = null, settingsPageId = settings)
        assertNull(back.page)
        // 返回后路由回到 (null, null)，再按返回交给系统，不会停在设置首页。
        val route = back.page to back.sub
        assertEquals(null to null, route)
    }
}
