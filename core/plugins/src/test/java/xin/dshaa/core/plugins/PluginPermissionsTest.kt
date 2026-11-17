package xin.dshaa.core.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginPermissionsTest {

    @Test
    fun parsesKnownPermissionsIntoCanonicalForm() {
        val parsed = PluginPermissions.parseAll(
            listOf("fs:workspace", "FS:ANY", "net:API.Example.com", "shell", "ui", "notify", "device"),
        )
        assertEquals(
            listOf("fs:workspace", "fs:any", "net:api.example.com", "shell", "ui", "notify", "device"),
            parsed.map { it.raw },
        )
        assertEquals(PluginPermissionKind.NET_HOST, parsed[2].kind)
        assertEquals("api.example.com", parsed[2].target)
        assertTrue(parsed[2].dangerous)
        assertFalse(parsed[0].dangerous)
    }

    @Test
    fun unknownOrUrlLikePermissionsAreNotGuessed() {
        val unknown = PluginPermissions.parseAll(
            listOf(
                "net:https://api.example.com/v1",
                "net:api.example.com:443",
                "fs:readonly",
                "",
            ),
        )
        assertEquals(3, unknown.size)
        unknown.forEach {
            assertEquals(PluginPermissionKind.UNKNOWN, it.kind)
            assertFalse(it.implemented)
        }
    }

    @Test
    fun uiSlotIsImplementedAndNeedsConfirmation() {
        // M87-C：ui:slot 生效（聊天输入条/工具卡/设置行）；危险程度低但能模仿宿主 UI，仍需确认一次。
        val slot = PluginPermissions.parse("ui:slot")!!
        assertEquals(PluginPermissionKind.UI_SLOT, slot.kind)
        assertEquals("ui:slot", slot.raw)
        assertTrue(slot.implemented)
        assertTrue(slot.dangerous)
        assertFalse(slot.autoGranted)
        assertEquals(listOf("ui:slot"), PluginPermissions.approvalNeeded(listOf(slot)).map { it.raw })
        assertTrue(slot.explain.contains("M87-C"), slot.explain)
        assertEquals(
            "ui:slot",
            PluginPermissions.parse("UI:SLOTS")!!.raw,
        )
        assertTrue(PluginPermissions.uiSlotGranted(listOf("ui:slot"), listOf("ui:slot")))
        assertFalse(PluginPermissions.uiSlotGranted(listOf("ui"), listOf("ui:slot")))
        assertFalse(PluginPermissions.uiSlotGranted(listOf("ui:slot"), emptyList()))
        assertTrue(PluginPermissions.uiSlotGranted(listOf("UI:SLOT"), listOf("Ui:Slot")))
    }

    @Test
    fun storageHooksIsImplementedAndNeedsConfirmation() {
        // M87-E：storage:hooks 能观测/拒绝会话落盘，属危险能力，必须逐项确认。
        val hooks = PluginPermissions.parse("storage:hooks")!!
        assertEquals(PluginPermissionKind.STORAGE_HOOKS, hooks.kind)
        assertEquals("storage:hooks", hooks.raw)
        assertTrue(hooks.implemented)
        assertTrue(hooks.dangerous)
        assertFalse(hooks.autoGranted)
        assertEquals(listOf("storage:hooks"), PluginPermissions.approvalNeeded(listOf(hooks)).map { it.raw })
        assertTrue(hooks.explain.contains("before-append"), hooks.explain)
        // storage 只读角色与写钩子是两回事：申请 storage 不等于拿到 storage:hooks
        assertFalse(PluginPermissions.storageHooksGranted(listOf("storage"), listOf("storage")))
        assertTrue(PluginPermissions.storageHooksGranted(listOf("storage:hooks"), listOf("storage:hooks")))
        assertFalse(PluginPermissions.storageHooksGranted(listOf("storage:hooks"), emptyList()))
        assertTrue(PluginPermissions.storageHooksGranted(listOf("STORAGE:HOOKS"), listOf("Storage:Hooks")))
        assertEquals("storage:hooks", PluginPermissions.parse("storage:write")!!.raw)
    }

    @Test
    fun autoGrantOnlyWorkspaceAndUiAndOnlyImplemented() {
        val permissions = PluginPermissions.parseAll(listOf("fs:workspace", "ui", "net:api.example.com", "shell", "notify"))
        // M85-A：ui 已实现（整壳），和 fs:workspace 一样导入即允许；M87-H5 起 notify 也实现了，
        // 但仍属危险能力，必须用户逐项确认（不进 autoGrants）。
        assertEquals(setOf("fs:workspace", "ui"), PluginPermissions.autoGrants(permissions))
        assertEquals(
            listOf("net:api.example.com", "shell", "notify"),
            PluginPermissions.approvalNeeded(permissions).map { it.raw },
        )
    }

    @Test
    fun notifyIsImplementedButNeverAutoGranted() {
        val notify = PluginPermissions.parse("notify")!!
        assertEquals(PluginPermissionKind.NOTIFY, notify.kind)
        assertTrue(notify.implemented, "M87-H5：notify 已实现，才能出现在确认列表里")
        assertTrue(notify.dangerous)
        assertFalse(notify.autoGranted)
        assertEquals(emptySet<String>(), PluginPermissions.autoGrants(listOf(notify)))
        assertTrue(notify.explain.contains("系统通知"))
    }

    @Test
    fun notifyGateRequiresBothManifestAndGrant() {
        // 清单申请 + 已授权 → 放行
        assertTrue(PluginPermissions.notifyGranted(listOf("notify"), listOf("notify")))
        // 授权表手写了但清单没申请 → 不放行（不认清单外的权限）
        assertFalse(PluginPermissions.notifyGranted(listOf("fs:workspace"), listOf("notify")))
        // 清单申请了但用户没确认 → 不放行
        assertFalse(PluginPermissions.notifyGranted(listOf("notify"), emptyList()))
        assertFalse(PluginPermissions.notifyGranted(listOf("notify"), listOf("fs:workspace")))
        // 大小写 / 别名写法都归一
        assertTrue(PluginPermissions.notifyGranted(listOf("NOTIFY"), listOf("Notify")))
        assertTrue(PluginPermissions.notifyGranted(listOf("notification"), listOf("notify")))
    }

    @Test
    fun notifyDeniedReplyIsStructured() {
        val json = PluginPermissions.NOTIFY_DENIED_JSON
        assertTrue(json.contains("\"ok\":false"), "要机器可读：$json")
        assertTrue(json.contains("\"code\":\"PERMISSION_DENIED\""), "要能区分权限拒绝：$json")
        assertTrue(json.contains("notify 权限"), "要给人看的原因：$json")
        assertTrue(json.startsWith("{") && json.endsWith("}"))
    }

    @Test
    fun toolsInterceptIsDangerousAndImplementedButNotAutoGranted() {
        val intercept = PluginPermissions.parse("tools:intercept")!!
        assertEquals(PluginPermissionKind.TOOLS_INTERCEPT, intercept.kind)
        assertTrue(intercept.implemented, "M87-A：describe/on 需要它真的生效")
        assertTrue(intercept.dangerous)
        assertFalse(intercept.autoGranted, "改工具结果必须用户逐项确认")
        assertEquals(emptySet<String>(), PluginPermissions.autoGrants(listOf(intercept)))
        assertEquals(listOf("tools:intercept"), PluginPermissions.approvalNeeded(listOf(intercept)).map { it.raw })
        // 别名/大小写归一
        assertEquals("tools:intercept", PluginPermissions.parse("TOOLS:INTERCEPT")!!.raw)
        assertEquals("tools:intercept", PluginPermissions.parse("tools")!!.raw)
        assertTrue(intercept.explain.contains("restrict/guard"), "文案要说清只收紧的那类不需要它")
    }

    @Test
    fun toolsInterceptGateRequiresManifestAndGrant() {
        assertTrue(PluginPermissions.toolsInterceptGranted(listOf("tools:intercept"), listOf("tools:intercept")))
        assertFalse(PluginPermissions.toolsInterceptGranted(listOf("fs:workspace"), listOf("tools:intercept")))
        assertFalse(PluginPermissions.toolsInterceptGranted(listOf("tools:intercept"), emptyList()))
        assertFalse(PluginPermissions.toolsInterceptGranted(listOf("tools:intercept"), listOf("notify")))
        assertTrue(PluginPermissions.toolsInterceptGranted(listOf("TOOLS"), listOf("Tools:Intercept")))
    }

    @Test
    fun llmRequestIsDangerousAndImplementedButNotAutoGranted() {
        val request = PluginPermissions.parse("llm:request")!!
        assertEquals(PluginPermissionKind.LLM_REQUEST, request.kind)
        assertTrue(request.implemented, "M87-B：四个请求扩展点要真的生效")
        assertTrue(request.dangerous, "能改模型看到的全部内容")
        assertFalse(request.autoGranted, "请求中间件必须用户逐项确认")
        assertEquals(emptySet<String>(), PluginPermissions.autoGrants(listOf(request)))
        assertEquals(listOf("llm:request"), PluginPermissions.approvalNeeded(listOf(request)).map { it.raw })
        assertEquals("llm:request", PluginPermissions.parse("LLM:REQUEST")!!.raw)
        assertEquals("llm:request", PluginPermissions.parse("llm")!!.raw)
        assertTrue(request.explain.contains("会话历史"), "文案要说清只改本次请求")
    }

    @Test
    fun llmRequestGateRequiresManifestAndGrant() {
        assertTrue(PluginPermissions.llmRequestGranted(listOf("llm:request"), listOf("llm:request")))
        assertFalse(PluginPermissions.llmRequestGranted(listOf("fs:workspace"), listOf("llm:request")))
        assertFalse(PluginPermissions.llmRequestGranted(listOf("llm:request"), emptyList()))
        assertFalse(PluginPermissions.llmRequestGranted(listOf("llm:request"), listOf("tools:intercept")))
        assertTrue(PluginPermissions.llmRequestGranted(listOf("LLM"), listOf("Llm:Request")))
        // sanitize 不会凭空留下没申请/没实现的授权
        assertTrue(
            PluginPermissions.sanitizeGrants(
                listOf(PluginPermissions.parse("llm:request")!!),
                listOf("llm:request"),
            ).contains("llm:request"),
        )
    }

    @Test
    fun agentRoleIsImplementedAndAutoGranted() {
        val agent = PluginPermissions.parse("agent")!!
        assertEquals(PluginPermissionKind.AGENT, agent.kind)
        assertTrue(agent.implemented)
        assertTrue(agent.autoGranted)
        assertEquals(setOf("agent"), PluginPermissions.autoGrants(listOf(agent)))
        // 文档写法 agent:run 也归一成 agent
        assertEquals("agent", PluginPermissions.parse("agent:run")!!.raw)
    }

    @Test
    fun sanitizeDropsUnrequestedAndUnimplemented() {
        val permissions = PluginPermissions.parseAll(listOf("fs:workspace", "net:api.example.com", "notify"))
        val grants = PluginPermissions.sanitizeGrants(
            permissions,
            listOf("fs:workspace", "net:api.example.com", "net:evil.example.org", "notify", "shell"),
        )
        // M87-H5：notify 已实现，清单申请过就会保留；shell 没申请、net:evil 是清单外的都丢掉。
        assertEquals(setOf("fs:workspace", "net:api.example.com", "notify"), grants)
        // 手改清单里出现 fs:any 但没申请 → 不能生效
        assertEquals(emptySet<String>(), PluginPermissions.sanitizeGrants(permissions, listOf("fs:any")))
    }

    @Test
    fun fsAnyImpliesWorkspace() {
        assertEquals(PluginFsMode.ANY, PluginPermissions.fsMode(listOf("fs:any")))
        assertEquals(PluginFsMode.WORKSPACE, PluginPermissions.fsMode(listOf("fs:workspace")))
        assertEquals(PluginFsMode.NONE, PluginPermissions.fsMode(emptyList()))
        assertTrue(
            PluginPermissions.isGranted(
                PluginPermissions.parse("fs:workspace")!!,
                listOf("fs:any"),
            ),
        )
    }

    @Test
    fun netHostCoversSubdomainsOnly() {
        val grants = listOf("net:api.example.com")
        assertTrue(PluginPermissions.netAllows(grants, "api.example.com"))
        assertTrue(PluginPermissions.netAllows(grants, "cdn.api.example.com"))
        assertFalse(PluginPermissions.netAllows(grants, "example.com"))
        assertFalse(PluginPermissions.netAllows(grants, "api.example.com.evil.net"))
        assertTrue(PluginPermissions.netAllows(listOf("net:any"), "anything.example"))
        assertFalse(PluginPermissions.netAllows(emptyList(), "api.example.com"))
    }

    @Test
    fun loopbackAndPrivateRangesDetected() {
        listOf("localhost", "127.0.0.1", "::1", "0.0.0.0", "169.254.1.1").forEach {
            assertTrue(PluginPermissions.isLoopbackOrLocal(it), it)
        }
        listOf("10.1.2.3", "192.168.1.1", "172.20.0.1").forEach {
            assertTrue(PluginPermissions.isPrivate(it), it)
        }
        assertFalse(PluginPermissions.isLoopbackOrLocal("api.example.com"))
        assertFalse(PluginPermissions.isPrivate("8.8.8.8"))
    }

    @Test
    fun packageManifestCarriesPermissions() {
        val parsed = PluginPackage.parse(
            listOf(
                PluginFile(
                    "package.json",
                    """{"name":"demo","main":"index.js","permissions":["fs:workspace","net:api.example.com"]}""".toByteArray(),
                ),
                PluginFile("index.js", "".toByteArray()),
            ),
        ) as PackageParse.Ok
        assertEquals(listOf("fs:workspace", "net:api.example.com"), parsed.manifest.permissions)
    }

    @Test
    fun broadStorageRootsAreDetected() {
        listOf("/", "/sdcard", "/mnt/sdcard/", "/storage/emulated/0", "/storage/emulated/1", "/storage/self/primary").forEach {
            assertTrue(PluginPermissions.isBroadStorageRoot(it), it)
        }
        listOf("/storage/emulated/0/Download", "/data/user/0/x/files/workspace").forEach {
            assertFalse(PluginPermissions.isBroadStorageRoot(it), it)
        }
    }

    @Test
    fun storageAndPromptRolesAreImplementedAndAutoGranted() {
        val storage = PluginPermissions.parse("storage")!!
        assertEquals(PluginPermissionKind.STORAGE, storage.kind)
        assertTrue(storage.implemented)
        assertTrue(storage.autoGranted)
        assertEquals("storage", PluginPermissions.parse("storage:read")!!.raw)
        val prompt = PluginPermissions.parse("prompt")!!
        assertEquals(PluginPermissionKind.PROMPT, prompt.kind)
        assertTrue(prompt.implemented)
        assertTrue(prompt.autoGranted)
        assertEquals("prompt", PluginPermissions.parse("prompt:assemble")!!.raw)
    }

    @Test
    fun permissionLabelsAndExplanationsAreHumanReadable() {
        val net = PluginPermissions.parse("net:api.example.com")!!
        assertTrue(net.title.contains("api.example.com"))
        assertTrue(net.explain.contains("https"))
        val shell = PluginPermissions.parse("shell")!!
        assertTrue(shell.explain.contains("root"))
    }
}
