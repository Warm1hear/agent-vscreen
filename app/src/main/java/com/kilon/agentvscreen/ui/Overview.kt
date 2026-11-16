package com.kilon.agentvscreen.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.TaskLog
import com.kilon.agentvscreen.core.VScreenRules
import com.kilon.agentvscreen.host.HostService
import com.kilon.agentvscreen.host.Readiness
import com.kilon.agentvscreen.host.ServiceControl
import com.kilon.agentvscreen.host.Wake
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 1.4.0-beta3 首页 = 现在 (rainbow approved the prototype). beta2 was a status card and two buttons over an empty half
 * screen. Now the page is built around the background screen, top to bottom in the order the user needs it:
 *  - the title is the state as a sentence ("正在天气里操作", "待命中", "需要你处理"), its line above says who and where;
 *  - questions waiting (only when there are any), sliding in from the top;
 *  - the stage: the live screen with a breathing frame, what the agent does now, take over / stop. It turns into a
 *    fix card when something is broken, and into a three-step guide before any agent was ever connected;
 *  - 接下来: bookings and watches as a row of small cards; 刚才: the last three tasks.
 * The service switch and "block agents" left the page: they sit behind the shield in the top bar.
 */

private var lastFlip = 0L

private enum class Home { ACTING, IDLE, BROKEN, FIRST }

@Composable
fun OverviewTab(bottom: Dp) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val nav = LocalNav.current
    val s by BackgroundOperation.state.collectAsState()
    var beat by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2_000); beat++ } }
    var health by remember { mutableStateOf<List<Readiness.Item>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<TaskLog.Task>>(emptyList()) }
    var used by remember { mutableStateOf(true) }
    LaunchedEffect(beat, app.tick) {
        withContext(Dispatchers.IO) {
            tasks = TaskLog.recent(3); used = TaskLog.everUsed()
            health = runCatching { Readiness.now(ctx) }.getOrDefault(emptyList())
        }
    }
    var shield by remember { mutableStateOf(false) }
    val mode = modeOf(s)
    // What only the user can fix. A stop the user pressed is not "broken": the stage says it and offers to resume.
    val problems = health.filter { it.code != "user_stopped" && (it.blocking || it.code in setOf("no_accessibility", "accessibility_cannot_see_display", "frozen_in_background")) }
    val setupLeft = app.items.isNotEmpty() && !app.allOk
    val home = when {
        problems.isNotEmpty() || setupLeft || app.liveProblem != null -> if (!used && setupLeft) Home.FIRST else Home.BROKEN
        !used && s.displayId == null -> Home.FIRST
        s.displayId != null && !s.idle -> Home.ACTING
        else -> Home.IDLE
    }
    val lastApp = appOf(tasks.firstOrNull()?.pkg).first
    val title = when (home) {
        Home.ACTING -> s.agentTitle ?: (lastApp?.let { tr("正在$it 里操作", "Working in $it") } ?: Palette.word(mode))
        Home.IDLE -> if (s.userStopped) tr("你停止了 AI", "You stopped the AI") else tr("待命中", "Standing by")
        Home.BROKEN -> tr("需要你处理", "Needs you")
        Home.FIRST -> tr("接上你的 agent", "Connect your agent")
    }
    val eyebrow = when (home) {
        Home.ACTING -> Palette.word(mode) + (s.ownerSessionId?.let { " · $it" } ?: "")
        Home.IDLE -> if (app.serviceOn) tr("agent 随时连得上", "Agents can connect any time") else ServiceControl.title(ctx)
        Home.BROKEN -> tr("agent 现在用不了副屏", "Agents cannot use the screen right now")
        Home.FIRST -> tr("还没有 agent 用过", "No agent has used it yet")
    }

    MiuixPage(title, back = false, bottom = bottom, subtitle = eyebrow, actions = {
        IconButton(onClick = { shield = true }, modifier = Modifier.padding(end = 12.dp)) { Icon(Icons.Outlined.Shield, tr("暂停与关闭", "Pause and turn off")) }
    }) {
        // Questions waiting: they slide in from the top and leave to the right when answered.
        val waiting = pendingAsks()
        AnimatedVisibility(waiting.isNotEmpty(), enter = slideInVertically(tween(380)) { -it / 3 } + fadeIn(tween(300)) + expandVertically(tween(380)),
            exit = slideOutHorizontally(tween(280)) { it } + fadeOut(tween(220)) + shrinkVertically(tween(320, delayMillis = 160))) {
            waiting.firstOrNull()?.let { a -> Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { HomeAskCard(a, waiting.size) } }
        }

        AnimatedContent(home, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(200)) }, label = "stage") { h ->
            Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                when (h) {
                    Home.ACTING, Home.IDLE -> Stage(s, mode, tasks.firstOrNull())
                    Home.BROKEN -> FixCard(problems, setupLeft)
                    Home.FIRST -> FirstUse(setupLeft)
                }
            }
        }

        if (home != Home.FIRST) {
            NextUp(beat, app.tick)
            if (tasks.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallTitle(tr("刚才", "Just now"), modifier = Modifier.weight(1f))
                    LinkButton(onClick = { nav.tab(1) }) { Text(tr("全部记录", "All records")) }
                }
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                    tasks.forEach { t -> TaskRow(t) { nav.go(Route.Task(t.id)) } }
                }
            }
        }
    }
    ShieldSheet(shield) { shield = false }
}

