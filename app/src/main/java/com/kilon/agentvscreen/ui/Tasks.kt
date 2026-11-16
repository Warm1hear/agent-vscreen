package com.kilon.agentvscreen.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.core.TaskLog
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 1.4.0-beta3: a task as the user sees it (TaskLog): what it was called, in which app, how it ended, four frames.
 * Shown as a row on the home page (刚才), as a card on the 记录 timeline, and as its own page with every command.
 */

/** The app's name and icon for [pkg] (null when unknown). */
@Composable
fun appOf(pkg: String?): Pair<String?, ImageBitmap?> {
    val ctx = LocalContext.current
    return remember(pkg) {
        if (pkg == null) null to null else runCatching {
            val pm = ctx.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString() to iconBitmap(pm.getApplicationIcon(ai)).asImageBitmap()
        }.getOrDefault(null to null)
    }
}

private fun iconBitmap(d: android.graphics.drawable.Drawable): android.graphics.Bitmap {
    val b = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b); d.setBounds(0, 0, 96, 96); d.draw(c); return b
}

/** What the user reads as the task's name: the agent's own title, else what happened where. */
fun taskTitle(t: TaskLog.Task, app: String?): String = t.title
    ?: if (app != null) tr("在$app 里操作了 ${t.commands} 步", "${t.commands} steps in $app") else tr("操作了 ${t.commands} 步", "${t.commands} steps")

/** How it ended, and whether that is good news. */
fun taskOutcome(t: TaskLog.Task): Pair<String, Boolean> = when {
    t.running -> tr("进行中", "Running") to true
    t.stoppedByUser -> tr("你停止了", "You stopped it") to false
    t.lastCode != "OK" -> tr("没做完", "Not finished") to false
    else -> tr("完成", "Done") to true
}

private val hm = SimpleDateFormat("HH:mm", Locale.ROOT)
fun hhmm(at: Long): String = hm.format(Date(at))

fun took(t: TaskLog.Task): String {
    val s = (((if (t.running) System.currentTimeMillis() else t.end) - t.start) / 1000).coerceAtLeast(1)
    return if (s < 60) tr("$s 秒", "${s}s") else tr("${s / 60} 分 ${s % 60} 秒", "${s / 60}m ${s % 60}s")
}

/** One of a task's small frames, read off the disk once. */
@Composable
fun TaskFrame(name: String?, modifier: Modifier, corner: Dp = 8.dp) {
    val img by produceState<ImageBitmap?>(null, name) {
        value = withContext(Dispatchers.IO) { name?.let { TaskLog.frameFile(it) }?.let { f -> runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() } }
    }
    Box(modifier.clip(RoundedCornerShape(corner)).background(MiuixTheme.colorScheme.surfaceContainerHigh)) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun AppBadge(icon: ImageBitmap?, size: Dp = 36.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(MiuixTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, null, Modifier.fillMaxSize())
    }
}

/** A task as one row: icon, name, how it ended, and a strip of its frames. */
@Composable
fun TaskRow(t: TaskLog.Task, onClick: () -> Unit) {
    val (app, icon) = appOf(t.pkg)
    val (word, good) = taskOutcome(t)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        AppBadge(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(taskTitle(t, app), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Text(word, fontSize = 12.5.sp, color = if (good) Palette.takeover else Palette.stopped)
                Text(" · ${t.commands} " + tr("步", "steps") + " · " + took(t) + " · " + hhmm(t.start), fontSize = 12.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            t.frames.takeLast(3).forEach { TaskFrame(it, Modifier.width(16.dp).height(32.dp), 4.dp) }
        }
    }
}

/** A task as a card on the timeline: name, how it ended, four frames. */
@Composable
fun TaskCard(t: TaskLog.Task, onClick: () -> Unit) {
    val (app, icon) = appOf(t.pkg)
    val (word, good) = taskOutcome(t)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), insideMargin = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppBadge(icon, 30.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(taskTitle(t, app), fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row {
                    Text(word, fontSize = 12.5.sp, color = if (good) Palette.takeover else Palette.stopped)
                    Text(" · ${t.commands} " + tr("步", "steps") + " · " + took(t), fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        if (t.frames.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0 until 4).forEach { i -> TaskFrame(t.frames.getOrNull(i), Modifier.weight(1f).aspectRatio(0.45f)) }
            }
        }
    }
}

/** A task's own page: its frames large, then every command it sent, from the audit log. */
@Composable
fun TaskPage(id: Long) {
    val t = remember(id) { TaskLog.recent().firstOrNull { it.id == id } }
    if (t == null) { MiuixPage(tr("任务", "Task")) { Note(tr("这条记录已经不在了", "This record is gone"), modifier = Modifier.padding(24.dp)) }; return }
    val (app, _) = appOf(t.pkg)
    val (word, good) = taskOutcome(t)
    val rows by produceState(emptyList<JSONObject>(), id) {
        value = withContext(Dispatchers.IO) {
            runCatching { Audit.tail(600).filter { o -> o.optString("kind") == "command" && o.optString("session") == t.session &&
                o.optLong("at") >= t.start - 1000 && (t.running || o.optLong("at") <= t.end + 1000) } }.getOrDefault(emptyList())
        }
    }
    MiuixPage(taskTitle(t, app), subtitle = hhmm(t.start) + " · " + t.token) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(word, fontSize = 14.sp, color = if (good) Palette.takeover else Palette.stopped)
            Text(" · ${t.commands} " + tr("步", "steps") + " · " + took(t) + (app?.let { " · $it" } ?: ""), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (t.frames.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0 until 4).forEach { i -> TaskFrame(t.frames.getOrNull(i), Modifier.weight(1f).aspectRatio(0.45f), 12.dp) }
        }
        Group(tr("每一步", "Every step")) {
            if (rows.isEmpty()) Note(tr("日志里没有这次的命令了", "The log no longer has these commands"), modifier = Modifier.padding(16.dp))
            rows.forEachIndexed { i, o ->
                val ok = o.optInt("exit") == 0
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("${i + 1}", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.width(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(o.optString("cmd").take(160), fontSize = 14.sp)
                        Text(hhmmss(o.optLong("at")) + " · " + o.optString("code") + " · ${o.optLong("ms")} ms", fontSize = 12.sp,
                            color = if (ok) MiuixTheme.colorScheme.onSurfaceVariantSummary else Palette.stopped)
                    }
                }
            }
        }
    }
}

private val hms = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
private fun hhmmss(at: Long) = hms.format(Date(at))

/** A small line between cards on the timeline (an answered question, a watch, a booking). */
@Composable
fun TimelineNote(text: String, tint: Color) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MiuixTheme.colorScheme.surfaceContainer).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(tint))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
