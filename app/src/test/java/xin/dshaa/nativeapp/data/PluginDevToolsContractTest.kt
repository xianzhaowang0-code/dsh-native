package xin.dshaa.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.4（用户 2026-11-15 第 8 条）：启停 / 删除都按**写工具**走现有审批卡。
 *
 * 审批是宿主按工具名分类的（`READ_ONLY_TOOLS` 之外的都弹卡），所以这里锁住契约：
 * `plugin_dev_delete` 必须在写工具名单里、且不在只读名单里。
 */
class PluginDevToolsContractTest {

    @Test fun enableAndDeleteAreWriteTools() {
        assertTrue("plugin_dev_enable" in PluginDevTools.writeNames)
        assertTrue("plugin_dev_delete" in PluginDevTools.writeNames)
    }

    @Test fun deleteIsNeverWhitelistedAsReadOnly() {
        assertFalse("plugin_dev_delete" in PluginDevTools.readOnlyNames)
        assertFalse("plugin_dev_enable" in PluginDevTools.readOnlyNames)
        // 只读名单仍只包含真正的查询类
        assertTrue("plugin_dev_list" in PluginDevTools.readOnlyNames)
        assertTrue("plugin_dev_test" in PluginDevTools.readOnlyNames)
    }

    @Test fun aiControlToolsAreWriteTools() {        // M88-E：四个 AI 全控制工具沿用写类审批策略，且都不会进只读白名单。
        listOf("plugin_dev_grant", "plugin_dev_revoke", "plugin_dev_config", "plugin_dev_role").forEach { name ->
            assertTrue("$name 应是写工具", name in PluginDevTools.writeNames)
            assertFalse("$name 不能进只读白名单", name in PluginDevTools.readOnlyNames)
        }
    }

    @Test fun middlewareSwitchIsAWriteToolUnderTheSameGroup() {
        // M88-I：全局旁路开关必须是写工具（走审批）、不能进只读白名单、且在「插件开发」组里。
        assertTrue("plugin_dev_middleware" in PluginDevTools.writeNames)
        assertFalse("plugin_dev_middleware" in PluginDevTools.readOnlyNames)
        assertEquals(
            "builtin.plugin.dev",
            BuiltinPlugins.byTool("plugin_dev_middleware")?.id,
        )
        // 同组还有一个只读的状态查询工具，不写审批、不写审计。
        assertTrue("plugin_dev_middleware_state" in PluginDevTools.readOnlyNames)
        assertFalse("plugin_dev_middleware_state" in PluginDevTools.writeNames)
        assertEquals(
            "builtin.plugin.dev",
            BuiltinPlugins.byTool("plugin_dev_middleware_state")?.id,
        )
    }

    @Test fun middlewareStateTextTellsModelWhenHooksAreBypassed() {
        val on = PluginDevTools.middlewareStateText(true)
        assertTrue(on, on.contains("插件中间件总开关：开"))
        assertFalse(on, on.contains("旁路"))

        val off = PluginDevTools.middlewareStateText(false)
        assertTrue(off, off.contains("插件中间件总开关：关"))
        assertTrue(off, off.contains("旁路"))
        assertTrue(off, off.contains("扩展点插件不会生效"))
        // M88-I：设置页「插件」分组已删除，开关只在 插件页 → 扩展点 顶部；文案不能再指旧位置。
        assertTrue(off, off.contains("插件页") && off.contains("扩展点"))
        assertFalse(off, off.contains("设置 → 插件"))
    }

    @Test fun builtinPluginDevGroupCoversEveryPluginDevTool() {
        // 插件化的判据：工具名必须挂在内置插件组上（关掉「插件开发」= 从 registry 注销）。
        val group = BuiltinPlugins.byId("builtin.plugin.dev") ?: error("缺内置组 builtin.plugin.dev")
        assertEquals(PluginDevTools.toolNames, group.tools)
        PluginDevTools.toolNames.forEach { name ->
            assertEquals("$name 未归属内置组", "builtin.plugin.dev", BuiltinPlugins.byTool(name)?.id)
        }
    }

    @Test fun aiControlSwitchAndRoleValidationArePure() {
        // 设置关闭 → 固定错误码；开启 → 放行（null）。
        assertNull(PluginDevTools.aiControlError(true))
        val disabled = PluginDevTools.aiControlError(false)!!
        assertTrue(disabled, disabled.contains("ai_plugin_control_disabled"))
        assertTrue(disabled, disabled.contains("AI 插件控制已关闭"))

        // role/value 解析：未知角色与空 value 都显式报错。
        assertNull(PluginDevTools.roleError("shell", "native"))
        assertNull(PluginDevTools.roleError("model", "builtin.model.deepseek"))
        assertNull(PluginDevTools.roleError("agent", "m85.echo#echo"))
        assertTrue(PluginDevTools.roleError("bogus", "x")!!.contains("未知角色"))
        assertTrue(PluginDevTools.roleError("shell", "  ")!!.contains("需要 value"))
        assertEquals(
            setOf("shell", "loading", "agent", "storage", "prompt", "model"),
            PluginDevTools.ROLE_NAMES,
        )
    }
}
