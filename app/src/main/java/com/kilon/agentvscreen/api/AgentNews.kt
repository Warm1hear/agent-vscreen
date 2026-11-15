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
        Entry(21, "1.3.0-beta8", listOf(
            "命令执行中手机上的 App 重启了，命令行不再一直挂着：返回 APP_RESTARTED（命令可能做了一部分，先 act 看一眼）；手机连续 1 分钟不回应时返回 NOT_READY。status 的 liveness 多了 previous_exit（上一个进程为什么结束）。",
            "VSCREEN_PROXY 现在优先于环境里的 no_proxy；连不上这个代理时直接说是代理的问题。",
        ), listOf(
            "A command no longer hangs when the app on the phone restarts while it runs: you get APP_RESTARTED (the command may be partly done: look with act first); NOT_READY when the phone stops answering for a minute. status.liveness has previous_exit (why the previous app process ended).",
            "VSCREEN_PROXY now wins over no_proxy in the environment; when that proxy cannot be reached, the message says so.",
        )),
        Entry(22, "1.3.0-beta9", listOf(
            "用管道读命令行输出（vscreen … | cat、\$(vscreen …)）时每条命令不再多等 20 秒。",
        ), listOf(
            "Reading the CLI's output through a pipe (vscreen … | cat, \$(vscreen …)) no longer adds 20 s to every command.",
        )),
        Entry(23, "1.3.0-beta10", listOf(
            "“一直在动的区域”现在是整块视频或轮播（含弹幕、字幕经过的地方），里面的字不再列进【这段时间】；视频页上“看一眼”不再先等满 3 秒。",
            "OCR 读到正在出现的字的半截（如“音频广”和“音频广播”）不再单独列出。collect 不再把吸顶栏、标签栏、底栏当成条目。",
        ), listOf(
            "\"Areas that keep moving\" is now the whole video or carousel (including where danmaku and subtitles pass); words there are no longer listed in [Meanwhile], and a look on a video page no longer waits out 3 s first.",
            "Pieces of words caught while they were drawing (\"音频广\" next to \"音频广播\") are no longer listed on their own. collect no longer takes top bars, tab bars and bottom bars as items.",
        )),
        Entry(25, "1.3.0-beta12", listOf(
            "status.remote 多了 connecting（已登录、中继还没连上：在连接，不算断开）、waiting_s（没连上多久了）、through_vpn / vpn_trouble（手机流量经过代理或 VPN App，且 Tailscale 连不稳：这时 user_action 会说请用户在代理 App 里把 Agent-VScreen 排除）。没连上时 health 一直带着原因。",
            "status.host 多了 network_blocked*：系统是否断过本 App 的网络（只是记录）。status.remote 的 bridge_silent* 去掉了：它把“没有状态变化”当成了“被冻结”，不准。",
        ), listOf(
            "status.remote has connecting (logged in, relay not reached yet: connecting, not a drop), waiting_s (how long it has not been connected), through_vpn / vpn_trouble (the phone's traffic goes through a proxy or VPN app and Tailscale is struggling; user_action then asks the user to exclude Agent-VScreen in that app). While not connected, health always carries the reason.",
            "status.host has network_blocked*: whether the system blocked this app's network (a record only). status.remote.bridge_silent* is gone: it took \"no state change\" for \"frozen\" and was wrong.",
        )),
        Entry(26, "1.3.0-beta13", listOf(
            "“一直在动的区域”不再算进我们自己的点按、滑动、拖动引起的画面变化；隔几秒才动一次的轮播也认得出。collect 不会因为自己的滑动把整页当成在动而提前停。",
            "同一行被 OCR 拆开的字会拼回一条；collect 不把顶部、底部固定栏的标签当条目。",
        ), listOf(
            "Moving areas no longer count screen changes caused by our own taps, swipes and drags; carousels that move every few seconds are recognized. collect no longer stops early because its own scroll made the whole page look moving.",
            "Text OCR split on one line is joined back into one item; collect no longer lists fixed top/bottom bar labels as entries.",
        )),
        Entry(27, "1.3.0-beta14", listOf(
            "示教录下的 until 和重放用同一条规则判断“这一步之后出现了什么”：点了画面没变的步骤不再带 until（审阅页标“可能点空了”），一直在原处的字（标题栏等）不再被当成新出现。",
            "超过上限的时长（task --wait、events --wait、步骤里的 wait/sleep）会在结果里说明被截到多少，不再悄悄截短。task 一次最多等 2m。",
            "副屏关闭或被释放时，要在副屏上回答的问题（point、area、teach）立刻结束，返回 ASK_DECLINED 并写明原因，不再一直等。",
        ), listOf(
            "A lesson's until and replay now judge \"what appeared after the step\" by the same rule: a step that changed nothing gets no until (the review page marks it as a possible miss), and words that stayed in place (title bars) no longer count as new.",
            "A duration over its limit (task --wait, events --wait, wait/sleep steps) is reported in the result with the value used, instead of being cut silently. task waits at most 2m per call.",
            "When the background screen is closed or released, questions answered on it (point, area, teach) end at once with ASK_DECLINED and the reason, instead of waiting on.",
        )),
        Entry(28, "1.3.0-beta15", listOf(
            "示教录下的输入是“输入框最后的内容”（用户在全屏页的输入框和副屏输入框同步，删改都算），重放用 type 整体写入。",
        ), listOf(
            "Typing in a lesson is recorded as what the box finally reads (the full screen box mirrors the screen's field, edits and deletes included); replay writes it whole with type.",
        )),
        Entry(29, "1.3.0-beta16", listOf(
            "用户可以在 App 里像闹钟一样改预约的时间、重复、保持时长，或关掉开关（schedule 列表里 on: false 的预约保留但不触发）。用户保存的示教在 App 里能看到和删除。",
            "用户回答了问题（ask/point/area/teach，包括示教做完）或把副屏交还给你时，会给发起的会话发一条事件（events 能收到）；有问题在等用户、或用户正接管副屏时，events --wait 也会等。示教点“完成”会同时交还副屏。",
            "type 在网页或自绘的输入框里读不回内容时（读到的还是原来的占位字），回执说“读不出来，看截图核对”，不再报 TYPE_FAILED。",
        ), listOf(
            "The user can now change a schedule's time, repeat and window in the app like an alarm, or switch it off (on: false in the schedule list: kept, not fired). Saved lessons are listed in the app and can be deleted there.",
            "When the user answers a question (ask/point/area/teach, a finished lesson too) or hands the screen back, the asking session gets an event (events receives it); events --wait also waits while a question is open or the user holds the screen. Done on a lesson also hands the screen back.",
            "When type cannot read a web or self-drawn box back (it still reads its old placeholder), the receipt says it cannot be read back (check the screenshot) instead of TYPE_FAILED.",
        )),
        Entry(30, "1.3.0-beta17", listOf(
            "tap #N 点输入框时，只要框还在原位就算同一个元素，框里的字或轮播的提示字变了也不再报 STALE_ELEMENT。",
            "events 里 App 重启前给过、没确认的旧事件只在 10 分钟内重发一次；更早的不再每次重启都回来。",
        ), listOf(
            "tap #N on an input box counts as the same element while the box is in its place, even when its text or a rotating hint changed (no more STALE_ELEMENT for that).",
            "events gives an unconfirmed batch from before an app restart again only within 10 minutes, and only once; older ones no longer come back after every restart.",
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
