package xin.dshaa.core.plugins

/**
 * 插件能力（manifest 的 `permissions` 数组，M28 / docs/04 §七）。
 *
 * 设计原则和 docs/04 一致：**危险能力不能静默获得**。插件只能在清单里「申请」，
 * 是否真的生效由 [PluginPermissions] 的授权表（`files/plugins.json` 的 `grantedPermissions`）决定：
 *
 * | 权限 | 范围 | 默认 |
 * |---|---|---|
 * | `fs:workspace` | 当前工作区（路径逃逸会被拒） | 导入即允许 |
 * | `fs:any` | App 能访问的任意路径 | 需在插件页确认 |
 * | `net:<host>` | 指定域名 + 子域名，仅 https | 需确认 |
 * | `net:any` | 任意 https 域名（本机/内网默认拒绝） | 需确认 |
 * | `shell` | 以 App 身份执行 shell | 需确认（红框） |
 * | `device` | 设备信息 / 应用 / 无障碍手势 / 截图 | 需确认（M67 起实现） |
 * | `memory` | 读写鲸歌记忆库（M68） | 需确认 |
 * | `ui` | 注册整壳（`ctx.ui.registerShell`，M85-A 起生效） | 导入即允许 |
 * | `ui:slot` | 声明式页面/装饰挂宿主槽位（M87-G 识别；M87-C 起生效） | 本版只识别、不自动授予 |
 * | `agent` | 注册回合实现（`ctx.agent.register`，M85-D 起生效） | 导入即允许（选中才生效） |
 * | `storage` | 注册存储实现（`ctx.storage.register`，M85-E 起生效；v1 只读） | 导入即允许（选中才生效） |
 * | `storage:hooks` | 会话写入钩子（`ctx.storage.on('before-append'|'after-load')`，M87-E） | 需确认（能观测/拒绝落盘） |
 * | `prompt` | 注册提示词实现（`ctx.systemPrompt.register`，M85-E 起生效） | 导入即允许（选中才生效） |
 * | `capability` | 注册能力提供方（`ctx.capability.register`，M85-F 起生效；调用于插件页/调试） | 导入即允许（调用才生效） |
 * | `tools:intercept` | 改工具描述/参数/结果、观测工具结果（`ctx.tools.describe/on`，M87-A） | 需确认（`restrict/guard` 只能收紧，不需要本权限） |
 * | `llm:request` | 改本次模型请求/流式输出/系统提示词拼装、按步注入（`ctx.on('agent/request'…)`，M87-B） | 需确认（能改模型看到的全部内容） |
 * | `notify` | 发系统通知（`ctx.whale.notify` / `agent.notify`；M87-H5 起生效） | 需确认（Android 13+ 还要系统通知权限） |
 *
 * 认不出的写法一律 [PluginPermissionKind.UNKNOWN]，**不猜、不授予**（宁可不支持，也不放大权限）。
 */
enum class PluginPermissionKind(val title: String) {
    FS_WORKSPACE("文件（工作区）"),
    FS_ANY("文件（任意路径）"),
    NET_HOST("网络（指定域名）"),
    NET_ANY("网络（任意域名）"),
    SHELL("执行命令"),
    UI("界面扩展"),
    UI_SLOT("界面插槽"),
    AGENT("回合扩展"),
    STORAGE("存储扩展"),
    STORAGE_HOOKS("存储写入钩子"),
    PROMPT("提示词扩展"),
    CAPABILITY("能力扩展"),
    TOOLS_INTERCEPT("工具中间件"),
    LLM_REQUEST("请求中间件"),
    NOTIFY("通知"),
    DEVICE("设备控制"),
    MEMORY("记忆库"),
    UNKNOWN("未识别"),
}

