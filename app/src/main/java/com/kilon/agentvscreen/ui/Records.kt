package com.kilon.agentvscreen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.core.VScreenRules
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.host.Wake
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 1.4 记录: what agents booked, watch, learned and did. Each group shows the latest few rows and leads to its own page
 * with all of them.
 */

@Composable
fun RecordsTab(bottom: Dp) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val nav = LocalNav.current
    var beat by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(4_000); beat++ } }
    MiuixPage(tr("记录", "Records"), back = false, bottom = bottom) {
        // Bookings, alarm-like: the time big, the switch on the right.
        var books by remember { mutableStateOf(emptyList<Wake.Entry>()) }
        LaunchedEffect(beat, app.tick) { books = withContext(Dispatchers.IO) { Wake.list(ctx) } }
        Group(tr("定时预约", "Schedules")) {
            if (books.isEmpty()) BasicComponent(title = tr("还没有预约", "Nothing booked"), summary = tr("agent 用 vscreen schedule 预约要用手机的时间", "Agents book the times they need the phone with vscreen schedule"))
            books.take(3).forEach { e -> BookingRow(e) { app.bump() } }
            if (books.isNotEmpty()) ArrowPreference(title = tr("全部预约", "All schedules"), endActions = { Value("${books.size}") }, onClick = { nav.go(Route.Schedules) })
        }

        // Watches: running first, then the ones that ended.
        val watches = remember(beat, app.tick) { VScreenRules.list() }
        Group(tr("盯守", "Watches")) {
            if (watches.isEmpty()) BasicComponent(title = tr("没有盯守", "No watches"), summary = tr("agent 用 act --stop … --keep 让手机盯着一件事", "Agents start one with act --stop … --keep"))
            watches.forEach { l ->
                val running = !l.stopped
                BasicComponent(
                    title = l.spec.name,
                    summary = l.spec.describe() + " · " + (if (running) l.status + tr(" · 还剩 ${l.json().opt("ends_in_min")} 分钟", " · ${l.json().opt("ends_in_min")} min left") else (l.endReason ?: tr("已结束", "ended"))),
                    endActions = {
                        TextButton(onClick = { if (running) VScreenRules.stop(l.spec.name) else VScreenRules.clearEnded(l.spec.name); app.bump() }) {
                            Text(if (running) tr("停止", "Stop") else tr("清掉", "Clear"))
                        }
                    },
                )
            }
        }

        // Lessons: how many, for how many apps.
        var lessons by remember { mutableStateOf(0 to 0) }
        LaunchedEffect(app.tick) {
            lessons = withContext(Dispatchers.IO) { runCatching { com.kilon.agentvscreen.core.VScreenMap.allSegments().let { it.size to it.map { p -> p.first }.distinct().size } }.getOrDefault(0 to 0) }
        }
        Group(tr("示教", "Lessons")) {
            ArrowPreference(
                title = tr("已保存的示教", "Saved lessons"),
                summary = if (lessons.first == 0) tr("agent 用 teach 请你演示一遍，或在全屏页点录制", "An agent asks with teach, or tap record on the full screen page") else null,
                endActions = { Value(if (lessons.first == 0) tr("无", "None") else tr("${lessons.first} 段 · ${lessons.second} 个应用", "${lessons.first} · ${lessons.second} apps")) },
                onClick = { nav.go(Route.Lessons) },
            )
        }

        // The log: the last few commands.
        val rows = remember(beat, app.tick) { runCatching { Audit.tail(3).reversed() }.getOrDefault(emptyList()) }
        Group(tr("操作日志", "Activity log")) {
            if (rows.isEmpty()) BasicComponent(title = tr("还没有记录", "Nothing yet"), summary = tr("agent 的每条命令都会记在这里", "Every command an agent sends is recorded here"))
            rows.forEach { o -> LogRow(o) }
            ArrowPreference(title = tr("全部日志", "Full log"), onClick = { nav.go(Route.Log) })
        }
    }
}

/** A booking as an alarm row: the time(s) as the title, what it means and when next below, its switch. */
@Composable
private fun BookingRow(e: Wake.Entry, changed: () -> Unit) {
    val ctx = LocalContext.current
    val big = if (e.spec.everyMin > 0) tr("每 ${e.spec.everyMin} 分钟", "Every ${e.spec.everyMin} min") else e.spec.times.joinToString("  ") { "%02d:%02d".format(it / 60, it % 60) }
    val next = e.json().optString("next_local", "")
    SwitchPreference(
        checked = e.on,
        onCheckedChange = { v -> runCatching { Wake.edit(ctx, e.name, e.text, e.forMin, v) }; changed() },
        title = big,
        summary = e.spec.describe() + " · " + (if (e.on && next.isNotEmpty() && next != "null") tr("下次 $next", "next $next") else tr("已关闭", "off")) + " · " + e.name,
    )
}

private val logTime = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)

