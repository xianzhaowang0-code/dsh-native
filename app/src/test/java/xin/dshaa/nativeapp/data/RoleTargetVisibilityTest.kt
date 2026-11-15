package xin.dshaa.nativeapp.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dshaa.ui.shellapi.AgentTargetUi
import xin.dshaa.ui.shellapi.RoleTargetUi
import xin.dshaa.ui.shellapi.visibleInRoleList

/**
 * 0.3.4（用户 2026-11-15 第 4 条 b）：插件页「角色」不再列停用/未授权的第三方实现；
 * 内置实现始终保留（可用性由内置开关决定，行内带原因）。
 */
class RoleTargetVisibilityTest {

    private fun thirdParty(enabled: Boolean = true, permitted: Boolean = true) = RoleTargetUi(
        role = "storage",
        implementationId = "export",
        label = "只读导出",
        pluginId = "demo.role",
        provider = "Demo",
        builtin = false,
        enabled = enabled,
        permitted = permitted,
        selectionValue = "demo.role#export",
    )

    @Test fun disabledOrUnauthorizedThirdPartyHidesFromRoleList() {
        assertTrue(thirdParty().visibleInRoleList())
        assertFalse(thirdParty(enabled = false).visibleInRoleList())
        assertFalse(thirdParty(permitted = false).visibleInRoleList())
    }

    @Test fun builtinAlwaysStaysVisibleEvenWhenItsPluginIsOff() {
        val builtin = RoleTargetUi(
            role = "shell",
            implementationId = "builtin.shell.native",
            label = "原生界面",
            builtin = true,
            enabled = false,
            active = false,
            selectionValue = "native",
            unavailableReason = "内置界面插件已停用",
        )
        assertTrue(builtin.visibleInRoleList())
    }

    @Test fun bundledPluginShellFollowsItsOwnSwitch() {
        // 2026-11-15 用户反馈：内置里关掉「鲸歌可选壳」后，角色页仍显示「鲸歌默认壳」。
        // whale 是 bundled 插件（有开关），不是宿主内置实现，停用后必须从角色页消失。
        val whale = RoleTargetUi(
            role = "shell",
            implementationId = "builtin.shell.whale",
            label = "鲸歌默认壳（Surface）· surface",
            pluginId = "builtin.shell.whale",
            provider = "鲸歌可选壳（Surface）",
            builtin = true,
            enabled = false,
            permitted = true,
            selectionValue = "builtin.shell.whale#builtin.shell.whale",
            unavailableReason = "提供方插件已停用",
        )
        assertFalse(whale.visibleInRoleList())
        assertTrue(whale.copy(enabled = true).visibleInRoleList())
    }

    @Test fun agentTargetsFollowTheSameRule() {
        val thirdParty = AgentTargetUi(
            agentId = "m85.echo",
            label = "回声回合",
            pluginId = "m85.echo-agent",
            builtin = false,
            enabled = false,
        )
        assertFalse(thirdParty.visibleInRoleList())
        assertFalse(thirdParty.copy(enabled = true, permitted = false).visibleInRoleList())
        assertTrue(thirdParty.copy(enabled = true, permitted = true).visibleInRoleList())
        assertTrue(thirdParty.copy(pluginId = null, builtin = true).visibleInRoleList())
    }
}
