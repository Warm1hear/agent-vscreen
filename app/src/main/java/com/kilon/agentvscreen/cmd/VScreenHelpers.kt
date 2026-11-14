package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.tr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.VPixels
import com.kilon.agentvscreen.core.VScreenDisplay
import com.kilon.agentvscreen.core.VScreenGame
import com.kilon.agentvscreen.core.VScreenOcr
import com.kilon.agentvscreen.core.VScreenPerception
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.core.VScreenSpots
import kotlin.math.roundToInt

// [vscreen] helpers: spots (colour blobs), zoom (a closer
// picture of an area), collect (scroll a list and gather its lines). They answer questions; they do not act on
// the app beyond collect's scrolling. Their numbers (#N, S1…) are screen points the act steps take.

private fun VScreenOffloadHandler.freshFrameOf(d: VScreenDisplay): Bitmap {
    val t0 = SystemClock.elapsedRealtime()
    d.requestFreshFrame()
    d.awaitFrameAfter(t0, 600)
    return d.latestFrame() ?: throw ActionError("NO_FRAME", tr("副屏还没有画面", "the screen has no picture yet"))
}

/** `spots [x1,y1,x2,y2] [--color #RRGGBB]`: colour blobs found on the device (no model), numbered S1…, with centres. */
internal fun VScreenOffloadHandler.spotsCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
    val region = args.positional.firstOrNull()?.let { parseRect(it, tr("区域", "region")) }
    val color = args.get("color")?.let { VScreenSpots.parseColor(it) ?: throw ActionError("INVALID_ARGS", tr("--color 写成 #RRGGBB", "write --color as #RRGGBB")) }
    return BackgroundOperation.agentAction(s, null, isRead = true, describe = tr("找色块", "find color spots")) { d ->
        val snap = snapshot(d)
        val frame = snap.frame ?: throw ActionError("NO_FRAME", tr("副屏还没有画面", "the screen has no picture yet"))
        // v0.5: blobs that are letters (inside an OCR word box) are not targets: left out, and counted.
        val words = snap.items.orEmpty().map { it.box }
        fun isText(b: Rect): Boolean = words.any { w -> val i = Rect(b); i.intersect(w) && i.width().toLong() * i.height() * 2 >= b.width().toLong() * b.height() }
        val found = VScreenSpots.find(frame, region = region, color = color, limit = 120)
        val list = found.filter { !isText(it.box) }.take(60)
        val textual = found.size - found.count { !isText(it.box) }
        if (list.isEmpty()) return@agentAction text(tr("没找到色块", "No color spots found") + (region?.let { tr("（区域 ${Report.box(it)}）", " (region ${Report.box(it)})") } ?: "") + if (textual > 0) tr("（有 $textual 个是文字，已略过）", " ($textual on text were skipped)") else "")
        val sb = StringBuilder(tr("色块（本地按颜色找的，不是识别；tap 用括号里的坐标）", "Color spots (found locally by color, not recognized; tap the coordinates in brackets)") + (if (textual > 0) tr("，另有 $textual 个落在文字上的已略过", "; $textual more on text were skipped") else "") + tr("：\n", ":\n"))
        list.forEachIndexed { i, sp ->
            sb.append("S${i + 1} ${sp.shape}${if (sp.hollow) tr("(空心)", "(hollow)") else ""} ${VScreenSpots.hex(sp.color)} (${sp.cx},${sp.cy}) ${sp.box.width()}x${sp.box.height()}\n")
        }
        runCatching { VScreenGame.annotate(frame, list.mapIndexed { i, sp -> VScreenGame.Label("S${i + 1}", sp.box, sp.cx to sp.cy) }) }.getOrNull()?.let { b ->
            savePic(s, b, "spots")?.let { sb.append(tr("标注图 ", "marked picture ")).append(it).append('\n') }; b.recycle()
        }
        text(sb.toString())
    }
}

