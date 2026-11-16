package com.kilon.agentvscreen.ui

import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.HostService
import com.kilon.agentvscreen.host.ServiceControl
import com.kilon.agentvscreen.host.Updates
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 1.4 设置: grouped rows, each with its current value. Choices are dropdowns or a bottom sheet, not segmented bars;
 * nothing here carries a "?": every explanation is on the Help page.
 */

@Composable
fun SettingsTab(bottom: Dp) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val nav = LocalNav.current
    val host = LocalHost.current
    val scope = rememberCoroutineScope()
    val s by BackgroundOperation.state.collectAsState()
    val ts by com.kilon.agentvscreen.host.Tailnet.status.collectAsState()
    val tokens by Tokens.list.collectAsState()
    val rules by com.kilon.agentvscreen.core.VScreenStore.rules.collectAsState()
    var autonomy by remember { mutableStateOf(ApiConfig.autonomy) }
    var sheet by remember { mutableStateOf(false) }
    var eventDialog by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<Updates.Outcome?>(null) }

    MiuixPage(tr("设置", "Settings"), back = false, bottom = bottom) {
        Group(tr("接入", "Access")) {
            ArrowPreference(title = tr("接入 agent", "Connect an agent"), summary = remember(ts, app.serviceOn, app.tick) { com.kilon.agentvscreen.host.Access.tile(ctx) },
                onClick = { nav.go(Route.Connect) })
            ArrowPreference(title = tr("远程连接", "Remote access"), summary = tr("其它设备经 Tailscale 连进来", "Other devices through Tailscale"),
                endActions = { Value(remember(ts, app.tick) { com.kilon.agentvscreen.host.Tailnet.line(ctx) }) }, onClick = { nav.go(Route.Remote) })
            ArrowPreference(title = tr("令牌", "Tokens"), endActions = { Value(tr("${tokens.size} 个", "${tokens.size}")) }, onClick = { nav.go(Route.Tokens) })
        }
        Group(tr("权限与安全", "Permissions")) {
            ArrowPreference(title = tr("自主程度", "How freely agents work"),
                endActions = { Value(if (autonomy == "careful") tr("谨慎", "Careful") else tr("放手跑", "Hands-off")) }, onClick = { sheet = true })
            ArrowPreference(title = tr("应用规则", "App rules"), summary = tr("禁止、只读或每次询问", "Blocked, read-only or ask every time"),
                endActions = { Value(if (rules.isEmpty()) tr("无", "None") else tr("${rules.size} 条", "${rules.size}")) }, onClick = { nav.go(Route.Rules) })
        }
        Group(tr("副屏", "Background screen")) {
            SwitchPreference(checked = s.showPreview, onCheckedChange = { BackgroundOperation.setShowPreview(it); if (it) Overlay.refresh() },
                title = tr("显示悬浮窗", "Floating window"), summary = tr("有副屏时显示在最上层", "On top while there is a screen"))
            val idleSave by com.kilon.agentvscreen.core.VScreenStore.idlePowerSave.collectAsState()
            SwitchPreference(checked = idleSave, onCheckedChange = { BackgroundOperation.setIdlePowerSave(it) },
                title = tr("空闲省电", "Idle power saving"), summary = tr("一轮结束后暂停渲染", "Pause rendering between turns"))
            var mute by remember { mutableStateOf(com.kilon.agentvscreen.core.VScreenMute.enabled) }
            SwitchPreference(checked = mute, onCheckedChange = { mute = it; com.kilon.agentvscreen.core.VScreenMute.enabled = it },
                title = tr("副屏静音", "Mute the screen"), summary = tr("副屏里的应用不出声", "Its apps make no sound"))
            val opts = listOf(1, 3, 5, 10, 30)
            var quiet by remember { mutableIntStateOf(ApiConfig.quietMinutes) }
            OverlayDropdownPreference(items = opts.map { tr("$it 分钟", "$it min") }, selectedIndex = opts.indexOf(quiet).coerceAtLeast(0),
                title = tr("一轮的长度", "Turn length"), summary = tr("agent 安静多久算这一轮结束", "How long the agent stays quiet before its turn ends"),
                onSelectedIndexChange = { i -> ApiConfig.quietMinutes = opts[i]; quiet = opts[i] })
        }
        Group(tr("识别和记录", "Reading and recording")) {
            SwitchPreference(checked = s.accurateOcr, onCheckedChange = { BackgroundOperation.setAccurateOcr(it) },
                title = tr("精确文字识别", "Accurate text recognition"), summary = tr("PP-OCRv6，在本机运行", "PP-OCRv6, on this phone"))
            SwitchPreference(checked = s.recording, onCheckedChange = { BackgroundOperation.setRecording(it) },
                title = tr("后台录制", "Background recording"), summary = tr("回看刚才发生了什么时用", "Used to replay what just happened"))
        }
        Group(tr("通用", "General")) {
            val names = listOf(tr("跟随系统", "System"), "中文", "English")
            OverlayDropdownPreference(items = names, selectedIndex = I18n.Lang.entries.indexOf(I18n.choice), title = "语言 / Language",
                onSelectedIndexChange = { i ->
                    if (I18n.Lang.entries[i] != I18n.choice) {
                        I18n.choice = I18n.Lang.entries[i]
                        Overlay.refresh()
                        if (HostService.isAlive) HostService.start(ctx)
                        host.recreateForLanguage()
                    }
                })
            val newer by Updates.newer.collectAsState()
            val checking by Updates.checking.collectAsState()
            ArrowPreference(title = tr("检查更新", "Check for updates"), endActions = {
                Value(when { checking -> tr("检查中…", "Checking…"); newer != null -> tr("有新版本 ${newer!!.version}", "New: ${newer!!.version}"); else -> BuildConfig.VERSION_NAME },
                    if (newer != null) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions)
            }, onClick = { if (!checking) scope.launch { update = withContext(Dispatchers.IO) { Updates.check(ctx) } } })
            ArrowPreference(title = tr("事件广播", "Event broadcast"), endActions = { Value(ApiConfig.eventTarget ?: tr("不广播", "Off")) }, onClick = { eventDialog = true })
            ArrowPreference(title = tr("首次配置", "Setup"), endActions = {
                Value(if (app.items.isEmpty()) "…" else if (app.allOk) tr("已就绪", "Ready") else tr("还差 ${app.left} 项", "${app.left} left"),
                    if (app.items.isNotEmpty() && !app.allOk) Palette.stopped else MiuixTheme.colorScheme.onSurfaceVariantActions)
            }, onClick = { nav.go(Route.Setup) })
            ArrowPreference(title = tr("诊断", "Diagnostics"), onClick = { nav.go(Route.Diag) })
            ArrowPreference(title = tr("帮助", "Help"), summary = tr("每个设置是什么意思", "What each setting means"), onClick = { nav.go(Route.Help) })
        }
        Text("Agent-VScreen ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    }

    OverlayBottomSheet(show = sheet, title = tr("agent 能自己做多少", "How freely agents work"), onDismissRequest = { sheet = false }) {
        Column(Modifier.padding(bottom = 24.dp)) {
            listOf(
                Triple("auto", tr("放手跑（推荐）", "Hands-off (recommended)"), tr("只有付钱的那一步问你", "Only the paying step asks you")),
                Triple("careful", tr("谨慎", "Careful"), tr("购买、删除、卸载等也先问你", "Buying, deleting, uninstalling also ask first")),
            ).forEach { (key, title, sub) ->
                RadioButtonPreference(title = title, summary = sub, selected = autonomy == key, onClick = {
                    autonomy = key; ApiConfig.autonomy = key; Audit.security("autonomy", key); sheet = false
                })
            }
        }
    }
    if (eventDialog) {
        var target by remember { mutableStateOf(ApiConfig.eventTarget.orEmpty()) }
        GlassDialog(
            onDismissRequest = { eventDialog = false },
            title = { DialogTitle(tr("事件广播", "Event broadcast")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("盯守看到条件时，事件默认只放进队列。填一个应用的包名，事件会另外广播给它；留空就不广播。", "When a watch sees its condition, the event is only queued by default. With a package here it is also broadcast to that app; empty means none."),
                        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    TextField(value = target, onValueChange = { target = it.trim() }, label = tr("包名", "Package"), useLabelAsPlaceholder = true, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { LinkButton(onClick = { ApiConfig.eventTarget = target.ifEmpty { null }; eventDialog = false; app.bump() }) { Text(tr("保存", "Save")) } },
            dismissButton = { LinkButton(onClick = { eventDialog = false }) { Text(tr("取消", "Cancel")) } },
        )
    }
    update?.let { u -> UpdateDialog(u) { update = null } }
}

/** What a check found: the new version with its notes and a download, already the newest, or why it failed. */
@Composable
private fun UpdateDialog(u: Updates.Outcome, close: () -> Unit) {
    val ctx = LocalContext.current
    fun view(url: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; close() }
    when (u) {
        is Updates.Outcome.Newer -> GlassDialog(
            onDismissRequest = close,
            title = { DialogTitle(tr("新版本 ${u.r.version}", "New version ${u.r.version}")) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("现在是 ${BuildConfig.VERSION_NAME}", "You have ${BuildConfig.VERSION_NAME}"), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    if (u.r.notes.isNotBlank()) Text(u.r.notes.take(3000), fontSize = 13.sp, lineHeight = 19.sp)
                }
            },
            confirmButton = { LinkButton(onClick = { view(u.r.apk ?: u.r.page) }) { Text(if (u.r.apk != null) tr("下载 APK", "Download APK") else tr("打开发布页", "Open the release")) } },
            dismissButton = { LinkButton(onClick = close) { Text(tr("稍后", "Later")) } },
        )
        is Updates.Outcome.Latest -> GlassDialog(
            onDismissRequest = close,
            title = { DialogTitle(tr("已是最新", "Up to date")) },
            text = { Text(tr("现在是 ${BuildConfig.VERSION_NAME}", "You have ${BuildConfig.VERSION_NAME}") + (u.newest?.let { tr("，GitHub 上最新的发布是 $it", "; the newest release on GitHub is $it") } ?: "")) },
            confirmButton = { LinkButton(onClick = close) { Text(tr("好", "OK")) } },
        )
        is Updates.Outcome.Failed -> GlassDialog(
            onDismissRequest = close,
            title = { DialogTitle(tr("没查到", "Could not check")) },
            text = { Text(u.why) },
            confirmButton = { LinkButton(onClick = { view(Updates.PAGE) }) { Text(tr("打开发布页", "Open releases")) } },
            dismissButton = { LinkButton(onClick = close) { Text(tr("关闭", "Close")) } },
        )
    }
}

/** Per-app limits: blocked, read-only, ask every time. */
@Composable
fun RulesPage() {
    MiuixPage(tr("应用规则", "App rules")) {
        Group(padded = true) { VScreenAppRules() }
        Footnote(tr("只在后台副屏里生效，不影响你自己用。", "Applies only to the background screen, not to your own use."))
    }
}

/** The main token (show, copy, renew) and one token per agent. */
@Composable
fun TokensPage() {
    val host = LocalHost.current
    var token by remember { mutableStateOf(ApiConfig.token) }
    var show by remember { mutableStateOf(false) }
    var askRenew by remember { mutableStateOf(false) }
    MiuixPage(tr("令牌", "Tokens")) {
        Group(tr("主令牌", "Main token")) {
            BasicComponent(
                title = if (show) token else "•".repeat(12) + token.takeLast(4),
                summary = tr("只给你信任的 agent", "Only for agents you trust"),
                endActions = {
                    IconButton(onClick = { show = !show }) { Icon(if (show) MiuixIcons.Hide else MiuixIcons.Show, tr("显示令牌", "Show token")) }
                    IconButton(onClick = { host.copy(tr("令牌", "Token"), token) }) { Icon(MiuixIcons.Copy, tr("复制令牌", "Copy token")) }
                    IconButton(onClick = { askRenew = true }) { Icon(MiuixIcons.Refresh, tr("换一个令牌", "New token")) }
                },
            )
        }
        SmallTitle(tr("每个 agent 一个令牌", "One token per agent"))
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TokensPanel(ApiConfig.baseUrl) { l, t -> host.copy(l, t) }
        }
    }
    if (askRenew) GlassDialog(
        onDismissRequest = { askRenew = false },
        title = { DialogTitle(tr("换一个主令牌？", "Make a new main token?")) },
        text = { Text(tr("旧的立即失效，用它的 agent 都要重新接入。", "The old one stops working at once; every agent using it has to connect again.")) },
        confirmButton = { LinkButton(onClick = { ApiConfig.resetToken(); token = ApiConfig.token; askRenew = false; host.say(tr("已换新令牌", "New token made")) }) { Text(tr("换新的", "Renew")) } },
        dismissButton = { LinkButton(onClick = { askRenew = false }) { Text(tr("取消", "Cancel")) } },
    )
}

/** Every explanation, one group per setting: what the rows only name. */
@Composable
fun HelpPage() {
    MiuixPage(tr("帮助", "Help")) {
        listOf(
            tr("常驻、收起和禁止", "Always on, hidden and blocked") to ServiceControl.help(),
            tr("agent 在哪", "Where the agent runs") to H.where,
            tr("接入方式", "How it connects") to H.how,
            tr("令牌", "Tokens") to (H.token + "\n\n" + H.tokens),
            tr("自主程度", "How freely agents work") to H.autonomy,
            tr("应用规则", "App rules") to H.rules,
            tr("悬浮窗", "Floating window") to H.window,
            tr("空闲省电", "Idle power saving") to H.idle,
            tr("副屏静音", "Mute the screen") to H.mute,
            tr("一轮的长度", "Turn length") to H.turn,
            tr("定时预约", "Schedules") to H.schedule,
            tr("示教", "Lessons") to H.lessons,
            tr("操作日志", "Activity log") to H.audit,
            tr("事件广播", "Event broadcast") to H.events,
            tr("首次配置", "Setup") to H.setup,
            tr("诊断", "Diagnostics") to H.diag,
        ).forEach { (t, body) ->
            Group(t, padded = true) { SelectionContainer { Text(body, fontSize = 14.sp, lineHeight = 22.sp) } }
        }
    }
}

/** A grey note under a group. */
@Composable
fun Footnote(t: String) = Text(t, fontSize = 12.sp, lineHeight = 17.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))

/** The long words. 1.4: all of them on the Help page (and nowhere else), one paragraph per setting. */
internal object H {
    val where get() = tr("同一台手机上的 agent（Termux、Minis、Operit 等）直接连本机 127.0.0.1:${ApiConfig.port}，什么都不用设。\n\n电脑、云端或另一台手机上的 agent 通过 Tailscale 连进来：只有你自己 Tailscale 网络里的设备能连，走加密隧道，局域网和公网都连不到，令牌照样要。“内置”不用装 Tailscale App，“外部 App”用这台手机上已装的 Tailscale。",
        "Agents on this phone (Termux, Minis, Operit, ...) connect to 127.0.0.1:${ApiConfig.port} directly; nothing to set up.\n\nAgents on a computer, in the cloud or on another phone connect through Tailscale: only devices in your own Tailscale network can, through an encrypted tunnel; the local network and the internet cannot, and the token is still required. \"Built-in\" needs no Tailscale app; \"External app\" uses the Tailscale app already on this phone.")
    val how get() = tr("命令行：带终端的 agent。把“给 agent 的话”发给它，它会装好 vscreen 命令，并把“我能在手机上后台操作应用”记进长期记忆，以后的新对话里也会用。只给安装命令的话，大多数 agent 装完就忘了。\n\nMCP：支持 MCP 的 agent。发给它的话里有 MCP 配置（streamable HTTP，每条命令一个工具，截图附在结果里）和能力说明。\n\nHTTP：自己写调用代码的 agent 应用。返回 JSON（ok、code、next、text、images、steps、screen、notes），完整说明在 GET /v2/help。\n\n能力说明也可以直接贴进 agent 的“自定义指令”或“记忆”设置。",
        "Shell: agents with a terminal. Send them the message: it installs the vscreen command and has the agent save \"I can operate apps on the phone in the background\" in its long-term memory, so new conversations use it too. With only the install command, most agents forget it.\n\nMCP: agents with MCP. The message has the MCP config (streamable HTTP, one tool per command, screenshots attached) and the ability note.\n\nHTTP: agent apps that write their own calls. Replies are JSON (ok, code, next, text, images, steps, screen, notes); full reference at GET /v2/help.\n\nYou can also paste the ability note into the agent's custom instructions or memory setting.")
    val token get() = tr("令牌只给你信任的 agent：拿到它就能在副屏上操作你的应用。服务只接受本机（和打开远程时你 Tailscale 网络里的设备），网页调用一律拒绝。换新令牌后旧的立即失效，各 agent 要重新接入。",
        "Give the token only to agents you trust: with it they can operate your apps on the screen. Only this phone (and, with remote access on, devices in your Tailscale network) can connect; calls from web pages are always refused. A new token stops the old one at once; every agent has to connect again.")
    val autonomy get() = tr("放手跑：agent 自己做完整件事，只有真正付钱的那一步（支付、付款、提交订单、转账、充值）会在手机上问你。\n\n谨慎：除了付钱，去购买的步骤、删除和卸载这类不可撤销的按钮、强制结束应用、把你正在用的应用接到后台，也都先问你。\n\n两档都会：每一步记进操作日志；你设为“每次询问”的应用一定先问；悬浮窗上随时可以停止或接管。没答复的确认 60 秒后这一步不做，agent 会来找你。",
        "Hands-off: the agent does the whole job; only the step that actually spends money (pay, place order, transfer, top up) asks you on the phone.\n\nCareful: besides paying, steps toward a purchase, irreversible buttons such as delete or uninstall, force-stopping an app and taking the app you are using to the background also ask you first.\n\nEither way: every step is logged; apps set to \"ask every time\" always ask; you can stop or take over from the floating window at any time. A confirmation left unanswered for 60 s skips that step and the agent comes to you.")
    val rules get() = tr("禁止操作：AI 不能在后台打开、读屏或操作这个应用。\n只读：AI 可以打开、看和滚动，但不能点击、输入或滑动。\n每次询问：AI 要点击或输入前，手机上先弹确认，你允许后 5 分钟内不再问。\n\n只在后台副屏里生效，不影响你自己用。",
        "Blocked: the AI cannot open, read or operate the app in the background.\nRead-only: the AI can open, look and scroll, but not tap, type or swipe.\nAsk every time: before the AI taps or types, the phone asks you; once allowed it does not ask again for 5 minutes.\n\nApplies only to the background screen, not to your own use.")
    val tokens get() = tr("给每个 agent 单独发一个令牌：可以只给“只看”或“可操作”的权限，单独撤销，日志里也分得清是谁做的。不同令牌的 agent 互相抢不了对方的会话。\n\n免确认：这段时间里这个令牌连付款也不再问你（仍会记进日志）。只在你盯着它付款时开。",
        "Give each agent its own token: it can be limited to \"look only\" or \"operate\", revoked alone, and the log shows who did what. Agents with different tokens cannot take each other's sessions.\n\nNo confirmations: during this period this token is not asked even about payments (still logged). Turn it on only while you watch it pay.")
    val window get() = tr("有副屏时在所有应用上方显示。拖动移动，双指缩放，推到屏幕边上收成胶囊，长按画面 1 秒接管。agent 在等你回答时，胶囊会变成紫色的问号。",
        "Shown above all apps while there is a screen. Drag to move, pinch to resize, push to an edge to fold it into a tab, hold the picture 1 s to take over. While an agent waits for your answer, the tab turns into a violet question mark.")
    val idle get() = tr("一轮结束后暂停副屏渲染，页面保留，下一条命令自动恢复。个别应用恢复时会重新加载，遇到可以关掉。", "Pauses rendering after a turn ends; the page is kept and the next command resumes it. A few apps reload on resume: turn this off if you see that.")
    val mute get() = tr("副屏里的应用不从手机放声音（视频、直播）。应用移到主屏、副屏释放时恢复声音；App 异常退出后，下次连接时先恢复。", "Apps on the background screen make no sound on the phone (videos, live streams). Their sound comes back when they move to the main screen or the screen is released; after a crash it is restored on the next connection.")
    val turn get() = tr("agent 安静多久算这一轮结束：之后副屏省电暂停，10 分钟后释放。agent 也可以用 vscreen done 马上结束。", "How long the agent stays quiet before its turn ends: the screen then pauses to save power and is released after 10 minutes. The agent can also end at once with vscreen done.")
    val lessons get() = tr("你在副屏上演示过、保存下来的操作。agent 用 act \"use 名字\" 照着做一遍；点开能看到每一步，用不到的可以删掉。\n\n示教在全屏页录：agent 用 teach 请你演示，或者你接管后自己点录制。",
        "What you showed on the background screen and saved. An agent runs one with act \"use name\"; open one to see its steps, delete the ones you no longer need.\n\nLessons are recorded on the full screen page: an agent asks with teach, or you take over and tap record.")
    val schedule get() = tr("agent 的定时任务要用手机时，可以用 vscreen schedule 预约时间。到点前 3 分钟这里会拉起服务并检查，没准备好会通知你。\n\n服务暂停时，下一个预约到点会自动打开；完全关闭时，到点不会打开，只通知你。\n\n点开一条可以像闹钟一样改时间、重复和保持时长；关掉开关会保留这条预约，但到点不触发。",
        "An agent whose timed task uses the phone can book the time with vscreen schedule. 3 minutes before, the service is started and checked here, and you are notified if it is not ready.\n\nWhile the service is paused, the next booking turns it on; while it is off, a booking only notifies you.\n\nOpen one to change its time, repeat and length like an alarm; switching it off keeps it but it does not fire.")
    val audit get() = tr("每条命令都记在这里：谁（哪个令牌、哪台设备）、什么时候、做了什么、结果。输入的文字只记字数。", "Every command is recorded here: who (which token, which device), when, what, and the result. Typed text is recorded as a length only.")
    val events get() = tr("盯守看到条件时的事件默认只放进队列（agent 用 vscreen events 取）。填了包名才会另外广播给那一个应用，别的应用收不到。", "When a watch sees its condition the event is only queued by default (agents collect it with vscreen events). With a package here it is also broadcast to that one app; no other app receives it.")
    val setup get() = tr("只用 Shizuku 这一条可共享的特权通道：不占用 adb，不改无线调试，不重启 adbd。每一项都只把本应用“加进去”，不动别的应用的设置。你的 agent 自己的 adb / Shizuku 照常用。", "Uses only Shizuku, a privilege channel that can be shared: adb is not taken, wireless debugging is not changed, adbd is not restarted. Each item only adds this app and leaves other apps' settings alone. Your agent's own adb / Shizuku keep working.")
    val diag get() = tr("出问题时把诊断信息发给开发者：版本、配置状态和最近日志（不含令牌、不含你输入的字）。", "When something goes wrong, send this to the developer: version, setup state and recent log (no token, nothing you typed).")
}

