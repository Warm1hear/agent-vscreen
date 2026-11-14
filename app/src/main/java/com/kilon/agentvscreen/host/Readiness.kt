package com.kilon.agentvscreen.host

import android.content.Context
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject

/**
 * 1.3: can an agent use the phone right now, and if not, why — in one place.
 *
 * Before 1.3 the answer was spread out: `status` had its `degraded` list, the CLI guessed from a 5 s silence, the app's
 * banner read [Liveness], and a command found out by failing somewhere deep down. An agent that runs on a timer (at
 * 07:00, with nobody looking at the phone) needs a plain yes or no it can read before it starts, and the same reasons
 * the user sees. [now] is that answer; `status` (ready / blocking), `/v2/ping` and the scheduled wake-up check all read
 * it, so they cannot disagree.
 *
 * Items are facts the app can see without running anything slow (no shell call): it is cheap enough for every ping.
 * [Item.blocking] = commands that touch the screen will fail until it is fixed; the others make failures likely
 * (the system may freeze the app, remote callers cannot reach it) but do not stop a command by themselves.
 */
object Readiness {
    class Item(val code: String, val blocking: Boolean, val text: String) {
        fun json(): JSONObject = JSONObject().put("code", code).put("blocking", blocking).put("text", text)
    }

    /** What [evaluate] looks at; a plain value so the rule table is testable without a phone. */
    data class Inputs(
        val serviceOn: Boolean,
        val serviceAlive: Boolean,
        val foreground: Boolean,
        val privilege: String,
        val connection: String,
        val needsReconnect: Boolean,
        val userStopped: Boolean,
        val frozen: Boolean,
        /** off / builtin / system */
        val remoteMode: String,
        val remoteUp: Boolean,
    )

    fun evaluate(i: Inputs): List<Item> = buildList {
        if (!i.serviceOn) add(Item("service_off", true, tr("用户在 Agent-VScreen 里禁止了 agent 访问", "The user blocked agent access in Agent-VScreen")))
        when (i.privilege) {
            // 1.3: with Shizuku there, the next command reconnects by itself: a warning, not a block.
            "READY" -> if (i.connection == "ERROR" || i.needsReconnect)
                add(Item("not_connected", false, tr("后台连接现在断着，下一条命令会自动重连", "The background connection is down now; the next command reconnects by itself")))
            "NOT_RUNNING" -> add(Item("no_privilege", true, tr("Shizuku 没在运行（手机重启后要用户重新启动 Shizuku）", "Shizuku is not running (after a phone restart the user must start Shizuku again)")))
            "NOT_INSTALLED" -> add(Item("no_privilege", true, tr("没装 Shizuku", "Shizuku is not installed")))
            else -> add(Item("no_privilege", true, tr("Agent-VScreen 没有 Shizuku 授权", "Agent-VScreen has no Shizuku permission")))
        }
        if (i.userStopped) add(Item("user_stopped", true, tr("用户按了“停止 AI”，只有用户能解除", "The user pressed \"Stop AI\"; only the user can lift it")))
        if (i.serviceOn && i.serviceAlive && !i.foreground)
            add(Item("not_foreground_service", false, tr("服务不是前台服务，后台可能被冻结；用户打开一次 App 就会补上", "The service is not a foreground service and may be frozen in the background; opening the app once fixes it")))
        else if (i.frozen)
            add(Item("frozen_in_background", false, tr("系统最近在后台冻结过 Agent-VScreen：需要用户在系统的后台管理里允许它后台活动", "The system recently froze Agent-VScreen in the background: the user must allow background activity in the system's background management")))
        if (i.remoteMode != "off" && !i.remoteUp)
            add(Item("remote_down", false, tr("其它设备访问打开了，但 Tailscale 现在没连上：其它设备上的 agent 连不进来", "Access from other devices is on but Tailscale is not connected now: agents on other devices cannot get in")))
    }

    fun inputs(ctx: Context): Inputs {
        val s = BackgroundOperation.state.value
        val mode = runCatching { com.kilon.agentvscreen.api.ApiConfig.tailnetMode }.getOrDefault(Tailnet.Mode.OFF)
        val remoteUp = when (mode) {
            Tailnet.Mode.BUILTIN -> Tailnet.status.value.loggedIn
            Tailnet.Mode.SYSTEM -> HostService.server?.tailAddress != null
            else -> true
        }
        return Inputs(
            serviceOn = !Prefs.blocked(ctx), serviceAlive = HostService.isAlive, foreground = Liveness.foreground,
            privilege = s.privilege.name, connection = s.connection.name, needsReconnect = s.needsExplicitReconnect,
            userStopped = s.userStopped, frozen = Liveness.frozenUnresolved(), remoteMode = mode.key, remoteUp = remoteUp,
        )
    }

    fun now(ctx: Context): List<Item> = evaluate(inputs(ctx))

    fun ready(items: List<Item>): Boolean = items.none { it.blocking }

    /** `ready`, `blocking` (codes) and `warnings` (codes), as status and ping give them. */
    fun json(items: List<Item>, withText: Boolean = true): JSONObject = JSONObject()
        .put("ready", ready(items))
        .put("blocking", JSONArray(items.filter { it.blocking }.map { if (withText) it.json() else it.code }))
        .put("warnings", JSONArray(items.filter { !it.blocking }.map { if (withText) it.json() else it.code }))
}