/** `zoom x1,y1,x2,y2 [--scale N]`: that area enlarged, with screen coordinates on its edges, plus the elements in it. */
internal fun VScreenOffloadHandler.zoomCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
    val r0 = args.positional.firstOrNull()?.let { parseRect(it, tr("区域", "region")) } ?: throw ActionError("INVALID_ARGS", "zoom x1,y1,x2,y2 [--scale 2]")
    return BackgroundOperation.agentAction(s, null, isRead = true, describe = tr("放大看", "zoom in")) { d ->
        val frame = freshFrameOf(d)
        val r = Rect(r0.left.coerceIn(0, frame.width - 1), r0.top.coerceIn(0, frame.height - 1), r0.right.coerceIn(1, frame.width), r0.bottom.coerceIn(1, frame.height))
        if (r.width() < 4 || r.height() < 4) throw ActionError("INVALID_ARGS", tr("区域太小或在副屏外（副屏 ${d.width}x${d.height}）", "region too small or outside the screen (screen ${d.width}x${d.height})"))
        val maxK = 1280.0 / maxOf(r.width(), r.height())
        val k = (args.getDouble("scale") ?: minOf(4.0, maxK)).coerceIn(0.5, maxOf(1.0, maxK))
        val w = (r.width() * k).roundToInt(); val h = (r.height() * k).roundToInt()
        val m = 34
        val out = Bitmap.createBitmap(w + m, h + m, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.WHITE)
        c.drawBitmap(frame, r, Rect(m, m, m + w, m + h), Paint(Paint.FILTER_BITMAP_FLAG))
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f; color = Color.rgb(30, 30, 160) }
        val step = listOf(10, 20, 50, 100, 200, 500).first { it * k >= 60 || it == 500 }
        var v = (r.left / step + 1) * step
        while (v < r.right) { val x = m + ((v - r.left) * k).toFloat(); c.drawLine(x, (m - 6).toFloat(), x, m.toFloat(), tick); c.drawText("$v", x + 2, (m - 8).toFloat(), tick); v += step }
        v = (r.top / step + 1) * step
        while (v < r.bottom) { val y = m + ((v - r.top) * k).toFloat(); c.drawLine((m - 6).toFloat(), y, m.toFloat(), y, tick); c.drawText("$v", 1f, y - 2, tick); v += step }
        val path = savePic(s, out, "zoom"); out.recycle()
        val inside = synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[s] }
            ?.takeIf { it.generation == d.generation }?.list?.filter { Rect.intersects(it.box, r) }.orEmpty()
        val sb = StringBuilder(run { val x = "%.1f".format(k); tr("区域 ${Report.box(r)} 放大 $x 倍", "Region ${Report.box(r)} zoomed ×$x") } + (path?.let { tr("：$it", ": $it") } ?: tr("（图没存下来）", " (picture not saved)")) + tr("（边上是副屏坐标）\n", " (screen coordinates on the edges)\n"))
        if (inside.isNotEmpty()) { sb.append(tr("上次列表里在这块的元素：\n", "elements from the last list inside it:\n")); inside.forEach { sb.append(it.line()).append('\n') } }
        text(sb.toString())
    }
}

/** Char bigrams of a normalised text (for "is this the same entry read twice"). */
private fun bigrams(t: String): Set<String> = if (t.length < 2) setOf(t) else (0 until t.length - 1).map { t.substring(it, it + 2) }.toSet()

/** Same entry: one text inside the other, or most of their character pairs shared (OCR splits / spaces differ). */
internal fun sameEntry(a: String, b: String): Boolean {
    if (a.isEmpty() || b.isEmpty()) return false
    if (a.contains(b) || b.contains(a)) return true
    val x = bigrams(a); val y = bigrams(b)
    return x.intersect(y).size.toDouble() / x.union(y).size >= 0.6
}

/**
 * The entries of one screen: OCR rows grouped into blocks — rows that follow each other with a gap smaller than a
 * line, and overlap sideways, are one entry (a title wrapped over two lines is one entry, not two).
 */
