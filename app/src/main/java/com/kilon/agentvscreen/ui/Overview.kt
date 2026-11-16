package com.kilon.agentvscreen.ui

import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Switch
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.R
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.VScreenRules
import com.kilon.agentvscreen.host.HostService
import com.kilon.agentvscreen.host.ServiceControl
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 1.4 概览: only "how is it now, and does anything need me". From the top: one status card (its colour is the state),
 * then blocks that appear only when there is something in them: questions waiting, things to fix, what is running.
 * Then two quick actions. Areas (connect, permissions, records) are not tiles here any more: they are the other tabs.
 */

private var lastFlip = 0L

@Composable
fun OverviewTab(bottom: Dp) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val nav = LocalNav.current
    val host = LocalHost.current
    val s by BackgroundOperation.state.collectAsState()
    // Watches and the next booking are not flows: read them again every few seconds while the tab is shown.
    var beat by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3_000); beat++ } }

    MiuixPage("Agent-VScreen", back = false, bottom = bottom) {
        StatusCard(s, app.serviceOn) { on ->
            // 1.3.0: a flip back within 1.5 s is ignored (each flip restarts the Tailscale node).
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastFlip < 1_500L) { host.say(tr("稍等一下再切换", "Wait a moment before switching again")); return@StatusCard }
            lastFlip = now
            if (on) ServiceControl.turnOn(ctx, "switch") else ServiceControl.hide(ctx, "switch")
            app.serviceOn = on; app.bump()
        }

        // Questions an agent is waiting on.
        val waiting = pendingAsks()
        if (waiting.isNotEmpty()) {
            SmallTitle(tr("等你回答", "Waiting for you"))
            Box(Modifier.padding(horizontal = 12.dp)) { HomeAskCard(waiting.first(), waiting.size) }
        }

        // Things only the user can fix.
        val fixes = buildList<@Composable () -> Unit> {
            if (s.userStopped) add {
                BasicComponent(title = tr("你停止了 AI", "You stopped the AI"), summary = tr("它的操作都会被拒绝，直到你允许", "Its actions are refused until you allow it"),
                    startAction = { Dot(Palette.stopped, 10.dp); Spacer(Modifier.width(12.dp)) },
                    endActions = { LinkButton(onClick = { BackgroundOperation.userResume() }) { Text(tr("允许继续", "Allow again")) } })
            }
            if (s.windowHidden && s.displayId != null && !s.userStopped) add {
                BasicComponent(title = tr("小窗已收起", "Floating window hidden"), summary = tr("AI 还在后台工作", "The AI is still working in the background"),
                    startAction = { Dot(Palette.standby, 10.dp); Spacer(Modifier.width(12.dp)) },
                    endActions = { LinkButton(onClick = { BackgroundOperation.showWindow() }) { Text(tr("显示", "Show")) } })
            }
            app.liveProblem?.let { p ->
                add {
                    ArrowPreference(title = tr("后台运行受限", "Background running restricted"), summary = p,
                        startAction = { Dot(Palette.stopped, 10.dp); Spacer(Modifier.width(12.dp)) },
                        onClick = {
                            val opened = com.kilon.agentvscreen.host.Liveness.openBackgroundSettings(ctx)
                            host.say(tr("已打开“$opened”：允许自启动、关联启动和后台活动", "Opened \"$opened\": allow auto-start, start by other apps and background activity"))
                            app.liveProblem = com.kilon.agentvscreen.host.Liveness.problem(); app.bump()
                        })
                }
            }
            if (app.items.isNotEmpty() && !app.allOk) add {
                ArrowPreference(title = tr("还差 ${app.left} 项配置", "${app.left} setup steps left"), summary = tr("配好之后 agent 才能用副屏", "Agents can use the screen once it is set up"),
                    startAction = { Dot(Palette.stopped, 10.dp); Spacer(Modifier.width(12.dp)) }, onClick = { nav.go(Route.Setup) })
            }
        }
        if (fixes.isNotEmpty()) Group(tr("需要处理", "Needs you")) { fixes.forEach { it() } }

        // What is going on now.
        @Suppress("UNUSED_EXPRESSION") beat
        val watches = remember(beat, app.tick) { VScreenRules.list().filter { !it.stopped } }
        val next = remember(beat, app.tick) { ServiceControl.nextBooking(ctx) }
        if (watches.isNotEmpty() || next != null) Group(tr("正在进行", "Going on")) {
            watches.forEach { l ->
                val left = l.json().opt("ends_in_min")
                BasicComponent(title = l.spec.name, summary = l.spec.describe() + " · " + l.status + tr(" · 还剩 $left 分钟", " · $left min left"),
                    endActions = { LinkButton(onClick = { VScreenRules.stop(l.spec.name); app.bump() }) { Text(tr("停止", "Stop")) } })
            }
            next?.let { at ->
                ArrowPreference(title = tr("下次预约", "Next booking"), endActions = { Value(ServiceControl.hhmm(at)) }, onClick = { nav.go(Route.Schedules) })
            }
        }

        // Quick actions.
        val st = remember(beat, app.tick, app.serviceOn) { ServiceControl.now(ctx) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.displayId != null && !s.userStopped) PrimaryButton(onClick = { BackgroundOperation.userStop() }, color = Palette.stopped, modifier = Modifier.weight(1f)) {
                Text(tr("停止 AI", "Stop the AI"), color = Color.White)
            }
            val blocked = st == ServiceControl.State.BLOCKED
            SoftButton(onClick = {
                if (blocked) ServiceControl.allow(ctx, "home") else ServiceControl.block(ctx, "home")
                app.serviceOn = com.kilon.agentvscreen.host.Prefs.serviceOn(ctx); app.bump()
            }, modifier = Modifier.weight(1f)) { Text(if (blocked) tr("允许 agent 唤起", "Allow waking") else tr("禁止 agent 访问", "Block agents"), maxLines = 1) }
        }
    }
}

