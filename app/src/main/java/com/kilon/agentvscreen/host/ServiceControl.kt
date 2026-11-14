package com.kilon.agentvscreen.host

import android.content.Context
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one place the service is turned on and off, and the one answer to "is it on".
 *
 * 1.3 beta4: "off" meant two different things to the user: "get out of my notification shade and my background" and
 * "agents may not use my phone". They are now two states:
 *  - ON: always running (foreground service, its notification); agents on this phone and on other devices reach it
 *    at any time.
 *  - HIDDEN (the switch off, the default way to turn it off): no service, no notification, nothing in the background.
 *    Whatever needs it wakes it ([wake]): a booked window (exact alarm), a local agent's command (the CLI's wake
 *    broadcast), and when the system refuses a background start, Shizuku starts it as the shell. Woken, it runs as a
 *    foreground service for that use and goes back to hidden by itself once nothing needs it ([shouldSleep]).
 *    Agents on other devices reach it only while it is awake: the built-in Tailscale node lives in the service.
 *  - BLOCKED: agents may not use the phone. Nothing wakes it; only the user turns it on.
 *
 * Every way to change it goes through here (the switch, the notification, the alarm, the CLI wake). What does not
 * change it, on purpose: swiping the app away from recents, and the × on the floating window (that closes the screen).
 */
object ServiceControl {
    enum class State { ON, HIDDEN, BLOCKED }

    /** A woken hidden service sleeps again this long after its last use. */
    const val SLEEP_AFTER_MS = 5 * 60_000L

    private val _state = MutableStateFlow(State.ON)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile var wokeAt = 0L; private set
    @Volatile var wokeBy: String? = null; private set

    fun now(ctx: Context): State = when {
        Prefs.serviceOn(ctx) -> State.ON
        Prefs.blocked(ctx) -> State.BLOCKED
        else -> State.HIDDEN
    }.also { _state.value = it }

    /** Hidden, but running right now because something woke it. */
    fun awakeOnDemand(ctx: Context) = now(ctx) == State.HIDDEN && HostService.isAlive

    /** The start of the next booked window not opened yet (epoch ms), or null. */
    fun nextBooking(ctx: Context): Long? = runCatching { Wake.list(ctx).mapNotNull { it.pending(System.currentTimeMillis()) }.minOrNull() }.getOrNull()

    fun hhmm(at: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(at))

    fun turnOn(ctx: Context, why: String) {
        Prefs.setServiceOn(ctx, true); Prefs.setBlocked(ctx, false)
        HostService.start(ctx)
        now(ctx)
        AppLogger.info("ServiceControl", "on: $why")
    }

    /** Out of the shade and the background; still woken by whatever needs it. */
    fun hide(ctx: Context, why: String) {
        Prefs.setServiceOn(ctx, false); Prefs.setBlocked(ctx, false)
        BackgroundOperation.stopAndRelease(tr("用户收起了服务（agent 用到时会再唤起）", "The user hid the service (it wakes again when an agent needs it)"))
        HostService.stop(ctx)
        now(ctx)
        AppLogger.info("ServiceControl", "hidden: $why")
    }

    /** Agents may not use the phone until the user says so. */
    fun block(ctx: Context, why: String) {
        Prefs.setServiceOn(ctx, false); Prefs.setBlocked(ctx, true)
        BackgroundOperation.stopAndRelease(tr("用户禁止了 agent 访问", "The user blocked agent access"))
        HostService.stop(ctx)
        now(ctx)
        AppLogger.info("ServiceControl", "blocked: $why")
    }

    /** From blocked back to hidden (woken on demand) without turning it on. */
    fun allow(ctx: Context, why: String) {
        Prefs.setBlocked(ctx, false); now(ctx)
        AppLogger.info("ServiceControl", "allowed (hidden): $why")
    }

    /** Something needs the service now. False when the user blocked agents. */
    fun wake(ctx: Context, why: String): Boolean {
        when (now(ctx)) {
            State.BLOCKED -> { AppLogger.info("ServiceControl", "not woken ($why): blocked"); return false }
            State.ON -> if (!HostService.isAlive) HostService.start(ctx)
            State.HIDDEN -> {
                wokeAt = System.currentTimeMillis(); wokeBy = why
                if (!HostService.isAlive) { AppLogger.info("ServiceControl", "woken: $why"); HostService.start(ctx) }
            }
        }
        return true
    }

    /** A booked window is due. */
    internal fun windowDue(ctx: Context): Boolean = wake(ctx, "booked window")

    /** Asked every minute by the running service: a woken hidden service goes back once nothing needs it. */
    fun shouldSleep(ctx: Context): Boolean {
        if (now(ctx) != State.HIDDEN) return false
        val t = System.currentTimeMillis()
        val lastUse = maxOf(wokeAt, com.kilon.agentvscreen.api.AgentLedger.snap.value.lastCallAt)
        if (t - lastUse < SLEEP_AFTER_MS) return false
        if (BackgroundOperation.state.value.displayId != null) return false
        if (Wake.windowOpen(ctx, t)) return false
        if (UserAsk.open.value.any { it.live }) return false
        if (runCatching { com.kilon.agentvscreen.core.VScreenRules.running().isNotEmpty() || com.kilon.agentvscreen.cmd.Tasks.running() > 0 }.getOrDefault(false)) return false
        return true
    }

    /** The home page's status line when the service is not always on: the state, then what it means. */
    fun title(ctx: Context): String = when (now(ctx)) {
        State.ON -> tr("常驻运行中", "Always on")
        State.HIDDEN -> if (HostService.isAlive) tr("已收起 · 现在被叫醒了", "Hidden · awake right now") else tr("已收起", "Hidden")
        State.BLOCKED -> tr("已禁止 agent 访问", "Agent access blocked")
    }

    fun sub(ctx: Context): String? = when (now(ctx)) {
        State.ON -> null
        State.HIDDEN -> if (HostService.isAlive) tr("用完会自己收起", "It hides again when done")
            else tr("agent 用到时自动唤起，用完再收起", "Woken when an agent needs it, hidden again after")
        State.BLOCKED -> tr("什么都叫不醒它，打开开关恢复", "Nothing wakes it; turn the switch on to resume")
    }

    /** The full story behind the "?" next to the status line. */
    fun help(): String = tr(
        "开关打开：常驻运行，通知栏里一直有它，本机和其它设备上的 agent 随时连得上。\n\n" +
            "开关关上 = 收起：通知栏和后台都没有它。本机 agent 发命令、agent 预约的时间到了，都会把它叫起来；一轮用完、安静 5 分钟后自己再收起。" +
            "收起时其它设备上的 agent 叫不醒它（内置 Tailscale 跑在服务里），只有在它醒着时（比如预约时间里）才连得进来。\n\n" +
            "禁止 agent 访问：真正的关闭，什么都叫不醒它，预约到点只通知你。\n\n" +
            "从最近任务里划掉 App 不会改变这些。手机重启后如果 Shizuku 没启动，只有预约闹钟能叫醒它。",
        "Switch on: always running, with its notification; agents on this phone and on other devices reach it at any time.\n\n" +
            "Switch off = hidden: nothing in the notification shade or the background. A local agent's command or an agent's booked time wakes it; after a turn and 5 quiet minutes it hides again. " +
            "While hidden, agents on other devices cannot wake it (the built-in Tailscale runs inside the service): they get in only while it is awake, e.g. during a booking.\n\n" +
            "Block agent access: really off. Nothing wakes it; a booking only notifies you.\n\n" +
            "Swiping the app away from recents changes none of this. After a phone restart without Shizuku running, only a booking's alarm can wake it.",
    )
}