private fun logWhat(o: JSONObject): String = when (o.optString("kind")) {
    "command" -> o.optString("cmd")
    "consent" -> tr("确认：", "Confirm: ") + o.optString("what")
    else -> (o.optString("what") + " " + o.optString("detail")).trim()
}

private fun logMeta(o: JSONObject): String = logTime.format(Date(o.optLong("at"))) + " · " + o.optString("token", "-") +
    (o.optString("peer").takeIf { it.isNotEmpty() && it != "null" }?.let { " @ $it" } ?: "") +
    when (o.optString("kind")) {
        "command" -> " · ${o.optString("code")} · ${o.optLong("ms")} ms"
        "consent" -> " · ${o.optString("answer")}"
        else -> ""
    }

private fun logLine(o: JSONObject) = logMeta(o) + " · " + logWhat(o)

@Composable
private fun LogRow(o: JSONObject) = BasicComponent(title = logWhat(o).take(120), summary = logMeta(o))

/** Every booking, each with its editor (time, repeat, length), like an alarm list. */
@Composable
fun SchedulesPage() {
    val app = LocalApp.current
    MiuixPage(tr("定时预约", "Schedules")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { SchedulesCards(app.tick) }
        Text(tr("到点前 3 分钟会拉起服务并检查，没准备好就通知你。点开一条可以改时间；关掉开关会保留它，但到点不触发。", "3 minutes before, the service is started and checked; you are told if it is not ready. Open one to change it; switched off it is kept but does not fire."),
            fontSize = 12.sp, color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
}

/** Every saved lesson: search, open to see its steps, select several to delete. */
@Composable
fun LessonsPage() {
    val app = LocalApp.current
    MiuixPage(tr("示教", "Lessons")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { LessonsList(app.tick) }
    }
}

/** The whole activity log; copy and clear are in the top bar. */
@Composable
fun LogPage() {
    val host = LocalHost.current
    var n by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf(emptyList<JSONObject>()) }
    var askClear by remember { mutableStateOf(false) }
    LaunchedEffect(n) { rows = withContext(Dispatchers.IO) { Audit.tail(200).reversed() } }
    MiuixPage(tr("操作日志", "Activity log"), actions = {
        IconButton(onClick = { n++ }) { Icon(MiuixIcons.Refresh, tr("刷新", "Refresh")) }
        IconButton(onClick = { host.copy(tr("操作日志", "Activity log"), rows.joinToString("\n") { logLine(it) }) }) { Icon(MiuixIcons.Copy, tr("复制", "Copy")) }
        IconButton(onClick = { askClear = true }, modifier = Modifier.padding(end = 12.dp)) { Icon(MiuixIcons.Delete, tr("清空", "Clear")) }
    }) {
        Group {
            if (rows.isEmpty()) BasicComponent(title = tr("还没有记录", "Nothing recorded yet"))
            rows.take(120).forEach { LogRow(it) }
        }
        Text(tr("谁（哪个令牌、哪台设备）、什么时候、做了什么、结果；输入的文字只记字数。", "Who (token, device), when, what and the result; typed text is recorded as a length only."),
            fontSize = 12.sp, color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
    if (askClear) AlertDialog(
        onDismissRequest = { askClear = false },
        title = { DialogTitle(tr("清空操作日志？", "Clear the activity log?")) },
        text = { Text(tr("清掉后无法找回。", "This cannot be undone.")) },
        confirmButton = { TextButton(onClick = { Audit.clear(); askClear = false; n++ }) { Text(tr("清空", "Clear"), color = Palette.stopped) } },
        dismissButton = { TextButton(onClick = { askClear = false }) { Text(tr("取消", "Cancel")) } },
    )
}

/** For the developer: copy or share the diagnostics, the recent app log. */
@Composable
fun DiagPage() {
    val host = LocalHost.current
    val app = LocalApp.current
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    MiuixPage(tr("诊断", "Diagnostics")) {
        Group {
            ArrowPreference(title = tr("复制诊断信息", "Copy diagnostics"), summary = tr("版本、配置状态和最近日志；不含令牌和你输入的字", "Version, setup state and recent log; no token, nothing you typed"),
                onClick = { host.copy(tr("诊断信息", "Diagnostics"), host.diagnostics(app.items)) })
            ArrowPreference(title = tr("分享诊断信息", "Share diagnostics"), onClick = {
                runCatching {
                    ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, tr("Agent-VScreen 诊断", "Agent-VScreen diagnostics"))
                        .putExtra(android.content.Intent.EXTRA_TEXT, host.diagnostics(app.items)), tr("发送诊断", "Send diagnostics")))
                }
            })
            SwitchPreference(checked = open, onCheckedChange = { open = it }, title = tr("显示最近日志", "Show the recent log"))
        }
        if (open) Group(padded = true) {
            SelectionContainer {
                Text(com.kilon.agentvscreen.host.AppLogger.tail(80), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
