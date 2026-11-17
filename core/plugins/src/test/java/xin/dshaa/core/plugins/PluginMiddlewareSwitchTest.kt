package xin.dshaa.core.plugins

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M87-F：插件中间件总开关（设置里关掉 = 工具/请求等链全部旁路）。 */
class PluginMiddlewareSwitchTest {

    @AfterTest
    fun tearDown() {
        PluginMiddlewareSwitch.resetForTest()
    }

    @Test
    fun `switch defaults to enabled`() {
        assertTrue(PluginMiddlewareSwitch.enabled)
        assertEquals("chain", PluginMiddlewareSwitch.bypass("chain", "empty"))
    }

    @Test
    fun `disabled switch bypasses to the empty chain`() {
        PluginMiddlewareSwitch.set(false)
        assertFalse(PluginMiddlewareSwitch.enabled)
        assertEquals("empty", PluginMiddlewareSwitch.bypass("chain", "empty"))
    }

    @Test
    fun `reset restores the enabled default`() {
        PluginMiddlewareSwitch.set(false)
        PluginMiddlewareSwitch.resetForTest()
        assertTrue(PluginMiddlewareSwitch.enabled)
        assertEquals("chain", PluginMiddlewareSwitch.bypass("chain", "empty"))
    }
}
