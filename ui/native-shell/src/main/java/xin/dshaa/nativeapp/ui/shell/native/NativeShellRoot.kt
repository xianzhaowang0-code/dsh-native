package xin.dshaa.nativeapp.ui.shell.native

import xin.dshaa.ui.shellapi.NativeShell
import xin.dshaa.ui.shellapi.NativeShells
import xin.dshaa.ui.shellapi.ShellPage
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xin.dshaa.ui.shellapi.BuiltinPageIds
import xin.dshaa.nativeapp.ui.shell.DshDrawer
import xin.dshaa.ui.shellapi.NativeShellHost
import xin.dshaa.ui.shellapi.NativeShellHostUi
import xin.dshaa.ui.shellapi.PageLevel
import xin.dshaa.ui.shellapi.ShellPageRegistry
import xin.dshaa.nativeapp.ui.shell.native.pages.NativeNav
import xin.dshaa.nativeapp.ui.shell.native.pages.NativePageDispatch
import xin.dshaa.nativeapp.ui.shell.native.pages.NativePages
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * 当前路由快照（M79，用户 2026-11-12 反馈「二级页进出一个动画都没有」）：
 * `page` = 一级页面 id（null = 聊天），`sub` = 设置里的二级页面 id。
 * 交给 [AnimatedContent] 当 targetState，进出二级页统一走滑入/滑出 + 淡入淡出。
 *
 * M84-B：这里只存**字符串 id**（[BuiltinPageIds]），页面标题/图标/层级
 * 由 [NativePages] 清单查表；`Screen` / `SubScreen` 枚举已删除。
 * M85-C2：清单和页面渲染 dispatch 都归壳（`ui/shell/native/pages/`），宿主不再持有页面清单。
 */
private data class NavRoute(val page: String?, val sub: String?) {
    /** 层级：聊天 0（根） < 一级页/设置 1 < 设置二级页 2；用来决定滑动方向。 */
    val depth: Int
        get() = when {
            page == null -> 0
            sub != null -> 2
            else -> 1
        }
}


