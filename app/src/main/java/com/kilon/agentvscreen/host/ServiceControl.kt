package com.kilon.agentvscreen.host

import android.content.Context
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 1.3 beta2: the one place the service is turned on and off, and the one answer to "is it on".
 *
 * Three states the user can tell apart:
 *  - ON: agents can use the phone.
 *  - PAUSED: off now, back on by itself when the next window an agent booked comes (`schedule`). "I'm done for now,
 *    but my 07:00 task should still work."
 *  - OFF: off until the user turns it on. A booked window does not turn it on; it is checked, the agent is told the
 *    service is off, and the user gets a notification.
 *
 * Every way to turn it off goes through here (the switch on the home page, the notification's actions), so they all
 * mean the same. What does NOT turn it off, on purpose: swiping the app away from recents (the service is a foreground
 * service so agents can reach the phone at any time) and the × on the floating window (it closes the background
 * screen, not the service).
 */
object ServiceControl {
    enum class State { ON, PAUSED, OFF }

    private val _state = MutableStateFlow(State.ON)
    val state: StateFlow<State> = _state.asStateFlow()

    fun now(ctx: Context): State = when {
        Prefs.serviceOn(ctx) -> State.ON
        Prefs.resumeOnSchedule(ctx) -> State.PAUSED
        else -> State.OFF
    }.also { _state.value = it }

    /** The start of the next booked window not opened yet (epoch ms), or null. */
    fun nextBooking(ctx: Context): Long? = runCatching { Wake.list(ctx).mapNotNull { it.pending(System.currentTimeMillis()) }.minOrNull() }.getOrNull()

    fun hhmm(at: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(at))

    fun turnOn(ctx: Context, why: String) {
        Prefs.setServiceOn(ctx, true); Prefs.setResumeOnSchedule(ctx, false)
        HostService.start(ctx)
        now(ctx)
        AppLogger.info("ServiceControl", "on: $why")
    }

    /** [untilNextBooking]: pause (back on at the next booked window) instead of off; ignored when nothing is booked. */
    fun turnOff(ctx: Context, untilNextBooking: Boolean, why: String) {
        val pause = untilNextBooking && nextBooking(ctx) != null
        Prefs.setServiceOn(ctx, false); Prefs.setResumeOnSchedule(ctx, pause)
        BackgroundOperation.stopAndRelease(if (pause) tr("用户暂停了服务（下次预约时恢复）", "The user paused the service (back at the next booking)") else tr("用户停止了服务", "The user stopped the service"))
        HostService.stop(ctx)
        now(ctx)
        AppLogger.info("ServiceControl", "${if (pause) "paused" else "off"}: $why")
    }

    /** A booked window is due: a paused service comes back; an off one stays off. Returns whether it is on now. */
    internal fun windowDue(ctx: Context): Boolean {
        if (now(ctx) == State.PAUSED) turnOn(ctx, "booked window")
        else if (Prefs.serviceOn(ctx)) HostService.start(ctx)
        return Prefs.serviceOn(ctx)
    }

    /** What the home page says under the switch. */
    fun line(ctx: Context): String? = when (now(ctx)) {
        State.ON -> null
        State.PAUSED -> nextBooking(ctx)?.let { tr("已暂停 · ${hhmm(it)} 的预约到点自动恢复", "Paused · back on for the ${hhmm(it)} booking") } ?: tr("已暂停", "Paused")
        State.OFF -> if (nextBooking(ctx) != null) tr("已关闭 · 预约到点不会打开，只通知你", "Off · booked windows do not turn it on, they only notify you") else tr("已关闭", "Off")
    }
}
