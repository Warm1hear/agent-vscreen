package com.kilon.agentvscreen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.core.PlayAudio
import com.kilon.agentvscreen.core.VScreenKnowledge
import com.kilon.agentvscreen.core.VScreenMute
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 1.4.0-beta13 (rainbow: a place to mute and unmute apps by hand; agents can do the same with `sound`). One page for
 * app sound: the screen's own muting, the apps that are silent now (whoever did it) with a way back, the apps muted by
 * hand, and the apps that keep their sound on the screen. Every change goes through [VScreenMute], as `sound` does.
 */
@Composable
fun SoundPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var silent by remember { mutableStateOf<List<PlayAudio.Hit>?>(null) }
    var scanning by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true: mute an app, false: an app that keeps its sound
    var screenMute by remember { mutableStateOf(VScreenMute.enabled) }
    LaunchedEffect(tick) { scanning = true; silent = withContext(Dispatchers.IO) { VScreenMute.silenced() }; scanning = false }
    fun label(p: String) = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
    fun said(c: VScreenMute.Change, done: String) = when {
        c.ok -> done
        c.why == "no-shizuku" -> tr("需要 Shizuku 已连接。", "Shizuku must be connected.")
        c.why == "denied" -> tr("系统不允许修改：小米 / 红米请在开发者选项里打开“USB 调试（安全设置）”。", "The system refused: on Xiaomi / Redmi turn on \"USB debugging (Security settings)\" in developer options.")
        else -> tr("没有改成，系统读回来还是原样。", "It did not change; the system still reads the same.")
    }
    fun run(op: () -> String) = scope.launch { msg = withContext(Dispatchers.IO) { op() }; tick++ }

    MiuixPage(tr("应用静音", "App sound")) {
        Group {
            SwitchPreference(checked = screenMute, onCheckedChange = { screenMute = it; VScreenMute.enabled = it },
                title = tr("副屏静音", "Mute the screen"), summary = tr("副屏里的应用不出声，离开副屏就恢复", "Apps on the screen are silent until they leave it"))
        }
        Group(tr("现在没有声音的应用", "Silent now")) {
            val l = silent
            when {
                scanning && l == null -> BasicComponent(title = tr("正在检查…", "Checking…"))
                l == null -> BasicComponent(title = tr("需要 Shizuku 已连接", "Shizuku must be connected"))
                l.isEmpty() -> BasicComponent(title = tr("没有", "None"))
                else -> l.forEach { h ->
                    val why = when {
                        h.user != 0 -> tr("分身", "clone")
                        h.pkg in VScreenMute.manual() -> tr("手动静音", "muted by hand")
                        h.pkg in VScreenMute.mutedNow() -> tr("在副屏上，离开后恢复", "on the screen, back when it leaves")
                        else -> tr("被留下的静音", "left silent")
                    }
                    ArrowPreference(title = label(h.pkg), summary = why, endActions = { Value(tr("恢复", "Unmute")) }, onClick = {
                        run { if (h.user == 0) said(VScreenMute.unmute(h.pkg), tr("${label(h.pkg)} 的声音恢复了，回到应用重新点一次播放。", "${label(h.pkg)} has its sound back; start playing again in the app."))
                            else VScreenMute.repair()?.let { tr("恢复了 ${it.fixed.size} 个。", "Restored ${it.fixed.size}.") } ?: tr("需要 Shizuku 已连接。", "Shizuku must be connected.") }
                    })
                }
            }
            if (!l.isNullOrEmpty() && l.size > 1) ArrowPreference(title = tr("全部恢复", "Unmute all"), onClick = {
                run { VScreenMute.repair()?.let { r -> tr("恢复了 ${r.fixed.size} 个，回到应用重新点一次播放。", "Restored ${r.fixed.size}; start playing again in the app.") +
                    (if (r.failed.isNotEmpty()) tr("${r.failed.size} 个没恢复。", " ${r.failed.size} could not be restored.") else "") +
                    (if (r.denied) tr("系统不允许修改：小米 / 红米请打开“USB 调试（安全设置）”。", " The system refused: on Xiaomi / Redmi turn on \"USB debugging (Security settings)\".") else "") } ?: tr("需要 Shizuku 已连接。", "Shizuku must be connected.") }
            })
            ArrowPreference(title = tr("手动静音一个应用", "Mute an app"), summary = tr("在哪块屏幕上都不出声，直到你恢复", "Silent on every screen until you unmute it"), onClick = { picking = true })
        }
        Group(tr("在副屏里也出声", "Keeps its sound on the screen")) {
            val plays = VScreenMute.plays().sortedBy { label(it) }
            plays.forEach { p -> ArrowPreference(title = label(p), endActions = { Value(tr("移除", "Remove")) }, onClick = { VScreenMute.setPlays(p, false); tick++ }) }
            ArrowPreference(title = tr("添加应用", "Add an app"), onClick = { picking = false })
        }
        Text(tr("已经在播放的声音，系统要等应用重新播放才会改变。agent 也可以用 sound 命令做同样的事。", "Sound already playing changes only when the app plays again. Agents can do the same with the sound command."),
            fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
    picking?.let { mute ->
        var q by remember { mutableStateOf("") }
        var apps by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
        LaunchedEffect(q) { apps = withContext(Dispatchers.IO) { VScreenKnowledge.findApps(q.trim().ifEmpty { null }).let { if (q.isBlank()) it.sortedBy { a -> a.first } else it }.take(60) } }
        GlassDialog(onDismissRequest = { picking = null },
            title = { DialogTitle(if (mute) tr("静音哪个应用", "Mute which app") else tr("哪个应用在副屏里也出声", "Which app keeps its sound")) },
            text = {
                Column {
                    TextField(value = q, onValueChange = { q = it.take(30) }, label = tr("搜索应用", "Search apps"), useLabelAsPlaceholder = true, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        apps.forEach { (name, pkg) ->
                            Text(name, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable {
                                picking = null
                                if (mute) run { said(VScreenMute.mute(pkg), tr("$name 已静音，直到你在这里恢复。", "$name is muted until you unmute it here.")) }
                                else { VScreenMute.setPlays(pkg, true); tick++ }
                            }.padding(vertical = 10.dp))
                        }
                    }
                }
            },
            confirmButton = { LinkButton(onClick = { picking = null }) { Text(tr("取消", "Cancel")) } })
    }
    msg?.let { m ->
        GlassDialog(onDismissRequest = { msg = null }, title = { DialogTitle(tr("应用静音", "App sound")) },
            text = { Text(m, fontSize = 14.sp, lineHeight = 21.sp) },
            confirmButton = { LinkButton(onClick = { msg = null }) { Text(tr("知道了", "Got it")) } })
    }
}