/** 一条规范化后的权限。 [raw] 是可持久化、可比较的规范写法（如 `net:api.example.com`）。 */
data class PluginPermission(
    val raw: String,
    val kind: PluginPermissionKind,
    val target: String? = null,
) {
    /** 需要用户确认的能力。 */
    val dangerous: Boolean
        get() = when (kind) {
            PluginPermissionKind.FS_ANY,
            PluginPermissionKind.NET_HOST,
            PluginPermissionKind.NET_ANY,
            PluginPermissionKind.SHELL,
            PluginPermissionKind.NOTIFY,
            PluginPermissionKind.DEVICE,
            PluginPermissionKind.MEMORY,
            PluginPermissionKind.TOOLS_INTERCEPT,
            PluginPermissionKind.LLM_REQUEST,
            PluginPermissionKind.UI_SLOT,
            PluginPermissionKind.STORAGE_HOOKS,
            -> true
            else -> false
        }

    /** 导入时自动允许（docs/04：fs:workspace、ui、agent、storage、prompt 默认允许；真正生效还要用户主动选中）。 */
    val autoGranted: Boolean
        get() = kind == PluginPermissionKind.FS_WORKSPACE ||
            kind == PluginPermissionKind.UI ||
            kind == PluginPermissionKind.AGENT ||
            kind == PluginPermissionKind.STORAGE ||
            kind == PluginPermissionKind.PROMPT ||
            kind == PluginPermissionKind.CAPABILITY

    /** 这个版本真的实现了的能力；其余只展示、不生效，避免「给了权限但什么也没发生」的误解。 */
    val implemented: Boolean
        get() = when (kind) {
            PluginPermissionKind.FS_WORKSPACE,
            PluginPermissionKind.FS_ANY,
            PluginPermissionKind.NET_HOST,
            PluginPermissionKind.NET_ANY,
            PluginPermissionKind.SHELL,
            PluginPermissionKind.DEVICE,
            PluginPermissionKind.MEMORY,
            // M85-A：`ui` 真的生效了——`ctx.ui.registerShell` 的整壳可由用户选为当前界面。
            PluginPermissionKind.UI,
            // M85-D：`agent` 生效——`ctx.agent.register` 的回合实现可被 `dsh_agent` 选中。
            PluginPermissionKind.AGENT,
            // M85-E：`storage` / `prompt` 生效——注册的存储/提示词实现可被选中（v1 存储只读）。
            PluginPermissionKind.STORAGE,
            PluginPermissionKind.PROMPT,
            // M85-F：`capability` 生效——`ctx.capability.register` 的能力可被插件页/调试通道调用
            //（没有提供方时如实报「不可用」）。
            PluginPermissionKind.CAPABILITY,
            // M87-H5：`notify` 生效——`plugin.notify` / `agent.notify` 真的发系统通知，
            // 未授权返回 PERMISSION_DENIED（不再静默成功）。
            PluginPermissionKind.NOTIFY,
            // M87-A：`tools:intercept` 生效——describe / pre-execute / post-execute / result
            // 可以改工具描述、参数与结果（restrict/guard 只收紧，不需要本权限）。
            PluginPermissionKind.TOOLS_INTERCEPT,
            // M87-B：`llm:request` 生效——四个请求/提示词扩展点（改本次请求/流式输出/系统提示词/按步注入）。
            PluginPermissionKind.LLM_REQUEST,
            // M87-C：`ui:slot` 生效——声明式 Surface 子树可挂聊天输入条/工具卡/设置行。
            PluginPermissionKind.UI_SLOT,
            // M87-E：`storage:hooks` 生效——`ctx.storage.on('before-append'|'after-load')` 挂到会话写入路径。
            PluginPermissionKind.STORAGE_HOOKS,
            -> true
            else -> false
        }

    /** 界面上的短标题（网络权限带上域名）。 */
    val title: String
        get() = if (kind == PluginPermissionKind.NET_HOST) "网络（${target.orEmpty()}）" else kind.title

    /** 红框确认里给用户看的一句话解释（docs/04：红框 + 原文 + 点击确认）。 */
    val explain: String
        get() = when (kind) {
            PluginPermissionKind.FS_WORKSPACE ->
                "读写当前工作区里的文件。相对路径按工作区解析，`..` 和指向外面的软链接会被拒绝。" +
                    "注意：如果当前工作区本身就是共享存储根（/sdcard），这等于整块手机存储。"
            PluginPermissionKind.FS_ANY ->
                "读写 App 能访问的任意路径，包括 DSH 自己的数据目录（会话、设置、令牌文件所在位置）。除非确有必要，不要授权。"
            PluginPermissionKind.NET_HOST ->
                "用 https 访问 ${target.orEmpty()} 及其子域名。插件能把要发送的内容（可能包含工作区文件）发到这个域名。"
            PluginPermissionKind.NET_ANY ->
                "用 https 访问任意域名。本机/内网地址（127.0.0.1、10.x、192.168.x 等）默认仍然拒绝。"
            PluginPermissionKind.SHELL ->
                "以 App 身份执行 shell 命令（不是 root）。命令能读写 App 能访问的一切，风险最高。"
            PluginPermissionKind.UI -> "注册插件自己的整壳（Shell）作为主界面：界面由插件返回的 UI 树渲染，" + "能看到当前会话的消息与状态、能发消息/停止/切会话（M85-A 起生效）。"
            PluginPermissionKind.UI_SLOT ->
                "把声明式页面/装饰挂到宿主的聊天输入条、工具卡、设置行等槽位（docs/181 §3.4）。" +
                    "槽位只渲染受限 Surface 子树（节点/深度/文本有预算），不能注入任意 Compose/WebView；" +
                    "点击只回到插件 JS。M87-C 起生效：聊天输入条上方、工具卡 body、设置首页。"
            PluginPermissionKind.AGENT ->
                "注册自己的回合实现（agent/v1）：被选中后，每条消息的回合循环由这个插件跑。" +
                    "插件能看到用户输入与会话状态，并通过宿主原语发通知/改状态（M85-D 起生效；" +
                    "选中它需要在回合插件设置里显式切换，停用即回安全提示）。"
            PluginPermissionKind.STORAGE ->
                "注册自己的会话存储（storage/v1，v1 只读导出）：被选中后，会话列表/打开由这个插件提供；" +
                    "插件能读到导出的会话摘要与内容，但不能新建或写入（M85-E 起生效；需显式选中，停用即回安全提示）。"
            PluginPermissionKind.STORAGE_HOOKS ->
                "挂会话写入钩子（ctx.storage.on('before-append'|'after-load')，docs/181 §3.5）：插件能在用户消息/工具结果落盘前看到事件内容，并拒绝写入" +
                    "（拒绝后这条事件不落盘、宿主在界面提示）。插件还能在会话打开后收到 after-load 观察事件。" +
                    "v1 只允许观测/拒绝，不允许改写事件字段（改写留到 0.3.6）。"
            PluginPermissionKind.PROMPT ->
                "注册自己的系统提示词实现（prompt/v1）：被选中后，每轮 system 由这个插件拼装/改写；" +
                    "插件能看到内置拼装结果与模型/工具信息（M85-E 起生效；需显式选中，停用即回内置提示词）。"
            PluginPermissionKind.CAPABILITY ->
                "注册自己的能力提供方（capability/v1，例如 NFC / root / Shizuku / ADB shell）：" +
                    "能力只在你显式调用时执行，宿主负责登记、权限门与超时；" +
                    "没有提供方时如实报「不可用」，宿主不写死任何特权能力（M85-F 起生效）。"
            PluginPermissionKind.TOOLS_INTERCEPT ->
                "改工具描述/参数/结果，并能观测工具结果：插件可以看到并改写模型发起的工具调用与返回值。" +
                    "被改写的结果在工具卡上标「已由 <插件> 改写」，原始哈希进诊断。只收紧的 " +
                    "restrict/guard 不需要这条权限。"
            PluginPermissionKind.LLM_REQUEST ->
                "改本次模型请求与提示词：插件可以在请求发出前改 model / messages / tools / 参数，" +
                    "改写系统提示词的拼装结果，改写流式输出（界面与落盘一致），并在每一步注入消息。" +
                    "等于能看到并修改模型收到的全部内容；只改这一次请求，不改会话历史。"
            PluginPermissionKind.NOTIFY ->
                "发系统通知：插件调用 ctx.whale.notify / new Notification / agent.notify 时，" +
                    "鲸歌会在通知栏弹一条通知（点击回到鲸歌聊天页）。Android 13+ 还需要系统通知权限，" +
                    "没开时会在插件页提示「去系统设置」。未授权时插件调用会收到 PERMISSION_DENIED。"
            PluginPermissionKind.DEVICE ->
                "读设备信息与应用列表、启动/停止应用、读系统设置、用无障碍点按/滑动/截图（M67 起生效）。" +
                    "不会给 root/Shizuku，也不会静默安装应用。"
            PluginPermissionKind.MEMORY ->
                "读写鲸歌记忆库：新增/修改/删除记忆、移动文件夹、建立/修改/删除记忆之间的链接（M68 起生效）。" +
                    "记忆库里有用户自己存的内容，授权前请确认这个插件可信。"
            PluginPermissionKind.UNKNOWN -> "认不出的权限写法，不会被授予。"
        }
}