@Composable
private fun NativeShellContent(vm: NativeShellHost, hostUi: NativeShellHostUi) {
    val c = DshTheme2.colors
    val conversation = vm.conversation
    // null = 聊天（落地页）；非空 = 一级页面 id（BuiltinPageIds）
    var screenName by rememberSaveable { mutableStateOf<String?>(null) }
    var subScreenName by rememberSaveable { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val currentSub = NativePages.byId(subScreenName)

    // 转场方向（M79）：同层级互跳（设置→会话列表）没有深度差，用这个记住是「进」还是「退」。
    var navForward by remember { mutableStateOf(true) }
    // 0.3.4（用户第 3 条）：显式的「从哪进」状态，返回就回哪。
    // backPage = 当前一级页/二级页的来路：null = 聊天，Settings = 设置首页，其它 = 对应一级页；
    // backSub 只在来路本身是设置二级页时非空（例如在二级页打开抽屉后再导航）。
    var backPage by rememberSaveable { mutableStateOf<String?>(null) }
    var backSub by rememberSaveable { mutableStateOf<String?>(null) }
    // 打开抽屉那一刻的路由快照（抽屉里的入口完成后按它返回）。
    var drawerOriginPage by rememberSaveable { mutableStateOf<String?>(null) }
    var drawerOriginSub by rememberSaveable { mutableStateOf<String?>(null) }
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }
    // 0.3.4（用户第 3 条）：抽屉既可能由顶栏 ☰（openDrawer）打开，也可能被手势拉出——
    // 手势走 ModalNavigationDrawer 内部、不经过 openDrawer 回调。所以在抽屉**真正打开**的
    // 那一刻统一记来路，两种打开方式行为一致。
    androidx.compose.runtime.LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            drawerOriginPage = screenName
            drawerOriginSub = subScreenName
        }
    }
    val go: (String) -> Unit = { id ->
        // M84-B：页面由内置插件提供；被关掉时不进（入口已隐藏，这里防调试通道/旧状态残留）
        val target = NativePages.byId(id)
        if (target != null && target.level == PageLevel.Primary &&
            vm.builtinScreenEnabled(id)
        ) {
            navForward = true
            // 从设置页进的功能页：返回回设置（0.3.4 显式记录）
            backPage = BuiltinPageIds.Settings
            backSub = null
            screenName = id
            subScreenName = null
        }
    }
    /** 从聊天页直接进的功能页：返回落回聊天页。 */
    val goFromChat: (String) -> Unit = { id ->
        val target = NativePages.byId(id)
        if (target != null && target.level == PageLevel.Primary &&
            vm.builtinScreenEnabled(id)
        ) {
            navForward = true
            backPage = null
            backSub = null
            screenName = id
            subScreenName = null
        }
    }
    /** 二级页统一从「设置」下挂；页面插件关掉时拒绝进入。设置内部进二级页，返回仍回设置。 */
    val openSub: (String) -> Unit = { id ->
        val target = NativePages.byId(id)
        if (target != null && target.level == PageLevel.Secondary &&
            vm.builtinScreenEnabled(id)
        ) {
            navForward = true
            backPage = BuiltinPageIds.Settings
            backSub = null
            screenName = BuiltinPageIds.Settings
            subScreenName = id
        }
    }
    /** 抽屉里点一级页：返回目标是打开抽屉时的来路（0.3.4 第 3 条）。 */
    val goFromDrawer: (String) -> Unit = { id ->
        val target = NativePages.byId(id)
        if (target != null && target.level == PageLevel.Primary &&
            vm.builtinScreenEnabled(id)
        ) {
            navForward = true
            backPage = drawerOriginPage
            backSub = drawerOriginSub
            screenName = id
            subScreenName = null
        }
    }
    /** 抽屉里的二级入口（工作区管理 / 助手配置 / 远程 DSH…）：返回同样回抽屉来路，不绕经设置。 */
    val openSubFromDrawer: (String) -> Unit = { id ->
        val target = NativePages.byId(id)
        if (target != null && target.level == PageLevel.Secondary &&
            vm.builtinScreenEnabled(id)
        ) {
            navForward = true
            backPage = drawerOriginPage
            backSub = drawerOriginSub
            screenName = BuiltinPageIds.Settings
            subScreenName = id
        }
    }
    // M84-B：恢复出来的路由落在「已被内置插件关掉」的页面时，退回聊天/设置。
    androidx.compose.runtime.LaunchedEffect(screenName) {
        val page = screenName ?: return@LaunchedEffect
        if (!vm.builtinScreenEnabled(page)) {
            screenName = null
            subScreenName = null
        }
    }
    androidx.compose.runtime.LaunchedEffect(subScreenName) {
        val sub = subScreenName ?: return@LaunchedEffect
        if (!vm.builtinScreenEnabled(sub)) subScreenName = null
    }
    /** 返回：回到显式记录的来路（聊天 / 设置首页 / 对应一级页）。 */
    val backFromPage: () -> Unit = {
        navForward = false
        screenName = backPage
        subScreenName = backSub
    }
    /** 二级页返回：同样回显式记录的来路（设置内部进二级页时来路就是设置）。 */
    val closeSub: () -> Unit = {
        navForward = false
        screenName = backPage
        subScreenName = backSub
    }
    // 宽屏（平板 / 折叠屏展开）侧边栏常驻：600dp 是 Material 的 compact/expanded 断点
    val wideLayout = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    android.util.Log.i(
        "DshShell",
        "native ui config width=${androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp}dp " +
            "density=${androidx.compose.ui.platform.LocalDensity.current.density} wide=$wideLayout",
    )
    // 设置页的滚动位置提到这里：进出二级页不再被重置到顶部（用户 23:00 第 3 条）
    val settingsScroll = androidx.compose.foundation.rememberScrollState()

    // 落地页必须是聊天：冷启动 / 删除当前会话后回来，都补一个空草稿（不建会话文件）。
    androidx.compose.runtime.LaunchedEffect(screenName, conversation.stateKey, conversation.state == null) {
        if (screenName == null) conversation.ensureDraft()
    }

    // 系统返回键：抽屉 → 关抽屉；二级页 → 回设置；一级页 → 回聊天；子代理回放 → 回父会话；
    // 聊天页本身不拦截（交给系统退出 App，RikkaHub 同款语义）。
    androidx.activity.compose.BackHandler(
        enabled = drawerState.isOpen || currentSub != null || screenName != null || (conversation.isLocal && vm.canGoBackFromChat),
    ) {
        navForward = false
        when {
            drawerState.isOpen -> closeDrawer()
            currentSub != null -> closeSub()
            // 功能页回到来路（0.3.4：聊天开的回聊天，设置开的回设置，抽屉开的回抽屉来路）
            screenName != null -> backFromPage()
            else -> vm.closeChat()
        }
    }

    // M85-C2：页面的动作交给页面 dispatch，路由状态只留在这里（页面不再认识路由状态机）。
    val nav = NativeNav(
        openDrawer = openDrawer,
        toChat = {
            navForward = false
            screenName = null
            subScreenName = null
        },
        go = go,
        goFromChat = goFromChat,
        openSub = openSub,
        closeSub = closeSub,
        backFromPage = backFromPage,
        settingsScroll = settingsScroll,
        wideLayout = wideLayout,
    )

    // 调试通道：`--es dsh_import_zip` 直接把旧版导入页顶到前面（真机无 SAF 也能验收）
    androidx.compose.runtime.LaunchedEffect(vm.importRequest) {
        if (vm.importRequest != null) openSub(BuiltinPageIds.DataMigration)
    }

    // 调试通道：`--es dsh_open_screen agents` 启动即落到指定页面（一级页或二级页都认）
    androidx.compose.runtime.LaunchedEffect(vm.debugScreenRequest) {
        val requested = vm.debugScreenRequest ?: return@LaunchedEffect
        if (requested.equals("chat", ignoreCase = true) || requested.equals("home", ignoreCase = true)) {
            // 验收用：清回聊天落地页（关掉页面后测试「路由被拦」需要一个已知起点）
            screenName = null
            subScreenName = null
            backPage = null
            backSub = null
        } else {
            val match = NativePages.find(requested)
            if (match != null) {
                if (match.level == PageLevel.Primary) {
                    go(match.id)
                } else {
                    openSub(match.id)
                }
            }
        }
        vm.consumeDebugScreen()
    }

    // 验收用：路由落的哪一页（logcat -s DshPage）；关掉的页面不会被记录，等于「被拦回」。
    androidx.compose.runtime.LaunchedEffect(screenName, subScreenName) {
        android.util.Log.i("DshPage", "route page=${screenName ?: "Chat"} sub=${subScreenName ?: "-"}")
    }

    // 抽屉内容：窄屏是滑出的 Modal 抽屉；宽屏（平板/折叠屏展开，>=600dp）常驻在左侧（M74，用户 23:00 第 5 条）
    // M83：参数 permanent 决定窄屏 Modal 抽屉 / 宽屏常驻直角面板（两种模式的白色缺口问题见 DshDrawer 注释）。
    val drawerContent: @Composable (Boolean) -> Unit = { permanent ->
            DshDrawer(
                permanent = permanent,
                currentSessionId = conversation.state?.sessionId,
                sessions = conversation.sessions,
                workspace = vm.currentWorkspace,
                workspaces = vm.workspaces,
                assistantName = conversation.assistantName,
                assistantAvatar = conversation.assistantAvatar,
                assistantProfiles = vm.settings.assistantProfiles,
                activeAssistantId = vm.settings.activeAssistantId,
                onSwitchAssistant = { id -> vm.settings.setActiveAssistant(id) },
                onManageAssistants = {
                    closeDrawer()
                    openSubFromDrawer(BuiltinPageIds.Assistant)
                },
                model = conversation.model,
                toolCount = conversation.toolCount,
                remoteProfileId = conversation.remoteProfileId,
                remoteProfiles = vm.remote.profiles,
                localFeatures = conversation.isLocal,
                onSelectTarget = { profileId ->
                    conversation.selectTarget(profileId)
                    screenName = null
                    subScreenName = null
                    closeDrawer()
                },
                onManageRemote = { closeDrawer(); openSubFromDrawer(BuiltinPageIds.RemoteDsh) },
                badgeFor = { id -> conversation.sessions.firstOrNull { it.id == id }?.let { it.badge ?: if (it.running) xin.dshaa.ui.shellapi.chat.SessionBadge.RUNNING else null } },
                onOpenSession = { summary ->
                    closeDrawer()
                    navForward = false
                    screenName = null
                    subScreenName = null
                    conversation.openSession(summary.id)
                },
                onNewChat = {
                    closeDrawer()
                    navForward = false
                    screenName = null
                    subScreenName = null
                    conversation.newChat()
                },
                onSwitchWorkspace = { path ->
                    closeDrawer()
                    navForward = false
                    screenName = null
                    subScreenName = null
                    vm.switchWorkspace(path)
                },
                onCreateWorkspace = { name, path ->
                    closeDrawer()
                    navForward = false
                    screenName = null
                    subScreenName = null
                    vm.createWorkspace(name, path)
                },
                onManageWorkspaces = {
                    closeDrawer()
                    openSubFromDrawer(BuiltinPageIds.Workspaces)
                },
                onSelectScreen = { id ->
                    closeDrawer()
                    goFromDrawer(id)
                },
                sessionsPageEnabled = conversation.isLocal && vm.builtinScreenEnabled(BuiltinPageIds.Sessions),
                // M85-C3-4：抽屉「插件页面」段只读 page/v1 注册表；同 id 覆盖项走内置页入口，不重复列
                pluginPages = ShellPageRegistry.drawerPluginPages(
                    shellPages = NativePages.declarations,
                    pluginPages = vm.pluginPageEntries(),
                ).map { page ->
                    xin.dshaa.nativeapp.ui.shell.PluginPageShortcut(
                        pluginId = page.pluginId.orEmpty(),
                        pluginName = page.pluginName.orEmpty(),
                        routeId = page.routeId.orEmpty(),
                        title = page.title,
                    )
                },
                onOpenPluginPage = { entry ->
                    closeDrawer()
                    vm.openPluginPageEntry(
                        xin.dshaa.ui.shellapi.PluginPageEntry(
                            pluginId = entry.pluginId,
                            pluginName = entry.pluginName,
                            routeId = entry.routeId,
                            title = entry.title,
                        ),
                    )
                },
                onRenameSession = { id, title -> vm.renameSession(id, title) },
                onDeleteSession = { id -> vm.deleteSession(id) },
                onDeleteSessionPermanently = { id -> vm.deleteSessionPermanently(id) },
            )
    }

    // 屏幕主体：宽屏时和常驻抽屉并排，窄屏时铺满
    val body: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(c.background)) {
            // M79：所有一级页/二级页共用一套转场（用户 2026-11-12：进二级页、退出都没有动画）。
            // 方向按层级差决定：进更深一层从右滑入，返回从左滑入；同层级用 navForward（进/退）决定。
            AnimatedContent(
                targetState = NavRoute(screenName, subScreenName),
                transitionSpec = {
                    val forward = if (targetState.depth == initialState.depth) {
                        navForward
                    } else {
                        targetState.depth > initialState.depth
                    }
                    val dir = if (forward) 1 else -1
                    (
                        slideInHorizontally(animationSpec = tween(260)) { full -> dir * full / 5 } +
                            fadeIn(animationSpec = tween(200))
                        ) togetherWith (
                        slideOutHorizontally(animationSpec = tween(260)) { full -> -dir * full / 5 } +
                            fadeOut(animationSpec = tween(160))
                        ) using SizeTransform(clip = false)
                },
                label = "dsh-screen",
            ) { route ->
                // M85-C2：页面内容由壳自己的 dispatch 画（标题/图标/层级都在 `NativePages`）
                NativePageDispatch(vm, route.page, route.sub, nav, hostUi)
            }
        }
    }

    if (wideLayout) {
        // M83：宽屏不再用 Material 的 PermanentNavigationDrawer。它内部会再套一层
        // PermanentDrawerSheet（默认容器色 surfaceContainerLow ≈ #FAFAFA、带圆角），
        // 深色主题下侧边栏右上/右下角会露出两块白色缺口（用户：「四角/边缘有白色空白」）。
        // 这里用普通 Row 拼常驻侧边栏：直角、铺满高度、紧贴左边缘，右侧一条 1dp 分隔线。
        Row(Modifier.fillMaxSize().background(c.surface)) {
            Box(Modifier.width(304.dp).fillMaxHeight()) { drawerContent(true) }
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
            Box(Modifier.weight(1f).fillMaxHeight()) { body() }
        }
    } else {
        androidx.compose.material3.ModalNavigationDrawer(
            drawerState = drawerState,
            // 0.3.4（用户第 2 条）：侧边栏手势只在聊天页与设置首页生效；二级页/其它一级页不认边缘滑动。
            // 宽屏常驻抽屉不走这里，不受影响。
            gesturesEnabled = subScreenName == null &&
                (screenName == null || screenName == BuiltinPageIds.Settings),
            drawerContent = {
                // Warm recreation in this host can leave the initial sheet at x=0 even
                // with a correct closed offset. Keep measuring for anchors, but do not
                // place fully closed content (including its semantics and touch targets).
                androidx.compose.ui.layout.Layout(content = { drawerContent(false) }) { measurables, constraints ->
                    val sheets = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
                    val width = sheets.maxOfOrNull { it.width } ?: 0
                    val height = sheets.maxOfOrNull { it.height } ?: 0
                    layout(width, height) {
                        val offset = drawerState.currentOffset
                        if (drawerState.isOpen || (!offset.isNaN() && offset > -width.toFloat())) {
                            sheets.forEach { it.placeRelative(0, 0) }
                        }
                    }
                }
            },
        ) { body() }
    }
}

