package com.kilon.agentvscreen.ui

import com.kilon.agentvscreen.tr

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.DisposableEffect
import com.kilon.agentvscreen.core.VScreenTyping
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kilon.agentvscreen.api.Turns
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.VScreenHandoff
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.cmd.Teach
import com.kilon.agentvscreen.host.UserAsk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The background screen full screen, as a native page: edge-to-edge and immersive, the picture fitted with the same
 * breathing border as the floating window. Hold the picture 1 s to take over; then taps, long presses and swipes go
 * to the background screen, and the bottom bar gives back / home / typing / enter. Stop, hand back and "move this
 * app to my screen" are one tap away.
 */
class FullscreenActivity : PageActivity() {
    override val closeWhenHidden = true
    // The floating window shows the same picture: it steps aside while this page is in front.

    /** 1.3 示教: opened from an agent's "show me how" (UserAsk TEACH): take over and record at once. */
    private val teachAsk = mutableStateOf(-1)
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); teachAsk.value = intent.getIntExtra("teach_ask", -1) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        teachAsk.value = intent.getIntExtra("teach_ask", -1)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            AvsTheme {
                val s by BackgroundOperation.state.collectAsState()
                val session = s.ownerSessionId
                LaunchedEffect(session, s.displayId) { if (session == null || s.displayId == null) back() }
                if (session != null) FullScreen(session)
            }
        }
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    @Composable
    private fun FullScreen(session: String) {
        val s by BackgroundOperation.state.collectAsState()
        val mode = modeOf(s)
        val takenOver = mode == Mode.TAKEOVER
        var typing by remember { mutableStateOf(false) }
        var text by remember { mutableStateOf("") }
        var hold by remember { mutableFloatStateOf(0f) }
        var holdAt by remember { mutableStateOf(Offset.Zero) }
        var hintAt by remember { mutableLongStateOf(0L) }
        var appLabel by remember { mutableStateOf<String?>(null) }
        var left by remember { mutableLongStateOf(-1L) }
        LaunchedEffect(s.displayId, s.operating) {
            val id = s.displayId ?: return@LaunchedEffect
            appLabel = withContext(Dispatchers.IO) {
                runCatching { VScreenShell.topPackage(id) }.getOrNull()?.let { pkg ->
                    runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                }
            }
        }
        LaunchedEffect(session) { while (true) { left = Turns.leftMs(session) ?: -1L; delay(1000) } }
        LaunchedEffect(takenOver) { if (!takenOver) { typing = false; if (Teach.recordingFor(session)) Teach.stop(applicationContext) } }
        val teachN by Teach.count.collectAsState()
        val recording = teachN != null
        val askId by teachAsk
        LaunchedEffect(askId) {
            val a = UserAsk.byId(askId) ?: return@LaunchedEffect
            teachAsk.value = -1
            Teach.beginFor(a)
        }

        // 1.3.0-beta17: the picture is the fixed floor of the page. Its place depends only on the title bar above and
        // the button row below at rest, never on the keyboard, the typing box, a question or the request card: those
        // float over it (beta16 squeezed the picture into whatever space they left, a thumbnail once the keyboard
        // opened). Floating things never leak through: each is a hit target of its own (eatTouches / clickable /
        // its drag), and Compose gives a touch only to the topmost thing it lands on. The request card folds into the
        // ball in the bar as soon as the user touches the picture, so it never hides what they are about to tap.
        val density = LocalDensity.current
        var topEdge by remember { mutableFloatStateOf(0f) }
        var rowH by remember { mutableFloatStateOf(0f) }
        val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
        val brief = if (recording) Teach.brief() else null
        Box(Modifier.fillMaxSize().background(Color(0xFF07080D))) {
            // The picture, fitted, with its border.
            BoxWithConstraints(Modifier.fillMaxSize().padding(top = with(density) { topEdge.toDp() } + 6.dp,
                bottom = with(density) { (rowH + navBottom).toDp() } + 24.dp + 6.dp, start = 14.dp, end = 14.dp),
                contentAlignment = Alignment.Center) {
                val aspect = Overlay.aspect()
                val boxAspect = maxWidth / maxHeight
                val pw = if (aspect > boxAspect) maxWidth else maxHeight * aspect
                val ph = pw / aspect
                Box(Modifier.size(pw, ph).glowBorder(mode, 22.dp, width = 3.dp, pad = 10.dp)) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(Palette.ink)
                        .pointerInput(session, takenOver) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                // The user starts working on the screen: the request card steps into the bar.
                                if (Teach.count.value != null && !BriefPlace.ball) BriefPlace.ball = true
                                val t0 = SystemClock.uptimeMillis()
                                // 1.2.0-beta2: while the user controls the screen the finger is sent as it moves
                                // (UserTouch), not replayed as one swipe after it lifts.
                                val live = takenOver && map(down.position, size.width.toFloat(), size.height.toFloat())
                                    ?.let { com.kilon.agentvscreen.core.UserTouch.down(session, it.first, it.second) } == true
                                var end = down.position
                                var moved = false
                                var held = false
                                while (true) {
                                    val ev = withTimeoutOrNull(16L) { awaitPointerEvent() }
                                    val elapsed = SystemClock.uptimeMillis() - t0
                                    if (!takenOver && !moved && !held) {
                                        hold = ((elapsed - 150L) / 850f).coerceIn(0f, 1f); holdAt = down.position
                                        if (hold >= 1f) {
                                            held = true; hold = 0f
                                            if (s.userStopped) BackgroundOperation.userResume()
                                            BackgroundOperation.beginTakeover(session)
                                        }
                                    }
                                    if (ev == null) continue
                                    val ch = ev.changes.firstOrNull() ?: break
                                    end = ch.position
                                    if (live && ch.pressed) map(end, size.width.toFloat(), size.height.toFloat())?.let { com.kilon.agentvscreen.core.UserTouch.move(it.first, it.second) }
                                    if (hypot(end.x - down.position.x, end.y - down.position.y) > viewConfiguration.touchSlop) { moved = true; hold = 0f }
                                    if (takenOver) ch.consume()
                                    if (!ch.pressed) break
                                }
                                hold = 0f
                                val ms = SystemClock.uptimeMillis() - t0
                                if (live) {
                                    val b = map(end, size.width.toFloat(), size.height.toFloat())
                                    com.kilon.agentvscreen.core.UserTouch.up(b?.first ?: -1, b?.second ?: -1)
                                } else if (takenOver) {
                                } else if (!held && !moved) hintAt = SystemClock.uptimeMillis()
                            }
                        }) {
                        VScreenLiveFrame(session, Modifier.fillMaxSize(), interactive = false)
                        if (hold > 0f) Canvas(Modifier.fillMaxSize()) {
                            val r = 40.dp.toPx()
                            drawCircle(Color(0x66000000), r + 8f, holdAt)
                            drawArc(Palette.takeover, -90f, 360f * hold, false, topLeft = Offset(holdAt.x - r, holdAt.y - r), size = Size(r * 2, r * 2),
                                style = Stroke(width = 8f, cap = StrokeCap.Round))
                        }
                    }
                }
            }

            // Top block: the bar (it sets the picture's top), and the request card floating under it when at the top.
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().onGloballyPositioned { topEdge = it.boundsInRoot().bottom }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Circle(Icons.Filled.CloseFullscreen, tr("退出全屏", "Exit full screen"), Color(0x33FFFFFF)) { if (Teach.recordingFor(session)) Teach.stop(applicationContext); back() }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(appLabel ?: tr("后台副屏", "Background screen"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (mode == Mode.ACTING) Brush.sweepGradient(Palette.rainbow) else SolidColor(Palette.of(mode))))
                        Spacer(Modifier.width(5.dp))
                        val word = if (recording) tr("示教录制中 · 已记 $teachN 步", "Recording a lesson · $teachN steps") else s.agentTitle ?: (Palette.word(mode) + if (mode == Mode.STANDBY && left >= 0) (" · " + tr("${left / 60000}:${"%02d".format((left / 1000) % 60)} 后省电", "power save in ${left / 60000}:${"%02d".format((left / 1000) % 60)}")) else "")
                        Text(word, color = Color(0xCCFFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // The request folded into a ball: it lives here, in the bar, not over the picture.
                if (brief != null && BriefPlace.ball) {
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable { BriefPlace.ball = false; BriefPlace.open = true }, contentAlignment = Alignment.Center) { AskMark(30f) }
                    Spacer(Modifier.width(8.dp))
                }
                when (mode) {
                    Mode.ACTING, Mode.STANDBY -> Pill(tr("停止 AI", "Stop AI"), Icons.Filled.Stop, Palette.stopped, Color.White) { BackgroundOperation.userStop() }
                    else -> Circle(Icons.Filled.Close, tr("关闭副屏", "Close screen"), Color(0x33FFFFFF)) {
                        BackgroundOperation.releaseNow(session); back()
                    }
                }
            }

            // 1.3.0-beta14: what the agent asked to be shown, in full, while the user shows it.
            if (brief != null && !BriefPlace.ball && !BriefPlace.bottom) TeachBrief(brief.first, brief.second, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 4.dp))
            }

            // Hint.
            var now by remember { mutableLongStateOf(0L) }
            LaunchedEffect(hintAt) { now = SystemClock.uptimeMillis(); delay(2600); now = SystemClock.uptimeMillis() }
            AnimatedVisibility(now - hintAt < 2500 && hintAt > 0, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
                Text(tr("长按画面 1 秒接管，接管后点、长按、滑动都会落到副屏上", "Hold the picture 1 s to take over; then taps, long presses and swipes go to the screen"), color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xE612141C)).padding(horizontal = 14.dp, vertical = 8.dp))
            }

            // Bottom block: floats over the picture and rides on top of the keyboard.
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().navigationBarsPadding().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (brief != null && !BriefPlace.ball && BriefPlace.bottom) TeachBrief(brief.first, brief.second, Modifier)
                // 1.3 beta2: an agent's question, answered without leaving the full screen.
                val waiting = pendingAsks().filter { !(recording && it.kind == UserAsk.Kind.TEACH) }
                waiting.firstOrNull()?.let { a -> AskQuick(a, waiting.size, 1.15f, inOverlay = false, modifier = Modifier.fillMaxWidth()) }
                if (typing) {
                    // 1.3.0-beta14: this box mirrors the focused field on the screen: it starts with what the field
                    // reads, and every change (typing, deleting with the keyboard's own key) goes there as it happens.
                    val fr = remember { FocusRequester() }
                    val mirror = remember(session) { FieldMirror(session) }
                    var field by remember { mutableStateOf(TextFieldValue("")) }
                    var secret by remember { mutableStateOf(false) }
                    var none by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        val f = withContext(Dispatchers.IO) { BackgroundOperation.state.value.displayId?.let { VScreenTyping.fieldNow(it) } }
                        mirror.adopt(f); none = f == null; secret = f?.password == true
                        field = TextFieldValue(f?.text.orEmpty(), TextRange(f?.text?.length ?: 0))
                        fr.requestFocus()
                        // Follow the field when it changes by itself (another box tapped, the app cleared or filled it).
                        while (true) {
                            delay(800)
                            if (mirror.busy()) continue
                            val g = withContext(Dispatchers.IO) { BackgroundOperation.state.value.displayId?.let { VScreenTyping.fieldNow(it) } }
                            none = g == null
                            if (g != null && mirror.follow(g)) { secret = g.password; field = TextFieldValue(g.text, TextRange(g.text.length)) }
                        }
                    }
                    DisposableEffect(Unit) { onDispose { Teach.commitTyping() } }
                    fun enter() { Teach.commitTyping(); vscreenUserInput(session) { Teach.key("enter"); VScreenShell.key(it, "enter") } }
                    Row(Modifier.fillMaxWidth().eatTouches().glassDark(RoundedCornerShape(24.dp)).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.weight(1f)) {
                            if (field.text.isEmpty()) Text(if (none) tr("先在副屏上点一下输入框", "Tap an input box on the screen first") else tr("和副屏的输入框同步", "Synced with the screen's input box"),
                                color = Color(0x88FFFFFF), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            BasicTextField(field, { v -> field = v; if (v.text != mirror.sent) mirror.push(v.text) }, Modifier.fillMaxWidth().focusRequester(fr), singleLine = true,
                                textStyle = TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(Palette.standby),
                                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { enter() }))
                        }
                        Circle(Icons.AutoMirrored.Filled.Backspace, tr("删除", "Delete"), Color(0x22FFFFFF)) {
                            val t = field.text
                            if (t.isNotEmpty()) { val n = t.dropLast(1); field = TextFieldValue(n, TextRange(n.length)); mirror.push(n) }
                            else vscreenUserInput(session) { Teach.key("del"); VScreenShell.key(it, "del") }
                        }
                        Circle(Icons.AutoMirrored.Filled.KeyboardReturn, tr("回车", "Enter"), Palette.brandA) { enter() }
                    }
                }
                Row(Modifier.onSizeChanged { rowH = it.height.toFloat() }.eatTouches().clip(RoundedCornerShape(30.dp)).background(Color(0xE612141C)).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (takenOver) {
                        Circle(Icons.AutoMirrored.Filled.ArrowBack, tr("返回", "Back"), Color(0x22FFFFFF)) { vscreenUserInput(session) { Teach.key("back"); VScreenShell.key(it, "back") } }
                        Circle(Icons.Filled.Keyboard, tr("打字", "Type"), if (typing) Palette.brandA else Color(0x22FFFFFF)) { typing = !typing }
                        if (!typing) Circle(Icons.AutoMirrored.Filled.Backspace, tr("删除", "Delete"), Color(0x22FFFFFF)) { vscreenUserInput(session) { Teach.key("del"); VScreenShell.key(it, "del") } }
                        if (!typing) Circle(Icons.AutoMirrored.Filled.KeyboardReturn, tr("回车", "Enter"), Color(0x22FFFFFF)) { vscreenUserInput(session) { Teach.key("enter"); VScreenShell.key(it, "enter") } }
                        // 示教: record what you do here as steps an agent can run again.
                        if (recording) Pill(tr("完成", "Done") + " · $teachN", Icons.Filled.Stop, Palette.asking, Color.White) { Teach.finish(applicationContext) }
                        else {
                            Circle(Icons.Filled.FiberManualRecord, tr("示教：录下接下来的操作", "Teach: record what you do next"), Color(0x22FFFFFF)) {
                                if (Teach.start(session, null, null)) toast(tr("开始录制：照常操作，做完点“完成”", "Recording: do it as usual, then tap Done"))
                            }
                            Pill(tr("交还 AI", "Hand back"), Icons.Filled.SmartToy, Palette.takeover, Color(0xFF052E1C)) { BackgroundOperation.userResume() }
                        }
                    } else {
                        if (mode == Mode.STOPPED) Pill(tr("交还 AI", "Hand back"), Icons.Filled.SmartToy, Palette.takeover, Color(0xFF052E1C)) { BackgroundOperation.userResume() }
                        Pill(tr("接管", "Take over"), Icons.Filled.TouchApp, Color(0x22FFFFFF), Color.White) {
                            if (s.userStopped) BackgroundOperation.userResume()
                            BackgroundOperation.beginTakeover(session)
                        }
                        Pill(tr("移到主屏", "Move to main screen"), Icons.Filled.PhoneAndroid, Color(0x22FFFFFF), Color.White) {
                            Thread {
                                val (ok, msg) = runCatching { VScreenHandoff.userToMain(session) }.getOrElse { false to (it.message ?: tr("失败", "failed")) }
                                toast(msg); if (ok) runOnUiThread { back() }
                            }.start()
                        }
                    }
                }
            }
        }
    }

    private fun androidx.compose.ui.input.pointer.PointerInputScope.map(p: Offset, w: Float, h: Float): Pair<Int, Int>? {
        val r = android.graphics.RectF(0f, 0f, w, h)
        return pictureToScreen(p.x.coerceIn(0f, w - 1), p.y.coerceIn(0f, h - 1), r)
    }
}

