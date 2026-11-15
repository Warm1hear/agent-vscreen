package com.kilon.agentvscreen.cmd

import android.content.Context
import android.content.Intent
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.host.UserAsk
import com.kilon.agentvscreen.core.VScreenOcr
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

// [vscreen] 1.3 示教: the user shows it once on the background screen; it is written down as the same steps an agent
// writes (`act`), so an agent can run it again with `use NAME`.
//
// What the user did is recorded as facts: where the finger landed, how it moved, how long it stayed; what was typed;
// which key. Each event also takes a reading of the screen (the accessibility words and the front app) at the moment
// it starts. A step's target and its `until` come from those readings, never from guesses:
//  - a tap on words that are on the screen once becomes `tap 词` (survives layout changes); otherwise `tap x y`;
//  - what the next reading shows that this one did not (new words, another app, words gone) becomes `until …`, so the
//    replay waits for the same result instead of a fixed time, and stops when it does not come;
//  - typed text becomes a parameter `{1}` by default (the same steps then search for anything); a password is never kept.

/** One reading of the screen: the front app and the elements. */
/** One reading of the screen while recording; [act] = the activity in front (a new one is a new page, as in replay). */
internal class TeachSnap(val pkg: String?, val els: List<El>, val act: String? = null)

/** One thing the user did. */
internal sealed class TeachEv {
    abstract val t: Long
    /** The screen when it started (read on a worker; null when it could not be read). */
    @Volatile var before: TeachSnap? = null
    @Volatile internal var pending: Future<*>? = null

    class Touch(override val t: Long, val x: Int, val y: Int) : TeachEv() {
        val path = ArrayList<IntArray>().apply { add(intArrayOf(x, y, 0)) }
        @Volatile var end = -1L
        @Volatile var cancelled = false
    }
    /** [whole]: the field now reads [text] (the full screen box mirrors the field); otherwise [text] was added. */
    class Typed(override val t: Long, val text: String, val whole: Boolean = false) : TeachEv()
    class Key(override val t: Long, val name: String) : TeachEv()
}

/** One step written from what the user did, before the user reviews it. */
internal data class TeachLine(
    /** The step without its until; for typing, the text is in [typed]. */
    val head: String,
    val until: String?,
    /** In words, for the review page: what it was and why the step is written so. */
    val why: String,
    val kind: String,
    val typed: String? = null,
    val secret: Boolean = false,
    /** Problems the user may want to fix (nothing changed after it, a target by coordinates). */
    val warn: String? = null,
)

internal object Teach {
    const val MAX = Steps.MAX_STEPS

    class Rec(val session: String, val displayId: Int, val purpose: String?, val askId: Int?, val name: String?) {
        val events = ArrayList<TeachEv>()
        val at = System.currentTimeMillis()
    }

    /** What the review page shows: the steps and where they came from. */
    class Result(val session: String, val purpose: String?, val askId: Int?, val name: String?, val app: String?, val lines: List<TeachLine>)

    @Volatile private var rec: Rec? = null
    private val _count = MutableStateFlow<Int?>(null)
    /** Steps recorded so far while recording; null = not recording. */
    val count: StateFlow<Int?> = _count.asStateFlow()
    @Volatile var result: Result? = null; private set
    private var current: TeachEv.Touch? = null
    private val reader = Executors.newSingleThreadExecutor { r -> Thread(r, "vscreen-teach").apply { isDaemon = true } }

    /** For tests: a reading without the phone. */
    internal var snapSource: ((Int) -> TeachSnap?)? = null

    val recording: Boolean get() = rec != null
    fun recordingFor(session: String) = rec?.session == session

    fun start(session: String, purpose: String?, askId: Int?, name: String? = null): Boolean {
        val id = BackgroundOperation.state.value.displayId ?: return false
        synchronized(this) { rec = Rec(session, id, purpose, askId, name); current = null; result = null }
        _count.value = 0
        UserAsk.hold(askId)
        return true
    }

