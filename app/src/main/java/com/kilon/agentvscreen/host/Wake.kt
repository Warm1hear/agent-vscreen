package com.kilon.agentvscreen.host

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 1.3: on call at agreed times.
 *
 * An agent with a timer (every morning at 07:00, every 2 hours) reaches the phone when nobody is using it: screen off
 * for hours, the app long in the background, maybe just rebooted. That is when the system freezes or kills us, and
 * when Shizuku may be gone. Starting the service once is not enough; what the agent needs is an agreement:
 *
 *  - The agent says when it will come (`vscreen schedule "weekdays 07:00"`). We keep the schedule on disk.
 *  - [LEAD_MS] before each window an exact alarm (allowed in Doze, and one of the cases where Android lets an app
 *    start its foreground service from the background) brings the service up and checks [Readiness]. With Shizuku we
 *    also put the app on the system's temporary power allowlist for the window (`cmd deviceidle tempwhitelist`) and
 *    re-apply the battery exemptions the system may have taken back.
 *  - The check is written down (status shows it, the session gets an event) and, when something blocks, the user gets
 *    a notification in time to fix it ("Shizuku is not running; the 07:00 call will fail").
 *  - During the window a bounded partial wake lock keeps the CPU up.
 *
 * Nothing here knows what the agent will do; it only keeps the door open at the times it named.
 *
 * Not verified on a phone yet (marked so in the docs): whether a vendor's own background manager (MagicOS, MIUI …)
 * honours the exact alarm and the temporary allowlist for a frozen app.
 */
object Wake {
    private const val TAG = "Wake"
    const val LEAD_MS = 3 * 60_000L
    const val MAX_ENTRIES = 8
    const val MIN_EVERY_MIN = 30
    const val DEFAULT_FOR_MIN = 10
    const val MAX_FOR_MIN = 30
    const val ACTION_ALARM = "com.kilon.agentvscreen.WAKE_ALARM"
    /** Sent by a local CLI that found the app silent: start the service (and with it, thaw the process). */
    const val ACTION_WAKE = "com.kilon.agentvscreen.WAKE"

    // ── the schedule language ────────────────────────────────────────

    /**
     * When: `[days] HH:MM[,HH:MM…]` or `[days] every N(m|h) [from HH:MM] [to HH:MM]`.
     * Days: daily (default) / weekdays / weekends / mon-fri / mon,wed,fri; 每天 / 工作日 / 周末 too.
     * [days] bit 0 = Monday … bit 6 = Sunday. Times are minutes of the day, local time.
     */
    data class Spec(val days: Int, val times: List<Int>, val everyMin: Int = 0, val fromMin: Int = 0, val toMin: Int = 24 * 60) {
        fun minutesOfDay(): List<Int> = if (everyMin > 0) (fromMin until toMin step everyMin).toList() else times
        fun runsOn(d: DayOfWeek): Boolean = days and (1 shl (d.value - 1)) != 0
        fun describe(): String {
            val dayText = when (days) {
                0x7F -> tr("每天", "daily"); 0x1F -> tr("工作日", "weekdays"); 0x60 -> tr("周末", "weekends")
                else -> DayOfWeek.entries.filter { runsOn(it) }.joinToString(",") { it.name.take(3).lowercase() }
            }
            fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
            return if (everyMin > 0) "$dayText " + tr("每 ${everyMin} 分钟", "every $everyMin min") + (if (fromMin > 0 || toMin < 1440) " ${hm(fromMin)}–${hm(toMin)}" else "")
            else "$dayText " + times.joinToString(",") { hm(it) }
        }
    }

    class BadSpec(msg: String) : IllegalArgumentException(msg)

    private val DAY_NAMES = mapOf("mon" to 0, "tue" to 1, "wed" to 2, "thu" to 3, "fri" to 4, "sat" to 5, "sun" to 6,
        "周一" to 0, "周二" to 1, "周三" to 2, "周四" to 3, "周五" to 4, "周六" to 5, "周日" to 6, "周天" to 6)

