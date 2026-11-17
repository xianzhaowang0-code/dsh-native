package xin.dshaa.nativeapp.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dshaa.core.plugins.PluginCapabilityProvider
import xin.dshaa.core.plugins.PluginRecord

/**
 * M88-B（docs/183 §2）：能力层只保留一条 Shell 身份链。
 *
 * `root.exec` 退出内置候选（直接按 id 调由 `CapabilityHost.invoke` 返回结构化拒绝，
 * JSON 结构在 support 的 `ShellCapability.mergedRootExecJson` 单测）；
 * `shell.exec` 走 root → Shizuku → App。
 */
class CapabilityHostCandidatesTest {

    private fun record(
        id: String = "cap.demo",
        enabled: Boolean = true,
        granted: List<String> = listOf("capability"),
    ) = PluginRecord(
        id = id,
        name = id,
        version = "1.0.0",
        description = "",
        path = "/tmp/$id",
        main = "index.js",
        format = "whale-lite",
        enabled = enabled,
        tools = emptyList(),
        imports = emptyList(),
        error = null,
        installedAt = 0L,
        grantedPermissions = granted,
        capabilities = listOf(PluginCapabilityProvider(id = "demo.echo", label = "回声", kind = "utility")),
    )

    @Test
    fun builtinCandidatesOnlyHaveTheMergedShellChain() {
        val ids = CapabilityHost.candidatesOf(emptyList()).map { it.id }
        assertFalse("root.exec 已并入 shell.exec，不能再出现在候选", ids.contains("root.exec"))
        assertTrue(ids.contains("shell.exec"))
        assertTrue(ids.contains("shell.status"))
        assertTrue(ids.contains("notify"))
        assertTrue(ids.contains("clipboard.read"))
    }

    @Test
    fun pluginCapabilitiesStillCarryEnabledAndPermissionFlags() {
        val candidates = CapabilityHost.candidatesOf(listOf(record(enabled = false, granted = emptyList())))
        val plugin = candidates.single { it.id == "demo.echo" }
        assertFalse(plugin.builtin)
        assertFalse(plugin.enabled)
        assertFalse(plugin.permitted)

        val granted = CapabilityHost.candidatesOf(listOf(record())).single { it.id == "demo.echo" }
        assertTrue(granted.enabled)
        assertTrue(granted.permitted)
    }
}