internal fun entriesOf(items: List<VScreenOcr.Item>): List<String> {
    val rows = VScreenOcr.rows(items.filter { !VScreenOcr.isJunk(it) && !isJunkLabel(it.text.trim()) })
        .map { r -> Triple(r.text.trim(), Rect(r.items.first().box).apply { r.items.forEach { union(it.box) } }, r) }
        .filter { it.first.isNotEmpty() }
    if (rows.isEmpty()) return emptyList()
    val h = rows.map { it.second.height() }.sorted()[rows.size / 2].coerceAtLeast(8)
    val out = ArrayList<StringBuilder>()
    var last: Rect? = null
    var rowsIn = 0
    for ((t, box, _) in rows.sortedBy { it.second.top }) {
        val l = last
        // 1.1: a wrapped title is joined, a long paragraph is not swallowed whole: at most 3 rows / 90 characters per
        // entry, then a new entry starts (an answer's body becomes several readable entries, not one 3000-char block).
        val joins = l != null && box.top - l.bottom <= h * 0.6 && box.left < l.right && l.left < box.right &&
            rowsIn < 3 && out.last().length + t.length <= 90
        if (joins) { out.last().append(if (t.first().isLetterOrDigit() && out.last().last().isLetterOrDigit() && t.first().code < 128) " " else "").append(t); l!!.union(box); rowsIn++ }
        else { out += StringBuilder(t); last = Rect(box); rowsIn = 1 }
    }
    return out.map { it.toString() }
}

/**
 * 1.3: the entries of one screen from the fused elements (see mergeElements), not from OCR lines: a tappable container
 * the app drew (a list row, a card) is one entry with every word inside it, in reading order; loose words are joined
 * when they follow each other in one column. Before, OCR rows ran across the whole width, so the titles of two cards
 * side by side became one entry, and the cards' own descriptions (title, views, length) were never used.
 * [fixed] = boxes that stayed in place while the page scrolled (a sticky search box, a bottom bar): left out, wherever
 * their words change (a rotating placeholder was recorded once per wording).
 */
internal fun entriesOfEls(els: List<El>, screenW: Int, screenH: Int, fixed: List<Rect> = emptyList()): List<Pair<String, Rect>> {
    fun isFixed(e: El) = fixed.any { f -> Math.abs(f.centerX() - e.box.centerX()) <= 16 && Math.abs(f.centerY() - e.box.centerY()) <= 16 }
    val live = els.filter { !isFixed(it) && (it.kind == ElKind.TEXT && it.text.isNotBlank()) }
    val area = screenW.toLong() * screenH
    val cards = live.filter { it.fromA11y && it.clickable && it.box.width().toLong() * it.box.height() in (area / 60)..(area / 2) }
        .sortedByDescending { it.box.width().toLong() * it.box.height() }
    val taken = HashSet<El>()
    val out = ArrayList<Pair<String, Rect>>()
    for (c in cards) {
        if (c in taken) continue
        val inside = live.filter { it !in taken && it !== c && c.box.contains(it.box.centerX(), it.box.centerY()) }
        taken += c; taken += inside
        val row = maxOf(16, screenH / 120)
        val words = (listOf(c) + inside).sortedWith(compareBy({ it.box.centerY() / row }, { it.box.centerX() })).map { it.text.trim() }
            .fold(ArrayList<String>()) { acc, w -> if (acc.none { VScreenOcr.norm(it).contains(VScreenOcr.norm(w)) }) { acc.removeAll { VScreenOcr.norm(w).contains(VScreenOcr.norm(it)) }; acc += w }; acc }
        out += words.joinToString(" · ") to Rect(c.box)
    }
    // Loose words: joined downwards within one column (overlapping sideways, a small gap), at most 3 pieces / 90 chars.
    val loose = live.filter { it !in taken }.sortedBy { it.box.top }
    if (loose.isNotEmpty()) {
        val h = loose.map { it.box.height() }.sorted()[loose.size / 2].coerceAtLeast(8)
        class B(val sb: StringBuilder, val box: Rect, var n: Int)
        val open = ArrayList<B>()
        for (e in loose) {
            val b = open.lastOrNull { l -> e.box.top - l.box.bottom in -h / 2..(h * 6 / 10) && e.box.left < l.box.right && l.box.left < e.box.right && l.n < 3 && l.sb.length + e.text.length <= 90 }
            if (b != null) { b.sb.append(if (e.text.first().isLetterOrDigit() && b.sb.last().isLetterOrDigit() && e.text.first().code < 128) " " else "").append(e.text.trim()); b.box.union(e.box); b.n++ }
            else open += B(StringBuilder(e.text.trim()), Rect(e.box), 1)
        }
        open.forEach { out += it.sb.toString() to it.box }
    }
    val row = maxOf(16, screenH / 120)
    return out.sortedWith(compareBy({ it.second.top / row }, { it.second.left }))
}

