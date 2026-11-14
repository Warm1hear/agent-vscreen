package com.kilon.agentvscreen.core

import android.graphics.Rect

/**
 * 1.3: the parts of the picture that keep moving on their own: a video, a live stream, a carousel, a game scene.
 *
 * Before, every look treated the whole picture alike. A playing video never "settled", so every action waited out its
 * 3 s settle limit; the words in it (danmaku, subtitles) filled 【这段时间】 with noise; a snapshot whose picture changed
 * while it was read was read again, so on a video page nearly every look ran OCR twice. The perception thread now
 * feeds every frame's changed cells here, and a cell that changed in most looks for a while is "moving". Waits judge
 * settling outside those areas, OCR words inside them are not listed, and the reply says which area is playing.
 *
 * A moving area is decided from the frames BEFORE an action (the caller takes [Mask] then), so the motion an action
 * causes itself (a scroll, a page sliding in) is never mistaken for a video: it lasts a second or two, not [MIN_SPAN_MS].
 * Pure (no Android pixels): fed with cell grids, testable on the JVM.
 */
class MotionTrack(val cols: Int, val rows: Int) {
    companion object {
        /** Motion must have gone on at least this long before it counts as moving (a fling or a page change is shorter). */
        const val MIN_SPAN_MS = 2_500L
        const val WINDOW_MS = 3_000L
        /** Share of looks in the window in which a cell changed. */
        const val RATE = 0.55
        const val MIN_LOOKS = 10
        /** Areas smaller than this many cells (a blinking cursor, a ticking clock digit) are not "playing". */
        const val MIN_CELLS = 3
    }

    private val looks = ArrayDeque<Pair<Long, BooleanArray>>()

    @Synchronized fun feed(atMs: Long, cells: BooleanArray) {
        if (cells.size != cols * rows) return
        looks.addLast(atMs to cells)
        while (looks.isNotEmpty() && atMs - looks.first().first > WINDOW_MS) looks.removeFirst()
    }

    @Synchronized fun clear() = looks.clear()

    /** Cells that keep moving now (null = none, or not enough looks to say). */
    @Synchronized fun moving(nowMs: Long): BooleanArray? {
        val fresh = looks.filter { nowMs - it.first <= WINDOW_MS }
        if (fresh.size < MIN_LOOKS || fresh.last().first - fresh.first().first < MIN_SPAN_MS - 500L) return null
        // The window must reach back far enough: the motion has to have lasted, not just started.
        val counts = IntArray(cols * rows)
        for ((_, c) in fresh) for (i in c.indices) if (c[i]) counts[i]++
        val m = BooleanArray(cols * rows) { counts[it] >= fresh.size * RATE }
        // Drop tiny islands.
        val keep = BooleanArray(m.size)
        val seen = BooleanArray(m.size)
        for (s in m.indices) {
            if (!m[s] || seen[s]) continue
            val group = ArrayList<Int>(); val st = ArrayDeque<Int>(); st.addLast(s); seen[s] = true
            while (st.isNotEmpty()) {
                val i = st.removeLast(); group += i
                val cx = i % cols; val cy = i / cols
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = cx + dx; val ny = cy + dy
                    if (nx !in 0 until cols || ny !in 0 until rows) continue
                    val j = ny * cols + nx
                    if (m[j] && !seen[j]) { seen[j] = true; st.addLast(j) }
                }
            }
            if (group.size >= MIN_CELLS) group.forEach { keep[it] = true }
        }
        return keep.takeIf { k -> k.any { it } }
    }
}

/** The moving areas at one moment, in screen coordinates (and the grid behind them). */
class Mask(val cells: BooleanArray, val cols: Int, val rows: Int, val screenW: Int, val screenH: Int) {
    val rects: List<Rect> by lazy { VScreenActivity.boxes(cells, cols, rows, screenW, screenH) }
    val share: Double get() = cells.count { it }.toDouble() / maxOf(1, cells.size)

    /** Whether screen point (x, y) lies in a moving cell. */
    fun covers(x: Int, y: Int): Boolean {
        if (x !in 0 until screenW || y !in 0 until screenH) return false
        val cx = (x.toLong() * cols / screenW).toInt().coerceIn(0, cols - 1)
        val cy = (y.toLong() * rows / screenH).toInt().coerceIn(0, rows - 1)
        return cells[cy * cols + cx]
    }

    /** Most of [r] (screen coordinates) is moving. */
    fun coversMostOf(r: Rect): Boolean {
        if (r.width() <= 0 || r.height() <= 0) return covers(r.centerX(), r.centerY())
        var n = 0; var hit = 0
        for (i in 0..4) for (j in 0..2) {
            n++; if (covers(r.left + r.width() * (2 * i + 1) / 10, r.top + r.height() * (2 * j + 1) / 6)) hit++
        }
        return hit * 2 > n
    }

    /** The same areas in the coordinates of a [w]x[h] small copy of the picture. */
    fun rectsIn(w: Int, h: Int): List<Rect> = rects.map { r ->
        Rect(r.left * w / screenW, r.top * h / screenH, maxOf(r.left * w / screenW + 1, r.right * w / screenW), maxOf(r.top * h / screenH + 1, r.bottom * h / screenH))
    }

    /** Clear [cells] where this mask is set (same grid). */
    fun minus(cells: BooleanArray): BooleanArray = if (cells.size != this.cells.size) cells else BooleanArray(cells.size) { cells[it] && !this.cells[it] }

    fun line(zh: Boolean): String = rects.take(3).joinToString(if (zh) "、" else ", ") { "[${it.left},${it.top},${it.right},${it.bottom}]" }
}
