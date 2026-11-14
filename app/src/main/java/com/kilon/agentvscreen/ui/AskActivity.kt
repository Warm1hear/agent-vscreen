package com.kilon.agentvscreen.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilon.agentvscreen.host.UserAsk
import com.kilon.agentvscreen.tr
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 1.3: where the user answers an agent's question that is not a plain yes / no: tap a place on the picture of the
 * background screen, drag a box on it, pick an option, or write a line. The answer goes back to the waiting `ask`.
 * Points are sent in background-screen coordinates (the same ones act uses), worked out from where the finger
 * landed on the picture as drawn.
 */
class AskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra("id", -1)
        val ask = UserAsk.byId(id)
        setContent { AvsTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (ask == null || !ask.live) Gone() else Answer(ask)
        } } }
    }

    @Composable
    private fun Gone() = Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("这个问题已经回答过或过期了", "This question was already answered or has expired"), fontSize = 16.sp)
        Button(onClick = { finish() }) { Text(tr("关闭", "Close")) }
    }

    private fun send(a: UserAsk.Ask, reply: JSONObject?) {
        UserAsk.answer(this, a.id, reply != null, reply)
        finish()
    }

    @Composable
    private fun Answer(a: UserAsk.Ask) {
        val bmp = remember { a.picture?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) } }
        var point by remember { mutableStateOf<Offset?>(null) }
        var boxA by remember { mutableStateOf<Offset?>(null) }
        var boxB by remember { mutableStateOf<Offset?>(null) }
        var view by remember { mutableStateOf(IntSize.Zero) }
        var text by remember { mutableStateOf("") }
        // From the picture as drawn (fills the width, keeps the aspect) to the screen's own coordinates.
        fun toScreen(o: Offset): Pair<Int, Int> {
            val w = view.width.coerceAtLeast(1); val h = view.height.coerceAtLeast(1)
            return (o.x / w * a.screenW).roundToInt().coerceIn(0, a.screenW - 1) to (o.y / h * a.screenH).roundToInt().coerceIn(0, a.screenH - 1)
        }
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(tr("agent「${a.tokenName}」在问你", "Agent \"${a.tokenName}\" asks you"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(a.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            when (a.kind) {
                UserAsk.Kind.POINT, UserAsk.Kind.AREA -> {
                    Text(if (a.kind == UserAsk.Kind.POINT) tr("在画面上点一下你说的地方", "Tap the place you mean on the picture")
                        else tr("在画面上拖出一个框", "Drag a box on the picture"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (bmp == null) Text(tr("画面没取到", "No picture"), color = MaterialTheme.colorScheme.error)
                    else Box(Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).onSizeChanged { view = it }
                        .pointerInput(a.kind) {
                            if (a.kind == UserAsk.Kind.POINT) detectTapGestures { point = it }
                            else detectDragGestures(onDragStart = { boxA = it; boxB = it }) { c, _ -> boxB = c.position }
                        }) {
                        Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        Canvas(Modifier.fillMaxSize()) {
                            point?.let { p ->
                                drawCircle(Color(0xFFFF3B30), radius = 22f, center = p, style = Stroke(5f))
                                drawCircle(Color(0xFFFF3B30), radius = 5f, center = p)
                            }
                            val s = boxA; val e = boxB
                            if (s != null && e != null) drawRect(Color(0xFFFF3B30), topLeft = Offset(minOf(s.x, e.x), minOf(s.y, e.y)),
                                size = Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y)), style = Stroke(5f))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val ready = if (a.kind == UserAsk.Kind.POINT) point != null else boxA != null && boxB != null && boxA != boxB
                        Button(enabled = ready, onClick = {
                            val r = JSONObject().put("kind", a.kind.key)
                            if (a.kind == UserAsk.Kind.POINT) { val (x, y) = toScreen(point!!); r.put("x", x).put("y", y) }
                            else {
                                val (x1, y1) = toScreen(boxA!!); val (x2, y2) = toScreen(boxB!!)
                                r.put("x1", minOf(x1, x2)).put("y1", minOf(y1, y2)).put("x2", maxOf(x1, x2)).put("y2", maxOf(y1, y2))
                            }
                            send(a, r)
                        }) { Text(tr("就是这里", "This one")) }
                        OutlinedButton(onClick = { send(a, null) }) { Text(tr("不回答", "Decline")) }
                    }
                }
                UserAsk.Kind.CHOOSE -> {
                    a.options.forEachIndexed { i, o ->
                        Button(onClick = { send(a, JSONObject().put("kind", "choose").put("index", i + 1).put("choice", o)) }, modifier = Modifier.fillMaxWidth()) { Text(o) }
                    }
                    OutlinedButton(onClick = { send(a, null) }, modifier = Modifier.fillMaxWidth()) { Text(tr("都不是 / 不回答", "None / decline")) }
                }
                else -> {
                    OutlinedTextField(text, { text = it.take(500) }, Modifier.fillMaxWidth(), label = { Text(tr("你的回答", "Your answer")) })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = text.isNotBlank(), onClick = { send(a, JSONObject().put("kind", "text").put("text", text.trim())) }) { Text(tr("发送", "Send")) }
                        OutlinedButton(onClick = { send(a, null) }) { Text(tr("不回答", "Decline")) }
                    }
                }
            }
        }
    }
}
