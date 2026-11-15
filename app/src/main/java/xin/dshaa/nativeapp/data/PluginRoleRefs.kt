package xin.dshaa.nativeapp.data

import xin.dshaa.core.plugins.PluginRecord

/**
 * 0.3.4（用户 2026-11-15 第 4 条 c / 第 6 条）：停用或删除插件时，判断哪些「角色」选择还指着它。
 *
 * 角色选择的持久化键与写法不统一：
 * - `dsh_shell`：`native` / `插件id` / `插件id#壳id`；
 * - `dsh_agent`：`native` / `插件id` / `插件id#回合id` / **裸回合 id**（调试通道/旧版本）；
 * - `dsh_storage` / `dsh_prompt` / `dsh_loading`：`native` / `插件id#实现id`（也兼容裸实现 id）。
 *
 * 停用/删除后如果只删插件记录、不清这些键，解析就会落到「未安装 xxx」，聊天页出现红字横幅、
 * `roleSafetyError` 直接拦发送，必须手点「恢复内置」——用户 2026-11-15 明确要求改成静默回退。
 * 纯函数放在这里，方便 JVM 单测覆盖各种写法。
 */
object PluginRoleRefs {

    /** 持久化键 → 角色中文名（清选择时的提示文案与顺序）。 */
    val prefKeys: List<Pair<String, String>> = listOf(
        "dsh_shell" to "界面壳",
        "dsh_agent" to "回合",
        "dsh_storage" to "存储",
        "dsh_prompt" to "提示词",
        "dsh_loading" to "加载页",
    )

    /**
     * [raw]（某个角色键当前的值）是否指向 [record]。
     *
     * 除了 `插件id` / `插件id#实现id`，也认裸实现 id：调试通道（`--es dsh_agent <回合id>`）和
     * 旧数据可能只写实现 id，不认就会留下「未安装」残影。
     */
    fun references(record: PluginRecord, key: String, raw: String?): Boolean {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.equals("native", true)) return false
        if (value.equals(record.id, true)) return true
        if (value.startsWith(record.id + "#", ignoreCase = true)) return true
        val implementationIds = when (key) {
            "dsh_shell" -> record.shells.map { it.id }
            "dsh_agent" -> record.agents.map { it.id }
            "dsh_storage" -> record.storages.map { it.id }
            "dsh_prompt" -> record.prompts.map { it.id }
            "dsh_loading" -> record.loadings.map { it.id }
            else -> emptyList()
        }
        return implementationIds.any { value.equals(it, true) }
    }

    /** 哪些角色键需要清：返回 (键, 角色名)，顺序按 [prefKeys]。 */
    fun rolesToClear(record: PluginRecord, read: (String) -> String?): List<Pair<String, String>> =
        prefKeys.filter { (key, _) -> references(record, key, read(key)) }

    /** 插件声明的模型通道（`ctx.llm.register`）是否等于某个已保存的 provider id。 */
    fun providerReferences(record: PluginRecord, providerId: String?): Boolean {
        val value = providerId?.trim().orEmpty()
        if (value.isEmpty()) return false
        return record.llmProviders.any { it.id.equals(value, true) }
    }

    /**
     * 0.3.4（用户第 7 条闸门 A）：删除前的「正在使用 / 运行中」判定；返回原因（null = 可以删）。
     *
     * 抽成纯函数便于 JVM 单测：宿主只负责把当前角色/运行时状态传进来。
     */
    fun inUseReason(
        record: PluginRecord,
        readPref: (String) -> String?,
        resident: Boolean,
        shellPluginId: String?,
        agentPluginId: String?,
        storagePluginId: String?,
        promptPluginId: String?,
        loadingOwnerPlugin: String?,
        modelProviderId: String?,
    ): String? {
        val roles = rolesToClear(record, readPref)
        if (roles.isNotEmpty()) {
            return "正在使用（角色：" + roles.joinToString("、") { it.second } + "）"
        }
        if (resident) return "常驻 JavaScript 运行时正在运行"
        if (shellPluginId == record.id) return "当前界面壳由它提供"
        if (agentPluginId == record.id) return "当前回合由它提供"
        if (storagePluginId == record.id) return "当前存储由它提供"
        if (promptPluginId == record.id) return "当前提示词由它提供"
        if (loadingOwnerPlugin == record.id) return "启动加载页快照由它提供"
        if (providerReferences(record, modelProviderId)) return "当前模型通道由它提供"
        return null
    }
}
