package com.kilon.agentvscreen.core

import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicReference

/**
 * 1.2.0-beta2: the user's finger on the picture (floating window or full-screen page), sent to the background screen
 * as it moves.
 *
 * Before, a touch on the picture was replayed after the finger lifted: a swipe became one synthetic swipe sent on
 * release, so a list did not move under the finger and a drag could not be steered. Now the finger is a held pointer
 * on the screen: down when it lands, moved as it moves, up when it lifts. Taps, long presses, scrolling and dragging
 * all come out of that one stream, exactly as on a real screen.
 *
 * Injection is a call into the display service, so it runs on its own thread; moves that pile up while one call is
 * in flight collapse into the newest position, which keeps the screen at the finger instead of behind it.
 */
object UserTouch {
    /** The pointer id kept for the user (agents use the low ids). */
    private const val FINGER = InputFence.USER_FINGER

    private val thread = HandlerThread("vscreen-user-touch").apply { isDaemon = true; start() }
    private val handler = Handler(thread.looper)
    private val pendingMove = AtomicReference<Pair<Int, Int>?>(null)
    @Volatile private var displayId: Int? = null
    @Volatile var sent = 0L; private set

    /** For tests: what would have been injected. */
    internal var sink: ((Int, String, Int, Int) -> Unit)? = null

    private fun inject(id: Int, op: String, x: Int, y: Int) {
        sent++
        sink?.let { it(id, op, x, y); return }
        // 1.4.2-beta6: the user's own input: not fenced, and its cancel takes away only the user's finger.
        runCatching { Control.asUser { VScreenShell.finger(id, op, FINGER, x, y) } }
    }

    /**
     * 1.4.2-beta6: run [r] on the touch thread, in order with the user's touches. A change of control is told to the
     * screen service here ([Control.fence]), so the user's first finger after taking over lands only after the
     * agent's gesture in flight was cancelled.
     */
    internal fun inOrder(r: () -> Unit) { handler.post { runCatching(r) } }

    private val flushMove = Runnable {
        val p = pendingMove.getAndSet(null) ?: return@Runnable
        val id = displayId ?: return@Runnable
        inject(id, "move", p.first, p.second)
    }

    /** The finger landed at ([x], [y]) of the screen. False when [sessionId] is not the one the user took over. */
    fun down(sessionId: String, x: Int, y: Int): Boolean {
        val s = BackgroundOperation.state.value
        val id = s.displayId ?: return false
        if (s.takeoverSessionId != sessionId) return false
        // The takeover preview already runs independently at 30 fps. Human touch does not boost full-size captures.
        begin(id, x, y)
        return true
    }

    internal fun begin(id: Int, x: Int, y: Int) {
        displayId = id
        com.kilon.agentvscreen.cmd.Teach.touchDown(id, x, y)
        pendingMove.set(null)
        handler.post { inject(id, "down", x, y) }
    }

    fun move(x: Int, y: Int) {
        if (displayId == null) return
        // Only the newest position is kept; one flush is queued at a time.
        if (pendingMove.getAndSet(x to y) == null) handler.post(flushMove)
        com.kilon.agentvscreen.cmd.Teach.touchMove(x, y)
    }

    fun up(x: Int, y: Int) = end("up", x, y)

    /** The gesture was taken away (a second finger, the window closed): cancelled, never a tap at the last point (1.4.2-beta6: before, the service lifted it, which was a tap). */
    fun cancel() = end("cancel", -1, -1)

    private fun end(op: String, x: Int, y: Int) {
        val id = displayId ?: return
        displayId = null
        val reading = com.kilon.agentvscreen.cmd.Teach.lastReading()
        com.kilon.agentvscreen.cmd.Teach.touchUp(x, y, cancelled = op == "cancel")
        val last = pendingMove.getAndSet(null)
        handler.post {
            // 示教: the screen is read before the finger lifts (a tap acts on lift), so the step's target is what was there.
            reading?.let { r -> runCatching { r.get(600, java.util.concurrent.TimeUnit.MILLISECONDS) } }
            // The last move the finger made before lifting decides where a fling starts from: never dropped.
            if (op == "up" && last != null) inject(id, "move", last.first, last.second)
            inject(id, op, x, y)
        }
    }

    val active: Boolean get() = displayId != null

}
