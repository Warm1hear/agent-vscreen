package com.kilon.agentvscreen.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.cmd.Teach
import com.kilon.agentvscreen.cmd.TeachLine
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.VScreenMap
import com.kilon.agentvscreen.host.UserAsk
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject

/**
 * 1.3 示教: what the user just showed, as the steps an agent will run. Untick a step that was a slip, choose whether
 * typed words are kept as they were or become a blank the agent fills ({1}), name it, save. Saved, it is a segment of
 * the app (`use 名字`); when an agent asked for the lesson, the agent gets the steps as the answer.
 */
class TeachActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val r = Teach.result
        if (r == null) { finish(); return }
        setContent { AvsTheme { Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
            Box(Modifier.fillMaxSize().backdrop()) { Review(r) }
        } } }
    }

    private fun defaultName(r: Teach.Result): String = (r.name ?: r.purpose?.take(16) ?: tr("示教", "lesson") + "-" +
        java.text.SimpleDateFormat("HHmm", java.util.Locale.ROOT).format(java.util.Date())).trim().replace(Regex("\\s+"), "-")

    @Composable
    private fun Review(r: Teach.Result) {
        var name by remember { mutableStateOf(defaultName(r)) }
        var keep by remember { mutableStateOf(r.lines.indices.toSet()) }
        var literal by remember { mutableStateOf(emptySet<Int>()) }
        val (steps, params) = Teach.build(r.lines, keep, literal)
        val ask = r.askId?.let { UserAsk.byId(it) }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("把刚才的操作存成一段", "Save what you just did"), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    ask?.let { Text(tr("「${it.tokenName}」请你演示：${it.title}", "\"${it.tokenName}\" asked you to show: ${it.title}"), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary) }
                    Text((r.app ?: tr("没认出应用", "app not recognised")) + " · " + tr("${r.lines.size} 步", "${r.lines.size} steps"),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(name, { name = it.replace(Regex("\\s+"), "-").take(32) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(tr("名字（agent 用 use 名字 来重放）", "Name (agents replay it with use <name>)")) }, shape = RoundedCornerShape(18.dp))
                if (r.lines.isEmpty()) Text(tr("没有记到操作。", "Nothing was recorded."), fontSize = 15.sp)
                r.lines.forEachIndexed { i, l -> StepRow(i, l, i in keep, i in literal, params = paramOf(r.lines, keep, literal, i),
                    onKeep = { keep = if (i in keep) keep - i else keep + i }, onLiteral = { literal = if (i in literal) literal - i else literal + i }) }
                if (keep.isNotEmpty()) Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), lift = false).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("agent 这样用", "How an agent runs it"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("act \"use $name" + (1..params).joinToString("") { " <$it>" } + "\"", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), lift = false).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { discard(r) }) { Text(tr("丢弃", "Discard")) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { again(r) }, modifier = Modifier.height(48.dp)) { Text(tr("重录", "Again")) }
                Button(enabled = name.isNotBlank() && keep.isNotEmpty() && r.app != null, onClick = { save(r, name, steps, params, keep) }, modifier = Modifier.height(48.dp)) {
                    Text(tr("保存", "Save"), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    /** The blank number this typing line gets, or 0. */
    private fun paramOf(lines: List<TeachLine>, keep: Set<Int>, literal: Set<Int>, i: Int): Int {
        if (lines[i].kind != "type" || i !in keep || (i in literal && !lines[i].secret)) return 0
        return lines.indices.count { j -> j <= i && j in keep && lines[j].kind == "type" && !(j in literal && !lines[j].secret) }
    }

    @Composable
    private fun StepRow(i: Int, l: TeachLine, kept: Boolean, lit: Boolean, params: Int, onKeep: () -> Unit, onLiteral: () -> Unit) {
        val shown = when {
            l.kind != "type" -> l.head + (l.until?.let { " until $it" } ?: "")
            lit && !l.secret -> "type ${l.typed}"
            else -> "type {$params}"
        }
        Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), lift = false).clickable(onClick = onKeep).padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top) {
            Checkbox(kept, { onKeep() })
            Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(Glass.accent), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(shown, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = if (kept) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                }
                Text(l.why, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                l.warn?.let { Text(it, fontSize = 12.sp, color = Palette.stopped) }
                if (l.kind == "type" && kept) {
                    if (l.secret) Text(tr("密码不会被保存，agent 用时要自己填", "The password is not kept; the agent fills it in"), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    else Text(if (lit) tr("固定输入这段字 · 点这里改成由 agent 填", "Always types these words · tap to let the agent fill it")
                        else tr("由 agent 每次填（{$params}）· 点这里改成固定输入原文", "Filled by the agent each time ({$params}) · tap to always type the original"),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onLiteral).padding(vertical = 2.dp))
                }
            }
        }
    }

    private fun save(r: Teach.Result, name: String, steps: String, params: Int, keep: Set<Int>) {
        val app = r.app ?: return
        VScreenMap.saveSegment(app, name, steps)
        r.askId?.let { id ->
            val lines = JSONArray(r.lines.withIndex().filter { it.index in keep }.map { it.value.why })
            UserAsk.answer(this, id, true, JSONObject().put("kind", "teach").put("name", name).put("app", app).put("steps", steps)
                .put("params", params).put("described", lines))
        }
        Toast.makeText(this, tr("已存成「$name」", "Saved as \"$name\""), Toast.LENGTH_SHORT).show()
        Teach.clearResult(); finish()
    }

    private fun discard(r: Teach.Result) {
        r.askId?.let { UserAsk.answer(this, it, false) }
        Teach.clearResult(); finish()
    }

    private fun again(r: Teach.Result) {
        val s = BackgroundOperation.state.value
        if (s.ownerSessionId != r.session || s.displayId == null) {
            Toast.makeText(this, tr("副屏已经关了，没法重录", "The background screen is closed; cannot record again"), Toast.LENGTH_SHORT).show(); return
        }
        if (s.userStopped) BackgroundOperation.userResume()
        BackgroundOperation.beginTakeover(r.session)
        Teach.start(r.session, r.purpose, r.askId, r.name)
        Teach.clearResult()
        startActivity(Intent(this, FullscreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
