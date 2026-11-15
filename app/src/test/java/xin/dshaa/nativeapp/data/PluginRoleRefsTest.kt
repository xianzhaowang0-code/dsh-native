package xin.dshaa.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dshaa.core.plugins.LoadingSpec
import xin.dshaa.core.plugins.PluginAgent
import xin.dshaa.core.plugins.PluginLlmProvider
import xin.dshaa.core.plugins.PluginPrompt
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.core.plugins.PluginShell
import xin.dshaa.core.plugins.PluginStorage

/**
 * 0.3.4（用户 2026-11-15 第 4 条 c）：停用/删除插件时清角色选择。
 *
 * 覆盖各种持久化写法：`插件id`、`插件id#实现id`、裸实现 id、`native`、别的插件。
 */
class PluginRoleRefsTest {

    private fun record() = PluginRecord(
        id = "demo.role",
        name = "Demo Role",
        version = "1.0.0",
        description = "",
        path = "/tmp/demo-role",
        main = "index.js",
        format = "dsh",
        enabled = true,
        tools = emptyList(),
        imports = emptyList(),
        error = null,
        installedAt = 0L,
        shells = listOf(PluginShell("shell.one", "壳一")),
        agents = listOf(PluginAgent("agent.one", "回合一")),
        storages = listOf(PluginStorage("storage.one", "存储一")),
        prompts = listOf(PluginPrompt("prompt.one", "提示词一")),
        loadings = listOf(LoadingSpec.parse(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":"loading.one","label":"加载一","tree":{"type":"text","text":"hi"}}""",
        )).loading!!),
        llmProviders = listOf(PluginLlmProvider(id = "provider.one")),
    )

    @Test fun referencesMatchesPluginIdQualifiedAndBareImplementationIds() {
        val record = record()
        assertTrue(PluginRoleRefs.references(record, "dsh_shell", "demo.role"))
        assertTrue(PluginRoleRefs.references(record, "dsh_shell", "demo.role#shell.one"))
        assertTrue(PluginRoleRefs.references(record, "dsh_shell", "shell.one"))
        assertTrue(PluginRoleRefs.references(record, "dsh_agent", "demo.role#agent.one"))
        assertTrue(PluginRoleRefs.references(record, "dsh_agent", "agent.one"))
        assertTrue(PluginRoleRefs.references(record, "dsh_storage", "storage.one"))
        assertTrue(PluginRoleRefs.references(record, "dsh_prompt", "prompt.one"))
        assertTrue(PluginRoleRefs.references(record, "dsh_loading", "demo.role#loading.one"))
    }

    @Test fun referencesIgnoresNativeOtherPluginsAndUnrelatedIds() {
        val record = record()
        assertFalse(PluginRoleRefs.references(record, "dsh_shell", null))
        assertFalse(PluginRoleRefs.references(record, "dsh_shell", ""))
        assertFalse(PluginRoleRefs.references(record, "dsh_shell", "native"))
        assertFalse(PluginRoleRefs.references(record, "dsh_shell", "other.plugin"))
        assertFalse(PluginRoleRefs.references(record, "dsh_shell", "other.plugin#shell.one"))
        assertFalse(PluginRoleRefs.references(record, "dsh_agent", "agent.two"))
        assertFalse(PluginRoleRefs.references(record, "dsh_storage", "builtin.storage.jsonl"))
    }

    @Test fun rolesToClearReturnsOnlyPointingRolesInStableOrder() {
        val record = record()
        val prefs = mapOf(
            "dsh_shell" to "demo.role#shell.one",
            "dsh_agent" to "native",
            "dsh_storage" to "demo.role#storage.one",
            "dsh_prompt" to "other.plugin#prompt.one",
            "dsh_loading" to "demo.role",
        )
        val cleared = PluginRoleRefs.rolesToClear(record) { prefs[it] }
        assertEquals(listOf("dsh_shell" to "界面壳", "dsh_storage" to "存储", "dsh_loading" to "加载页"), cleared)
    }

    @Test fun rolesToClearIsEmptyWhenNothingPointsAtPlugin() {
        val record = record()
        val prefs = mapOf(
            "dsh_shell" to "native",
            "dsh_agent" to "other.plugin#agent.one",
            "dsh_storage" to "builtin.storage.jsonl",
            "dsh_prompt" to null,
            "dsh_loading" to "",
        )
        assertTrue(PluginRoleRefs.rolesToClear(record) { prefs[it] }.isEmpty())
    }

    @Test fun providerReferencesMatchesDeclaredModelProvider() {
        val record = record()
        assertTrue(PluginRoleRefs.providerReferences(record, "provider.one"))
        assertFalse(PluginRoleRefs.providerReferences(record, "provider.two"))
        assertFalse(PluginRoleRefs.providerReferences(record, null))
        assertFalse(PluginRoleRefs.providerReferences(record, "debug.echo"))
    }

    @Test fun inUseReasonCoversRolePrefsResidentAndActiveSelections() {
        val record = record()
        fun reason(
            readPref: (String) -> String? = { null },
            resident: Boolean = false,
            shell: String? = null,
            agent: String? = null,
            storage: String? = null,
            prompt: String? = null,
            loadingOwner: String? = null,
            modelProvider: String? = null,
        ) = PluginRoleRefs.inUseReason(record, readPref, resident, shell, agent, storage, prompt, loadingOwner, modelProvider)

        assertNull(reason())
        assertEquals(
            "正在使用（角色：存储）",
            reason(readPref = { if (it == "dsh_storage") "demo.role#storage.one" else null }),
        )
        assertEquals("常驻 JavaScript 运行时正在运行", reason(resident = true))
        assertEquals("当前界面壳由它提供", reason(shell = record.id))
        assertEquals("当前回合由它提供", reason(agent = record.id))
        assertEquals("当前存储由它提供", reason(storage = record.id))
        assertEquals("当前提示词由它提供", reason(prompt = record.id))
        assertEquals("启动加载页快照由它提供", reason(loadingOwner = record.id))
        assertEquals("当前模型通道由它提供", reason(modelProvider = "provider.one"))
    }
}