@Composable
private fun Circle(icon: ImageVector, desc: String, bg: Color, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Pill(label: String, icon: ImageVector, bg: Color, fg: Color, onClick: () -> Unit) {
    Row(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Runs [op] with the display id off the main thread, only while [sessionId] holds the screen (接管). */
/** The user's typing goes out on one lane, in order, newest value only (a burst of keystrokes is one write). */
private val typingLane = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "vscreen-typing").apply { isDaemon = true } }

/** 1.3.0-beta14: keeps the full screen box and the screen's focused field the same. */
private class FieldMirror(private val session: String) {
    /** What the screen's field reads, as far as we know. */
    @Volatile var sent = ""
    private var start = ""
    private var key: String? = null
    @Volatile private var editAt = 0L
    private val latest = java.util.concurrent.atomic.AtomicReference<String?>(null)

    fun adopt(f: VScreenTyping.FieldNow?) { sent = f?.text.orEmpty(); start = sent; key = f?.key }
    /** The user typed in the last moment, or a write is on its way: do not follow the field now. */
    fun busy() = SystemClock.uptimeMillis() - editAt < 1_500 || latest.get() != null

    fun push(v: String) {
        editAt = SystemClock.uptimeMillis()
        latest.set(v)
        typingLane.execute {
            val x = latest.getAndSet(null) ?: return@execute
            val st = BackgroundOperation.state.value
            val id = st.displayId ?: return@execute
            if (st.takeoverSessionId != session) return@execute
            // 1.3.0 (test M): the lesson's "before typing" screen is read before the first character lands.
            if (x != start) runCatching { Teach.beforeTyping()?.get(1500, java.util.concurrent.TimeUnit.MILLISECONDS) }
            runCatching { VScreenTyping.setField(id, x, sent) }.onSuccess { sent = x; Teach.typingNow(if (x != start) x else null) }
        }
    }

    /** The field changed by itself: true when the box should show [g]. Another field: what was typed is one step. */
    fun follow(g: VScreenTyping.FieldNow): Boolean {
        if (g.key != key) { Teach.commitTyping(); key = g.key; start = g.text; sent = g.text; return true }
        if (g.password || g.text == sent) return false
        sent = g.text; start = g.text; Teach.typingNow(null)
        return true
    }
}

/** Where the request card is (kept while the app runs): top or bottom, open or one line, or a ball in the bar. */
private object BriefPlace {
    var open by mutableStateOf(true)
    var bottom by mutableStateOf(false)
    var ball by mutableStateOf(false)
}

/**
 * The agent's request, on dark glass with the orchid edge, in its own space above or below the picture.
 * Tap: open / one line. Drag up or down: move it to the top or the bottom. Swipe sideways: fold it into a ball in the bar.
 */
@Composable
private fun TeachBrief(who: String?, what: String, modifier: Modifier) {
    val open = BriefPlace.open
    val shape = RoundedCornerShape(if (open) 22.dp else 50.dp)
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val far = with(density) { 64.dp.toPx() }
    Column(
        modifier.fillMaxWidth().animateContentSize()
            .graphicsLayer { translationX = dx; translationY = dy * 0.5f; alpha = 1f - (kotlin.math.abs(dx) / (far * 3)).coerceIn(0f, 0.6f) }
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = {
                    when {
                        kotlin.math.abs(dx) > far -> BriefPlace.ball = true
                        dy > far -> BriefPlace.bottom = true
                        dy < -far -> BriefPlace.bottom = false
                    }
                    dx = 0f; dy = 0f
                }, onDragCancel = { dx = 0f; dy = 0f }) { ch, d -> ch.consume(); dx += d.x; dy += d.y }
            }
            .glassDark(shape).border(1.2.dp, Brush.linearGradient(listOf(Color(0xFF8B7CFF), Palette.asking)), shape)
            .clickable { BriefPlace.open = !open }.padding(horizontal = 14.dp, vertical = if (open) 12.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AskMark(18f)
            Spacer(Modifier.width(8.dp))
            Text(if (open) (who?.let { tr("「$it」请你演示", "\"$it\" asks you to show") } ?: tr("要演示的事", "What to show")) else what,
                color = if (open) Color(0xCCFFFFFF) else Color.White, fontSize = if (open) 12.sp else 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) tr("收起", "Fold") else tr("展开", "Unfold"),
                tint = Color(0x99FFFFFF), modifier = Modifier.size(18.dp))
        }
        if (open) {
            Text(what, color = Color.White, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(tr("做完点“完成”。点卡片收起，上下拖换位置，左右滑收成小球", "Tap Done when finished. Tap to fold, drag up/down to move, swipe sideways for a ball"), color = Color(0x99FFFFFF), fontSize = 11.5.sp)
        }
    }
}

internal fun vscreenUserInput(sessionId: String, op: (Int) -> Unit) {
    val s = BackgroundOperation.state.value
    val id = s.displayId ?: return
    if (s.takeoverSessionId != sessionId) return
    Thread { runCatching { op(id) } }.apply { isDaemon = true; name = "vscreen-user-input" }.start()
}