/** The stage: the screen with its breathing frame, what is happening, and the two things the user may do. */
@Composable
private fun Stage(s: BackgroundOperation.State, mode: Mode, last: TaskLog.Task?) {
    val ctx = LocalContext.current
    val c = MiuixTheme.colorScheme
    val live = s.displayId != null && !s.idle
    val ratio = if (s.screenHeight > 0) s.screenWidth.toFloat() / s.screenHeight else 0.45f
    val (app, icon) = appOf(last?.pkg)
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Row {
            val open = { ctx.startActivity(Intent(ctx, FullscreenActivity::class.java)) }
            Box(Modifier.width(112.dp).aspectRatio(ratio).glowBorder(if (live) mode else Mode.IDLE, 16.dp, pad = 5.dp).clickable(enabled = s.displayId != null) { open() }) {
                if (s.displayId != null) ScreenThumb(s.ownerSessionId ?: "default", Modifier.fillMaxSize(), 16.dp)
                else TaskFrame(last?.frames?.lastOrNull(), Modifier.fillMaxSize(), 16.dp)
                if (!live) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.35f)))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).height(112.dp / ratio), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) { AppBadge(icon, 22.dp); Spacer(Modifier.width(8.dp)) }
                    Text(if (live) (app ?: tr("副屏", "Screen")) + " · ${s.screenWidth}×${s.screenHeight}" else tr("副屏在休息", "Screen resting"),
                        fontSize = 13.sp, color = c.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                AnimatedContent(if (live) (s.action ?: Palette.word(mode)) else tr("agent 下次用时自动亮起", "Lights up when an agent next uses it"),
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "step") { t ->
                    Text(t, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Text(when {
                    live && last?.running == true -> tr("第 ${last.commands} 步 · 已用 ${took(last)}", "Step ${last.commands} · ${took(last)}")
                    last != null -> tr("上次：", "Last: ") + taskTitle(last, app) + " · " + taskOutcome(last).first
                    else -> ""
                }, fontSize = 12.sp, color = c.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        s.userStopped -> RoundAction(Icons.Filled.PlayArrow, tr("允许继续", "Resume"), c.primary) { BackgroundOperation.userResume() }
                        live -> {
                            RoundAction(Icons.Filled.PanTool, tr("接管", "Take over"), c.primary) {
                                s.ownerSessionId?.let { BackgroundOperation.beginTakeover(it) }; open()
                            }
                            RoundAction(Icons.Filled.Stop, tr("停止", "Stop"), Palette.stopped) { BackgroundOperation.userStop() }
                        }
                        s.displayId != null -> RoundAction(Icons.Filled.PlayArrow, tr("看一眼", "Look"), c.primary) { open() }
                    }
                }
            }
        }
    }
}

