package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.tr

/**
 * 1.3 beta4: an agent keeps what it learned about vscreen in its own memory or skill, and after an app update that
 * note can be out of date. Every agent (token) confirms the version its note is for with `remembered`; while that is
 * older than the app, the agent's next command carries a short notice: what changed for agents since its version,
 * and how to refresh its note. The same for the shell, MCP and HTTP (it is in the command output), so no client needs
 * anything of its own. The app shows the user which version each agent's note is for.
 *
 * Only what changes how an agent should work goes here, not every fix.
 */
object AgentNews {
    class Entry(val code: Int, val name: String, val zh: List<String>, val en: List<String>)

    val entries = listOf(
        Entry(15, "1.3.0-beta2", listOf(
            "`until text:` 只算动作之后新出现的字；动作后应用没反应会直接说。",
            "#编号只属于一个页面：换了页面用旧编号会报 STALE_ELEMENT，先重新看一眼。",
            "一直在动的区域（视频、轮播）会单独写出来，里面的字不再列进变化。",
            "collect 按卡片收；`--from-top` 先回到顶部；取消时返回已收的部分（CANCELLED）。",
            "schedule 可以只约一次（today / once / MM-DD）；ask --async 最多等 10 分钟。",
        ), listOf(
            "`until text:` only counts words that appear after the action; an app that does not react is reported.",
            "#numbers belong to one page: an old number on a new page gives STALE_ELEMENT; look again first.",
            "Moving areas (video, carousels) are reported apart; words inside them are no longer listed as changes.",
            "collect goes by cards; `--from-top` scrolls to the top first; a cancel returns what was collected (CANCELLED).",
            "schedule can book once (today / once / MM-DD); ask --async waits up to 10 minutes.",
        )),
        Entry(17, "1.3.0-beta4", listOf(
            "服务有三种状态：常驻；收起（本机命令和预约时间会叫醒它，用完自己收起；其它设备只在它醒着时连得上）；禁止访问（什么都叫不醒）。要远程随时用，请用户打开常驻；连不上时命令行会说可能是哪一种。",
            "status 的 remote 里有 phase 和 user_action：内置 Tailscale 要登录或批准时，照 user_action 请用户处理。",
            "App 更新后你会收到这样的提醒；更新完你的说明后执行 remembered，提醒就不再出现。",
        ), listOf(
            "The service has three states: always on; hidden (a local command or a booked time wakes it, it hides again after; other devices reach it only while awake); blocked (nothing wakes it). For remote use at any time, ask the user to keep it always on; when the phone cannot be reached, the shell says which it may be.",
            "status.remote has phase and user_action: when the built-in Tailscale needs a login or an approval, ask the user as user_action says.",
            "After an app update you get a notice like this; once your note is refreshed, run remembered and it stops.",
        )),
        Entry(18, "1.3.0-beta5", listOf(
            "【这段时间】的拼图改成每个动作前一张，圈和编号是你点的位置（拖动是线），结果看下一张或【现在】；点了没反应时也会给拼图，用来核对是不是点错了地方。",
            "新命令 vscreen frames：回看录像。默认是上一次返回的那段；--last 2m 更长、--every 0.3s 更密、--from 14:03:10 --to 14:03:30 指定一段、--big 放大；帧多时分成几张图。",
        ), listOf(
            "The [Meanwhile] sheet now has one frame before each action, with a ring and number where you touched (a line for a drag); the result is the next frame or [Now]. It also comes when a tap changed nothing, to check whether you hit the wrong place.",
            "New command vscreen frames: look back at the recording. By default the stretch of the last reply; --last 2m longer, --every 0.3s denser, --from 14:03:10 --to 14:03:30 a given stretch, --big larger; many frames come as several pictures.",
        )),
        Entry(19, "1.3.0-beta6", listOf(
            "status 的 remote.phase 多了 unstable：登录还在，但手机现在连不上 Tailscale 服务器或中继；remote 里有 control_ok、relay_ok、relay、flaps_10m、problem。连不上时照 user_action 告诉用户。",
        ), listOf(
            "status.remote.phase can now be unstable: still logged in, but the phone cannot reach the Tailscale server or relay right now; remote has control_ok, relay_ok, relay, flaps_10m and problem. When it cannot be reached, tell the user what user_action says.",
        )),
        Entry(20, "1.3.0-beta7", listOf(
            "新命令 vscreen teach \"要演示什么\"：请用户在副屏上亲手做一遍，审阅后存成这个应用的一段，返回段名和步骤；之后 act \"use 段名 参数…\" 照做。用户输入的字变成参数 {1} {2}…，use 时按顺序填（有空格用引号）。用户自己录的段同样能 use。",
            "status.remote 多了 bridge_silent、bridge_silent_in_background、app_in_front：手机上的 Tailscale 节点被系统停过几次、其中几次是用户离开了 App。",
        ), listOf(
            "New command vscreen teach \"what to show\": the user does it once on the background screen, reviews it and saves it as a segment of the app; you get its name and steps, then act \"use <name> <values>…\" runs it. Words the user typed become blanks {1} {2}…, filled in order by use (quote values with spaces). Segments the user recorded on their own can be used the same way.",
            "status.remote has bridge_silent, bridge_silent_in_background and app_in_front: how often the phone's Tailscale node was stopped by the system, and how many of those while the user was out of the app.",
        )),
    )

    /** What changed for agents after [since] (a version code), newest last; at most the last few versions. */
    fun since(since: Int): List<Entry> = entries.filter { it.code > since && it.code <= BuildConfig.VERSION_CODE }.takeLast(3)

    fun nameOf(code: Int): String = entries.lastOrNull { it.code <= code }?.name ?: tr("更早的版本", "an earlier version")

    /** The notice, or null when this agent's note is current or it saw the notice a moment ago. */
    fun notice(tokenId: String, freshStretch: Boolean): String? {
        val ack = AgentLedger.ackVersion(tokenId)
        if (ack >= BuildConfig.VERSION_CODE) return null
        val news = since(ack)
        if (news.isEmpty()) return null
        if (!AgentLedger.nudgeDue(tokenId, freshStretch)) return null
        val lines = news.flatMap { e -> (if (com.kilon.agentvscreen.I18n.en) e.en else e.zh).map { "- $it" } }
        val was = if (ack <= 0) tr("你确认过的版本未知", "the version your note is for is unknown") else tr("你的说明是 ${nameOf(ack)} 时记下的", "your note is from ${nameOf(ack)}")
        return tr(
            "【Agent-VScreen 已更新到 ${BuildConfig.VERSION_NAME}，$was】和你记下的用法相比，变化：\n${lines.joinToString("\n")}\n" +
                "请执行 guide 重读一遍用法，把你长期记忆或 skill 里关于 Agent-VScreen 的说明换成新的，然后执行 remembered \"<存在哪里>\"（命令行：vscreen remembered \"…\"），之后不再提醒。\n",
            "[Agent-VScreen was updated to ${BuildConfig.VERSION_NAME}; $was] Changes to how it is used:\n${lines.joinToString("\n")}\n" +
                "Run guide to read the usage again, replace what your long-term memory or skill says about Agent-VScreen, then run remembered \"<where>\" (shell: vscreen remembered \"…\"); the notice then stops.\n",
        )
    }
}
