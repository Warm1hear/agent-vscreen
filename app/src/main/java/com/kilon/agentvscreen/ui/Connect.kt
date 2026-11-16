package com.kilon.agentvscreen.ui

import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.api.AgentGuide
import com.kilon.agentvscreen.api.AgentLedger
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.host.HostService
import com.kilon.agentvscreen.host.ServiceControl
import com.kilon.agentvscreen.host.Tailnet
import com.kilon.agentvscreen.host.Tailnet.Phase as TsPhase
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 1.4 接入 agent: two questions (where the agent runs, how it connects), one button that copies the message,
 * then the raw pieces for people who want them. 远程连接 is its own page: the Tailscale phase, its one action,
 * and the advanced options in a sheet. No toasts: feedback goes through the snackbar (host.say).
 */

/** The address an agent on another device uses; kept through short gaps so what the user copies does not flip back. */
@Composable
private fun rememberRemoteBase(mode: Tailnet.Mode): String? {
    val ts by Tailnet.status.collectAsState()
    var sysAddr by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { HostService.server?.tailAddress?.let { sysAddr = it }; delay(3_000) } }
    val live = when (mode) {
        Tailnet.Mode.BUILTIN -> ts.ip?.takeIf { ts.loggedIn }
        Tailnet.Mode.SYSTEM -> sysAddr
        else -> null
    }
    var last by remember(mode) { mutableStateOf<String?>(null) }
    if (live != null && live != last) last = live
    return (live ?: last)?.let { "http://$it:${ApiConfig.port}" }
}

@Composable
fun ConnectPage() {
    val host = LocalHost.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
    var where by rememberSaveable { mutableIntStateOf(com.kilon.agentvscreen.host.Access.lastWay()) }
    var how by rememberSaveable { mutableIntStateOf(0) }
    var raw by remember { mutableStateOf(false) }
    val ledger by AgentLedger.snap.collectAsState()
    val ts by Tailnet.status.collectAsState()
    val remoteBase = rememberRemoteBase(ApiConfig.tailnetMode)
    val remoteReady = where == 1 && remoteBase != null
    val base = if (remoteReady) remoteBase!! else ApiConfig.baseUrl
    val canCopy = where == 0 || remoteReady

    MiuixPage(tr("接入 agent", "Connect an agent")) {
        if (ledger.installed) Group(tr("已经接入的", "Already connected")) { AgentMemoryRow(ledger) }
        Group(tr("1 · agent 在哪", "1 · Where the agent runs")) {
            RadioButtonPreference(title = tr("这台手机上", "On this phone"), summary = tr("Termux、Operit 等，直接连 127.0.0.1:${ApiConfig.port}", "Termux, Operit…, straight to 127.0.0.1:${ApiConfig.port}"),
                selected = where == 0, onClick = { where = 0 })
            RadioButtonPreference(title = tr("其它设备上", "Another device"), summary = tr("电脑、云端或另一台手机，经 Tailscale", "A computer, the cloud or another phone, through Tailscale"),
                selected = where == 1, onClick = { where = 1 })
            if (where == 1) ArrowPreference(title = tr("远程连接", "Remote access"),
                summary = remoteBase ?: tr("先连好 Tailscale，才能复制给其它设备的内容", "Connect Tailscale first to copy what the other device needs"),
                endActions = { Value(remember(ts) { Tailnet.line(ctx) }, if (remoteReady) Palette.takeover else Palette.stopped) },
                onClick = { nav.go(Route.Remote) })
        }
        Group(tr("2 · 怎么连", "2 · How it connects")) {
            listOf(
                Triple(tr("命令行", "Shell"), tr("带终端的 agent：装好 vscreen，并记住这个能力", "Agents with a terminal: installs vscreen and remembers it"), 0),
                Triple("MCP", tr("支持 MCP 的 agent：MCP 配置和能力说明", "Agents with MCP: the MCP config and the ability note"), 1),
                Triple("HTTP", tr("自己写调用的 agent：地址、令牌和说明", "Apps that write their own calls: address, token and notes"), 2),
            ).forEach { (t, sub, i) -> RadioButtonPreference(title = t, summary = sub, selected = how == i, onClick = { how = i; raw = false }) }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            PrimaryButton(onClick = {
                host.copy(tr("给 agent 的话", "Message for your agent"), when (how) { 1 -> AgentGuide.onboardMcp(base); 2 -> AgentGuide.onboardHttp(base); else -> AgentGuide.onboard(base) })
            }, enabled = canCopy, modifier = Modifier.fillMaxWidth(), color = MiuixTheme.colorScheme.primary) {
                Text(tr("复制给 agent 的话", "Copy message for your agent"), color = MiuixTheme.colorScheme.onPrimary)
            }
        }
        Footnote(tr("把这段话发给 agent，它会自己装好、记住，并告诉你记在了哪里。", "Send this to your agent: it sets itself up, remembers it and tells you where it saved it."))
        Group(tr("单独的部分", "The pieces")) {
            if (how != 2) ArrowPreference(title = if (how == 0) tr("安装命令", "Install command") else tr("MCP 配置", "MCP config"),
                endActions = { Value(if (raw) tr("收起", "Hide") else tr("查看", "Show")) }, enabled = canCopy, onClick = { raw = !raw })
            AnimatedVisibility(raw && canCopy && how != 2) {
                val text = if (how == 0) ApiConfig.installLine(base) else ApiConfig.mcpJson(base)
                Code(text) { host.copy(if (how == 0) tr("安装命令", "Install command") else tr("MCP 配置", "MCP config"), text) }
            }
            BasicComponent(title = tr("能力说明", "Ability note"), summary = tr("贴进 agent 的自定义指令或记忆", "Paste into the agent's custom instructions or memory"),
                endActions = { IconButton(onClick = { host.copy(tr("能力说明", "Ability note"), when (how) { 1 -> AgentGuide.memoryMcp(); 2 -> AgentGuide.memoryHttp(base); else -> AgentGuide.memory(base) }) }, enabled = canCopy) {
                    Icon(MiuixIcons.Copy, tr("复制", "Copy"))
                } })
            ArrowPreference(title = tr("令牌", "Token"), onClick = { nav.go(Route.Tokens) })
        }
    }
}