    private fun hhmm(t: String): Int? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(t.trim()) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        if (h == 24 && mi == 0) return 24 * 60
        return if (h in 0..23 && mi in 0..59) h * 60 + mi else null
    }

    private fun days(t: String): Int? {
        when (t.lowercase()) { "daily", "everyday", "每天" -> return 0x7F; "weekdays", "工作日" -> return 0x1F; "weekends", "周末" -> return 0x60 }
        var mask = 0
        for (part in t.lowercase().split(',')) {
            val r = part.split('-')
            if (r.size == 2) {
                val a = DAY_NAMES[r[0].take(3)] ?: DAY_NAMES[r[0]] ?: return null
                val b = DAY_NAMES[r[1].take(3)] ?: DAY_NAMES[r[1]] ?: return null
                var i = a; while (true) { mask = mask or (1 shl i); if (i == b) break; i = (i + 1) % 7 }
            } else mask = mask or (1 shl (DAY_NAMES[part.take(3)] ?: DAY_NAMES[part] ?: return null))
        }
        return mask.takeIf { it != 0 }
    }

    fun parse(text: String): Spec {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        if (words.isEmpty()) throw BadSpec(tr("没写时间", "no time given"))
        val d = days(words.first())?.also { words.removeAt(0) } ?: 0x7F
        if (words.isEmpty()) throw BadSpec(tr("只有日期没有时间", "days without a time"))
        if (words.first().lowercase() in setOf("every", "每")) {
            val n = words.getOrNull(1) ?: throw BadSpec(tr("every 后面写间隔，例如 every 2h", "every needs an interval, e.g. every 2h"))
            val m = Regex("^(\\d+)(m|min|h)$").find(n.lowercase()) ?: throw BadSpec(tr("间隔写成 30m 或 2h（收到 $n）", "write the interval as 30m or 2h (got $n)"))
            val every = m.groupValues[1].toInt() * if (m.groupValues[2] == "h") 60 else 1
            if (every < MIN_EVERY_MIN || every > 24 * 60) throw BadSpec(tr("间隔要在 ${MIN_EVERY_MIN} 分钟到 24 小时之间", "the interval must be between $MIN_EVERY_MIN minutes and 24 hours"))
            var from = 0; var to = 24 * 60
            var i = 2
            while (i < words.size) {
                val k = words[i].lowercase(); val v = words.getOrNull(i + 1)?.let { hhmm(it) } ?: throw BadSpec(tr("$k 后面写 HH:MM", "$k needs HH:MM"))
                when (k) { "from", "从" -> from = v; "to", "到" -> to = v; else -> throw BadSpec(tr("看不懂 $k（只有 from / to）", "unknown word $k (only from / to)")) }
                i += 2
            }
            if (from >= to) throw BadSpec(tr("from 要早于 to", "from must be before to"))
            return Spec(d, emptyList(), every, from, to)
        }
        val times = words.joinToString(",").split(',').filter { it.isNotBlank() }.map { t -> hhmm(t)?.takeIf { it < 1440 } ?: throw BadSpec(tr("时间写成 HH:MM（收到 $t）", "write times as HH:MM (got $t)")) }.distinct().sorted()
        if (times.size > 24) throw BadSpec(tr("一条最多 24 个时间点", "at most 24 times per entry"))
        return Spec(d, times)
    }

    /** The first window start strictly after [afterMs], or null when the spec has no time at all. */
    fun next(spec: Spec, afterMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val start = Instant.ofEpochMilli(afterMs).atZone(zone).toLocalDate()
        for (k in 0..8) {
            val day: LocalDate = start.plusDays(k.toLong())
            if (!spec.runsOn(day.dayOfWeek)) continue
            for (m in spec.minutesOfDay()) {
                val at = ZonedDateTime.of(day.year, day.monthValue, day.dayOfMonth, m / 60, m % 60, 0, 0, zone).toInstant().toEpochMilli()
                if (at > afterMs) return at
            }
        }
        return null
    }

    // ── entries ──────────────────────────────────────────────────────

    class Entry(val name: String, val text: String, val spec: Spec, val forMin: Int, val session: String, val by: String, val at: Long) {
        @Volatile var nextAt: Long = 0L
        @Volatile var last: JSONObject? = null
        fun json(): JSONObject = JSONObject().put("name", name).put("when", text).put("means", spec.describe()).put("for_min", forMin)
            .put("session", session).put("by", by).put("next_at", if (nextAt > 0) nextAt / 1000 else JSONObject.NULL)
            .put("next_local", if (nextAt > 0) java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(nextAt)) else JSONObject.NULL)
            .put("last_check", last ?: JSONObject.NULL)
        fun store(): JSONObject = JSONObject().put("name", name).put("when", text).put("for", forMin).put("session", session).put("by", by).put("at", at)
            .also { o -> last?.let { o.put("last", it) } }
    }

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    @Volatile private var loaded = false
    @Volatile private var app: Context? = null

    private fun file(ctx: Context) = File(File(ctx.filesDir, "vscreen").apply { mkdirs() }, "wake.json")

    private fun load(ctx: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            app = ctx.applicationContext
            quiet("wake-load") {
                val f = file(ctx); if (!f.isFile) return@quiet
                val a = JSONObject(f.readText()).optJSONArray("entries") ?: JSONArray()
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val spec = runCatching { parse(o.getString("when")) }.getOrNull() ?: continue
                    entries += Entry(o.getString("name"), o.getString("when"), spec, o.optInt("for", DEFAULT_FOR_MIN), o.optString("session", "default"), o.optString("by", "main"), o.optLong("at"))
                        .also { e -> e.last = o.optJSONObject("last") }
                }
            }
            loaded = true
        }
    }

    private fun save(ctx: Context) = quiet("wake-save") {
        file(ctx).writeText(JSONObject().put("entries", JSONArray(synchronized(lock) { entries.map { it.store() } })).toString(1))
    }

    fun list(ctx: Context): List<Entry> { load(ctx); return synchronized(lock) { entries.toList() } }

    /** Add or replace [name]. Throws [BadSpec] / IllegalStateException (too many). */
    fun add(ctx: Context, name: String?, text: String, forMin: Int?, session: String, by: String): Entry {
        load(ctx)
        val spec = parse(text)
        val f = (forMin ?: DEFAULT_FOR_MIN).coerceIn(1, MAX_FOR_MIN)
        val e = synchronized(lock) {
            val n = name?.trim()?.takeIf { it.isNotEmpty() }?.take(32) ?: run { var i = entries.size + 1; while (entries.any { it.name == "w$i" }) i++; "w$i" }
            val others = entries.filter { it.name != n }
            if (others.size >= MAX_ENTRIES) throw IllegalStateException(tr("最多 $MAX_ENTRIES 条预约：先 schedule off 一条", "At most $MAX_ENTRIES schedules: remove one with schedule off first"))
            Entry(n, text.trim(), spec, f, session, by, System.currentTimeMillis()).also { entries.clear(); entries.addAll(others + it) }
        }
        save(ctx); arm(ctx)
        return e
    }

    fun remove(ctx: Context, name: String): Int {
        load(ctx)
        val n = synchronized(lock) { val before = entries.size; if (name == "all") entries.clear() else entries.removeAll { it.name == name }; before - entries.size }
        if (n > 0) { save(ctx); arm(ctx) }
        return n
    }

    // ── the alarm ────────────────────────────────────────────────────

    fun exactAllowed(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        runCatching { ctx.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() }.getOrNull() == true

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(ctx, 7701,
        Intent(ctx, WakeAlarmReceiver::class.java).setAction(ACTION_ALARM), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Work out every entry's next window and set one alarm for the earliest. Called on change, boot, app start and after each alarm. */
    fun arm(ctx: Context) {
        load(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val list = synchronized(lock) { entries.toList() }
        // A window that has started but not ended is still "next" (its alarm already fired, or must fire now).
        list.forEach { e -> e.nextAt = next(e.spec, now - LEAD_MS) ?: 0L }
        val first = list.filter { it.nextAt > 0 }.minOfOrNull { it.nextAt - LEAD_MS }
        val pi = alarmIntent(ctx)
        if (first == null) { am.cancel(pi); return }
        val at = maxOf(first, now + 1_000L)
        runCatching {
            if (exactAllowed(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure { AppLogger.warning(TAG, "arm: ${it.message}") }
        AppLogger.info(TAG, "armed for ${java.util.Date(at)} (${list.size} entries, exact=${exactAllowed(ctx)})")
    }

    /** The alarm went off: open every window that starts within the lead time. */
    internal fun onAlarm(ctx: Context) {
        load(ctx)
        val now = System.currentTimeMillis()
        val due = synchronized(lock) { entries.filter { it.nextAt > 0 && it.nextAt - LEAD_MS <= now + 30_000L && it.nextAt + it.forMin * 60_000L > now } }
        if (due.isNotEmpty()) {
            HostService.start(ctx)
            val until = due.maxOf { it.nextAt + it.forMin * 60_000L }
            openWindow(ctx, due, until)
        }
        // Next alarm after these windows' starts.
        synchronized(lock) { due.forEach { e -> e.nextAt = next(e.spec, e.nextAt) ?: 0L } }
        armAfter(ctx)
    }

    private fun armAfter(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val first = synchronized(lock) { entries.filter { it.nextAt > 0 }.minOfOrNull { it.nextAt - LEAD_MS } } ?: return
        val at = maxOf(first, now + 1_000L)
        runCatching {
            if (exactAllowed(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx))
        }
    }

    @Volatile private var lockHeld: PowerManager.WakeLock? = null

    private fun openWindow(ctx: Context, due: List<Entry>, untilMs: Long) {
        val holdMs = (untilMs - System.currentTimeMillis()).coerceIn(60_000L, LEAD_MS + MAX_FOR_MIN * 60_000L)
        quiet("wake-lock") {
            val pm = ctx.getSystemService(PowerManager::class.java)
            lockHeld?.let { if (it.isHeld) it.release() }
            lockHeld = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AgentVScreen:wake").apply { setReferenceCounted(false); acquire(holdMs) }
        }
        Thread({
            val t0 = SystemClock.elapsedRealtime()
            // Shizuku comes up after us after a boot, and binding takes a moment: give it a minute.
            while (!ShizukuManager.isReady() && SystemClock.elapsedRealtime() - t0 < 60_000L) Thread.sleep(1_000)
            val did = ArrayList<String>()
            if (ShizukuManager.isReady()) {
                val r = runCatching { com.kilon.agentvscreen.core.VScreenShell.run(arrayOf("cmd", "deviceidle", "tempwhitelist", "-d", holdMs.toString(), ctx.packageName), 6_000L) }.getOrNull()
                did += if (r?.exitCode == 0) tr("临时省电豁免 ${holdMs / 60_000} 分钟", "temporary power allowlist for ${holdMs / 60_000} min") else tr("临时省电豁免没成功", "temporary power allowlist failed")
                quiet("wake-power") { com.kilon.agentvscreen.core.VScreenPower.heal(ctx, "wake") }
                val s = BackgroundOperation.state.value
                if (s.connection.name == "ERROR" && !s.needsExplicitReconnect) quiet("wake-connect") { BackgroundOperation.connect(); did += tr("重新连接了后台连接", "reconnected the background connection") }
            }
            BackgroundOperation.refreshStatus()
            val items = Readiness.now(ctx)
            val ok = Readiness.ready(items)
            val check = JSONObject().put("at", System.currentTimeMillis() / 1000).put("ready", ok)
                .put("problems", JSONArray(items.map { it.json() })).put("done", JSONArray(did)).put("exact_alarm", exactAllowed(ctx))
            synchronized(lock) { due.forEach { it.last = check } }
            save(ctx)
            val whenText = due.joinToString(tr("、", ", ")) { "${it.name} " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(it.nextAt)) }
            val text = if (ok) tr("预约窗口 $whenText 已就绪：服务在运行，Shizuku 可用。", "Window $whenText is ready: the service is running and Shizuku is available.") +
                    items.joinToString("") { tr("（提醒：${it.text}）", " (note: ${it.text})") }
                else tr("预约窗口 $whenText 前的检查没通过：", "The check before window $whenText failed: ") + items.filter { it.blocking }.joinToString(tr("；", "; ")) { it.text }
            due.map { it.session }.distinct().forEach { s -> quiet("wake-event") { com.kilon.agentvscreen.api.EventHub.post(ctx, s, "wake", due.first { it.session == s }.name, text) } }
            if (!ok) quiet("wake-alert") {
                com.kilon.agentvscreen.core.VScreenHandoff.alert(ctx, 7702, tr("agent 的定时任务快到了，但 Agent-VScreen 还没准备好", "An agent's scheduled task is due, but Agent-VScreen is not ready"),
                    items.filter { it.blocking }.joinToString("\n") { it.text } + tr("\n（$whenText）", "\n($whenText)"))
            }
            AppLogger.info(TAG, "window $whenText: ready=$ok ${items.joinToString(",") { it.code }} did=$did")
        }, "vscreen-wake").apply { isDaemon = true }.start()
    }

    // ── the local wake-up (CLI) ──────────────────────────────────────

    @Volatile private var lastPoke = 0L

    /** A local caller found us silent. Start the service; nothing else, nothing returned, at most every 3 s. */
    internal fun poke(ctx: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPoke < 3_000L) return
        lastPoke = now
        AppLogger.info(TAG, "woken by a local caller")
        if (!HostService.isAlive) HostService.start(ctx)
    }
}

/** The schedule's alarm (not exported: only our own PendingIntent sends it). */
class WakeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_TIMEZONE_CHANGED || intent.action == Intent.ACTION_TIME_CHANGED) { Wake.arm(context.applicationContext); return }
        if (intent.action != Wake.ACTION_ALARM) return
        val pending = goAsync()
        Thread({ try { Wake.onAlarm(context.applicationContext) } finally { pending.finish() } }, "vscreen-wake-alarm").start()
    }
}

/**
 * Exported on purpose: a CLI on this phone (any app's terminal) that finds the app silent sends this explicit
 * broadcast. Delivering a broadcast thaws a frozen process, and the receiver makes sure the service runs. It takes no
 * extras and returns nothing, so it cannot be used to read or change anything.
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Wake.ACTION_WAKE) Wake.poke(context.applicationContext)
    }
}
