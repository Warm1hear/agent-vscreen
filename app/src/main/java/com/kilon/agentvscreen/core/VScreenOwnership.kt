package com.kilon.agentvscreen.core

import android.content.Context
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 1.4.0-beta2: who has each app the agent uses. Three owners only:
 *  - the agent, on the background screen (no entry here);
 *  - the user, on the background screen (the existing takeover: BackgroundOperation.takeoverSessionId);
 *  - the user, on the main screen ([given]): handed out with `handoff out`, or pulled there by the user (opening the
 *    app from the launcher or recents moves a single-instance app's task off our screen).
 * Every way in (agent command, launcher, overlay button, notification) only changes this; the overlay, the replies and
 * the events read it. Detection compares task ids and display ids, never package names or app kinds, so it is the
 * same for every app.
 */
object VScreenOwnership {
    private const val TAG = "VScreenOwnership"
    /** How long after the user leaves a handed-over app before one notification asks whether to hand it back. */
    private const val NUDGE_MS = 30_000L

    data class Given(
        val pkg: String, val taskId: Int, val session: String, val at: Long,
        /** "agent" (handoff out) or "user" (the user opened it on the main screen). */
        val why: String,
        /** The activity the app was on when it left the background screen. */
        val page: String?,
        val leftAt: Long = 0L, val nudged: Boolean = false,
        /** The agent launched another app after this one left: its actions are about that app now. */
        val agentMovedOn: Boolean = false,
    )

    private val _given = MutableStateFlow<Map<String, Given>>(emptyMap())
    val given: StateFlow<Map<String, Given>> = _given.asStateFlow()

    /** Notes for an agent's next reply (session → lines), e.g. "the user also opened X on the main screen". */
    private val pendingNotes = HashMap<String, MutableList<String>>()
    /** Packages already said to be open on both screens, per session (said once). */
    private val bothSaid = HashMap<String, MutableSet<String>>()

    @Volatile private var thread: Thread? = null
    /** Tasks seen on the background screen at the last tick: task id → package. */
    private var lastOnScreen: Map<Int, String> = emptyMap()

    fun label(ctx: Context, pkg: String): String =
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    /** The entry that blocks [session]'s actions now: an app of its that the user has, and it has not moved on. */
    fun blocking(session: String?): Given? = session?.let { s -> _given.value.values.firstOrNull { it.session == s && !it.agentMovedOn } }

    fun givenFor(pkg: String): Given? = _given.value[pkg]

    /** The agent launched [pkg]: entries of other apps stop blocking it (it works on another app meanwhile). */
    fun agentLaunched(session: String, pkg: String) {
        _given.update { m -> m.mapValues { (k, g) -> if (g.session == session && k != pkg) g.copy(agentMovedOn = true) else g } }
    }

    fun takeNotes(session: String): List<String> = synchronized(pendingNotes) { pendingNotes.remove(session).orEmpty() }

    /** `handoff out` moved [pkg] (task [taskId]) to the main screen on purpose. */
    fun handedOut(ctx: Context, session: String, pkg: String, taskId: Int, page: String?) = record(ctx, Given(pkg, taskId, session, System.currentTimeMillis(), "agent", page))

    private fun record(ctx: Context, g: Given) {
        _given.update { it + (g.pkg to g) }
        AppLogger.info(TAG, "${g.pkg} (task ${g.taskId}) is with the user on the main screen (${g.why})")
        val app = label(ctx, g.pkg)
        com.kilon.agentvscreen.host.quiet("handover-event") {
            com.kilon.agentvscreen.api.EventHub.post(ctx, g.session, "handover", g.pkg,
                if (g.why == "user") tr("用户在主屏打开了 $app，它已经交给用户（副屏上没有它了）。在用户交回之前不要操作 $app；用 vscreen events --wait 等 handback，或去做别的应用。",
                    "The user opened $app on the main screen; it is with the user now (no longer on the background screen). Don't operate $app until the user hands it back; wait for handback with vscreen events --wait, or work on another app.")
                else tr("$app 已交给用户。用户交回时会有 handback 事件。", "$app is with the user. A handback event comes when the user hands it back."))
        }
        com.kilon.agentvscreen.ui.Overlay.refresh()
    }

    /**
     * Hand [pkg] back to the agent: its task goes back to the background screen on the page the user left it. When the
     * task is gone (closed on the main screen, app restarted) nothing is moved and the agent is told to launch again.
     * [by]: "overlay", "notification" or "agent". Returns what happened, for the caller to show.
     */
    fun handBack(ctx: Context, pkg: String, by: String): String {
        val g = _given.value[pkg] ?: return tr("$pkg 不在你手上", "$pkg is not with you")
        val vs = BackgroundOperation.state.value.displayId
        val onMain = runCatching { com.kilon.agentvscreen.core.VScreenHandoff.tasksOn(0) }.getOrDefault(emptyList()).any { it.taskId == g.taskId }
        val app = label(ctx, pkg)
        val (moved, line) = when {
            vs == null -> false to tr("副屏已经释放，agent 下次 launch $app 时重新打开", "The screen was released; the agent's next launch of $app opens it again")
            !onMain -> false to tr("$app 原来的页面已经没有了（在主屏关掉了或应用重启了），agent 下次 launch 时从首页打开", "$app's page is gone (closed on the main screen or the app restarted); the agent's next launch opens it from its home page")
            else -> {
                val m = com.kilon.agentvscreen.core.VScreenHandoff.move(g.taskId, pkg, vs)
                if (m.ok) true to tr("$app 已移回副屏，停在你最后的页面", "$app is back on the background screen, on the page you left it")
                else false to tr("系统没让 $app 移回副屏（${m.detail}）", "The system did not move $app back (${m.detail})")
            }
        }
        if (!moved && vs != null && onMain) return line // nothing changed: it stays with the user
        _given.update { it - pkg }
        if (moved) runCatching { BackgroundOperation.currentDisplay()?.requestFreshFrame() }
        val now = if (moved && vs != null) runCatching { VScreenShell.topActivity(vs) }.getOrNull() else null
        AppLogger.info(TAG, "$pkg handed back by $by (moved=$moved)")
        com.kilon.agentvscreen.host.quiet("handback-event") {
            com.kilon.agentvscreen.api.EventHub.post(ctx, g.session, "handback", pkg, line + "。" +
                (if (moved) tr("现在的页面 ${now ?: "?"}" + (if (now != null && g.page != null && now != g.page) "（交出时是 ${g.page}，用户换过页面）" else "") + "。先看一眼现在的画面再继续。",
                    "Page now: ${now ?: "?"}" + (if (now != null && g.page != null && now != g.page) " (it was ${g.page} when handed over; the user moved on)" else "") + ". Look at the screen before going on.")
                else tr("launch $pkg 重新打开。", "launch $pkg to open it again.")))
        }
        com.kilon.agentvscreen.ui.Overlay.refresh()
        return line
    }

    /** Start watching (idempotent). Runs while the service runs; ticks only do work while there is a screen. */
    fun start(ctx: Context) {
        if (thread?.isAlive == true) return
        val app = ctx.applicationContext
        thread = Thread({
            while (true) {
                try { Thread.sleep(1_000); tick(app) } catch (_: InterruptedException) { break } catch (t: Throwable) { com.kilon.agentvscreen.host.Swallowed.note("ownership", t) }
            }
        }, "vscreen-ownership").apply { isDaemon = true; start() }
    }

    private fun tick(ctx: Context) {
        val st = BackgroundOperation.state.value
        val vs = st.displayId
        val owner = st.ownerSessionId
        if (vs == null || owner == null) { lastOnScreen = emptyMap(); if (_given.value.isNotEmpty() && vs == null) checkLeft(ctx); return }
        val onScreen = VScreenHandoff.tasksOn(vs).associate { it.taskId to it.pkg }
        val main = VScreenHandoff.tasksOn(0)
        val mainIds = main.associate { it.taskId to it.pkg }
        // A task of ours that is now on the main screen: the user pulled it there (handoff out records itself first).
        for ((id, pkg) in lastOnScreen) if (id !in onScreen && mainIds[id] == pkg && _given.value[pkg] == null) {
            record(ctx, Given(pkg, id, owner, System.currentTimeMillis(), "user", runCatching { synchronized(com.kilon.agentvscreen.cmd.VScreenOffloadHandler.lastActivity) { com.kilon.agentvscreen.cmd.VScreenOffloadHandler.lastActivity[vs] } }.getOrNull()))
        }
        // The same app in front on the main screen as a separate copy (multi-instance): only a note for the agent.
        val mainTop = main.firstOrNull { it.visible }
        if (mainTop != null && mainTop.taskId !in onScreen && onScreen.values.contains(mainTop.pkg) && _given.value[mainTop.pkg] == null) {
            val first = synchronized(bothSaid) { bothSaid.getOrPut(owner) { HashSet() }.add(mainTop.pkg) }
            if (first) synchronized(pendingNotes) { pendingNotes.getOrPut(owner) { ArrayList() } += tr("用户也在主屏上用 ${label(ctx, mainTop.pkg)}（另一份，和副屏这份互不影响）：不要做会影响用户那份的事（退出登录、清数据、改账号设置）。",
                "The user is also using ${label(ctx, mainTop.pkg)} on the main screen (a separate copy; the two don't affect each other): don't do things that would affect theirs (log out, clear data, change account settings).") }
        }
        // A handed-over task back on our screen by another way (the agent's handoff in, recents): it is the agent's again.
        for (g in _given.value.values) if (g.taskId in onScreen) { _given.update { it - g.pkg }; com.kilon.agentvscreen.ui.Overlay.refresh() }
        lastOnScreen = onScreen
        checkLeft(ctx)
    }

    /** The user left a handed-over app: after [NUDGE_MS] one notification asks whether to hand it back. Never automatic. */
    private fun checkLeft(ctx: Context) {
        val top = runCatching { VScreenShell.topPackage(0) }.getOrNull()
        val now = System.currentTimeMillis()
        for (g in _given.value.values) {
            val away = top != null && top != g.pkg
            when {
                !away && g.leftAt != 0L -> _given.update { it + (g.pkg to g.copy(leftAt = 0L)) }
                away && g.leftAt == 0L -> _given.update { it + (g.pkg to g.copy(leftAt = now)) }
                away && !g.nudged && now - g.leftAt >= NUDGE_MS -> {
                    _given.update { it + (g.pkg to g.copy(nudged = true)) }
                    val app = label(ctx, g.pkg)
                    val pi = android.app.PendingIntent.getService(ctx, 7300 + (g.pkg.hashCode() and 0xff),
                        android.content.Intent(ctx, com.kilon.agentvscreen.host.HostService::class.java).setAction(com.kilon.agentvscreen.host.HostService.ACTION_HAND_BACK).putExtra("pkg", g.pkg),
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                    VScreenHandoff.alert(ctx, 7300 + (g.pkg.hashCode() and 0xff), tr("$app 用完了吗？", "Done with $app?"),
                        tr("agent 在等你把 $app 交回去。用完了就点“交回”，它会从你离开的页面接着做。", "The agent is waiting for $app. When you are done, tap Hand back and it carries on from where you left it."),
                        actions = listOf(tr("交回", "Hand back") to pi))
                }
            }
        }
    }
}
