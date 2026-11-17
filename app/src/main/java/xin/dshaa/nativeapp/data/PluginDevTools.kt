package xin.dshaa.nativeapp.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import xin.dshaa.core.plugins.LoadingSpec
import xin.dshaa.core.plugins.PluginLoading
import xin.dshaa.core.plugins.PluginSchema
import xin.dshaa.core.protocol.ApiTool
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import xin.dshaa.core.tools.resolvePath
import java.io.File

/** App-owned tools: mutations rely on the caller's existing ToolExecutor approval policy. */
class PluginDevTools(
    private val store: PluginStore,
    private val onChanged: suspend () -> Unit,
    private val callTool: suspend (String, String, JsonObject, ToolContext) -> ToolResult = { _, _, _, _ -> ToolResult.err("工具测试未挂载") },
    private val testCapability: suspend (String, JsonObject) -> ToolResult = { _, _ -> ToolResult.err("能力测试未挂载") },
    /** 0.3.4：停用后清角色选择（与 UI 开关同一条路径）；参数是插件 id。 */
    private val onDisabled: suspend (String) -> Unit = {},
    /** 0.3.4：删除插件（共享实现：清角色选择 + 常驻运行时 + 依赖重算）；force 只给 UI 用。 */
    private val delete: suspend (String, Boolean) -> PluginStore.Result = { id, _ ->
        PluginStore.Result(false, "删除通道未挂载：$id")
    },
    /** M88-E：AI 全控制插件总开关（设置 → 插件）；关掉后 grant/revoke/config/role 直接拒绝。 */
    private val aiPluginControlEnabled: () -> Boolean = { false },
    /** M88-E：审计写入（action / target / detail）。 */
    private val audit: (String, String, String) -> Unit = { _, _, _ -> },
    /** M88-E：最近一次 AI 操作（插件列表用）。 */
    private val lastAiAction: (String) -> String? = { null },
    /** M88-E：选择角色（shell/loading/agent/storage/prompt/model）；复用 AppViewModel 的选择逻辑。 */
    private val selectRole: (String, String) -> Boolean = { _, _ -> false },
    /** M88-E：AI 刚授予权限（插件 id、`、` 分隔的权限）；宿主据此显示「AI 已授予 … · 撤销」横幅。 */
    private val onAiGranted: (String, String) -> Unit = { _, _ -> },
    /** M88-I：插件中间件总开关的当前值（只读工具用；不进运行时上下文、不进 plugin_dev_list）。 */
    private val middlewareEnabled: () -> Boolean = { true },
    /** M88-I：改插件中间件总开关（复用 AppViewModel.setPluginMiddlewareEnabled，会重建链/钩子并即时生效）。 */
    private val setMiddlewareEnabled: (Boolean) -> Unit = {},
) {
    val tools: List<DshTool> = definitions.map { api -> object : DshTool {
        override val name = api.name
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            PluginSchema.validate(api.inputSchema, args)?.let { return ToolResult.err(it) }
            return try {
                executeTool(name, args, ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult.err(e.message ?: e.javaClass.simpleName)
            }
        }
    } }

    fun apiTools(): List<ApiTool> = definitions

    private suspend fun executeTool(name: String, args: JsonObject, ctx: ToolContext): ToolResult {
        fun string(key: String) = args[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun result(value: PluginStore.Result) = if (value.ok) ToolResult.ok(value.message) else ToolResult.err(value.message)
        when (name) {
            "plugin_dev_list" -> {
                val id = string("id")
                val records = withContext(Dispatchers.IO) { store.list() }
                if (id.isNotEmpty() && records.none { it.id == id }) return ToolResult.err("插件不存在：$id")
                val matches = records.filter { id.isEmpty() || it.id == id }
                return ToolResult.ok(buildJsonObject {
                    put("total", matches.size)
                    put("truncated", matches.size > 100)
                    put("plugins", buildJsonArray {
                        matches.take(100).forEach { record -> add(buildJsonObject {
                            put("id", record.id); put("name", record.name); put("version", record.version)
                            put("enabled", record.enabled); put("path", record.path)
                            put("tools", JsonArray(record.tools.take(100).map(::JsonPrimitive)))
                            put("shells", buildJsonArray { record.shells.forEach { shell -> add(buildJsonObject {
                                put("id", shell.id); put("backend", shell.backend)
                            }) } })
                            put("loadings", loadingMetadata(record.id, record.loadings))
                            put("permissions", JsonArray(record.permissions.map(::JsonPrimitive)))
                            put("grantedPermissions", JsonArray(record.grantedPermissions.map(::JsonPrimitive)))
                            lastAiAction(record.id)?.let { put("lastAiAction", it) }
                            record.error?.let { put("error", it.take(4000)) }
                            record.configError?.let { put("configError", it.take(2000)) }
                            // Do not return config/service values: they can contain credentials/user data.
                        }) }
                    })
                }.toString())
            }
            "plugin_dev_test" -> {
                val tested = store.testPath(resolvePath(string("path"), ctx))
                val text = buildJsonObject {
                    put("ok", tested.ok); put("message", tested.message.take(8000))
                    tested.id?.let { put("id", it) }
                    tested.run?.let { run ->
                        put("tools", JsonArray(run.tools.take(100).map { JsonPrimitive(it.name) }))
                        put("shells", buildJsonArray { run.shells.forEach { shell -> add(buildJsonObject {
                            put("id", shell.id); put("backend", shell.backend)
                        }) } })
                        put("loadings", loadingMetadata(tested.id, run.loadings))
                        put("unsupported", JsonArray(run.unsupported.take(50).map(::JsonPrimitive)))
                        put("missingServices", JsonArray(run.missingServices.map(::JsonPrimitive)))
                    }
                }.toString()
                return if (tested.ok) ToolResult.ok(text) else ToolResult.err(text)
            }
            "plugin_dev_install" -> {
                val path = resolvePath(string("path"), ctx)
                val installed = if (path.isDirectory) store.installDirectory(path) else store.installFile(path)
                if (installed.ok) onChanged()
                return result(installed)
            }
            "plugin_dev_enable" -> {
                val id = string("id")
                val enabled = args.getValue("enabled").jsonPrimitive.boolean
                val changed = withContext(Dispatchers.IO) { store.setEnabled(id, enabled) }
                if (!changed) return ToolResult.err("插件不存在：$id")
                // 0.3.4（用户第 6 条）：停用正在使用的插件时静默回退内置并清选择，不留红字、不拦发送。
                if (!enabled) onDisabled(id)
                onChanged()
                return ToolResult.ok(
                    "插件 $id：" + (if (enabled) "已启用" else "已停用（指向它的角色选择已回退内置）"),
                )
            }
            // 0.3.4（用户第 7 条闸门 A）：默认拒绝删除正在使用/运行中的插件并返回原因；
            // 模型先 plugin_dev_enable {enabled:false} 停用（会回退内置），停用后再删即可。
            // UI 删除走确认弹窗，使用 AppViewModel.deletePlugin 的 force 路径，不经过这里。
            "plugin_dev_delete" -> {
                val id = string("id")
                val deleted = delete(id, false)
                return if (deleted.ok) ToolResult.ok(deleted.message) else ToolResult.err(deleted.message)
            }
            "plugin_dev_reload" -> {
                val id = string("id")
                val record = withContext(Dispatchers.IO) { store.list().firstOrNull { it.id == id } }
                    ?: return ToolResult.err("插件不存在：$id")
                val tested = store.testPath(File(record.path))
                if (!tested.ok) return ToolResult.err(tested.message)
                if (tested.id != id) return ToolResult.err("清单 id 已变化，请重新安装")
                val reloaded = withContext(Dispatchers.IO) { store.reload(id) }
                // reload can update error/schema metadata even on failure.
                onChanged()
                return result(reloaded)
            }
            "plugin_dev_call" -> {
                return callTool(string("id"), string("tool"), args["args"]?.jsonObject ?: buildJsonObject {}, ctx)
            }
            "plugin_dev_test_capability" -> {
                return testCapability(string("capability"), args["args"]?.jsonObject ?: buildJsonObject {})
            }
            "plugin_dev_test_shell" -> {
                val record = withContext(Dispatchers.IO) { store.list().firstOrNull { it.id == string("id") } }
                    ?: return ToolResult.err("插件不存在：${string("id")}")
                return withContext(Dispatchers.IO) {
                    PluginDevProbe.testShell(record, ctx.workspace, string("shell").ifEmpty { null },
                        string("action").ifEmpty { null }, args["value"]?.jsonObject ?: buildJsonObject {})
                }
            }
            "plugin_dev_scaffold" -> {
                val path = resolvePath(string("path"), ctx)
                val template = string("template").ifEmpty { "tool" }
                withContext(Dispatchers.IO) { PluginDevScaffold.create(path, string("id"), template) }
                return ToolResult.ok("已创建 $template 插件：${path.absolutePath}；可运行 plugin_dev_test")
            }
            // ---- M88-E（docs/183 §5）：AI 全控制插件（授予/撤销/配置/角色） ----
            "plugin_dev_grant" -> {
                if (!aiPluginControlEnabled()) return aiControlDisabled()
                val id = string("id")
                val record = withContext(Dispatchers.IO) { store.list().firstOrNull { it.id == id } }
                    ?: return ToolResult.err("插件不存在：$id")
                val all = args["all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
                val requested = requestedPermissions(args)
                if (!all && requested.isEmpty()) {
                    return ToolResult.err("plugin_dev_grant 需要 permissions 数组，或 all:true 授予清单里全部可授予权限")
                }
                val toGrant = if (all) {
                    xin.dshaa.core.plugins.PluginPermissions
                        .approvalNeeded(xin.dshaa.core.plugins.PluginPermissions.parseAll(record.permissions))
                        .map { it.raw }
                } else {
                    requested
                }
                val before = record.grantedPermissions.toSet()
                val granted = withContext(Dispatchers.IO) { store.grantMany(id, toGrant) }
                if (!granted.ok) return ToolResult.err(granted.message)
                onChanged()
                val added = withContext(Dispatchers.IO) {
                    (store.list().firstOrNull { it.id == id }?.grantedPermissions?.toSet() ?: before) - before
                }
                val detail = (if (added.isEmpty()) toGrant else added.toList()).joinToString("、")
                audit(PluginAuditLog.ACTION_GRANT, id, detail)
                if (added.isNotEmpty()) onAiGranted(id, added.joinToString("、"))
                return ToolResult.ok(
                    buildJsonObject {
                        put("ok", true)
                        put("id", id)
                        put("granted", JsonArray(toGrant.map(::JsonPrimitive)))
                        put("added", JsonArray(added.map(::JsonPrimitive)))
                        put("message", "已授予 ${record.name}：${detail.ifEmpty { "（没有新增权限）" }}")
                    }.toString(),
                )
            }
            "plugin_dev_revoke" -> {
                if (!aiPluginControlEnabled()) return aiControlDisabled()
                val id = string("id")
                val record = withContext(Dispatchers.IO) { store.list().firstOrNull { it.id == id } }
                    ?: return ToolResult.err("插件不存在：$id")
                val all = args["all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
                val requested = requestedPermissions(args)
                if (!all && requested.isEmpty()) {
                    return ToolResult.err("plugin_dev_revoke 需要 permissions 数组，或 all:true 撤销全部已授予权限")
                }
                val toRevoke = if (all) record.grantedPermissions else requested
                val before = record.grantedPermissions.toSet()
                val revoked = withContext(Dispatchers.IO) { store.revokeGrants(id, toRevoke) }
                if (!revoked.ok) return ToolResult.err(revoked.message)
                onChanged()
                val removed = before - withContext(Dispatchers.IO) {
                    store.list().firstOrNull { it.id == id }?.grantedPermissions?.toSet().orEmpty()
                }
                val detail = if (removed.isEmpty()) toRevoke.joinToString("、") else removed.joinToString("、")
                audit(
                    if (all) PluginAuditLog.ACTION_REVOKE_ALL else PluginAuditLog.ACTION_REVOKE,
                    id,
                    detail,
                )
                return ToolResult.ok(
                    buildJsonObject {
                        put("ok", true)
                        put("id", id)
                        put("removed", JsonArray(removed.map(::JsonPrimitive)))
                        put("message", "已撤销 ${record.name}：${detail.ifEmpty { "（没有可撤销的权限）" }}")
                    }.toString(),
                )
            }
            "plugin_dev_config" -> {
                if (!aiPluginControlEnabled()) return aiControlDisabled()
                val id = string("id")
                val record = withContext(Dispatchers.IO) { store.list().firstOrNull { it.id == id } }
                    ?: return ToolResult.err("插件不存在：$id")
                val reset = args["reset"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
                val config = args["config"]
                if (!reset && config != null && config !is JsonObject) {
                    return ToolResult.err("plugin_dev_config 的 config 必须是 JSON 对象，或 reset:true 恢复默认")
                }
                if (!reset && config == null) {
                    return ToolResult.err("plugin_dev_config 需要 config 对象，或 reset:true 恢复默认")
                }
                val text = if (reset) "" else config.toString()
                val saved = withContext(Dispatchers.IO) { store.setConfig(id, text) }
                if (!saved.ok) return ToolResult.err(saved.message)
                onChanged()
                audit(PluginAuditLog.ACTION_CONFIG, id, if (reset) "已恢复默认" else "已更新配置")
                return ToolResult.ok(
                    buildJsonObject {
                        put("ok", true)
                        put("id", id)
                        put("reset", reset)
                        put("message", saved.message)
                    }.toString(),
                )
            }
            "plugin_dev_role" -> {
                if (!aiPluginControlEnabled()) return aiControlDisabled()
                val role = string("role").trim().lowercase()
                val value = string("value").trim()
                roleError(role, value)?.let { return ToolResult.err(it) }
                if (!selectRole(role, value)) {
                    return ToolResult.err("角色选择失败：$role = $value（目标不存在或不可用；原因见插件页提示）")
                }
                audit(PluginAuditLog.ACTION_ROLE, role, value)
                return ToolResult.ok(
                    buildJsonObject {
                        put("ok", true)
                        put("role", role)
                        put("value", value)
                        put("message", "已切换 $role：$value")
                    }.toString(),
                )
            }
            // M88-I：读总开关状态（只读，无审批、不写审计）。
            "plugin_dev_middleware_state" -> {
                val enabled = middlewareEnabled()
                return ToolResult.ok(
                    buildJsonObject {
                        put("ok", true)
                        put("enabled", enabled)
                        put("state", if (enabled) "on" else "off")
                        put("message", middlewareStateText(enabled))
                    }.toString(),
                )
            }
            // M88-I：插件中间件总开关（全局旁路）。扩展点插件“没效果”时由 AI 打开；
            // 与其它插件控制工具一样受「AI 全控制插件」开关约束，并写审计。
            "plugin_dev_middleware" -> {
                if (!aiPluginControlEnabled()) return aiControlDisabled()
                val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull
                    ?: return ToolResult.err("plugin_dev_middleware 需要 enabled:true/false")
                setMiddlewareEnabled(enabled)
                audit(PluginAuditLog.ACTION_MIDDLEWARE, "middleware", if (enabled) "on" else "off")
                return ToolResult.ok(
                    buildJsonObject {
                        put("ok", true)
                        put("enabled", enabled)
                        put("message", middlewareStateText(enabled) + "（已立即生效）")
                    }.toString(),
                )
            }
            else -> return ToolResult.err("未知插件开发工具")
        }
    }

    /** `permissions` 数组里的原始字符串（非字符串项丢弃）。 */
    private fun requestedPermissions(args: JsonObject): List<String> =
        (args["permissions"] as? JsonArray)
            ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private fun aiControlDisabled(): ToolResult = ToolResult.err(AI_PLUGIN_CONTROL_DISABLED)

    companion object {
        /** M88-E：AI 全控制关闭时，这些工具的固定错误码（设置页开关已按用户要求移除）。 */
        const val AI_PLUGIN_CONTROL_DISABLED =
            "AI 插件控制已关闭：ai_plugin_control_disabled"

        /** M88-E：总开关判定（纯函数，JVM 可测）；null = 允许执行。 */
        fun aiControlError(enabled: Boolean): String? = if (enabled) null else AI_PLUGIN_CONTROL_DISABLED

        /** M88-E：`plugin_dev_role` 的 role/value 校验（纯函数，JVM 可测）；null = 合法。 */
        fun roleError(role: String, value: String): String? = when {
            role !in ROLE_NAMES -> "未知角色：$role（可用：${ROLE_NAMES.joinToString(" / ")}）"
            value.isBlank() -> "plugin_dev_role 需要 value：内置实现写 native（加载页 builtin.loading.native，模型 deepseek/openai），第三方写插件 id 或 插件id#实现id"
            else -> null
        }

        /** M88-E：可用于 `plugin_dev_role` 的六个角色。 */
        val ROLE_NAMES = setOf("shell", "loading", "agent", "storage", "prompt", "model")

        /** M88-I：中间件总开关的可读状态（plugin_dev_middleware(_state) 的工具返回共用）。 */
        fun middlewareStateText(enabled: Boolean): String =
            if (enabled) {
                "插件中间件总开关：开"
            } else {
                "插件中间件总开关：关（工具/请求/存储/事件钩子全部旁路，扩展点插件不会生效；" +
                    "插件页 →「扩展点」顶部「插件中间件总开关」可打开）"
            }

        /** 全部 `plugin_dev_*` 工具名（含 M88-I 的中间件总开关）。 */
        val toolNames: Set<String> get() = definitions.map { it.name }.toSet()

        /** Only these may enter AppViewModel's read-only whitelist; all other names require normal write approval. */
        val readOnlyNames = setOf("plugin_dev_list", "plugin_dev_test", "plugin_dev_middleware_state")
        val writeNames = setOf("plugin_dev_install", "plugin_dev_enable", "plugin_dev_delete", "plugin_dev_reload",
            "plugin_dev_scaffold", "plugin_dev_call", "plugin_dev_test_shell", "plugin_dev_test_capability",
            // M88-E：AI 全控制插件（写类，沿用审批策略；权限确认不再单独弹窗，靠审计/撤销/总开关兜底）。
            "plugin_dev_grant", "plugin_dev_revoke", "plugin_dev_config", "plugin_dev_role",
            // M88-I：全局旁路开关（写类；关掉会让所有扩展点插件失效，所以必须走审批）。
            "plugin_dev_middleware")

        /** Registration metadata only; no static text/tree, config, services or active-selection claim. */
        internal fun loadingMetadata(pluginId: String?, loadings: List<PluginLoading>): JsonArray = buildJsonArray {
            loadings.take(LoadingSpec.MAX_DECLARATIONS).forEach { loading -> add(buildJsonObject {
                put("id", loading.id)
                put("label", loading.label)
                put("protocol", LoadingSpec.VERSION)
                pluginId?.let { put("selectionValue", "$it#${loading.id}") }
            }) }
        }

        private fun schema(properties: String, required: String = "[]"): JsonObject =
            Json.parseToJsonElement("""{"type":"object","properties":$properties,"required":$required,"additionalProperties":false}""").jsonObject

        private val definitions = listOf(
            ApiTool("plugin_dev_list", "列出已安装插件的版本、开关、权限和工具/壳/加载页注册元数据；可按 id 查询，登记不等于选中。", schema("""{"id":{"type":"string","minLength":1}}""")),
            ApiTool("plugin_dev_test", "隔离试运行目录或 zip 的 apply，返回工具/壳/加载页登记和错误；不安装、不执行工具或验证冷启动画面。", schema("""{"path":{"type":"string","minLength":1,"description":"插件目录或 zip 路径，相对当前目录解析。"}}""", """["path"]""")),
            ApiTool("plugin_dev_install", "验证和试运行通过后安装插件目录或 zip；保留兼容配置，失败回滚旧包，不授危险权限或选择壳。", schema("""{"path":{"type":"string","minLength":1}}""", """["path"]""")),
            ApiTool("plugin_dev_enable", "启用或停用已安装插件并刷新注册，不授予权限或选择壳；停用会自动回退内置角色。", schema("""{"id":{"type":"string","minLength":1},"enabled":{"type":"boolean"}}""", """["id","enabled"]""")),
            ApiTool("plugin_dev_delete", "删除已安装插件并清掉指向它的角色选择；正在使用/运行中会被拒绝，请先用 plugin_dev_enable 停用。", schema("""{"id":{"type":"string","minLength":1}}""", """["id"]""")),
            ApiTool("plugin_dev_reload", "验证已安装插件后重新加载清单和注册，刷新现有插件。", schema("""{"id":{"type":"string","minLength":1}}""", """["id"]""")),
            ApiTool("plugin_dev_call", "执行已启用插件的一项工具，沿用其权限、参数校验和超时；可能产生真实效果。", schema("""{"id":{"type":"string","minLength":1},"tool":{"type":"string","minLength":1},"args":{"type":"object"}}""", """["id","tool"]""")),
            ApiTool("plugin_dev_test_shell", "在隔离运行时离屏挂载壳并可测试动作；宿主命令仅记录，验证树或 HTML，不切换当前界面。", schema("""{"id":{"type":"string","minLength":1},"shell":{"type":"string"},"action":{"type":"string"},"value":{"type":"object"}}""", """["id"]""")),
            ApiTool("plugin_dev_test_capability", "调用已授权的能力并返回结果；沿用能力提供方权限和超时，可能产生真实效果。", schema("""{"capability":{"type":"string","minLength":1},"args":{"type":"object"}}""", """["capability"]""")),
            ApiTool("plugin_dev_scaffold", "在新目录生成工具、Surface/WebView 壳或 loading/v1 静态加载页模板，不覆盖已有目录。", schema("""{"path":{"type":"string","minLength":1},"id":{"type":"string","minLength":1,"maxLength":80},"template":{"type":"string","enum":["tool","surface-shell","webview-shell","loading"],"default":"tool"}}""", """["path","id"]""")),
            // M88-E：AI 全控制插件（默认开启；关掉后四个工具返回 ai_plugin_control_disabled）。
            ApiTool("plugin_dev_grant", "授予已安装插件清单里声明过的权限（清单外/未实现的会被丢弃）。permissions 逐项给，或 all:true 授予全部可授予项；不修改系统权限。", schema("""{"id":{"type":"string","minLength":1},"permissions":{"type":"array","items":{"type":"string"}},"all":{"type":"boolean"}}""", """["id"]""")),
            ApiTool("plugin_dev_revoke", "撤销已安装插件的权限（对称于 plugin_dev_grant）；permissions 逐项撤销，或 all:true 撤销全部已授予项。", schema("""{"id":{"type":"string","minLength":1},"permissions":{"type":"array","items":{"type":"string"}},"all":{"type":"boolean"}}""", """["id"]""")),
            ApiTool("plugin_dev_config", "写入插件配置（按插件 Config schema 校验）；config 是 JSON 对象，reset:true 恢复默认。", schema("""{"id":{"type":"string","minLength":1},"config":{"type":"object"},"reset":{"type":"boolean"}}""", """["id"]""")),
            ApiTool("plugin_dev_role", "切换插件角色：shell/loading/agent/storage/prompt/model；value 用角色选择值（内置实现 native，加载页 builtin.loading.native，模型 deepseek/openai），第三方插件 id 或 插件id#实现id。", schema("""{"role":{"type":"string","enum":["shell","loading","agent","storage","prompt","model"]},"value":{"type":"string","minLength":1}}""", """["role","value"]""")),
            // M88-I：插件中间件总开关（全局旁路）。扩展点插件“没效果”时由 AI 打开；
            // 与其它插件控制工具一样受「AI 全控制插件」开关约束，并写审计。
            // M88-I：读总开关状态（只读）。
            ApiTool("plugin_dev_middleware_state", "读取插件中间件总开关的当前状态（开/关），不修改任何设置；扩展点插件没效果时先用它确认。", schema("""{}""")),
            ApiTool("plugin_dev_middleware", "打开/关闭插件中间件总开关（全局旁路：关掉后工具/请求/存储/事件钩子全部不生效，扩展点插件会看起来没效果）。仅在排障或修复“扩展点插件无效”时使用。", schema("""{"enabled":{"type":"boolean"}}""", """["enabled"]""")),
        )
    }
}