/** 文件权限档位。 */
enum class PluginFsMode { NONE, WORKSPACE, ANY }

/**
 * 权限解析 / 授权判断。
 *
 * 这个对象刻意不依赖 Android，纯 JVM 单测覆盖所有分支；宿主（[PluginSandbox]）只调它的判断函数。
 */
object PluginPermissions {

    const val FS_WORKSPACE = "fs:workspace"
    const val FS_ANY = "fs:any"
    const val NET_ANY = "net:any"
    const val SHELL = "shell"
    const val UI = "ui"
    /** M87-G：声明式页面/装饰的槽位权限（M87-C 起生效；本版只识别、不执行）。 */
    const val UI_SLOT = "ui:slot"
    const val AGENT = "agent"
    const val STORAGE = "storage"
    /** M87-E：会话写入钩子（`ctx.storage.on('before-append'|'after-load')`）；默认需确认。 */
    const val STORAGE_HOOKS = "storage:hooks"
    const val PROMPT = "prompt"
    const val CAPABILITY = "capability"
    const val TOOLS_INTERCEPT = "tools:intercept"
    /** M87-B：请求/提示词中间件（`ctx.on` 的四个扩展点）；默认需确认、不进 autoGrants。 */
    const val LLM_REQUEST = "llm:request"
    const val NOTIFY = "notify"
    const val DEVICE = "device"
    const val MEMORY = "memory"