/** A round button with its word under it; it shrinks a little under the finger. */
@Composable
private fun RoundAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val down by press.collectIsPressedAsState()
    val k by androidx.compose.animation.core.animateFloatAsState(if (down) 0.88f else 1f, label = "press")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(press, null) { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onClick() }.padding(4.dp)) {
        Box(Modifier.size(44.dp).graphicsLayer { scaleX = k; scaleY = k }
            .clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
        }
        Text(label, fontSize = 11.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** Something is broken: one sentence on what, one button that fixes it (or opens the place where it is fixed). */
@Composable
private fun FixCard(problems: List<Readiness.Item>, setupLeft: Boolean) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val nav = LocalNav.current
    val host = LocalHost.current
    val first = problems.firstOrNull()
    val (title, body) = when {
        app.liveProblem != null && first == null && !setupLeft -> tr("后台运行受限", "Background running is restricted") to app.liveProblem!!
        first != null -> when (first.code) {
            "no_accessibility" -> tr("无障碍服务被关掉了", "Accessibility was turned off")
            "accessibility_cannot_see_display" -> tr("无障碍看不到副屏", "Accessibility cannot see the screen")
            "no_privilege" -> tr("Shizuku 没有在运行", "Shizuku is not running")
            "service_off" -> tr("服务关着", "The service is off")
            "frozen_in_background" -> tr("系统冻结了后台", "The system froze the app")
            else -> tr("有一项需要处理", "Something needs you")
        } to first.text
        else -> tr("还差 ${app.left} 项配置", "${app.left} setup steps left") to tr("配好之后 agent 才能用副屏", "Agents can use the screen once it is set up")
    }
    var busy by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(Palette.stopped.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Text("!", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.stopped)
            }
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(body, fontSize = 14.sp, lineHeight = 21.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            PrimaryButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                when (first?.code) {
                    "no_accessibility", "accessibility_cannot_see_display" -> {
                        busy = true
                        Thread { val (ok, msg) = com.kilon.agentvscreen.host.Heal.accessibility(ctx, restart = first.code != "no_accessibility"); host.say(msg); if (ok) app.bump(); busy = false }.start()
                    }
                    "frozen_in_background" -> { val (_, msg) = com.kilon.agentvscreen.host.Heal.power(ctx); host.say(msg); app.bump() }
                    "service_off" -> { ServiceControl.turnOn(ctx, "home fix"); app.serviceOn = true; app.bump() }
                    null -> if (app.liveProblem != null && !setupLeft) {
                        val opened = com.kilon.agentvscreen.host.Liveness.openBackgroundSettings(ctx)
                        host.say(tr("已打开“$opened”：允许自启动、关联启动和后台活动", "Opened \"$opened\": allow auto-start, start by other apps and background activity"))
                    } else nav.go(Route.Setup)
                    else -> nav.go(Route.Setup)
                }
            }) { Text(if (busy) tr("正在修复…", "Fixing…") else tr("修复", "Fix")) }
        }
    }
}

/** Before any agent ever connected: three steps, the current one lit; the stage takes over once one does. */
@Composable
private fun FirstUse(setupLeft: Boolean) {
    val nav = LocalNav.current
    val c = MiuixTheme.colorScheme
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tr("把你的 agent 接上这台手机", "Connect your agent to this phone"), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(tr("接上之后，agent 在后台的副屏里操作应用，不占你正在用的屏幕。", "Once connected, the agent works in apps on a background screen, not on the one you are using."),
                fontSize = 14.sp, lineHeight = 21.sp, color = c.onSurfaceVariantSummary)
            val steps = listOf(
                tr("授权", "Permissions") to tr("Shizuku 和无障碍", "Shizuku and accessibility"),
                tr("把说明发给 agent", "Give your agent the note") to tr("复制一段说明，粘贴给它", "Copy a note and paste it to the agent"),
                tr("等它第一次连上", "Wait for its first call") to tr("连上后这里会变成副屏画面", "Then this turns into the screen"),
            )
            val cur = if (setupLeft) 0 else 1
            steps.forEachIndexed { i, (t, sub) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(when { i < cur -> Palette.takeover; i == cur -> c.primary; else -> c.surfaceContainerHigh }),
                        contentAlignment = Alignment.Center) {
                        Text(if (i < cur) "✓" else "${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (i <= cur) Color.White else c.onSurfaceVariantSummary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column { Text(t, fontSize = 15.sp, fontWeight = FontWeight.SemiBold); Text(sub, fontSize = 13.sp, color = c.onSurfaceVariantSummary) }
                }
            }
            PrimaryButton(onClick = { nav.go(if (setupLeft) Route.Setup else Route.Connect) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (setupLeft) tr("去授权", "Grant permissions") else tr("接入 agent", "Connect an agent"))
            }
        }
    }
}

