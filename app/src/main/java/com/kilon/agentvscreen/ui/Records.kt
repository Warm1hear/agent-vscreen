package com.kilon.agentvscreen.ui

import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.kilon.agentvscreen.core.TaskLog
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
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
 * 1.4.0-beta3 记录 = a timeline (rainbow approved the prototype). beta2 listed four boxes of settings-like rows. Now it
 * reads like a diary: day by day, each task an agent did as a card with its frames, the questions the user answered
 * between them. Chips on top narrow it to tasks, questions, plans (bookings and watches) or lessons. The raw command
 * log moved to Settings › 关于与诊断.
 */

private enum class Filter { ALL, TASKS, ASKS, PLANS, LESSONS }

private sealed class Line(val at: Long) {
    class T(val t: TaskLog.Task) : Line(t.start)
    class A(val o: JSONObject) : Line(o.optLong("at"))
}

private val dayFmt = SimpleDateFormat("M月d日", Locale.ROOT)

private fun dayOf(at: Long): String {
    val c = java.util.Calendar.getInstance()
    fun key(t: Long) = c.apply { timeInMillis = t }.let { it.get(java.util.Calendar.YEAR) * 400 + it.get(java.util.Calendar.DAY_OF_YEAR) }
    val now = System.currentTimeMillis()
    return when (key(at)) {
        key(now) -> tr("今天", "Today")
        key(now - 86_400_000L) -> tr("昨天", "Yesterday")
        else -> if (!com.kilon.agentvscreen.I18n.en) dayFmt.format(Date(at)) else SimpleDateFormat("MMM d", Locale.ENGLISH).format(Date(at))
    }
}

@Composable
fun RecordsTab(bottom: Dp) {
    val app = LocalApp.current
    val nav = LocalNav.current
    var beat by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(4_000); beat++ } }
    var filter by remember { mutableStateOf(Filter.ALL) }
    var lines by remember { mutableStateOf(emptyList<Line>()) }
    LaunchedEffect(beat, app.tick) {
        lines = withContext(Dispatchers.IO) {
            val tasks = TaskLog.recent(60).map { Line.T(it) }
            val asks = runCatching { Audit.tail(400).filter { it.optString("kind") == "consent" && answerWord(it.optString("answer")) != null }.map { Line.A(it) } }.getOrDefault(emptyList())
            (tasks + asks).sortedByDescending { it.at }
        }
    }
    MiuixPage(tr("记录", "Records"), back = false, bottom = bottom) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Filter.ALL to tr("全部", "All"), Filter.TASKS to tr("任务", "Tasks"), Filter.ASKS to tr("提问", "Questions"),
                Filter.PLANS to tr("预约与盯守", "Plans"), Filter.LESSONS to tr("示教", "Lessons")).forEach { (f, label) ->
                FilterChip(label, filter == f) { filter = f }
            }
        }
        AnimatedContent(filter, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "filter") { f ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (f) {
                    Filter.PLANS -> Plans(beat)
                    Filter.LESSONS -> LessonsList(app.tick)
                    else -> {
                        val shown = lines.filter { f == Filter.ALL || (f == Filter.TASKS && it is Line.T) || (f == Filter.ASKS && it is Line.A) }
                        if (shown.isEmpty()) Empty(when (f) {
                            Filter.ASKS -> tr("agent 还没问过你什么", "No agent has asked you anything yet")
                            else -> tr("agent 用过副屏之后，每次做了什么会按时间排在这里", "Once an agent has used the screen, what it did each time appears here")
                        })
                        var day = ""
                        shown.forEach { l ->
                            val d = dayOf(l.at)
                            if (d != day) { day = d; Text(d, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(start = 6.dp, top = 8.dp)) }
                            when (l) {
                                is Line.T -> TaskCard(l.t) { nav.go(Route.Task(l.t.id)) }
                                is Line.A -> {
                                    val a = l.o.optString("answer")
                                    TimelineNote(hhmm(l.at) + " · " + l.o.optString("what").substringBefore(" — ").take(40) + " → " + answerWord(a),
                                        if (a == "allowed" || a.startsWith("answered")) Palette.takeover else Palette.stopped)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** What the user did with a question, in their words; null for answers the app gave itself (pre-authorized, automatic). */
private fun answerWord(a: String): String? = when {
    a == "allowed" -> tr("你允许了", "you allowed")
    a == "denied" -> tr("你拒绝了", "you refused")
    a.startsWith("answered") -> tr("你回答了", "you answered")
    a == "declined" -> tr("你没有回答", "you declined")
    a.startsWith("ended") -> tr("没等到回答", "not answered in time")
    else -> null
}

@Composable
private fun FilterChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = MiuixTheme.colorScheme
    Text(label, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) c.onPrimary else c.onSurface,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) c.primary else c.surfaceContainer).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun Empty(text: String) =
    Text(text, fontSize = 14.sp, lineHeight = 21.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp))

/** Bookings (alarm-like cards) and watches (running first). */
@Composable
private fun Plans(beat: Int) {
    val app = LocalApp.current
    val watches = remember(beat, app.tick) { VScreenRules.list() }
    SchedulesCards(app.tick)
    if (watches.isNotEmpty()) Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        watches.forEach { l ->
            val running = !l.stopped
            BasicComponent(
                title = tr("盯守 · ", "Watch · ") + l.spec.name,
                summary = l.spec.describe() + " · " + (if (running) l.status + tr(" · 还剩 ${l.json().opt("ends_in_min")} 分钟", " · ${l.json().opt("ends_in_min")} min left") else (l.endReason ?: tr("已结束", "ended"))),
                endActions = {
                    LinkButton(onClick = { if (running) VScreenRules.stop(l.spec.name) else VScreenRules.clearEnded(l.spec.name); app.bump() }) {
                        Text(if (running) tr("停止", "Stop") else tr("清掉", "Clear"))
                    }
                },
            )
        }
    }
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
    if (askClear) GlassDialog(
        onDismissRequest = { askClear = false },
        title = { DialogTitle(tr("清空操作日志？", "Clear the activity log?")) },
        text = { Text(tr("清掉后无法找回。", "This cannot be undone.")) },
        confirmButton = { LinkButton(onClick = { Audit.clear(); askClear = false; n++ }) { Text(tr("清空", "Clear"), color = Palette.stopped) } },
        dismissButton = { LinkButton(onClick = { askClear = false }) { Text(tr("取消", "Cancel")) } },
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