/** 内置 native 壳（`builtin.shell.native`）：把现有 Compose UI 原样包进 `shell/v1`。 */
object BuiltinNativeShell : NativeShell {
    override val id: String = NativeShells.BUILTIN_ID
    override val label: String = "原生界面"

    /** M85-C2：把壳的 22 页声明交给宿主（只有 id/标题/层级；图标和渲染留在壳里）。 */
    override val pages: List<ShellPage> = NativePages.declarations

    /** M85-C2：调试通道的宽松匹配由壳解释（id/标题/`<id>Screen`）。 */
    override fun findPage(requested: String): ShellPage? = NativePages.find(requested)?.declaration

    @Composable
    override fun Root(vm: NativeShellHost, ui: NativeShellHostUi) {
        if (!vm.onboardingDone) {
            Onboarding(onDone = { vm.completeOnboarding() })
        } else {
            NativeShellContent(vm, ui)
        }
    }

    @Composable
    override fun PluginManager(host: NativeShellHost, ui: NativeShellHostUi, onBack: () -> Unit) {
        xin.dshaa.nativeapp.ui.shell.native.pages.NativePluginsPage(host, ui, onBack, onBack)
    }

    /** M85-C3：引导页实现留在壳里；宿主（可选 Surface 壳路径）只通过契约要它。 */
    @Composable
    override fun Onboarding(onDone: () -> Unit) {
        xin.dshaa.nativeapp.ui.onboarding.OnboardingScreen(onDone = onDone)
    }
}