/** Code in a dark monospace box with its own copy button. */
@Composable
fun Code(text: String, onCopy: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF16131F))) {
        SelectionContainer {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 17.sp, color = Color(0xFFE8E4F2), softWrap = false,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 52.dp))
        }
        IconButton(onClick = onCopy, modifier = Modifier.align(Alignment.TopEnd)) { Icon(MiuixIcons.Copy, tr("复制", "Copy"), tint = Color(0xFFE8E4F2)) }
    }
}

/** A tinted note inside a group: a state in a word plus what it means. */
@Composable
fun InfoCard(color: Color, title: String, body: String = "") {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.12f).compositeOver(MiuixTheme.colorScheme.surfaceContainer)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) { Dot(color); Text("  $title", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        if (body.isNotEmpty()) SelectionContainer { Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
    }
}

/** One action under a card: a full-width button, primary or plain. */
@Composable
private fun CardAction(text: String, primary: Boolean = true, onClick: () -> Unit) =
    PrimaryButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = if (primary) MiuixTheme.colorScheme.primary else null) {
        Text(text, color = if (primary) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface)
    }

/** 1.1.6: did the agent keep VScreen in mind? Facts only (see AgentLedger). */
@Composable
private fun AgentMemoryRow(l: AgentLedger.Snapshot) {
    val fmt = remember(I18n.en) { java.text.SimpleDateFormat(tr("M月d日 HH:mm", "MMM d HH:mm"), if (I18n.en) java.util.Locale.US else java.util.Locale.CHINA) }
    fun at(t: Long) = fmt.format(java.util.Date(t))
    val (color, title, body) = when {
        l.cameBack -> Triple(Palette.takeover, tr("agent 记住了", "The agent remembers"),
            tr("安装后，agent 在隔了一段时间的新对话里又主动用了 ${l.comebacks} 次（最近 ${at(l.lastComebackAt)}）。", "Since installing, the agent came back on its own in a later conversation ${l.comebacks} times (last ${at(l.lastComebackAt)}). ") +
                (l.rememberedWhere?.let { tr("它说存在了「$it」。", "It says it saved it in \"$it\".") } ?: ""))
        l.remembered -> Triple(Palette.standby, tr("agent 说已记住", "The agent says it saved it"),
            tr("存在了「${l.rememberedWhere}」（${at(l.rememberedAt)}）。等它在新对话里主动用起来，这里会变成“记住了”。", "Saved in \"${l.rememberedWhere}\" (${at(l.rememberedAt)}). Once it uses vscreen on its own in a new conversation, this turns into \"remembers\"."))
        else -> Triple(Palette.stopped, tr("agent 还没确认记住", "The agent has not confirmed it saved it"),
            tr("${at(l.installAt)} 装好了 vscreen，但 agent 没有报告把说明存在哪。新对话里它可能不知道自己有这个能力：再把“给 agent 的话”发给它一次，或把能力说明放进它的自定义指令。",
                "vscreen was installed ${at(l.installAt)}, but the agent has not said where it saved the note. New conversations may not know they have this ability: send the message for your agent again, or put the ability note in its custom instructions."))
    }
    val ack = remember(l) { AgentLedger.ackVersion("main") }
    val stale = if (ack >= BuildConfig.VERSION_CODE) "" else "\n" + (
        if (ack <= 0) tr("还不知道它记下的说明是哪个版本的；它下次用 vscreen 时会收到更新提醒。", "Not known which version its note is for; its next use of vscreen brings an update notice.")
        else tr("它记下的说明是 ${com.kilon.agentvscreen.api.AgentNews.nameOf(ack)} 的；下次用 vscreen 时会收到这一版的变化，并被请求更新说明。", "Its note is from ${com.kilon.agentvscreen.api.AgentNews.nameOf(ack)}; its next use of vscreen brings this version's changes and a request to refresh the note."))
    InfoCard(color, title, body + stale)
}

