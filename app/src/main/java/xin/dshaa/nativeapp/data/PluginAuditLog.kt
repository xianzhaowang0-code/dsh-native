package xin.dshaa.nativeapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条插件审计记录（`files/plugin-audit.jsonl` 的一行）。 */
data class PluginAuditEntry(
    val ts: Long,
    /** `ai` 或 `user`。 */
    val actor: String,
    /** `grant` / `revoke` / `config` / `role` / `enable` … */
    val action: String,
    /** 目标插件 id。 */
    val target: String,
    /** 人类可读细节；grant/revoke 时是 `、` 分隔的权限原文（UI 用来还原 AI 授予集合）。 */
    val detail: String = "",
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("ts", ts)
        put("actor", actor)
        put("action", action)
        put("target", target)
        put("detail", detail)
    }

    /** 插件详情/横幅展示：`MM-dd HH:mm ai grant m87e-storage：storage:hooks`。 */
    fun summary(): String {
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
        return "$stamp $actor $action $target" + if (detail.isBlank()) "" else "：$detail"
    }

    companion object {
        fun parse(line: String): PluginAuditEntry? {
            val obj = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
            val ts = runCatching { obj["ts"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: return null
            return PluginAuditEntry(
                ts = ts,
                actor = obj.text("actor").orEmpty(),
                action = obj.text("action").orEmpty(),
                target = obj.text("target").orEmpty(),
                detail = obj.text("detail").orEmpty(),
            )
        }

        private fun JsonObject.text(key: String): String? =
            runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
    }
}

/**
 * M88-E（docs/183 §5）：插件操作审计（AI 授予/撤销/改配置/选角色都记一行）。
 *
 * 只做追加 + 读回：`files/plugin-audit.jsonl`，每行一个 JSON 对象；
 * 超过 [maxBytes]（默认 512KB）轮转一份 `.1` 备份（保留 1 份），读回时先旧后新。
 * 用途：AI 全控制插件后的可见性与追责，以及「AI 授予」在插件详情的标注。
 */
class PluginAuditLog(
    private val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    @Synchronized
    fun append(actor: String, action: String, target: String, detail: String = "") {
        val entry = PluginAuditEntry(
            ts = System.currentTimeMillis(),
            actor = actor,
            action = action,
            target = target,
            detail = detail,
        )
        val line = entry.toJson().toString() + "\n"
        runCatching {
            file.parentFile?.mkdirs()
            if (file.length() + line.toByteArray(Charsets.UTF_8).size > maxBytes) rotate()
            file.appendText(line)
        }
    }

    /** 全部记录（旧备份在前，当前文件在后；坏行跳过）。 */
    @Synchronized
    fun entries(): List<PluginAuditEntry> {
        val backup = backupFile()
        return buildList {
            if (backup.exists()) addAll(readEntries(backup))
            if (file.exists()) addAll(readEntries(file))
        }
    }

    /** 某个插件最近 [limit] 条（新→旧）。 */
    fun recent(pluginId: String, limit: Int = 5): List<PluginAuditEntry> =
        entries().filter { it.target == pluginId }.takeLast(limit).reversed()

    /** `plugin_dev_list` 每条带的最近一次 AI 操作（没有 = null）。 */
    fun lastAiAction(pluginId: String): String? =
        entries().lastOrNull { it.target == pluginId && it.actor == ACTOR_AI }
            ?.let { "${it.action} ${if (it.detail.isBlank()) "" else it.detail}".trim() }
            ?.takeIf { it.isNotEmpty() }

    /** 按审计回放「当前仍由 AI 授予」的权限集合（AI grant 加、任何来源的 revoke 减、revoke_all 清空）。 */
    fun aiGrantedPermissions(pluginId: String): Set<String> {
        val out = LinkedHashSet<String>()
        entries().filter { it.target == pluginId }.forEach { entry ->
            val permissions = entry.detail.split('、').map { it.trim() }.filter { it.isNotEmpty() }
            when (entry.action) {
                // 用户自己在插件页点的「确认启用」不算 AI 授予（不标「AI 授予」）。
                ACTION_GRANT -> if (entry.actor == ACTOR_AI) out += permissions
                // 撤销不分来源：用户撤销后就不该再标「AI 授予」。
                ACTION_REVOKE -> out -= permissions.toSet()
                ACTION_REVOKE_ALL -> out.clear()
            }
        }
        return out
    }

    private fun readEntries(source: File): List<PluginAuditEntry> = runCatching {
        source.readLines().mapNotNull { PluginAuditEntry.parse(it.trim()) }
    }.getOrDefault(emptyList())

    private fun backupFile(): File = File(file.parentFile, file.name + ".1")

    private fun rotate() {
        val backup = backupFile()
        runCatching { if (backup.exists()) backup.delete() }
        runCatching { if (file.exists()) file.renameTo(backup) }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 512L * 1024
        const val ACTOR_AI = "ai"
        const val ACTOR_USER = "user"
        const val ACTION_GRANT = "grant"
        const val ACTION_REVOKE = "revoke"
        const val ACTION_REVOKE_ALL = "revoke_all"
        const val ACTION_CONFIG = "config"
        const val ACTION_ROLE = "role"
        /** M88-I：插件中间件总开关（target 固定 `middleware`，detail `on`/`off`）。 */
        const val ACTION_MIDDLEWARE = "middleware"

        fun fileIn(filesDir: File): File = File(filesDir, "plugin-audit.jsonl")
    }
}