/** A row's current value, on the right before the arrow. */
@Composable
fun Value(t: String, color: Color = MiuixTheme.colorScheme.onSurfaceVariantActions) =
    Text(t, fontSize = 14.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 6.dp))

/** The state of the service and the screen, in one light card whose tint is the state. */
@Composable
private fun StatusCard(s: BackgroundOperation.State, serviceOn: Boolean, setOn: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val c = MiuixTheme.colorScheme
    val mode = modeOf(s)
    val error = if (serviceOn && HostService.isAlive) HostService.server?.lastError else null
    val st = ServiceControl.now(ctx)
    val (color, title, sub) = when {
        error != null -> Triple(Palette.stopped, tr("服务出错", "Service error"), error)
        serviceOn && !HostService.isAlive -> Triple(c.primary, tr("正在启动…", "Starting…"), tr("几秒钟", "A few seconds"))
        s.displayId != null -> Triple(Palette.of(mode), Palette.word(mode), s.agentTitle ?: tr("副屏 ${s.screenWidth}×${s.screenHeight}", "Screen ${s.screenWidth}×${s.screenHeight}"))
        serviceOn -> Triple(c.primary, tr("常驻运行中", "Always on"), tr("agent 随时连得上 · 本机 127.0.0.1:${ApiConfig.port}", "Agents can connect at any time · 127.0.0.1:${ApiConfig.port}"))
        st == ServiceControl.State.BLOCKED -> Triple(Palette.stopped, ServiceControl.title(ctx), ServiceControl.sub(ctx).orEmpty())
        else -> Triple(Palette.idle, ServiceControl.title(ctx), ServiceControl.sub(ctx).orEmpty())
    }
    Card(
        Modifier.padding(horizontal = 12.dp).padding(top = 12.dp, bottom = 6.dp).fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        colors = CardDefaults.defaultColors(color = color.copy(alpha = 0.13f).compositeOver(c.surfaceContainer)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_logo), null, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(color, 8.dp); Spacer(Modifier.width(6.dp))
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, color = c.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Switch(checked = serviceOn, onCheckedChange = setOn)
        }
        if (error != null) Row(Modifier.padding(top = 8.dp)) {
            if (HostService.server?.portBusy == true) LinkButton(onClick = {
                val np = if (ApiConfig.port >= 65535) ApiConfig.DEFAULT_PORT + 1 else ApiConfig.port + 1
                ApiConfig.setPort(np); HostService.server?.start(np)
            }) { Text(tr("换个端口", "Use another port")) }
            else LinkButton(onClick = { HostService.server?.start(ApiConfig.port) }) { Text(tr("立即重试", "Retry now")) }
        }
        // The screen: a live thumbnail while there is one (tap to open it full screen).
        if (s.displayId != null) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface.copy(alpha = 0.55f))
                    .clickable { ctx.startActivity(Intent(ctx, FullscreenActivity::class.java)) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ratio = if (s.screenHeight > 0) s.screenWidth.toFloat() / s.screenHeight else 0.46f
                ScreenThumb(s.ownerSessionId ?: "default", Modifier.width(58.dp).aspectRatio(ratio))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.agentTitle ?: Palette.word(mode), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(tr("会话 ${s.ownerSessionId ?: "-"} · 点开全屏看", "Session ${s.ownerSessionId ?: "-"} · tap to view"), fontSize = 12.sp, color = c.onSurfaceVariantSummary)
                }
            }
        } else if (serviceOn || HostService.isAlive) {
            Text(tr("还没有副屏：agent 第一次 launch 时自动创建", "No screen yet: made on the agent's first launch"), fontSize = 12.sp,
                color = c.onSurfaceVariantSummary, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/**
 * A small live picture of the screen from the agent's own frames. It does not use the viewer's preview stream (the
 * floating window's): turning that off when this page closes would stop the window's picture.
 */
@Composable
fun ScreenThumb(session: String, modifier: Modifier) {
    val st by BackgroundOperation.state.collectAsState()
    val display = remember(session, st.displayId) { BackgroundOperation.previewDisplayFor(session) }
    val v = display?.frameVersion?.collectAsState()?.value
    val img = remember(display, v) {
        display?.latestFrame()?.takeIf { !it.isRecycled && it.width > 0 }?.let { src ->
            runCatching { Bitmap.createScaledBitmap(src, 180, (180f * src.height / src.width).toInt().coerceAtLeast(1), true).asImageBitmap() }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Palette.ink)) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