    private val HOST_PATTERN =
        Regex("""^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*$""")

    /** 解析一条 manifest 权限；空串/空白返回 null。 */
    fun parse(raw: String?): PluginPermission? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        return when {
            lower == "fs" || lower == FS_WORKSPACE || lower == "fs:read" ->
                PluginPermission(FS_WORKSPACE, PluginPermissionKind.FS_WORKSPACE)
            lower == FS_ANY || lower == "fs:*" ->
                PluginPermission(FS_ANY, PluginPermissionKind.FS_ANY)
            lower == "ui" || lower == "ui:register" ->
                PluginPermission(UI, PluginPermissionKind.UI)
            // M87-G：声明式页面/装饰走槽位（docs/181 §3.6.6）；M87-C 之前只识别、不生效。
            lower == UI_SLOT || lower == "ui:slots" || lower == "ui:surface" ->
                PluginPermission(UI_SLOT, PluginPermissionKind.UI_SLOT)
            // M85-D：回合契约只接受规范的 `agent` 写法（`agent:run` 也认，保持和文档一致）。
            lower == AGENT || lower == "agent:run" ->
                PluginPermission(AGENT, PluginPermissionKind.AGENT)
            // M85-E：存储/提示词角色（v1 存储只读；`storage:read` 与 `storage` 等价）。
            lower == STORAGE || lower == "storage:read" || lower == "storage:rw" || lower == "session" ->
                PluginPermission(STORAGE, PluginPermissionKind.STORAGE)
            // M87-E：会话写入钩子（before-append/after-load）；与 storage 只读角色分开授权。
            lower == STORAGE_HOOKS || lower == "storage:hook" || lower == "storage:write" ->
                PluginPermission(STORAGE_HOOKS, PluginPermissionKind.STORAGE_HOOKS)
            lower == PROMPT || lower == "prompt:assemble" || lower == "system-prompt" ->
                PluginPermission(PROMPT, PluginPermissionKind.PROMPT)
            // M85-F：能力提供方（capability/v1）；`capability:provide` 与 `capability` 等价。
            lower == CAPABILITY || lower == "capability:provide" || lower == "capabilities" ->
                PluginPermission(CAPABILITY, PluginPermissionKind.CAPABILITY)
            // M87-A：工具中间件（describe/on 需要；restrict/guard 只收紧，不需要这条）。
            lower == TOOLS_INTERCEPT || lower == "tools:intercept:*" || lower == "tools" ->
                PluginPermission(TOOLS_INTERCEPT, PluginPermissionKind.TOOLS_INTERCEPT)
            // M87-B：请求/提示词中间件（agent/request、llm/stream、system-prompt/assemble、agent/pre-step）。
            lower == LLM_REQUEST || lower == "llm" || lower == "llm:request:*" || lower == "llm:intercept" ->
                PluginPermission(LLM_REQUEST, PluginPermissionKind.LLM_REQUEST)
            lower == NOTIFY || lower == "notification" ->
                PluginPermission(NOTIFY, PluginPermissionKind.NOTIFY)
            lower == DEVICE || lower.startsWith("device:") ->
                PluginPermission(DEVICE, PluginPermissionKind.DEVICE)
            lower == MEMORY || lower == "memory:rw" || lower == "memory:write" ->
                PluginPermission(MEMORY, PluginPermissionKind.MEMORY)
            lower == SHELL || lower == "shell:exec" || lower == "exec" ->
                PluginPermission(SHELL, PluginPermissionKind.SHELL)
            lower == NET_ANY || lower == "net:*" ->
                PluginPermission(NET_ANY, PluginPermissionKind.NET_ANY)
            lower.startsWith("net:") -> parseHost(lower.removePrefix("net:"), text)
            else -> PluginPermission(text, PluginPermissionKind.UNKNOWN)
        }
    }

    private fun parseHost(fragment: String, original: String): PluginPermission {
        var host = fragment.trim().trimEnd('.')
        if (host.isEmpty()) return PluginPermission(original, PluginPermissionKind.UNKNOWN)
        // 只接受裸域名；带 scheme/路径/端口/用户名的一律不认（避免「按主机授权」被理解成整条 URL）。
        if (host.contains('/') || host.contains('@') || host.contains(':') || host.contains(' ')) {
            return PluginPermission(original, PluginPermissionKind.UNKNOWN)
        }
        if (!HOST_PATTERN.matches(host) || host.contains("..")) {
            return PluginPermission(original, PluginPermissionKind.UNKNOWN)
        }
        return PluginPermission("net:$host", PluginPermissionKind.NET_HOST, host)
    }

    /** 解析整个数组：去重（按规范写法），保持顺序；未知写法保留给界面展示。 */
    fun parseAll(raws: List<String>): List<PluginPermission> {
        val out = ArrayList<PluginPermission>(raws.size)
        val seen = HashSet<String>()
        raws.forEach { raw ->
            val permission = parse(raw) ?: return@forEach
            if (seen.add(permission.raw)) out += permission
        }
        return out
    }

    /** 导入时自动授予的权限（只有非危险且已实现的能力才会自动给）。 */
    fun autoGrants(permissions: List<PluginPermission>): Set<String> =
        permissions.filter { it.autoGranted && it.implemented }.map { it.raw }.toSet()

    /** 需要用户逐项确认的权限。 */
    fun approvalNeeded(permissions: List<PluginPermission>): List<PluginPermission> =
        permissions.filter { it.dangerous && it.implemented }

    /**
     * 规范化授权表：只保留「清单里申请过 + 已实现」的项；`fs:any` 天然覆盖 `fs:workspace`。
     * 这样即使 `plugins.json` 被手改，也不会有清单外的权限生效。
     */
    fun sanitizeGrants(permissions: List<PluginPermission>, granted: List<String>): Set<String> {
        val requested = permissions.map { it.raw }.toSet()
        val out = LinkedHashSet<String>()
        granted.forEach { raw ->
            val normalized = parse(raw)?.raw ?: return@forEach
            if (normalized !in requested) return@forEach
            val permission = parse(normalized) ?: return@forEach
            if (!permission.implemented) return@forEach
            out += normalized
        }
        if (FS_ANY in out) out += FS_WORKSPACE
        return out
    }

    fun isGranted(permission: PluginPermission, granted: Collection<String>): Boolean {
        if (permission.kind == PluginPermissionKind.UNKNOWN || !permission.implemented) return false
        val set = granted.map { it.trim().lowercase() }.toSet()
        return permission.raw.lowercase() in set ||
            (permission.kind == PluginPermissionKind.FS_WORKSPACE && FS_ANY in set)
    }

    fun fsMode(granted: Collection<String>): PluginFsMode {
        val set = granted.map { it.trim().lowercase() }.toSet()
        return when {
            FS_ANY in set -> PluginFsMode.ANY
            FS_WORKSPACE in set -> PluginFsMode.WORKSPACE
            else -> PluginFsMode.NONE
        }
    }

    fun shellGranted(granted: Collection<String>): Boolean =
        SHELL in granted.map { it.trim().lowercase() }

    fun netAnyGranted(granted: Collection<String>): Boolean =
        NET_ANY in granted.map { it.trim().lowercase() }

    fun memoryGranted(granted: Collection<String>): Boolean =
        MEMORY in granted.map { it.trim().lowercase() }

    /**
     * M87-H5：`notify` 权限门——清单里真的申请了 notify，且授权表里有。
     *
     * 两边都要满足：只在授权表里手写 `notify`（清单没申请）不算；清单申请了但用户没确认也不发。
     * 未授权时宿主返回 `{"ok":false,"code":"PERMISSION_DENIED"}`，不再静默成功。
     */
    fun notifyGranted(requested: Collection<String>, granted: Collection<String>): Boolean {
        val asks = requested.any { parse(it)?.kind == PluginPermissionKind.NOTIFY }
        if (!asks) return false
        return NOTIFY in granted.map { it.trim().lowercase() }
    }

    /**
     * M87-A：`tools:intercept` 是否放行 describe / pre-execute / post-execute / result。
     * 与 notify 同一形状：清单申请 + 授权表都有；`restrict/guard` 不走这里（只能收紧）。
     */
    fun toolsInterceptGranted(requested: Collection<String>, granted: Collection<String>): Boolean {
        val asks = requested.any { parse(it)?.kind == PluginPermissionKind.TOOLS_INTERCEPT }
        if (!asks) return false
        return TOOLS_INTERCEPT in granted.map { it.trim().lowercase() }
    }

    /**
     * M87-B：`llm:request` 是否放行四个请求/提示词扩展点（agent/request、llm/stream、
     * system-prompt/assemble、agent/pre-step）。与 notify/tools:intercept 同一形状：
     * 清单申请 + 授权表都有；未授权时宿主不挂链（插件仍能注册，但不会收到 dispatch）。
     */
    fun llmRequestGranted(requested: Collection<String>, granted: Collection<String>): Boolean {
        val asks = requested.any { parse(it)?.kind == PluginPermissionKind.LLM_REQUEST }
        if (!asks) return false
        return LLM_REQUEST in granted.map { it.trim().lowercase() }
    }

    /**
     * M87-E：`storage:hooks` 是否放行 `ctx.storage.on('before-append'|'after-load')`。
     * 与 notify/tools:intercept 同一形状：清单申请 + 授权表都有；未授权时钩子不挂（插件仍能注册）。
     */
    fun storageHooksGranted(requested: Collection<String>, granted: Collection<String>): Boolean {
        val asks = requested.any { parse(it)?.kind == PluginPermissionKind.STORAGE_HOOKS }
        if (!asks) return false
        return STORAGE_HOOKS in granted.map { it.trim().lowercase() }
    }

    /**
     * M87-C：`ui:slot` 是否放行声明式宿主插槽。与 notify/tools:intercept 同一形状：
     * 清单申请 + 授权表都有；未授权时插槽不渲染（插件仍可注册，但不生效）。
     */
    fun uiSlotGranted(requested: Collection<String>, granted: Collection<String>): Boolean {
        val asks = requested.any { parse(it)?.kind == PluginPermissionKind.UI_SLOT }
        if (!asks) return false
        return UI_SLOT in granted.map { it.trim().lowercase() }
    }

    /**
     * M87-H5：notify 未授权时给插件 JS 的结构化回复（两处宿主共用，文案不漂移）。
     * 插件能区分「没权限」和「运行时/参数错误」，不再像以前那样静默成功。
     */
    const val NOTIFY_DENIED_JSON: String =
        """{"ok":false,"code":"PERMISSION_DENIED",""" +
            """"message":"缺少 notify 权限（在插件页确认授权后才能发系统通知）"}"""

    /**
     * 工作区是不是「共享存储根 / 整个存储」这种宽边界。
     *
     * 是的话 `fs:workspace` 实际上等于整块手机存储（本机默认工作区就是 `/storage/emulated/0`），
     * 插件页要给用户明确提示，不能让人以为只是一个小项目目录。
     */
    fun isBroadStorageRoot(path: String): Boolean {
        val clean = path.trim().trimEnd('/').lowercase()
        if (clean.isEmpty()) return true
        return clean == "/" ||
            clean == "/sdcard" ||
            clean == "/mnt/sdcard" ||
            clean == "/storage" ||
            clean == "/storage/emulated" ||
            clean == "/storage/self/primary" ||
            Regex("""^/storage/emulated/\d+$""").matches(clean)
    }

    /**
     * 域名是否被允许：`net:<host>` 覆盖该主机和它的子域名（`api.example.com` 也允许
     * `cdn.api.example.com`）；`net:any` 允许所有主机。
     */
    fun netAllows(granted: Collection<String>, host: String): Boolean {
        val target = host.trim().trimEnd('.').lowercase()
        if (target.isEmpty()) return false
        val set = granted.map { it.trim().lowercase() }
        if (NET_ANY in set) return true
        return set.any { raw ->
            if (!raw.startsWith("net:") || raw == NET_ANY) return@any false
            val allowed = raw.removePrefix("net:").trimEnd('.')
            allowed.isNotEmpty() && (target == allowed || target.endsWith(".$allowed"))
        }
    }

    /** 本机 / 链路本地地址：任何权限都不允许（防插件打自己或内网控制面）。 */
    fun isLoopbackOrLocal(host: String): Boolean {
        val h = host.trim().lowercase().removeSurrounding("[", "]")
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h == "::1" || h == "0:0:0:0:0:0:0:1" || h == "::" ) return true
        if (h == "0.0.0.0") return true
        val v4 = h.split('.')
        if (v4.size == 4) {
            val a = v4[0].toIntOrNull() ?: return false
            val b = v4[1].toIntOrNull() ?: return false
            if (a == 127 || a == 0) return true
            if (a == 169 && b == 254) return true
        }
        return false
    }

    /** 内网地址：只有 `net:any` 才允许（`net:<host>` 明确写了内网域名/IP 也仍然拦）。 */
    fun isPrivate(host: String): Boolean {
        val h = host.trim().lowercase().removeSurrounding("[", "]")
        val v4 = h.split('.')
        if (v4.size != 4) return false
        val a = v4[0].toIntOrNull() ?: return false
        val b = v4[1].toIntOrNull() ?: return false
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127)
    }
}
