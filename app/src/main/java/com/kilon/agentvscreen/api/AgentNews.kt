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
        Entry(31, "1.3.0-beta18", listOf(
            "tap #N：同一页没滚动时，同样大小、同一位置的元素就算同一个，不管它的字变没变（轮播提示的搜索框，可点或可输入都算）。",
            "collect 一次画面没动不再算到底：换个位置再滑一次确认；两次都不动时，列表说不能再往下才写“到底了”，否则说明可能在加载。顶部或底部一排可点的标签栏不算条目。",
            "步骤语言（act、段、示教共用）：字里 * 是任意字（tap *蓝牙*），字@N 是从上往下第 N 处；新增 try 一步 / try { … }（没做成就跳过）、if <条件> { … } else { … }、repeat 3 { … }、repeat { … } until <条件>，括号可换行、可嵌套。",
            "示教保存时，打字后点了含输入内容的结果，记成 tap *{1}*@N（第 N 个含这个值的项），换参数重放时按内容点，不按位置。",
            "note save 段名 \"步骤\"：不执行，直接存下或改写一个段（示教的段也能改）。",
        ), listOf(
            "tap #N: on the same page with no scroll, an element of the same size at the same place is the same element whatever it says now (a search box with rotating hints, tappable or editable).",
            "collect no longer takes one still picture as the end: a second swipe elsewhere confirms it; if both moved nothing it says the end only when the list itself cannot go further, otherwise that it may be loading. A row of tappable tabs at the top or bottom is not an item.",
            "The step language (act, segments and lessons alike): * in words is any characters (tap *Bluetooth*), words@N is the N-th place from the top; new try <step> / try { … } (skipped if it doesn't work), if <condition> { … } else { … }, repeat 3 { … }, repeat { … } until <condition>; braces may span lines and nest.",
            "When a lesson is saved, a tap on a result holding the typed text is kept as tap *{1}*@N (the N-th item holding that value), so replaying with another value taps by content, not by place.",
            "note save <segment> \"steps\": store or rewrite a segment without running it (taught ones too).",
        )),
        Entry(32, "1.3.0-beta19", listOf(
            "act --human / note human on：仿人节奏。每个动作前随机停 0.2–2.6 秒，点击在元素内略偏、按住 55–140ms，长按、滑动、滚动、拖动的时长和端点有波动，打字 1–3 个字一段。等待和条件判断不变。",
            "按文字点（tap 字 / tap *x*@N）先查应用自己的控件，能定下就不再等 OCR，快很多；控件里没有或有歧义时才整屏识别。",
            "示教保存：打字前那步的 until 不会再是后面输入的字（以前点两次搜索框会存成 until text:<输入的字>，重放卡住）；点了含输入内容的结果，until 一律是 change。",
        ), listOf(
            "act --human / note human on: human pacing. A random 0.2–2.6 s pause before each action, taps slightly off-centre inside the element and held 55–140 ms, varied durations and ends for presses, swipes, scrolls and drags, typing 1–3 characters at a time. Waits and conditions are unchanged.",
            "Word targets (tap <words> / tap *x*@N) are looked up in the app's own elements first and settle without waiting for OCR when they can, which is much faster; the whole screen is read only when the elements have no match or more than one.",
            "Saving a lesson: a step before the typing never gets until text:<the typed words> any more (tapping the search box twice used to save that and replay got stuck); a tap on a result holding the typed value always waits for change.",
        )),
        Entry(33, "1.3.0", listOf(
            "示教存的段带一张关键步骤缩略图（最多 8 格：步骤前的画面、红圈是点的位置、最后一格是结束时）。teach 的回复里附上；之后用 note show 段名 再看（步骤后来改写过会注明）。",
            "示教里打字前的画面在第一个字输入之前读，打字前那步不会再配上因输入才出现或消失的字。",
        ), listOf(
            "A taught segment keeps one contact sheet of its key steps (up to 8 cells: the screen before each step, red rings for taps, the last cell is the end). It is attached to the teach reply; see it again with note show <segment> (it says so when the steps were rewritten later).",
            "In a lesson the screen before typing is read before the first character goes in, so the step before the typing no longer waits for words that only the typing made appear or go.",
        )),
        Entry(34, "1.4.0-beta1", listOf(
            "你记下的能力说明改成按“遇到什么情况 → 用哪个命令”逐条列出，由 App 生成；以后每次更新，这里会附上完整的新说明，原样替换即可，不用自己改写。",
            "用户要你定时、每天或到点做事时：用你自己的定时功能按时运行，并用 schedule 预约那个时间（手机会提前准备好）。",
        ), listOf(
            "The ability note you keep is now one line per situation (\"when … → which command\"), generated by the app; every update from now on carries the whole new note: save it as it is instead of rewriting it.",
            "When the user wants something done at a time or daily: run on your own timer and book the time with schedule (the phone gets ready beforehand).",
        )),
        Entry(35, "1.4.0-beta2", listOf(
            "用户正在主屏用的应用，launch 会返回 APP_IN_USE：先问用户，同意后加 --asked 或用 handoff in。",
            "用户在主屏打开了你正在用的应用时，它交给用户：动作返回 APP_WITH_USER，事件 handover；用户交回时有 handback 事件，先看画面再继续。",
            "文字识别失败时回执会写 OCR_DOWN：那时元素只来自无障碍，以截图为准。",
            "MCP 和直接读图的接入方式只在画面变了或出错时附图（压缩版）；画面没变时写“没附图”，要看就 act（不带步骤）。",
        ), listOf(
            "For an app the user is using on the main screen, launch returns APP_IN_USE: ask the user, then add --asked or use handoff in.",
            "When the user opens the app you are using on the main screen, it is theirs: actions return APP_WITH_USER and a handover event comes; a handback event comes when they hand it back: look before going on.",
            "When text recognition fails the reply says OCR_DOWN: elements then come from accessibility only; trust the screenshot.",
            "Over MCP and clients that read images directly, a picture (compact) comes only when the screen changed or something failed; otherwise the reply says no picture was attached: act with no steps to see it.",
        )),
    )

    /** What changed for agents after [since] (a version code), newest last; at most the last few versions. */
    fun since(since: Int): List<Entry> = entries.filter { it.code > since && it.code <= BuildConfig.VERSION_CODE }.takeLast(3)

    fun nameOf(code: Int): String = entries.lastOrNull { it.code <= code }?.name ?: tr("更早的版本", "an earlier version")

    /**
     * The notice, or null when this agent's note is current or it saw the notice a moment ago.
     *
     * 1.4: abilities added or reworded since the agent's version come from [Abilities] (nobody has to remember to
     * write them here), and when any did, the notice carries the whole new note for the agent to save in place of the
     * old one: agents that were asked to "re-read the guide and update your note" mostly kept the old note.
     */
    fun notice(tokenId: String, freshStretch: Boolean, via: Abilities.Via = Abilities.Via.CLI): String? {
        val ack = AgentLedger.ackVersion(tokenId)
        if (ack >= BuildConfig.VERSION_CODE) return null
        val news = since(ack)
        val abilities = Abilities.changedSince(ack)
        if (news.isEmpty() && abilities.isEmpty()) return null
        if (!AgentLedger.nudgeDue(tokenId, freshStretch)) return null
        val lines = news.flatMap { e -> (if (com.kilon.agentvscreen.I18n.en) e.en else e.zh).map { "- $it" } }
        val was = if (ack <= 0) tr("你确认过的版本未知", "the version your note is for is unknown") else tr("你的说明是 ${nameOf(ack)} 时记下的", "your note is from ${nameOf(ack)}")
        val remembered = if (via == Abilities.Via.CLI) "vscreen remembered \"<存在哪里>\"" else "remembered <存在哪里>"
        val rememberedEn = if (via == Abilities.Via.CLI) "vscreen remembered \"<where>\"" else "remembered <where>"
        return tr(
            "【Agent-VScreen 已更新到 ${BuildConfig.VERSION_NAME}，$was】\n" +
                (if (lines.isNotEmpty()) "用法上的变化：\n${lines.joinToString("\n")}\n" else "") +
                (if (abilities.isNotEmpty()) "能力有新增或变化（${abilities.joinToString("、") { it.id }}）。请把你长期记忆或 skill 里关于 Agent-VScreen 的说明整段换成下面这段（原样保存，不要改写成摘要）：\n「${AgentGuide.memoryFor(via)}」\n"
                    else "请执行 guide 重读一遍用法，按需更新你记下的说明。\n") +
                "存好后执行 $remembered，之后不再提醒。\n",
            "[Agent-VScreen was updated to ${BuildConfig.VERSION_NAME}; $was]\n" +
                (if (lines.isNotEmpty()) "Changes in use:\n${lines.joinToString("\n")}\n" else "") +
                (if (abilities.isNotEmpty()) "Abilities were added or changed (${abilities.joinToString(", ") { it.id }}). Replace what your long-term memory or skill says about Agent-VScreen with the note below, whole (save it as it is, not as a summary):\n\"${AgentGuide.memoryFor(via)}\"\n"
                    else "Run guide to read the usage again and update your note as needed.\n") +
                "When saved, run $rememberedEn; the notice then stops.\n",
        )
    }
}