    /**
     * 1.3.0-beta14: the user took an agent's "show me how" (from any card, the notification or the full screen page):
     * take the screen over and record at once. The question's card goes away while the lesson is under way.
     */
    fun beginFor(a: UserAsk.Ask): Boolean {
        if (!a.live || a.kind != UserAsk.Kind.TEACH) return false
        if (rec?.askId == a.id) return true
        val st = BackgroundOperation.state.value
        val session = st.ownerSessionId ?: return false
        if (st.userStopped) BackgroundOperation.userResume()
        if (!BackgroundOperation.beginTakeover(session) && BackgroundOperation.state.value.takeoverSessionId != session) return false
        return start(session, a.title, a.id, a.options.firstOrNull())
    }

    fun cancel() { synchronized(this) { rec = null; current = null; typing = null; _count.value = null }; UserAsk.hold(null) }

    /** While recording an agent's request: who asked and what (the full screen page shows it in full). */
    fun brief(): Pair<String?, String>? = rec?.let { r -> r.purpose?.takeIf { it.isNotBlank() }?.let { p -> (r.askId?.let { UserAsk.byId(it)?.tokenName }) to p } }

    private fun read(id: Int): TeachSnap? {
        snapSource?.let { return it(id) }
        val d = BackgroundOperation.state.value.ownerSessionId?.let { BackgroundOperation.previewDisplayFor(it) } ?: return null
        // 1.3.0-beta14: a reading that failed (or came back empty) is "unknown", never "a blank screen": a blank before
        // made every word on the page look new, and a lesson got `until text:<title bar words>` it could never meet.
        val els = runCatching { a11yRaw(d) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return TeachSnap(runCatching { VScreenShell.topPackage(id) }.getOrNull(), els, runCatching { VScreenShell.topActivity(id) }.getOrNull())
    }

    private fun add(r: Rec, e: TeachEv) {
        if (r.events.size >= MAX) return
        r.events += e
        e.pending = reader.submit { e.before = read(r.displayId) }
        _count.value = r.events.size
    }

    // ── what the user does (UserTouch, the typing field, the keys) ──

    fun touchDown(displayId: Int, x: Int, y: Int) = synchronized(this) {
        val r = rec?.takeIf { it.displayId == displayId } ?: return
        flushTyping(r)
        current = TeachEv.Touch(System.currentTimeMillis(), x, y).also { add(r, it) }
    }

    fun touchMove(x: Int, y: Int) = synchronized(this) {
        val c = current ?: return
        if (c.path.size < 400) c.path += intArrayOf(x, y, (System.currentTimeMillis() - c.t).toInt())
    }

    /** The reading of the touch in progress (the finger's lift waits for it while recording). */
    fun lastReading(): Future<*>? = synchronized(this) { if (rec == null) null else current?.pending }

    fun touchUp(x: Int, y: Int, cancelled: Boolean) = synchronized(this) {
        val c = current ?: return
        current = null
        if (x >= 0 && y >= 0) c.path += intArrayOf(x, y, (System.currentTimeMillis() - c.t).toInt())
        c.end = System.currentTimeMillis()
        if (cancelled) c.cancelled = true
    }

    /** Typed text / a key, called on the thread that sends it: waits (briefly) until the screen before it was read. */
    fun typed(text: String) = record(TeachEv.Typed(System.currentTimeMillis(), text))
    fun key(name: String) = record(TeachEv.Key(System.currentTimeMillis(), name))

    /** 1.3.0-beta14: what the mirrored field reads now (null: unchanged); it becomes one step at the next action. */
    @Volatile private var typing: String? = null
    fun typingNow(value: String?) = synchronized(this) { if (rec != null) typing = value?.takeIf { it.isNotEmpty() } }
    fun commitTyping() = synchronized(this) { rec?.let { flushTyping(it) } }
    private fun flushTyping(r: Rec) { typing?.let { typing = null; add(r, TeachEv.Typed(System.currentTimeMillis(), it, whole = true)) } }

    private fun record(e: TeachEv) {
        synchronized(this) { val r = rec ?: return; flushTyping(r); add(r, e) }
        runCatching { e.pending?.get(800, TimeUnit.MILLISECONDS) }
    }

    /** Stop recording and write the steps (reads the screen once more). Opens the review page. */
    fun stop(ctx: Context?) {
        val r = synchronized(this) { rec?.let { flushTyping(it) }; rec.also { rec = null; current = null; typing = null } } ?: return
        _count.value = null
        Thread({
            val end = runCatching { reader.submit<TeachSnap?> { read(r.displayId) }.get(4, TimeUnit.SECONDS) }.getOrNull()
            r.events.forEach { e -> runCatching { e.pending?.get(4, TimeUnit.SECONDS) } }
            val evs = r.events.filter { it !is TeachEv.Touch || (!it.cancelled && it.end > 0) }
            val app = evs.firstNotNullOfOrNull { it.before?.pkg } ?: end?.pkg
            result = Result(r.session, r.purpose, r.askId, r.name, app, translate(evs, end))
            ctx?.let { c -> runCatching { c.startActivity(Intent(c, com.kilon.agentvscreen.ui.TeachActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }, "vscreen-teach-stop").apply { isDaemon = true }.start()
    }

    /** For tests: stop and wait for the steps. */
    internal fun stopNow(): Result? {
        stop(null)
        val t0 = System.currentTimeMillis()
        while (result == null && System.currentTimeMillis() - t0 < 5_000) Thread.sleep(20)
        return result
    }

    /** The review page is done with the steps (saved, discarded or left): an unanswered question shows again. */
    fun clearResult() { result = null; if (rec == null) UserAsk.hold(null) }

    // ── from events to steps (pure) ──

    private const val SLOP = 24
    private const val PRESS_MS = 500L

    /** The words someone could read and a step could name. */
    private fun nameable(s: String) = s.length in 1..20 && s.none { it == ';' || it == '；' || it == '"' || it == '“' || it == '”' || it == '\'' || it == '\n' } &&
        !s.startsWith("#") && s.any { it.isLetter() } && !Regex("\\s+(or|或)\\s+").containsMatchIn(s)

    /** Words that make a good "it worked" sign: real words, not a clock or a counter. */
    private fun sign(s: String) = s.length in 2..16 && nameable(s) && !s.contains(" until ")

    private fun texts(sn: TeachSnap?) = sn?.els.orEmpty().filter { !it.placeholder }

    /** The words of [sn] exactly once on the screen. */
    private fun unique(sn: TeachSnap?, text: String): Boolean = texts(sn).count { VScreenOcr.norm(it.text) == VScreenOcr.norm(text) } == 1

    private fun quote(s: String) = if (s.any { it.isWhitespace() }) "\"$s\"" else s

    /** The words under (x, y) that name it once, or null. */
    internal fun wordsAt(sn: TeachSnap?, x: Int, y: Int): String? {
        val under = texts(sn).filter { it.box.contains(x, y) }.sortedBy { it.box.width().toLong() * it.box.height() }
        val e = under.firstOrNull() ?: return null
        val t = e.text.trim()
        if (!nameable(t) || !unique(sn, t)) return null
        // A word alone that is a number pair would read as coordinates.
        if (Regex("^[\\d\\s,，.]+$").matches(t)) return null
        return t
    }

    /** What changed from [a] to [b], as a condition; null when the readings show no difference. */
    /** Nothing a person could read moved, appeared or went away between [a] and [b] (the step changed nothing). */
    internal fun sameScreen(a: TeachSnap, b: TeachSnap): Boolean {
        fun key(sn: TeachSnap) = texts(sn).map { Triple(VScreenOcr.norm(it.text), it.box.centerX() / 24, it.box.centerY() / 24) }.toSet()
        return a.pkg == b.pkg && a.act == b.act && key(a) == key(b)
    }

    /**
     * The `until` for a step: what it caused, judged by the SAME rule replay uses ([counts], 1.3.0-beta14). Words count
     * when they were not at that place before the step, unless the step opened a new page (another app or activity).
     * A step after which nothing changed gets no `until` (replay would wait for a change that never comes).
     */
    internal fun changeCond(a: TeachSnap?, b: TeachSnap?, skip: Set<String> = emptySet()): Pair<String, String>? {
        if (a == null || b == null || sameScreen(a, b)) return null
        val before = texts(a).map { VScreenOcr.norm(it.text) }.toSet()
        val after = texts(b).map { VScreenOcr.norm(it.text) }.toSet()
        val newPage = (a.pkg != null && b.pkg != null && a.pkg != b.pkg) || (a.act != null && b.act != null && a.act != b.act)
        fun priorAt(t: String) = texts(a).filter { VScreenOcr.norm(it.text) == VScreenOcr.norm(t) }.map { it.box }
        val skipN = skip.map { VScreenOcr.norm(it) }
        fun ok(t: String) = sign(t) && skipN.none { s -> s.isNotEmpty() && (VScreenOcr.norm(t).contains(s) || s.contains(VScreenOcr.norm(t))) }
        val fresh = texts(b).filter { counts(it.box, priorAt(it.text), null, newPage) && (newPage || VScreenOcr.norm(it.text) !in before) && ok(it.text.trim()) && unique(b, it.text.trim()) }
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
        fresh.firstOrNull()?.let { return "text:${it.text.trim()}" to tr("之后出现了「${it.text.trim()}」", "then \"${it.text.trim()}\" appeared") }
        if (a.pkg != null && b.pkg != null && a.pkg != b.pkg) return "app:${b.pkg}" to tr("之后换到了 ${b.pkg}", "then ${b.pkg} came to the front")
        val gone = texts(a).filter { VScreenOcr.norm(it.text) !in after && ok(it.text.trim()) && unique(a, it.text.trim()) }
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
        gone.firstOrNull()?.let { return "gone:${it.text.trim()}" to tr("之后「${it.text.trim()}」不见了", "then \"${it.text.trim()}\" went away") }
        return null
    }

    /** The steps for [evs]; [end] is the screen when recording stopped. */
    internal fun translate(evs: List<TeachEv>, end: TeachSnap?): List<TeachLine> {
        val out = ArrayList<TeachLine>()
        var i = 0
        while (i < evs.size) {
            val e = evs[i]
            // Typing sent in several pieces into the same box is one step.
            if (e is TeachEv.Typed) {
                val sb = StringBuilder(e.text)
                while (i + 1 < evs.size && evs[i + 1] is TeachEv.Typed) { i++; val n = evs[i] as TeachEv.Typed; if (n.whole) sb.setLength(0); sb.append(n.text) }
                val secret = e.before?.els.orEmpty().any { it.focused && it.password }
                out += TeachLine("type", null, if (secret) tr("在密码框里输入（原文不保存）", "typed into a password box (not kept)") else tr("输入「${sb.toString().take(30)}」", "typed \"${sb.toString().take(30)}\""),
                    "type", typed = sb.toString(), secret = secret)
                i++; continue
            }
            val next = evs.getOrNull(i + 1)?.before ?: end
            when (e) {
                is TeachEv.Key -> {
                    val c = changeCond(e.before, next)
                    out += TeachLine("key ${e.name}", c?.first, tr("按了 ${e.name}", "pressed ${e.name}") + (c?.let { "，" + it.second } ?: ""), "key",
                        warn = if (c == null && next != null) tr("之后画面上没读到变化", "no change was read afterwards") else null)
                }
                is TeachEv.Touch -> out += touchLine(e, next)
                else -> {}
            }
            i++
        }
        return out
    }

    private fun touchLine(e: TeachEv.Touch, next: TeachSnap?): TeachLine {
        val last = e.path.last()
        val far = e.path.maxOf { hypot((it[0] - e.x).toDouble(), (it[1] - e.y).toDouble()) }
        val ms = (e.end - e.t).coerceAtLeast(0)
        if (far < SLOP) {
            val press = ms >= PRESS_MS
            val words = wordsAt(e.before, e.x, e.y)
            val target = words?.let { quote(it) } ?: "${e.x} ${e.y}"
            val verb = if (press) "press" else "tap"
            val head = if (press) "$verb $target ${((ms + 50) / 100 * 100).coerceIn(100, 10_000)}ms" else "$verb $target"
            val c = changeCond(e.before, next, listOfNotNull(words).toSet())
            val what = (if (press) tr("长按", "held") else tr("点了", "tapped")) + (words?.let { tr("「$it」", " \"$it\"") } ?: " (${e.x}, ${e.y})")
            val warn = when {
                c == null && next != null -> tr("之后画面上没读到变化：可能点空了，可以不勾这一步", "no change was read afterwards: it may have missed; you can untick it")
                words == null -> tr("那里没有能叫出名字的字，按坐标记", "no words name that place: kept as a point")
                else -> null
            }
            return TeachLine(head, c?.first, what + (c?.let { "，" + it.second } ?: ""), verb, warn = warn)
        }
        // Moved: a straight line is a swipe; a bent path is a drag through its turns.
        val dur = ms.coerceIn(50, 10_000)
        val pts = e.path
        val dx = (last[0] - e.x).toDouble(); val dy = (last[1] - e.y).toDouble()
        val len = max(1.0, hypot(dx, dy))
        val bend = pts.maxOf { abs((it[0] - e.x) * dy - (it[1] - e.y) * dx) / len }
        if (bend < 48 || pts.size < 4) {
            return TeachLine("swipe ${e.x} ${e.y} ${last[0]} ${last[1]} ${dur}ms", null, tr("从 (${e.x}, ${e.y}) 滑到 (${last[0]}, ${last[1]})", "swiped from (${e.x}, ${e.y}) to (${last[0]}, ${last[1]})"), "swipe")
        }
        val n = 6
        val picked = (0 until n).map { k -> pts[(k * (pts.size - 1)) / (n - 1)] }.distinctBy { it[0] to it[1] }
        return TeachLine("drag " + picked.joinToString(" ") { "${it[0]} ${it[1]}" } + " ${dur}ms", null,
            tr("拖动，经过 ${picked.size} 个点", "dragged through ${picked.size} points"), "drag")
    }

    /**
     * The segment text from the reviewed lines: [keep] the lines kept, [literal] the typing lines whose words are kept
     * as they were (others become {1}, {2} …). Returns the text and how many parameters it takes.
     */
    internal fun build(lines: List<TeachLine>, keep: Set<Int>, literal: Set<Int>): Pair<String, Int> {
        var p = 0
        val steps = lines.withIndex().filter { it.index in keep }.map { (i, l) ->
            val head = if (l.kind == "type") {
                if (i in literal && !l.secret && l.typed != null && l.typed.none { it == ';' || it == '；' || it == '\n' }) "type ${l.typed}" else "type {${++p}}"
            } else l.head
            if (l.until != null) "$head until ${l.until}" else head
        }
        return steps.joinToString("; ") to p
    }

    /** Replace {1}, {2} … in [body] by [params]; the names of the ones not given. */
    internal fun fill(body: String, params: List<String>): Pair<String, List<Int>> {
        val missing = ArrayList<Int>()
        val s = Regex("\\{(\\d)\\}").replace(body) { m ->
            val k = m.groupValues[1].toInt()
            params.getOrNull(k - 1) ?: run { missing += k; m.value }
        }
        return s to missing.distinct()
    }
}

/** `teach "<what to show>"`: the user shows it once on the background screen; the answer is the saved segment. */
internal fun VScreenOffloadHandler.teachCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val session = sid ?: VScreenOffloadHandler.DEFAULT_SESSION
    val what = args.positional.joinToString(" ").trim()
    if (what.isEmpty()) throw ActionError("INVALID_ARGS", tr("teach \"要演示什么\" [--name 名字]", "teach \"what to show\" [--name name]"))
    if (what.length > 200) throw ActionError("INVALID_ARGS", tr("最多 200 字", "at most 200 characters"))
    val name = args.get("name")?.trim()?.replace(Regex("\\s+"), "-")?.take(32)?.takeIf { it.isNotEmpty() }
    val call = Call.current()
    val inTask = call?.taskId != null
    val valid = 15 * 60_000L
    val wait = if (inTask && args.get("wait") == null) valid else bounded("wait", parseDuration(args.get("wait"), 120_000L), 0L, if (inTask) valid else ASK_MAX_WAIT_MS)
    val key = "teach:$session:$what:${name.orEmpty()}"
    val old = UserAsk.find(key, call?.tokenId ?: "main")?.takeIf { it.kind == UserAsk.Kind.TEACH }
    val ask = if (old != null && (old.answer != null || old.live)) old else {
        BackgroundOperation.previewDisplayFor(session) ?: throw ActionError("NO_SCREEN", tr("示教在副屏上做，现在没有副屏：先 launch 要演示的应用", "The lesson is done on the background screen and there is none: launch the app to show first"))
        UserAsk.create(context, UserAsk.Kind.TEACH, key, what, tr("在副屏上做一遍，做完点“完成”，看一下再保存", "Do it once on the background screen, tap Done, check it and save"),
            options = listOfNotNull(name), validMs = valid)
    }
    if (ask.answer == null) {
        call?.report(tr("请求已显示在手机上，等用户演示", "The request is on the phone; waiting for the user to show it"))
        UserAsk.await(ask, wait)
    }
    if (call?.cancelled == true && ask.answer == null) { UserAsk.withdraw(context, ask); throw ActionError("CANCELLED", tr("调用方断开了，请求已从手机上撤下", "The caller went away; the request was taken off the phone")) }
    when (ask.answer) {
        null -> throw ActionError("ASK_PENDING", tr("请求已显示在手机上（通知、副屏小窗和 App 首页），用户 ${wait / 1000} 秒内还没做完。告诉用户去手机上演示；之后把同一条 teach 原样再发一次就能拿到结果（${ask.validMs / 60_000} 分钟内有效）",
            "The request is on the phone (notification, the floating window and the app's home page); the user has not finished within ${wait / 1000} s. Tell the user to show it on the phone; then send the same teach again for the result (valid for ${ask.validMs / 60_000} minutes)"))
        false -> { UserAsk.markUsed(ask); UserAsk.endedWhy(ask)?.let { why -> throw ActionError("ASK_DECLINED", tr("副屏已经关闭（$why），示教请求结束了：$what。要演示请先 launch 应用再发 teach", "The background screen was closed ($why), so the lesson request ended: $what. launch the app first, then send teach again")) }
            throw ActionError("ASK_DECLINED", tr("用户没有演示（或丢弃了录下的步骤）：$what", "The user did not show it (or discarded the recording): $what")) }
        true -> UserAsk.markUsed(ask)
    }
    val r = ask.reply ?: org.json.JSONObject()
    val seg = r.optString("name"); val n = r.optInt("params")
    val use = "use $seg" + (1..n).joinToString("") { " <$it>" }
    val data = org.json.JSONObject().put("what", what).put("name", seg).put("app", r.optString("app")).put("steps", r.optString("steps"))
        .put("params", n).put("described", r.optJSONArray("described") ?: org.json.JSONArray()).put("use", use)
    data.put("message", tr("用户演示完并存成了段「$seg」（${r.optString("app")}）。照做：act \"$use\"" + (if (n > 0) "（按顺序填 $n 个参数，是用户当时输入的字的位置）" else "") + "。步骤：${r.optString("steps")}",
        "The user showed it and saved it as segment \"$seg\" (${r.optString("app")}). Run it: act \"$use\"" + (if (n > 0) " (fill $n values in order: where the user typed)" else "") + ". Steps: ${r.optString("steps")}"))
    return ok(args, data)
}
