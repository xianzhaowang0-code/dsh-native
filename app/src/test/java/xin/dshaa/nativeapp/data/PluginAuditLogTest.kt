package xin.dshaa.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * M88-E（docs/183 §5）：插件审计。
 *
 * `files/plugin-audit.jsonl` 追加 `{ts,actor,action,target,detail}`；512KB 轮转保留 1 份；
 * 读回按先旧后新；「AI 授予」集合按 grant/revoke 回放。
 */
class PluginAuditLogTest {

    private fun tempDir(): File = Files.createTempDirectory("plugin-audit-test").toFile()

    @Test
    fun appendAndReadBackBothActors() {
        val dir = tempDir()
        val log = PluginAuditLog(File(dir, "plugin-audit.jsonl"))
        log.append(PluginAuditLog.ACTOR_AI, PluginAuditLog.ACTION_GRANT, "m87e-storage", "storage:hooks")
        log.append(PluginAuditLog.ACTOR_USER, PluginAuditLog.ACTION_REVOKE, "m87e-storage", "storage:hooks")

        val entries = log.entries()
        assertEquals(2, entries.size)
        assertEquals("ai", entries[0].actor)
        assertEquals("grant", entries[0].action)
        assertEquals("m87e-storage", entries[0].target)
        assertEquals("storage:hooks", entries[0].detail)
        assertEquals("user", entries[1].actor)

        assertEquals(2, log.recent("m87e-storage").size)
        assertEquals("revoke", log.recent("m87e-storage").first().action)
        assertTrue(log.recent("other").isEmpty())
    }

    @Test
    fun lastAiActionIsPerPlugin() {
        val log = PluginAuditLog(File(tempDir(), "plugin-audit.jsonl"))
        log.append(PluginAuditLog.ACTOR_AI, PluginAuditLog.ACTION_GRANT, "a", "storage:hooks")
        log.append(PluginAuditLog.ACTOR_AI, PluginAuditLog.ACTION_ROLE, "b", "shell → native")
        assertTrue(log.lastAiAction("a")!!.startsWith("grant storage:hooks"))
        assertTrue(log.lastAiAction("b")!!.startsWith("role shell → native"))
        assertNull(log.lastAiAction("c"))
    }

    @Test
    fun aiGrantedPermissionsReplayGrantsAndRevokes() {
        val log = PluginAuditLog(File(tempDir(), "plugin-audit.jsonl"))
        log.append(PluginAuditLog.ACTOR_AI, PluginAuditLog.ACTION_GRANT, "p", "storage:hooks、llm:request")
        assertEquals(setOf("storage:hooks", "llm:request"), log.aiGrantedPermissions("p"))

        log.append(PluginAuditLog.ACTOR_USER, PluginAuditLog.ACTION_REVOKE, "p", "llm:request")
        // 用户撤销也要计入回放（否则详情会一直标「AI 授予」）。
        assertEquals(setOf("storage:hooks"), log.aiGrantedPermissions("p"))

        log.append(PluginAuditLog.ACTOR_AI, PluginAuditLog.ACTION_REVOKE_ALL, "p", "storage:hooks")
        assertTrue(log.aiGrantedPermissions("p").isEmpty())
    }

    @Test
    fun rotationKeepsOneBackupAndStillReadsBoth() {
        val dir = tempDir()
        val file = File(dir, "plugin-audit.jsonl")
        val log = PluginAuditLog(file, maxBytes = 300)
        repeat(20) { index ->
            log.append(PluginAuditLog.ACTOR_AI, PluginAuditLog.ACTION_GRANT, "p$index", "storage:hooks")
        }
        val backup = File(dir, "plugin-audit.jsonl.1")
        assertTrue(backup.exists())
        assertTrue(file.length() <= 300)
        val entries = log.entries()
        assertTrue(entries.size in 2..20)
        // 最新一条仍在当前文件里；读回顺序 = 旧 → 新。
        assertEquals("p19", entries.last().target)
        assertTrue(entries.zipWithNext().all { (a, b) -> a.ts <= b.ts })
    }

    @Test
    fun badLinesAreSkippedAndMissingFileIsEmpty() {
        val dir = tempDir()
        val file = File(dir, "plugin-audit.jsonl")
        file.writeText("not-json\n{\"ts\":1,\"actor\":\"ai\",\"action\":\"grant\",\"target\":\"p\",\"detail\":\"x\"}\n")
        val log = PluginAuditLog(file)
        assertEquals(1, log.entries().size)
        assertTrue(PluginAuditLog(File(dir, "missing.jsonl")).entries().isEmpty())
    }
}