/** Bookings and watches still to come, as a row of small cards; nothing at all when there are none. */
@Composable
private fun NextUp(beat: Int, tick: Int) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    var books by remember { mutableStateOf(emptyList<Wake.Entry>()) }
    LaunchedEffect(beat / 5, tick) { books = withContext(Dispatchers.IO) { runCatching { Wake.list(ctx).filter { it.on } }.getOrDefault(emptyList()) } }
    val watches = remember(beat, tick) { VScreenRules.list().filter { !it.stopped } }
    if (books.isEmpty() && watches.isEmpty()) return
    SmallTitle(tr("接下来", "Coming up"))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        books.forEach { e ->
            val next = e.pending(System.currentTimeMillis())
            NextCard(tr("预约", "Booked"), next?.let { ServiceControl.hhmm(it) } ?: "--:--", e.spec.describe()) { nav.go(Route.Schedules) }
        }
        watches.forEach { l -> NextCard(tr("盯守", "Watching"), l.status, l.spec.name) { nav.tab(1) } }
    }
}

@Composable
private fun NextCard(kind: String, big: String, what: String, onClick: () -> Unit) {
    Column(Modifier.width(168.dp).clip(RoundedCornerShape(18.dp)).background(MiuixTheme.colorScheme.surfaceContainer).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(kind, fontSize = 11.sp, color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        Text(big, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(what, fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Behind the shield: pause every agent, or turn the service off. Rare, but found at once when needed. */
@Composable
private fun ShieldSheet(show: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val host = LocalHost.current
    val st = remember(show, app.tick) { ServiceControl.now(ctx) }
    OverlayBottomSheet(show = show, title = tr("暂停与关闭", "Pause and turn off"), onDismissRequest = close) {
        Column(Modifier.padding(bottom = 24.dp)) {
            val blocked = st == ServiceControl.State.BLOCKED
            ArrowPreference(title = if (blocked) tr("允许 agent 访问", "Allow agents") else tr("禁止 agent 访问", "Block agents"),
                summary = if (blocked) tr("恢复后 agent 可以再连进来", "Agents can connect again") else tr("正在做的停下，新的请求先拒绝，直到你恢复", "What runs stops; new requests are refused until you allow them again"),
                onClick = { if (blocked) ServiceControl.allow(ctx, "shield") else ServiceControl.block(ctx, "shield"); app.serviceOn = com.kilon.agentvscreen.host.Prefs.serviceOn(ctx); app.bump(); close() })
            ArrowPreference(title = if (app.serviceOn) tr("关闭服务", "Turn the service off") else tr("打开服务", "Turn the service on"),
                summary = if (app.serviceOn) tr("副屏释放，agent 连不上", "The screen is released; agents cannot connect") else tr("agent 随时连得上 · 本机 127.0.0.1:${ApiConfig.port}", "Agents can connect · 127.0.0.1:${ApiConfig.port}"),
                onClick = {
                    // 1.3.0: a flip back within 1.5 s is ignored (each flip restarts the Tailscale node).
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastFlip < 1_500L) { host.say(tr("稍等一下再切换", "Wait a moment before switching again")); return@ArrowPreference }
                    lastFlip = now
                    if (app.serviceOn) ServiceControl.hide(ctx, "shield") else ServiceControl.turnOn(ctx, "shield")
                    app.serviceOn = !app.serviceOn; app.bump(); close()
                })
            HostService.server?.lastError?.let { e -> Note(e, Palette.stopped, Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
        }
    }
}

/** A row's current value, on the right before the arrow. */
@Composable
fun Value(t: String, color: Color = MiuixTheme.colorScheme.onSurfaceVariantActions) =
    Text(t, fontSize = 14.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 6.dp))

/**
 * A small live picture of the screen from the agent's own frames. It does not use the viewer's preview stream (the
 * floating window's): turning that off when this page closes would stop the window's picture.
 */
@Composable
fun ScreenThumb(session: String, modifier: Modifier, corner: Dp = 10.dp) {
    val st by BackgroundOperation.state.collectAsState()
    val display = remember(session, st.displayId) { BackgroundOperation.previewDisplayFor(session) }
    val v = display?.frameVersion?.collectAsState()?.value
    val img = remember(display, v) {
        display?.latestFrame()?.takeIf { !it.isRecycled && it.width > 0 }?.let { src ->
            runCatching { Bitmap.createScaledBitmap(src, 240, (240f * src.height / src.width).toInt().coerceAtLeast(1), true).asImageBitmap() }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(corner)).background(Palette.ink)) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