/** 远程连接: off / built-in Tailscale / the Tailscale app on this phone, and what state it is in. */
@Composable
fun RemotePage() {
    val ctx = LocalContext.current
    val host = LocalHost.current
    val ts by Tailnet.status.collectAsState()
    var mode by remember { mutableStateOf(ApiConfig.tailnetMode) }
    var advanced by remember { mutableStateOf(false) }
    val remoteBase = rememberRemoteBase(mode)
    val M = Tailnet.Mode.entries
    fun open(url: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    MiuixPage(tr("远程连接", "Remote access"), subtitle = tr("只限你自己的 Tailscale 网络", "Only from your own Tailscale network")) {
        Group(tr("方式", "Mode")) {
            listOf(
                tr("关", "Off") to tr("只有这台手机上的 agent 能连", "Only agents on this phone can connect"),
                tr("内置 Tailscale", "Built-in Tailscale") to tr("不用装 Tailscale App", "No Tailscale app needed"),
                tr("外部 Tailscale App", "External Tailscale app") to tr("用这台手机上已经装好的 Tailscale", "Uses the Tailscale app already on this phone"),
            ).forEachIndexed { i, (t, sub) ->
                RadioButtonPreference(title = t, summary = sub, selected = mode == M[i], onClick = {
                    if (mode != M[i]) {
                        mode = M[i]; ApiConfig.tailnetMode = M[i]
                        HostService.server?.start(ApiConfig.port)
                        Tailnet.sync(ctx.applicationContext)
                    }
                })
            }
        }
        when (mode) {
            Tailnet.Mode.BUILTIN -> Group(tr("状态", "State")) { BuiltinState(ts, host, ::open) }
            Tailnet.Mode.SYSTEM -> Group(tr("状态", "State")) {
                if (remoteBase == null) InfoCard(Palette.stopped, tr("还没找到 Tailscale 地址", "No Tailscale address yet"),
                    tr("在这台手机上装好并登录 Tailscale App，打开连接后 20 秒内自动生效。不想装 App 就选“内置”。", "Install and log in to the Tailscale app on this phone; once it is connected this takes effect within 20 seconds. To skip the app, choose \"Built-in\"."))
                else InfoCard(Palette.takeover, tr("已连上 Tailscale App", "Connected through the Tailscale app"), tr("地址", "Address") + " $remoteBase")
            }
            else -> {}
        }
        if (mode == Tailnet.Mode.BUILTIN && Tailnet.available(ctx)) Group {
            ArrowPreference(title = tr("高级选项", "Advanced"), summary = tr("自建服务器（Headscale）、auth key", "Own server (Headscale), auth key"), onClick = { advanced = true })
        }
        if (mode != Tailnet.Mode.OFF) Group(tr("安全", "Safety"), padded = true) { RemoteSafety() }
    }

    OverlayBottomSheet(show = advanced, title = tr("高级选项", "Advanced"), onDismissRequest = { advanced = false }) {
        var key by remember { mutableStateOf("") }
        var ctl by remember { mutableStateOf(ApiConfig.tailnetControlUrl ?: "") }
        val problem = ApiConfig.controlUrlProblem(ctl)
        Column(Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(value = ctl, onValueChange = { ctl = it }, label = tr("控制服务器（留空用 Tailscale）", "Control server (blank = Tailscale)"), useLabelAsPlaceholder = true, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(problem ?: ApiConfig.normalizeControlUrl(ctl)?.let { tr("将使用 $it", "Will use $it") } ?: tr("使用 Tailscale 官方服务器", "Using Tailscale's own server"),
                fontSize = 12.sp, color = if (problem != null) Palette.stopped else MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 12.dp))
            TextField(value = key, onValueChange = { key = it }, label = tr("auth key（可选，代替网页登录）", "Auth key (optional, instead of the web login)"), useLabelAsPlaceholder = true, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(tr("每个服务器的登录分开保存，换回原来的服务器不用重新登录。内置节点不接管手机上网，所以没有出口节点这一项。", "Each server keeps its own login, so switching back needs no new login. The built-in node does not carry the phone's own traffic, so there is no exit node option."),
                fontSize = 12.sp, lineHeight = 17.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 12.dp))
            PrimaryButton(onClick = {
                if (problem != null) { host.say(problem); return@PrimaryButton }
                val k = key.trim(); val c = ctl
                Thread { Tailnet.configure(ctx.applicationContext, c, k) }.start()
                advanced = false
                host.say(tr("已保存，内置 Tailscale 重新连接中", "Saved, built-in Tailscale is reconnecting"))
            }, modifier = Modifier.fillMaxWidth(), color = MiuixTheme.colorScheme.primary) { Text(tr("保存并重连", "Save and reconnect"), color = MiuixTheme.colorScheme.onPrimary) }
        }
    }
}

/** The built-in node: one phase decides the card (Tailnet.phase), the same word the settings row and agents see. */
@Composable
private fun BuiltinState(ts: Tailnet.Status, host: AppHost, open: (String) -> Unit) {
    val ctx = LocalContext.current
    if (!Tailnet.available(ctx)) { InfoCard(Palette.stopped, tr("这个安装包里没有内置 Tailscale", "This package has no built-in Tailscale"), tr("只支持 arm64 手机。", "Only arm64 phones are supported.")); return }
    val phase = Tailnet.phase(ctx)
    val known = remember(ts.loggedIn, phase) { Tailnet.known(ctx) }
    var wasOut by remember { mutableStateOf(false) }
    LaunchedEffect(phase) {
        if (phase == TsPhase.NEEDS_LOGIN || phase == TsPhase.EXPIRED || phase == TsPhase.NEEDS_APPROVAL) wasOut = true
        if (phase == TsPhase.CONNECTED && wasOut) { wasOut = false; host.say(tr("已连上 Tailscale", "Connected to Tailscale")) }
    }
    when (phase) {
        TsPhase.SERVICE_OFF -> {
            InfoCard(Palette.idle, if (ServiceControl.now(ctx) == ServiceControl.State.BLOCKED) tr("服务已关闭，内置 Tailscale 没在运行", "The service is off; the built-in Tailscale is not running")
                else tr("服务收起了，内置 Tailscale 只在服务醒着时在线", "The service is hidden; the built-in Tailscale is online only while it is awake"),
                (known?.let { tr("登录还在（${it.dnsName ?: it.ip}），醒来后自动连上。", "The login is kept (${it.dnsName ?: it.ip}); it reconnects when awake.") } ?: tr("打开服务后再登录。", "Turn the service on, then log in.")))
            CardAction(tr("打开常驻", "Keep it always on")) { ServiceControl.turnOn(ctx, "tailnet card") }
        }
        TsPhase.CONNECTED -> {
            InfoCard(Palette.takeover, tr("已连上 Tailscale", "Connected to Tailscale"),
                tr("名字", "Name") + " ${ts.dnsName ?: ApiConfig.tailnetHostname}\n" + tr("地址", "Address") + " ${ts.ip}" +
                    (ts.user?.let { "\n" + tr("账号", "Account") + " $it" } ?: "") +
                    (if (ts.ownServer) "\n" + tr("服务器", "Server") + " ${ts.serverName}" else "") +
                    "\n" + tr("在线设备 ${ts.online} 台", "${ts.online} devices online") +
                    (ts.relay?.let { "\n" + tr("中继", "Relay") + " $it" } ?: "") +
                    (if (ts.flaps >= 2) "\n" + tr("10 分钟内断过 ${ts.flaps} 次", "Dropped ${ts.flaps} times in 10 minutes") else "") +
                    (ts.lastPeer?.let { "\n" + tr("最近来访", "Last visitor") + " $it" } ?: ""))
            val days = if (ts.keyExpiry > 0) ((ts.keyExpiry - System.currentTimeMillis()) / 86_400_000L).toInt() else -1
            if (days in 0..14) Footnote(tr("登录 $days 天后过期，到时要重新登录。可以在 Tailscale 控制台给这台设备关掉“密钥过期”。", "The login expires in $days days; then it needs logging in again. You can turn off key expiry for this device in the Tailscale console."))
            CardAction(tr("退出 Tailscale 登录", "Log out of Tailscale"), primary = false) { Tailnet.logout(ctx) }
        }
        TsPhase.NEEDS_APPROVAL -> InfoCard(Palette.standby, tr("等待批准这台设备", "Waiting for this device to be approved"),
            tr("登录已经完成，你的网络要求新设备先批准。在 ${if (ts.ownServer) ts.serverName + " 的管理端" else "Tailscale 控制台（login.tailscale.com/admin/machines）"}里批准“${ApiConfig.tailnetHostname}”，批准后这里自动连上。",
                "The login is done; your network requires new devices to be approved. Approve \"${ApiConfig.tailnetHostname}\" in ${if (ts.ownServer) "the admin of " + ts.serverName else "the Tailscale console (login.tailscale.com/admin/machines)"}; this connects by itself afterwards."))
        TsPhase.NEEDS_LOGIN, TsPhase.EXPIRED -> if (ts.authUrl != null) {
            InfoCard(Palette.standby,
                if (phase == TsPhase.EXPIRED) tr("Tailscale 登录过期了，重新登录一次", "The Tailscale login ran out: log in once more") else tr("登录 Tailscale（只要一次）", "Log in to Tailscale (once)"),
                if (ts.ownServer) tr("打开 ${ts.serverName} 的注册页。Headscale 通常会给出一条注册命令，在服务器上运行它就连上了；也可以在高级选项里填预授权 key。", "Open the registration page of ${ts.serverName}. Headscale usually shows a register command: run it on the server and the node joins. Or put a pre-auth key in Advanced.")
                else tr("用你电脑上 Tailscale 的同一个账号登录，登录完回到这里会自动连上。手机上不方便登录的话，复制链接到电脑上打开。", "Log in with the same account as Tailscale on your computer; back here it connects by itself. If logging in on the phone is awkward, copy the link and open it on the computer."))
            CardAction(if (ts.ownServer) tr("打开注册页", "Open the registration page") else tr("在浏览器里登录", "Log in in the browser")) { open(ts.authUrl!!) }
            CardAction(tr("复制登录链接", "Copy login link"), primary = false) { host.copy(tr("登录链接", "Login link"), ts.authUrl!!) }
        } else {
            val slow = ts.waitingS >= 15 || ts.loginError != null
            InfoCard(if (slow) Palette.stopped else Palette.standby,
                if (ts.loginError != null) tr("连不上控制服务器 ${ts.serverName}", "Cannot reach the control server ${ts.serverName}")
                else if (slow) tr("拿不到登录链接（已等 ${ts.waitingS} 秒）", "No login link yet (waited ${ts.waitingS} s)")
                else tr("正在向 ${ts.serverName} 要登录链接…", "Asking ${ts.serverName} for a login link…"),
                if (!slow) tr("通常几秒内出现。", "It usually appears within seconds.")
                else (ts.loginError?.let { tr("出错原因：", "Error: ") + it.take(240) + "\n" } ?: "") +
                    (if (ts.ownServer) tr("请确认地址和端口跟你在 Tailscale App 里填的“登录服务器”完全一样，并且这台手机现在能打开它（浏览器打开 ${ts.control}/key?v=1 应该能看到一串字）。", "Check that the address and port are exactly your login server in the Tailscale app, and that this phone can open it now (${ts.control}/key?v=1 should show some text in a browser).")
                    else tr("这台手机连不上 Tailscale 的服务器（controlplane.tailscale.com）时会这样：换个网络或打开能访问它的代理后会自动出现。也可以在高级选项里填 auth key。", "This happens when the phone cannot reach Tailscale's server (controlplane.tailscale.com): switch networks or turn on a proxy that reaches it. Or put an auth key in Advanced.")) +
                    (if (ts.loginError == null) ts.health?.let { "\n" + tr("Tailscale 报告：", "Tailscale says: ") + it.take(240) } ?: "" else ""))
            CardAction(tr("重新获取登录链接", "Get a login link again"), primary = false) { Tailnet.login() }
        }
        TsPhase.UNSTABLE -> InfoCard(Palette.standby, tr("Tailscale 断开了，正在重连", "Tailscale dropped, reconnecting"),
            tr("登录还在（${ts.dnsName ?: ts.ip}），但手机现在连不上", "Still logged in (${ts.dnsName ?: ts.ip}), but the phone cannot reach ") +
                (if (!ts.controlOk) tr(" Tailscale 服务器", "the Tailscale server") else tr("中继${ts.relay?.let { " $it" } ?: ""}", "the relay${ts.relay?.let { " $it" } ?: ""}")) +
                tr("，其它设备暂时连不上它（已 ${ts.downS} 秒）。", ", so other devices cannot reach it for now (${ts.downS} s).") +
                (if (ts.flaps >= 2) tr("10 分钟内断了 ${ts.flaps} 次。", " It dropped ${ts.flaps} times in 10 minutes.") else ""))
        TsPhase.ERROR -> InfoCard(Palette.stopped, tr("内置 Tailscale 出错，会自动重试", "Built-in Tailscale failed, retrying automatically"), ts.error ?: ts.state)
        else -> {
            val waited = Tailnet.waitedMs()
            InfoCard(Palette.standby, tr("正在连接 Tailscale…", "Connecting to Tailscale…") + if (waited > Tailnet.SLOW_MS) tr("（已 ${waited / 1000} 秒）", " (${waited / 1000} s)") else "",
                (known?.let { tr("用上次的登录（${it.dnsName ?: it.ip}），不用重新登录。", "Using the saved login (${it.dnsName ?: it.ip}); no new login needed.") } ?: tr("通常几秒钟。", "Usually a few seconds.")) +
                    (if (waited > Tailnet.SLOW_MS) "\n" + tr("还没连上 ${if (ts.loggedIn) "中继" else ts.serverName}", "Not reached ${if (ts.loggedIn) "the relay" else ts.serverName} yet") +
                        ((ts.loginError ?: ts.health)?.let { "：${it.take(160)}" } ?: "") else ""))
        }
    }
    if (Tailnet.vpnTrouble(ctx)) Footnote(Tailnet.vpnAdvice())
    val appVpn = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { appVpn.value = withContext(Dispatchers.IO) { com.kilon.agentvscreen.api.ApiServer.tailnetAddress()?.hostAddress }; delay(5_000) } }
    if (appVpn.value != null) Footnote(tr("这台手机上的 Tailscale App 已经连着（${appVpn.value}）。手机要靠它的出口节点上网的话，App 得一直开着，这时直接选“外部 Tailscale App”更省事。",
        "The Tailscale app on this phone is already connected (${appVpn.value}). If the phone needs its exit node to get online, the app has to stay on anyway, so choosing \"External Tailscale app\" is simpler."))
}
