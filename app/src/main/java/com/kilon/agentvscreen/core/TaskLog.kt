package com.kilon.agentvscreen.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 1.4.0-beta3: what the user sees as "a task" — one turn of one session (Turns: from its first command until it says
 * `done` or goes quiet), with what it was called (`vscreen title`), which app it was in, how many commands, how it
 * ended, and four small frames. The 记录 timeline and the home page's 刚才 read this; the commands themselves stay in
 * the audit log (a task's detail page reads them from there, between its start and end).
 *
 * Kept in files/tasks: log.jsonl (newest last, the last [KEEP]) and the frames beside it. A frame is taken after a
 * command at most every [FRAME_GAP_MS]; a task keeps its first frame and its last three.
 */
object TaskLog {
    const val KEEP = 200
    private const val FRAME_GAP_MS = 2_500L
    private const val FRAMES = 4
    private const val QUIET_MS = 10 * 60_000L

    class Task(
        val id: Long, val session: String, val token: String, val start: Long,
        var end: Long = 0L, var title: String? = null, var pkg: String? = null,
        var commands: Int = 0, var lastCode: String = "OK", var failed: Int = 0, var stoppedByUser: Boolean = false,
        val frames: MutableList<String> = ArrayList(), @Volatile var lastFrameAt: Long = 0L,
    ) {
        /** When its last command came (only while open). */
        @Volatile var lastAt: Long = start
        val running get() = end == 0L
        fun json(): JSONObject = JSONObject().put("id", id).put("session", session).put("token", token).put("start", start).put("end", end)
            .put("title", title ?: JSONObject.NULL).put("pkg", pkg ?: JSONObject.NULL).put("commands", commands).put("last_code", lastCode)
            .put("failed", failed).put("stopped_by_user", stoppedByUser).put("frames", JSONArray(frames))
        companion object {
            fun of(o: JSONObject) = Task(o.optLong("id"), o.optString("session"), o.optString("token"), o.optLong("start"), o.optLong("end"),
                o.optString("title").takeIf { it.isNotEmpty() && it != "null" }, o.optString("pkg").takeIf { it.isNotEmpty() && it != "null" },
                o.optInt("commands"), o.optString("last_code", "OK"), o.optInt("failed"), o.optBoolean("stopped_by_user"),
                o.optJSONArray("frames")?.let { a -> MutableList(a.length()) { a.optString(it) } } ?: ArrayList())
        }
    }

    @Volatile private var dir: File? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "vscreen-tasklog").apply { isDaemon = true } }
    private val open = HashMap<String, Task>()
    private val done = ArrayList<Task>()
    @Volatile private var loaded = false

    fun init(ctx: Context) = synchronized(this) { dir = File(ctx.filesDir, "tasks").apply { mkdirs() }; loaded = false; open.clear(); done.clear() }
    fun frameFile(name: String): File? = dir?.let { File(it, name) }?.takeIf { it.isFile && name.matches(Regex("^t[0-9_]+\\.jpg$")) }

    private fun load() {
        if (loaded) return
        loaded = true
        val f = dir?.let { File(it, "log.jsonl") } ?: return
        runCatching { f.readLines().mapNotNull { l -> runCatching { Task.of(JSONObject(l)) }.getOrNull() } }.getOrNull()?.let { done.addAll(it.takeLast(KEEP)) }
    }

    /** A command of [session] finished with [code] (called for every audited command). */
    fun onCommand(session: String, token: String, argv: List<String>, code: String, exit: Int) {
        val sub = argv.firstOrNull() ?: return
        if (sub in setOf("status", "guide", "help", "remembered", "events", "diag", "version")) return
        val now = System.currentTimeMillis()
        val t = synchronized(this) {
            open.getOrPut(session) { Task(now, session, token, now) }.also { t ->
                t.commands++; t.lastCode = code; t.lastAt = now; if (exit != 0) t.failed++
                BackgroundOperation.state.value.agentTitle?.let { t.title = it }
            }
        }
        if (now - t.lastFrameAt < FRAME_GAP_MS) return
        t.lastFrameAt = now
        worker.execute { runCatching { capture(t) } }
    }

    private fun capture(t: Task) {
        val d = BackgroundOperation.previewDisplayFor(t.session) ?: return
        VScreenShell.topPackage(d.displayId)?.let { p -> if (p !in VScreenShell.protectedPackages) t.pkg = p }
        val bmp = d.latestFrame()?.takeIf { !it.isRecycled && it.width > 0 } ?: return
        val dir = dir ?: return
        val name = "t${t.id}_${System.currentTimeMillis() % 1_000_000}.jpg"
        Pictures.write(bmp, File(dir, name), Pictures.Use.THUMB)
        synchronized(this) {
            t.frames += name
            if (t.frames.size > FRAMES) t.frames.removeAt(1).let { File(dir, it).delete() }
        }
    }

    /** The turn of [session] is over (done, quiet, released); [byUser]: the user pressed stop. */
    fun onTurnEnd(session: String, byUser: Boolean = false) {
        val t = synchronized(this) { open.remove(session) } ?: return
        t.end = maxOf(t.lastAt, if (System.currentTimeMillis() - t.lastAt > QUIET_MS) t.lastAt else System.currentTimeMillis()); t.stoppedByUser = t.stoppedByUser || byUser
        synchronized(this) { load(); done += t }
        worker.execute {
            synchronized(this) {
                val drop = if (done.size > KEEP) done.subList(0, done.size - KEEP).toList() else emptyList()
                drop.forEach { old -> old.frames.forEach { n -> dir?.let { File(it, n).delete() } } }
                done.removeAll(drop.toSet())
                dir?.let { File(it, "log.jsonl").writeText(done.joinToString("\n") { it.json().toString() } + "\n") }
            }
        }
    }

    /** The user pressed stop: the running task of [session] ends as stopped by the user. */
    fun stoppedByUser(session: String) { synchronized(this) { open[session]?.stoppedByUser = true } }

    /** Newest first: running tasks, then finished ones. */
    fun recent(n: Int = KEEP): List<Task> {
        // 1.4.0-beta4: an agent that went away without `done` (or a screen released under it) left its task "running"
        // for ever; the home page then said "last: … running" over a resting screen. Quiet for QUIET_MS = over.
        val now = System.currentTimeMillis()
        synchronized(this) { open.values.filter { now - it.lastAt > QUIET_MS }.map { it.session } }.forEach { onTurnEnd(it) }
        return recentNow(n)
    }

    private fun recentNow(n: Int): List<Task> = synchronized(this) {
        load()
        (open.values.sortedByDescending { it.start } + done.asReversed()).take(n)
    }

    /** Whether any agent has ever run a task here (the home page's first-use guide). */
    fun everUsed(): Boolean = synchronized(this) { load(); open.isNotEmpty() || done.isNotEmpty() }
}
