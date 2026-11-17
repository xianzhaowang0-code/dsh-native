package xin.dshaa.nativeapp.data

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.capability.CAPABILITY_INVOKE_METHOD
import xin.dshaa.core.capability.CapabilityCandidate
import xin.dshaa.core.capability.CapabilityLookup
import xin.dshaa.core.capability.CapabilityRouting
import xin.dshaa.core.js.JsOutcome
import xin.dshaa.core.js.JsStatus
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.nativeapp.device.DeviceShell
import xin.dshaa.nativeapp.device.ShellCapability
import xin.dshaa.ui.shellapi.CapabilityUi

/**
 * M85-F：能力宿主（`capability/v1`）。
 *
 * 职责只有三件（docs/144 §3）：**登记 + 权限门 + 超时**。
 * - 登记：内置骨架平台能力由宿主直接登记；插件能力来自 `ctx.capability.register`
 *   （`PluginRecord.capabilities`，常驻运行时里 dispatch `capability.invoke`）。
 * - 权限门：插件提供方必须启用且拿到 `capability` 授权；内置提供方不经过插件权限。
 * - 超时：插件调用 4.5s（dispatch 自带）外层再兜 5s；超时/运行时消失如实报错。
 *
 * 特权能力（root / NFC / Shizuku / adb-shell）**不在宿主写死**：没有插件提供时
 * [CapabilityRouting] 会给出「没有提供方」的不可用原因，插件页/调试 dump 原样展示。
 */
