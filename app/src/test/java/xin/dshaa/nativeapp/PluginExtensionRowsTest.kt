package xin.dshaa.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dshaa.ui.shellapi.PluginExtensionInput
import xin.dshaa.ui.shellapi.PluginExtensionRows
import xin.dshaa.ui.shellapi.PluginLlmExtensionInput
import xin.dshaa.ui.shellapi.PluginSlotInput
import xin.dshaa.ui.shellapi.PluginStorageExtensionInput
import xin.dshaa.ui.shellapi.PluginToolExtensionInput

/**
 * M88-A（docs/183 §1）：扩展点页签隐藏停用插件。
 *
 * 用户要求：插件关闭后不要在「选择」里显示；和角色页一样，关闭不显示、删除彻底删除。
 * 启用了但常驻运行时还没起来的插件仍显示「未常驻」——这是运行时状态，不是停用。
 */
class PluginExtensionRowsTest {

    private fun plugin(
        id: String = "m87e-storage",
        enabled: Boolean = true,
        permissions: List<String> = listOf("storage:hooks", "llm:request"),
        granted: List<String> = permissions,
        toolMiddleware: Boolean = false,
        llmMiddleware: Boolean = false,
        storageHooks: Boolean = false,
        slots: List<PluginSlotInput> = emptyList(),
        tool: PluginToolExtensionInput? = null,
        llm: PluginLlmExtensionInput? = null,
        storage: PluginStorageExtensionInput? = null,
    ) = PluginExtensionInput(
        pluginId = id,
        pluginName = id,
        enabled = enabled,
        permissions = permissions,
        grantedPermissions = granted,
        toolMiddleware = toolMiddleware,
        llmMiddleware = llmMiddleware,
        storageHooks = storageHooks,
        slots = slots,
        tool = tool,
        llm = llm,
        storage = storage,
    )

    @Test fun disabledPluginProducesNoRowsEvenWithAllFourChainsDeclared() {
        val record = plugin(
            enabled = false,
            toolMiddleware = true,
            llmMiddleware = true,
            storageHooks = true,
            slots = listOf(PluginSlotInput("chat.composer.dock", "dock", 0)),
        )
        // 即使常驻元数据还在（停用前的残留），停用插件也不出现。
        val rows = PluginExtensionRows.build(
            inputs = listOf(
                record.copy(
                    tool = PluginToolExtensionInput(guard = listOf("*"), preExecute = 2),
                    llm = PluginLlmExtensionInput(assemble = 1),
                    storage = PluginStorageExtensionInput(beforeAppend = 1),
                ),
            ),
            switchOn = true,
        )
        assertTrue(rows.isEmpty())
    }

    @Test fun enabledButNotResidentStillShowsOneNotResidentRow() {
        val rows = PluginExtensionRows.build(
            inputs = listOf(plugin(toolMiddleware = true)),
            switchOn = true,
        )
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].chains.size)
        assertEquals("middleware", rows[0].chains[0].name)
        assertEquals("未常驻（重新启用后加载）", rows[0].chains[0].status)
        assertTrue(rows[0].enabled)
    }

    @Test fun permissionAndSwitchStateAreRenderedPerChain() {
        val residentTool = PluginToolExtensionInput(guard = listOf("*"), preExecute = 1)
        val missingGrant = PluginExtensionRows.build(
            inputs = listOf(
                plugin(
                    permissions = listOf("tools:intercept"),
                    granted = emptyList(),
                    tool = residentTool,
                ),
            ),
            switchOn = true,
        )
        assertEquals("缺授权 tools:intercept", missingGrant[0].chains.first { it.name == "pre-execute" }.status)

        val switchOff = PluginExtensionRows.build(
            inputs = listOf(plugin(permissions = listOf("tools:intercept"), tool = residentTool)),
            switchOn = false,
        )
        assertEquals("总开关已关（旁路）", switchOff[0].chains.first { it.name == "pre-execute" }.status)

        val effective = PluginExtensionRows.build(
            inputs = listOf(plugin(permissions = listOf("tools:intercept"), tool = residentTool)),
            switchOn = true,
        )
        assertEquals("生效", effective[0].chains.first { it.name == "pre-execute" }.status)
    }

    @Test fun deletedOrDeclarationFreePluginHasNoRow() {
        assertTrue(PluginExtensionRows.build(inputs = emptyList(), switchOn = true).isEmpty())
        // 启用但没声明任何扩展点：不占位。
        assertTrue(PluginExtensionRows.build(inputs = listOf(plugin()), switchOn = true).isEmpty())
    }

    @Test fun disabledPluginDoesNotShiftRemainingOrder() {
        val rows = PluginExtensionRows.build(
            inputs = listOf(
                plugin(id = "off", enabled = false, llmMiddleware = true),
                plugin(id = "on", llmMiddleware = true),
            ),
            switchOn = true,
        )
        assertEquals(1, rows.size)
        assertEquals("on", rows[0].pluginId)
        assertEquals(2, rows[0].order)
    }

    @Test fun storageHooksAndSlotsAreListedWithTheirStatus() {
        val rows = PluginExtensionRows.build(
            inputs = listOf(
                plugin(
                    permissions = listOf("storage:hooks", "ui:slot"),
                    storageHooks = true,
                    slots = listOf(PluginSlotInput("chat.composer.dock", "dock", 3)),
                    storage = PluginStorageExtensionInput(beforeAppend = 1, afterLoad = 1),
                ),
            ),
            switchOn = true,
        )
        assertEquals(1, rows.size)
        val names = rows[0].chains.map { it.name }
        assertTrue(names.contains("storage.on('before-append')"))
        assertTrue(names.contains("storage.on('after-load')"))
        assertTrue(names.contains("chat.composer.dock"))
        assertEquals("生效", rows[0].chains.first { it.name == "chat.composer.dock" }.status)
    }

    @Test fun statsLambdaIsUsedForExecutionChains() {
        val calls = ArrayList<String>()
        val rows = PluginExtensionRows.build(
            inputs = listOf(
                plugin(
                    permissions = listOf("tools:intercept"),
                    tool = PluginToolExtensionInput(postExecute = 2),
                ),
            ),
            switchOn = true,
            stats = { pluginId, phase, hashes ->
                calls += "$pluginId/$phase/$hashes"
                "命中 1"
            },
        )
        assertEquals("命中 1", rows[0].chains.first { it.name == "post-execute" }.stats)
        assertEquals(listOf("m87e-storage/tools.postExecute/true"), calls)
    }
}
