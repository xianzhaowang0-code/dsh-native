package xin.dshaa.ui.shellapi

/**
 * M88-A：生成「扩展点」行所需的单个插件快照（纯数据，不含 App/运行时依赖）。
 *
 * 只有**已启用**的插件会产出行；停用插件不出现（与角色页同一规则），删除后自然也不出现。
 * 数据源仍是宿主已经拿到的：清单声明 + 常驻运行时注册元数据 + 本进程命中统计。
 */
data class PluginExtensionInput(
    val pluginId: String,
    val pluginName: String,
    val enabled: Boolean,
    /** 清单里申请的权限原文。 */
    val permissions: List<String> = emptyList(),
    /** 用户/AI 已授予的权限。 */
    val grantedPermissions: List<String> = emptyList(),
    /** 清单声明了工具中间件（restrict/guard/describe/pre/post/result）。 */
    val toolMiddleware: Boolean = false,
    /** 清单声明了请求/提示词中间件。 */
    val llmMiddleware: Boolean = false,
    /** 清单声明了会话写入钩子。 */
    val storageHooks: Boolean = false,
    /** 清单声明的插槽。 */
    val slots: List<PluginSlotInput> = emptyList(),
    /** 常驻运行时注册的工具中间件元数据；null = 未常驻/没有。 */
    val tool: PluginToolExtensionInput? = null,
    /** 常驻运行时注册的请求中间件元数据；null = 未常驻/没有。 */
    val llm: PluginLlmExtensionInput? = null,
    /** 常驻运行时注册的存储钩子元数据；null = 未常驻/没有。 */
    val storage: PluginStorageExtensionInput? = null,
)

/** 一条插槽声明（[slot] 是槽位名，[id] 是插件内 id，[order] 是注册顺序）。 */
data class PluginSlotInput(val slot: String, val id: String, val order: Int)

/** 工具中间件元数据（同步部分 + 三个 JS 往返计数）。 */
data class PluginToolExtensionInput(
    val restrict: List<String> = emptyList(),
    val guard: List<String> = emptyList(),
    val describe: List<String> = emptyList(),
    val preExecute: Int = 0,
    val postExecute: Int = 0,
    val result: Int = 0,
) {
    val isEmpty: Boolean
        get() = restrict.isEmpty() && guard.isEmpty() && describe.isEmpty() &&
            preExecute <= 0 && postExecute <= 0 && result <= 0
}

/** 请求/提示词中间件元数据（四个扩展点的注册计数）。 */
data class PluginLlmExtensionInput(
    val assemble: Int = 0,
    val request: Int = 0,
    val stream: Int = 0,
    val preStep: Int = 0,
) {
    val isEmpty: Boolean
        get() = assemble <= 0 && request <= 0 && stream <= 0 && preStep <= 0
}

/** 存储/事件钩子元数据。 */
data class PluginStorageExtensionInput(
    val beforeAppend: Int = 0,
    val afterLoad: Int = 0,
    val subscriptions: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = beforeAppend <= 0 && afterLoad <= 0 && subscriptions.isEmpty()
}

/**
 * M88-A：把插件快照变成「扩展点」页签的行（纯函数，JVM 可测）。
 *
 * 规则：
 * - 只处理 [PluginExtensionInput.enabled] 的插件；停用插件**不产生任何行**（连「声明存在」占位也没有）；
 * - 启用但常驻运行时还没起来 → 声明过的链显示「未常驻（重新启用后加载）」；
 * - 缺授权 / 总开关已关 / 生效 三态与 M87-F 一致；
 * - [order] 用**原始清单顺序**（1 起），过滤停用插件不改变已启用插件的序号；
 * - 最终只保留至少有一条链的行（没有任何声明 = 扩展点页签里不占位）。
 */
object PluginExtensionRows {
    fun build(
        inputs: List<PluginExtensionInput>,
        switchOn: Boolean,
        stats: (pluginId: String, phase: String, hashes: Boolean) -> String = { _, _, _ -> "" },
    ): List<PluginExtensionUi> {
        return inputs.mapIndexedNotNull { index, input ->
            if (!input.enabled) return@mapIndexedNotNull null
            val chains = chainsOf(input, switchOn, stats)
            if (chains.isEmpty()) return@mapIndexedNotNull null
            PluginExtensionUi(
                pluginId = input.pluginId,
                pluginName = input.pluginName,
                enabled = true,
                order = index + 1,
                chains = chains,
            )
        }
    }