class CapabilityHost(
    private val context: Context,
    private val dispatch: (pluginId: String, method: String, payloadJson: String, timeoutMs: Long) -> JsOutcome?,
    private val records: () -> List<PluginRecord>,
) {

    data class InvokeResult(
        val ok: Boolean,
        val valueJson: String = "null",
        val error: String? = null,
        /** 实际执行的提供方插件 id；内置为 null。 */
        val pluginId: String? = null,
        /** 给用户/调试看的一句话。 */
        val summary: String = "",
    )

    companion object {
        private const val TAG = "DshCapability"

        /** M88-C：工具层给模型的合并说明（与 `shell.exec` 描述同一句事实）。 */
        fun mergedRootExecMessage(): String = ShellCapability.MERGED_MESSAGE

        private data class Builtin(
            val id: String,
            val label: String,
            val description: String,
            val kind: String,
            val invoke: suspend (Context, JsonObject) -> JsonElement,
        )

        /** 内置骨架平台能力：只覆盖通知/剪贴板/分享/Shell 身份这类原语；NFC/adb-shell 由插件提供。 */
        private val builtins: List<Builtin> = listOf(
        Builtin(
            id = "notify",
            label = "系统通知",
            description = "骨架平台能力：发一条系统通知（Android 13+ 需系统通知权限；点击回到聊天页）。" +
                "只想弹 Toast 用 notify.toast。",
            kind = "system",
        ) { ctx, args ->
            // M87-H5（docs/182）：内置 notify 从 Toast 改成真实通知，并如实检查系统权限；
            // 没权限时抛错（invoke 会包成 ok=false），不假装「已弹过」。
            if (!systemNotificationsAllowed(ctx)) {
                error("系统通知权限未开：去系统设置 → 通知 允许鲸歌通知后再试（或改用 notify.toast）")
            }
            val title = args.string("title").orEmpty()
            val body = args.string("body").orEmpty()
            val text = if (title.isBlank()) body else "$title：$body"
            PluginNotifier.notify(ctx, title.ifBlank { "鲸歌能力调用" }, body)
            buildJsonObject { put("shown", true); put("via", "notification"); put("text", text) }
        },
        Builtin(
            id = "notify.toast",
            label = "系统提示（Toast）",
            description = "骨架平台能力：弹一条 Toast（不写通知栏、不需要 notify 权限）。",
            kind = "system",
        ) { ctx, args ->
            val title = args.string("title").orEmpty()
            val body = args.string("body").orEmpty()
            val text = if (title.isBlank()) body else "$title：$body"
            runCatching {
                Toast.makeText(ctx, text.ifBlank { "鲸歌能力调用" }, Toast.LENGTH_SHORT).show()
            }
            buildJsonObject { put("shown", true); put("via", "toast"); put("text", text) }
        },
        Builtin(
            id = "clipboard.read",
            label = "读剪贴板",
            description = "骨架平台能力：读当前剪贴板的纯文本（无文本返回空串）。",
            kind = "system",
        ) { ctx, _ ->
            val text = runCatching {
                val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
            }.getOrNull().orEmpty()
            buildJsonObject { put("text", text) }
        },
        Builtin(
            id = "clipboard.write",
            label = "写剪贴板",
            description = "骨架平台能力：把一段纯文本放进剪贴板。",
            kind = "system",
        ) { ctx, args ->
            val text = args.string("text").orEmpty()
            val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("鲸歌", text))
            buildJsonObject { put("written", true); put("length", text.length) }
        },
        Builtin(
            id = "share",
            label = "分享文本",
            description = "骨架平台能力：调系统分享面板发一段纯文本。",
            kind = "system",
        ) { ctx, args ->
            val text = args.string("text").orEmpty()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, args.string("title") ?: "分享").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { ctx.startActivity(chooser) }
            buildJsonObject { put("shared", true); put("length", text.length) }
        },
        // M88-B（docs/183 §2，取代 docs/182 §H7）：root 与 Shizuku 合并成一条身份链。
        // root.exec 不再是独立能力：按 id 直调会返回 merged_into_shell_exec（见 invoke）。
        // shell.exec 走 root → Shizuku → App；identity 如实回报实际身份。
        Builtin(
            id = "shell.exec",
            label = "Shell 执行",
            description = "自动选择身份执行一条 shell 命令：root 优先，其次 Shizuku，最后 App；" +
                "返回 {ok, exitCode, stdout, stderr, identity}，identity 说明实际身份（不假装 root）。" +
                "root 与 Shizuku 已合并到此能力。",
            kind = "shell",
        ) { _, args ->
            val command = args.string("command").orEmpty().trim()
            if (command.isEmpty()) {
                buildJsonObject {
                    put("ok", false)
                    put("reason", "missing_command")
                    put("message", "shell.exec 需要 command")
                }
            } else {
                val result = DeviceShell.runElevated(command)
                Json.parseToJsonElement(
                    ShellCapability.execJson(
                        ok = result.success,
                        exitCode = result.exitCode,
                        stdout = result.stdout,
                        stderr = result.stderr,
                        identity = DeviceShell.elevatedIdentity(),
                    ),
                ).jsonObject
            }
        },
        // 只读状态：插件页/诊断看「当前 Shell 身份」，失败时能说清为什么。
        Builtin(
            id = "shell.status",
            label = "Shell 身份",
            description = "只读：返回 {root, rootCommand, shizuku, shizukuPermission, default, " +
                "preference, reason}；preference 固定为 root>shizuku>app。",
            kind = "shell",
        ) { _, _ ->
            val status = withContext(Dispatchers.IO) { DeviceShell.shellStatus() }
            Json.parseToJsonElement(ShellCapability.statusJson(status)).jsonObject
        },
    )

        /**
         * M88-B：纯函数候选清单（内置 + 插件声明），JVM 可测、不需要 Context。
         *
         * `root.exec` 已并入 `shell.exec`，这里不再出现（也不再按 root 探测结果过滤）。
         */
        fun candidatesOf(records: List<PluginRecord>): List<CapabilityCandidate> {
            val out = ArrayList<CapabilityCandidate>()
            builtins.forEach { item ->
                out += CapabilityCandidate(
                    id = item.id,
                    label = item.label,
                    description = item.description,
                    kind = item.kind,
                    pluginId = null,
                    builtin = true,
                )
            }
            records.forEach { record ->
                record.capabilities.forEach { capability ->
                    out += CapabilityCandidate(
                        id = capability.id,
                        label = capability.label,
                        description = capability.description,
                        kind = capability.kind,
                        pluginId = record.id,
                        builtin = false,
                        enabled = record.enabled,
                        permitted = record.grantedPermissions.any { it.equals("capability", true) },
                    )
                }
            }
            return out
        }

        /** M87-H5：本 App 现在能不能发系统通知（Android 13+ 的 POST_NOTIFICATIONS + 通知总开关）。 */
        private fun systemNotificationsAllowed(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            return androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        private fun JsonObject.string(key: String): String? =
            runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
    }

    /** 全部候选（内置 + 每个已安装插件声明的能力；停用/缺授权也进来，用于解释原因）。 */
    fun candidates(): List<CapabilityCandidate> = candidatesOf(records())

    /** 插件页展示用：每个能力 id 一行，插件优先内置；没有可用提供方时如实说不可用。 */
    fun ui(): List<CapabilityUi> {
        val all = candidates()
        return CapabilityRouting.catalog(all).map { first ->
            when (val lookup = CapabilityRouting.resolve(first.id, all)) {
                is CapabilityLookup.Found -> {
                    val capability = lookup.capability
                    CapabilityUi(
                        id = capability.id,
                        label = capability.label,
                        description = capability.description,
                        kind = capability.kind,
                        provider = providerName(capability.pluginId, capability.builtin),
                        pluginId = capability.pluginId,
                        builtin = capability.builtin,
                        available = true,
                    )
                }

                is CapabilityLookup.Unavailable -> CapabilityUi(
                    id = first.id,
                    label = first.label,
                    description = first.description,
                    kind = first.kind,
                    provider = providerName(first.pluginId, first.builtin),
                    pluginId = first.pluginId,
                    builtin = first.builtin,
                    available = false,
                    enabled = first.enabled,
                    permitted = first.permitted,
                    unavailableReason = lookup.reason,
                )
            }
        }
    }

    /**
     * 调用一个能力。解析失败/超时/插件报错都返回 `ok=false` + 可读原因，
     * **不静默换成别的提供方**（同 id 覆盖的插件停用时，解析规则会回落到内置；
     * 这是角色语义，不是把失败藏起来）。
     */
    suspend fun invoke(id: String, argsJson: String?): InvokeResult {
        val cleanId = id.trim()
        // M88-B：root.exec 已并入 shell.exec。直接按 id 调用时给出结构化拒绝，
        // 而不是含糊的「没有提供方」，也不会静默改成别的能力。
        if (cleanId.equals("root.exec", ignoreCase = true)) {
            Log.w(TAG, "invoke root.exec: merged into shell.exec")
            return InvokeResult(
                ok = false,
                valueJson = ShellCapability.mergedRootExecJson(),
                error = ShellCapability.MERGED_ROOT_EXEC_REASON,
                summary = ShellCapability.MERGED_MESSAGE,
            )
        }
        val all = candidates()
        val lookup = CapabilityRouting.resolve(cleanId, all)
        if (lookup !is CapabilityLookup.Found) {
            val reason = (lookup as CapabilityLookup.Unavailable).reason
            Log.w(TAG, "invoke id=$cleanId unavailable: $reason")
            return InvokeResult(ok = false, error = reason, summary = reason)
        }
        val capability = lookup.capability
        val args = runCatching {
            Json.parseToJsonElement(argsJson?.takeIf { it.isNotBlank() } ?: "{}")
        }.getOrNull() as? JsonObject ?: buildJsonObject { }
        if (capability.builtin) {
            val builtin = builtins.firstOrNull { it.id.equals(capability.id, true) }
                ?: return InvokeResult(ok = false, error = "内置能力 ${capability.id} 没有实现", summary = "内置能力没有实现")
            // shell 可能要等 su 授权弹窗 / Shizuku 往返，给 20s；其它骨架能力维持 5s。
            val timeoutMs = if (capability.id == "shell.exec") 20_000L else 5_000L
            return runCatching {
                val value = withTimeoutOrNull(timeoutMs) {
                    withContext(Dispatchers.Default) { builtin.invoke(context, args) }
                }
                    ?: return InvokeResult(ok = false, error = "能力调用超时（${timeoutMs / 1000}s）", summary = "调用超时")
                val valueObj = value as? JsonObject
                if (valueObj?.string("ok")?.toBooleanStrictOrNull() == false) {
                    // 内置能力的结构化拒绝：保留 valueJson（含 reason），不包装成普通异常。
                    val reason = valueObj.string("reason").orEmpty().ifBlank { "capability_failed" }
                    val message = valueObj.string("message").orEmpty().ifBlank { "内置能力 ${capability.id} 不可用" }
                    Log.w(TAG, "builtin ${capability.id} rejected: $reason $message")
                    InvokeResult(
                        ok = false,
                        valueJson = value.toString(),
                        error = message,
                        pluginId = null,
                        summary = "$reason：$message",
                    )
                } else {
                    InvokeResult(
                        ok = true,
                        valueJson = value.toString(),
                        pluginId = null,
                        summary = "内置能力 ${capability.id} 调用成功",
                    )
                }
            }.getOrElse { error ->
                val reason = "内置能力 ${capability.id} 调用失败：${error.message ?: error.javaClass.simpleName}"
                Log.w(TAG, reason)
                InvokeResult(ok = false, error = reason, summary = reason)
            }
        }
        val pluginId = capability.pluginId
            ?: return InvokeResult(ok = false, error = "能力 ${capability.id} 没有提供方", summary = "没有提供方")
        val payload = buildJsonObject {
            put("id", capability.id)
            put("args", args)
        }
        Log.i(TAG, "invoke id=${capability.id} plugin=$pluginId")
        val startedAt = System.currentTimeMillis()
        val outcome = withTimeoutOrNull(5_000) {
            withContext(Dispatchers.IO) {
                dispatch(pluginId, CAPABILITY_INVOKE_METHOD, payload.toString(), 4_500)
            }
        }
        val elapsed = System.currentTimeMillis() - startedAt
        if (outcome == null) {
            val reason = "能力 ${capability.id} 调用超时/插件运行时不可用（${elapsed}ms）"
            Log.w(TAG, reason)
            return InvokeResult(ok = false, error = reason, pluginId = pluginId, summary = reason)
        }
        if (outcome.status != JsStatus.COMPLETED) {
            val reason = "能力 ${capability.id} 调用失败：${outcome.error ?: outcome.status}"
            Log.w(TAG, reason)
            return InvokeResult(ok = false, error = reason, pluginId = pluginId, summary = reason)
        }
        val obj = runCatching { Json.parseToJsonElement(outcome.valueJson ?: "null").jsonObject }.getOrNull()
        if (obj == null) {
            val reason = "能力 ${capability.id} 返回了非 JSON 结果"
            Log.w(TAG, reason)
            return InvokeResult(ok = false, error = reason, pluginId = pluginId, summary = reason)
        }
        val ok = obj["ok"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
        if (!ok) {
            val reason = obj["error"]?.jsonPrimitive?.contentOrNull
                ?: "能力 ${capability.id} 调用失败"
            Log.w(TAG, "invoke failed plugin=$pluginId: $reason")
            return InvokeResult(ok = false, error = reason, pluginId = pluginId, summary = reason)
        }
        val value = obj["value"] ?: obj["result"] ?: buildJsonObject { }
        Log.i(TAG, "invoke ok id=${capability.id} plugin=$pluginId elapsed=${elapsed}ms")
        return InvokeResult(
            ok = true,
            valueJson = value.toString(),
            pluginId = pluginId,
            summary = "能力 ${capability.id} 调用成功（${providerName(pluginId, false)}，${elapsed}ms）",
        )
    }

    private fun providerName(pluginId: String?, builtin: Boolean): String {
        if (builtin || pluginId.isNullOrBlank()) return "内置（骨架平台）"
        return records().firstOrNull { it.id == pluginId }?.name ?: pluginId
    }

    /** M88-C：`notify` 工具与内置 `notify` 能力共用同一句系统通知权限判定。 */
    fun notificationsAllowed(): Boolean = systemNotificationsAllowed(context)
}
