package xin.dshaa.nativeapp.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M87-H7：root 探测的解析/降级纯函数。
 *
 * 本轮**不实测 root 成功路径**（设备无 root，docs/182 §H7-4）：只锁住
 * 「哪种 su 写法成功怎么记」「超时=等待授权」「没 root 不降级」这些能在 JVM 验的部分。
 */
class RootProbeTest {

    @Test
    fun looksLikeRootOnlyForUidZero() {
        assertTrue(RootProbe.looksLikeRoot("uid=0(root) gid=0(root) groups=0(root)"))
        assertTrue(RootProbe.looksLikeRoot("uid=0 gid=0"))
        assertFalse(RootProbe.looksLikeRoot("uid=2000(shell) gid=2000(shell)"))
        assertFalse(RootProbe.looksLikeRoot("uid=10123(u0_a123)"))
        assertFalse(RootProbe.looksLikeRoot("uid=1000(system)"))
    }

    @Test
    fun probesInOrderAndRecordsWinningCommand() {
        val tried = mutableListOf<List<String>>()
        val result = RootProbe.probe { argv ->
            tried += argv
            SuOutput(stdout = if (argv[1] == "0") "uid=0(root)" else "", exitCode = 0)
        }
        assertTrue(result.available)
        assertEquals("su 0 id", result.command)
        assertEquals(listOf("su -c id", "su 0 id"), tried.map { RootProbe.commandText(it) })
    }

    @Test
    fun firstVariantWinsWithoutTryingOthers() {
        val tried = mutableListOf<String>()
        val result = RootProbe.probe { argv ->
            tried += RootProbe.commandText(argv)
            SuOutput(stdout = "uid=0(root) gid=0(root)", exitCode = 0)
        }
        assertTrue(result.available)
        assertEquals("su -c id", result.command)
        assertEquals(listOf("su -c id"), tried)
    }

    @Test
    fun noRootReturnsReadableUnavailableReason() {
        val result = RootProbe.probe { SuOutput(stderr = "su: permission denied", exitCode = 1) }
        assertFalse(result.available)
        assertTrue(result.reason, result.reason.contains("root 不可用"))
        assertTrue(result.reason, result.reason.contains("permission denied"))
    }

    @Test
    fun missingSuBinaryAlsoDegradesToUnavailable() {
        val result = RootProbe.probe { null }
        assertFalse(result.available)
        assertTrue(result.reason, result.reason.contains("root 不可用"))
    }

    @Test
    fun timeoutMeansWaitingForAuthorizationAndStopsProbing() {
        val tried = mutableListOf<String>()
        val result = RootProbe.probe { argv ->
            tried += RootProbe.commandText(argv)
            SuOutput(timedOut = true)
        }
        assertFalse(result.available)
        assertTrue(result.waiting)
        assertTrue(result.reason, result.reason.contains("等待 root 授权"))
        assertEquals("超时后不应再试其它写法，否则会重复弹授权框", listOf("su -c id"), tried)
    }

    @Test
    fun identityPrefersRootThenPermittedShizukuThenApp() {
        assertEquals("root", RootProbe.identity(root = true, shizuku = true, shizukuPermission = true))
        assertEquals("shizuku", RootProbe.identity(root = false, shizuku = true, shizukuPermission = true))
        assertEquals("app", RootProbe.identity(root = false, shizuku = true, shizukuPermission = false))
        assertEquals("app", RootProbe.identity(root = false, shizuku = false, shizukuPermission = false))
    }

    @Test
    fun statusExplainsIdentityAndPermissions() {
        val root = RootProbe.status(true, "su -c id", shizuku = false, shizukuPermission = false, rootReason = "root 可用（su -c id）")
        assertEquals("root", root.defaultIdentity)
        assertTrue(root.reason.contains("root 可用"))

        val shizuku = RootProbe.status(false, null, shizuku = true, shizukuPermission = true, rootReason = "root 不可用")
        assertEquals("shizuku", shizuku.defaultIdentity)
        assertTrue(shizuku.reason.contains("Shizuku"))

        val app = RootProbe.status(false, null, shizuku = true, shizukuPermission = false, rootReason = "root 不可用")
        assertEquals("app", app.defaultIdentity)
        assertTrue(app.reason, app.reason.contains("未授权"))
    }

    @Test
    fun rootUnavailableJsonKeepsStructuredReason() {
        val json = ShellCapability.rootUnavailableJson()
        assertTrue(json.contains("\"ok\":false"))
        assertTrue(json.contains("\"reason\":\"root_unavailable\""))
        assertTrue(json.contains("message"))
    }

    @Test
    fun execIdentityUsesTheSameRootShizukuAppChain() {
        // M88-B：能力层的 shell.exec 身份链与执行顺序 / shell.status 的 default 一致。
        assertEquals("root", ShellCapability.execIdentity(root = true, shizuku = true, shizukuPermission = true))
        assertEquals("shizuku", ShellCapability.execIdentity(root = false, shizuku = true, shizukuPermission = true))
        assertEquals("app", ShellCapability.execIdentity(root = false, shizuku = true, shizukuPermission = false))
        assertEquals("app", ShellCapability.execIdentity(root = false, shizuku = false, shizukuPermission = false))
    }

    @Test
    fun mergedRootExecJsonIsStructuredAndExplainsTheMerge() {
        val json = ShellCapability.mergedRootExecJson()
        assertTrue(json.contains("\"ok\":false"))
        assertTrue(json.contains("\"reason\":\"merged_into_shell_exec\""))
        assertTrue(json.contains("root.exec 已并入 shell.exec"))
        assertTrue(json.contains("root → Shizuku → App"))
    }

    @Test
    fun execJsonEscapesOutputAndKeepsIdentity() {
        val json = ShellCapability.execJson(
            ok = false,
            exitCode = 1,
            stdout = "a\nb",
            stderr = "quote\"here",
            identity = "app",
        )
        assertTrue(json.contains("\"ok\":false"))
        assertTrue(json.contains("\"exitCode\":1"))
        assertTrue(json.contains("a\\nb"))
        assertTrue(json.contains("quote\\\"here"))
        assertTrue(json.contains("\"identity\":\"app\""))
    }

    @Test
    fun statusJsonHasContractFields() {
        val status = ShellStatus(
            root = false,
            rootCommand = null,
            shizuku = true,
            shizukuPermission = false,
            defaultIdentity = "app",
            reason = "Shizuku 已连接但未授权；当前只能用 App 身份",
        )
        val json = ShellCapability.statusJson(status)
        assertTrue(json.contains("\"root\":false"))
        assertTrue(json.contains("\"rootCommand\":null"))
        assertTrue(json.contains("\"shizuku\":true"))
        assertTrue(json.contains("\"shizukuPermission\":false"))
        assertTrue(json.contains("\"default\":\"app\""))
        assertTrue(json.contains("\"preference\":\"root>shizuku>app\""))
        assertTrue(json.contains("\"reason\""))
    }
}