    private fun chainsOf(
        input: PluginExtensionInput,
        switchOn: Boolean,
        stats: (pluginId: String, phase: String, hashes: Boolean) -> String,
    ): List<PluginExtensionChainUi> {
        val chains = ArrayList<PluginExtensionChainUi>()
        fun statusOf(granted: Boolean, permission: String, resident: Boolean): String = when {
            !resident -> "未常驻（重新启用后加载）"
            !granted -> "缺授权 $permission"
            !switchOn -> "总开关已关（旁路）"
            else -> "生效"
        }

        // 工具中间件：restrict/guard/describe 是同步元数据；pre/post/result 是 JS 往返。
        val toolMeta = input.tool
        val toolResident = toolMeta != null && !toolMeta.isEmpty
        if (input.toolMiddleware || toolResident) {
            val intercept = PermissionChecks.toolsInterceptGranted(input.permissions, input.grantedPermissions)
            if (input.toolMiddleware && !toolResident) {
                chains += PluginExtensionChainUi(
                    "工具", "middleware", "未常驻（重新启用后加载）",
                    detail = "声明存在；具体阶段在常驻运行时启动后展示",
                )
            }
            if (toolMeta != null && toolMeta.restrict.isNotEmpty()) {
                chains += PluginExtensionChainUi(
                    kind = "工具",
                    name = "restrict",
                    status = statusOf(true, "-", toolResident),
                    detail = toolMeta.restrict.joinToString("、"),
                )
            }
            if (toolMeta != null && toolMeta.guard.isNotEmpty()) {
                chains += PluginExtensionChainUi(
                    kind = "工具",
                    name = "guard",
                    status = statusOf(true, "-", toolResident),
                    stats = stats(input.pluginId, "tools.guard", false),
                    detail = toolMeta.guard.joinToString("、"),
                )
            }
            if (toolMeta != null && toolMeta.describe.isNotEmpty()) {
                chains += PluginExtensionChainUi(
                    kind = "工具",
                    name = "describe",
                    status = statusOf(intercept, "tools:intercept", toolResident),
                    detail = toolMeta.describe.joinToString("、"),
                )
            }
            if (toolMeta != null) {
                if (toolMeta.preExecute > 0) {
                    chains += PluginExtensionChainUi(
                        "工具", "pre-execute", statusOf(intercept, "tools:intercept", toolResident),
                        stats(input.pluginId, "tools.preExecute", false),
                    )
                }
                if (toolMeta.postExecute > 0) {
                    chains += PluginExtensionChainUi(
                        "工具", "post-execute", statusOf(intercept, "tools:intercept", toolResident),
                        stats(input.pluginId, "tools.postExecute", true),
                    )
                }
                if (toolMeta.result > 0) {
                    chains += PluginExtensionChainUi(
                        "工具", "result", statusOf(intercept, "tools:intercept", toolResident),
                        stats(input.pluginId, "tools.result", false),
                    )
                }
            }
        }

        // 请求/提示词中间件（llm:request）。
        val llmMeta = input.llm
        val llmResident = llmMeta != null && !llmMeta.isEmpty
        if (input.llmMiddleware || llmResident) {
            val granted = PermissionChecks.llmRequestGranted(input.permissions, input.grantedPermissions)
            if (input.llmMiddleware && !llmResident) {
                chains += PluginExtensionChainUi(
                    "请求", "middleware", "未常驻（重新启用后加载）",
                    detail = "声明存在；具体扩展点在常驻运行时启动后展示",
                )
            }
            fun requestLine(label: String, count: Int, method: String) {
                if (count <= 0) return
                chains += PluginExtensionChainUi(
                    "请求", label, statusOf(granted, "llm:request", llmResident),
                    stats(input.pluginId, method, false),
                )
            }
            if (llmMeta != null) {
                requestLine("system-prompt/assemble", llmMeta.assemble, "ext.assemble")
                requestLine("agent/request", llmMeta.request, "ext.request")
                requestLine("llm/stream", llmMeta.stream, "ext.stream")
                requestLine("agent/pre-step", llmMeta.preStep, "ext.preStep")
            }
        }

        // 存储/事件钩子（storage:hooks）。
        val storageMeta = input.storage
        val storageResident = storageMeta != null && !storageMeta.isEmpty
        if (input.storageHooks || storageResident) {
            val granted = PermissionChecks.storageHooksGranted(input.permissions, input.grantedPermissions)
            if (input.storageHooks && !storageResident) {
                chains += PluginExtensionChainUi(
                    "存储", "storage hooks", "未常驻（重新启用后加载）",
                    detail = "声明存在；before-append/after-load 在常驻运行时启动后展示",
                )
            }
            if (storageMeta != null && storageMeta.beforeAppend > 0) {
                chains += PluginExtensionChainUi(
                    "存储", "storage.on('before-append')", statusOf(granted, "storage:hooks", storageResident),
                    stats(input.pluginId, "storage.beforeAppend", false),
                )
            }
            if (storageMeta != null && storageMeta.afterLoad > 0) {
                chains += PluginExtensionChainUi(
                    "存储", "storage.on('after-load')", statusOf(granted, "storage:hooks", storageResident),
                    stats(input.pluginId, "storage.afterLoad", false),
                )
            }
            if (storageMeta != null && storageMeta.subscriptions.isNotEmpty()) {
                chains += PluginExtensionChainUi(
                    "事件", "session/event 订阅", statusOf(true, "-", storageResident),
                    detail = storageMeta.subscriptions.joinToString("、"),
                )
            }
        }

        // 插槽（ui:slot）：不是中间件，总开关关掉也照常渲染（排障时不藏 UI）。
        if (input.slots.isNotEmpty()) {
            val granted = PermissionChecks.uiSlotGranted(input.permissions, input.grantedPermissions)
            input.slots.forEach { slot ->
                chains += PluginExtensionChainUi(
                    kind = "插槽",
                    name = slot.slot,
                    status = if (!granted) "缺授权 ui:slot" else "生效",
                    detail = "${slot.id} · order=${slot.order}",
                )
            }
        }
        return chains
    }

    /** 与 `core:plugins/PluginPermissions` 同一套判定，这里只做只读调用（不复制规则）。 */
    private object PermissionChecks {
        fun toolsInterceptGranted(requested: Collection<String>, granted: Collection<String>): Boolean =
            xin.dshaa.core.plugins.PluginPermissions.toolsInterceptGranted(requested, granted)

        fun llmRequestGranted(requested: Collection<String>, granted: Collection<String>): Boolean =
            xin.dshaa.core.plugins.PluginPermissions.llmRequestGranted(requested, granted)

        fun storageHooksGranted(requested: Collection<String>, granted: Collection<String>): Boolean =
            xin.dshaa.core.plugins.PluginPermissions.storageHooksGranted(requested, granted)

        fun uiSlotGranted(requested: Collection<String>, granted: Collection<String>): Boolean =
            xin.dshaa.core.plugins.PluginPermissions.uiSlotGranted(requested, granted)
    }
}