/** 1.3, pure: boxes that are at the same place in two looks taken before and after a scroll (fixed bars). */
internal fun fixedBoxes(before: List<El>, after: List<El>): List<Rect> =
    after.filter { a -> before.any { b -> b.kind == a.kind && Math.abs(b.box.centerX() - a.box.centerX()) <= 12 && Math.abs(b.box.centerY() - a.box.centerY()) <= 12 &&
        Math.abs(b.box.height() - a.box.height()) <= 16 } }.map { Rect(it.box) }

/** `collect [--pages N] [--until 文字]`: scroll down page by page and gather the entries (de-duplicated). */
internal fun VScreenOffloadHandler.collectCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", tr("没有会话（请求头 X-VScreen-Session）", "No session (header X-VScreen-Session)"))
    val pages = bounded("pages", args.getInt("pages") ?: 8, 1, 40)
    val until = args.get("until")?.let { VScreenOcr.norm(it) }
    var cancelled: String? = null
    val body = BackgroundOperation.agentAction(s, null, isRead = false, describe = tr("滚动收集", "scroll and collect")) { d ->
        VScreenPerception.ensure(d)
        val entries = ArrayList<String>()
        val keys = ArrayList<String>()
        var why = tr("翻完 $pages 页", "went through $pages pages")
        var scanned = 0
        var fixed: List<Rect> = emptyList()
        var prevEls: List<El>? = null
        var chrome = 0
        var startNote: String? = null
        // 1.3 beta2: where the collection starts is said (it starts here, not at the top), or --from-top goes up first.
        if (args.hasFlag("from-top")) {
            var ups = 0
            while (ups < 15) {
                val before = d.latestFrame()?.let { VScreenPerception.small(it) }
                val x = d.width / 2
                val o = VScreenShell.swipe(d.displayId, x, (d.height * 0.25).toInt(), x, (d.height * 0.85).toInt(), 250)
                if (!o.ok) throw ActionError(o.code, o.message)
                val still = Probe(this, d, listOf(Cond("stable", "still")))
                try { waitOn(d, still, 1_500L, null) } finally { still.close() }
                val after = d.latestFrame()?.let { VScreenPerception.small(it) }
                val skip = VScreenPerception.mask(d.displayId)?.let { m -> after?.let { m.rectsIn(it.width, it.height) } }.orEmpty()
                val moved = if (before != null && after != null && before.width == after.width && before.height == after.height) VPixels.regionDiff(before, after, Rect(0, 0, after.width, after.height), skip) else 1.0
                before?.recycle(); after?.recycle(); ups++
                if (moved in 0.0..0.003 || runCatching { BackgroundOperation.canScrollBack(d.displayId) }.getOrNull() == false) break
            }
            startNote = tr("先往上滚了 $ups 次回到顶部", "scrolled up $ups times to the top first")
        } else if (runCatching { BackgroundOperation.canScrollBack(d.displayId) }.getOrNull() == true)
            startNote = tr("是从当前位置往下收集的，这一页上面还有内容没有收（要从头收：collect --from-top）", "collected from the current position down; there is more above that was not collected (from the top: collect --from-top)")
        try {
            for (p in 0 until pages) {
                if (Call.current()?.cancelled == true) throw ActionError("CANCELLED", tr("调用方取消了", "The caller cancelled"))
                val els = snapshot(d).els
                scanned++
                // Bars that did not move with the scroll are kept out from the second page on (the first page keeps them once).
                prevEls?.let { pe -> fixed = (fixed + fixedBoxes(pe, els)).distinctBy { "${it.centerX() / 12},${it.centerY() / 12}" } }
                prevEls = els
                var hit = false
                for ((e, _) in entriesOfEls(els, d.width, d.height, if (p == 0) emptyList() else fixed)) {
                    val n = VScreenOcr.norm(e)
                    if (n.length < 2) continue
                    val dup = keys.indexOfFirst { sameEntry(it, n) || (n.length >= 6 && (it.contains(n) || n.contains(it))) }
                    if (dup >= 0) { if (n.length > keys[dup].length) { keys[dup] = n; entries[dup] = e }; continue }
                    keys += n; entries += e
                    if (until != null && n.contains(until)) hit = true
                }
                if (hit) { why = run { val u = args.get("until"); tr("看到了“$u”", "saw \"$u\"") }; break }
                if (p == pages - 1) break
                val before = d.latestFrame()?.let { VScreenPerception.small(it) }
                val x = d.width / 2
                val o = VScreenShell.swipe(d.displayId, x, (d.height * 0.78).toInt(), x, (d.height * 0.28).toInt(), 450)
                if (!o.ok) throw ActionError(o.code, o.message)
                val still = Probe(this, d, listOf(Cond("stable", "still")))
                try { waitOn(d, still, 2_500L, null) } finally { still.close() }
                val after = d.latestFrame()?.let { VScreenPerception.small(it) }
                val skip = VScreenPerception.mask(d.displayId)?.let { m -> after?.let { m.rectsIn(it.width, it.height) } }.orEmpty()
                val moved = if (before != null && after != null && before.width == after.width && before.height == after.height)
                    VPixels.regionDiff(before, after, Rect(0, 0, before.width, before.height), skip) else 1.0
                before?.recycle(); after?.recycle()
                if (moved in 0.0..0.003) { why = tr("再滑画面不动了（到底了，或这块不能滚）", "swiping no longer moves the picture (the end, or this area does not scroll)"); break }
            }
        } catch (e: ActionError) {
            // 1.3: a cancelled (or interrupted) collection returns what it had, and says where it stopped.
            if (entries.isEmpty() && e.code != "CANCELLED") throw e
            cancelled = e.code
            why = tr("在第 $scanned 屏停下（${e.code}：${e.message}）", "stopped at screen $scanned (${e.code}: ${e.message})")
        }
        chrome = fixed.size
        val sb = StringBuilder(tr("收集了 ${entries.size} 条，看了 $scanned 屏，停下原因：$why（控件文字为主、OCR 补充，可能有错字；一张卡片或一行列表是一条，同一条在几屏里重复出现只记一次" + (if (chrome > 0) "；不随滚动移动的吸顶栏/底栏 $chrome 处只在第一屏记" else "") + "）\n",
            "Collected ${entries.size} items over $scanned screens, stopped because: $why (widget text first, OCR fills in, may have typos; one card or list row is one item, an item repeated across screens is counted once" + (if (chrome > 0) "; $chrome bars that stay in place while scrolling are recorded on the first screen only" else "") + ")\n"))
        startNote?.let { sb.append(it).append('\n') }
        entries.forEachIndexed { i, l -> sb.append(i + 1).append(". ").append(if (l.length > 160) l.take(150) + tr("…（这条共 ${l.length} 字）", "… (${l.length} characters in all)") else l).append('\n') }
        sb.toString()
    }
    cancelled?.let { return NativeOffloadResult(1, body, it, softFail = true) }
    return text(body)
}
