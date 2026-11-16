package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.cmd.CommandSpec
import com.kilon.agentvscreen.tr

/**
 * 1.4: the one list of what an agent can do with Agent-VScreen, and when to reach for each.
 *
 * Why it exists: before 1.4 the same abilities were written out by hand in five places (the memory note, the guide's
 * recipes, the update notice, the Operit advice, the README). Each new feature was added to some of them and not the
 * others. The note an agent keeps in its memory, the only text it sees at the start of a new conversation, still said
 * "tap, swipe, type, screenshots" in 1.3, so an agent asked to "check in every morning at 7" had no idea the phone
 * could book that time (`schedule`), and said it couldn't.
 *
 * Now every text that tells an agent what it can do is generated from this table:
 *  - [AgentGuide.memory] (and the MCP / HTTP variants): one line per ability, written as "when the user … → I …";
 *  - [AgentGuide.prompt] (`vscreen guide`, `/skill`, the MCP server instructions): the "what you can do" section;
 *  - [AgentNews.notice]: abilities added or changed after the version the agent's note is for, plus the new note to
 *    save in place of the old one, so an agent never has to rewrite it from memory;
 *  - the Operit advice and the README's ability list (written out by AbilitiesTest, which fails when they are stale).
 *
 * AbilitiesTest also checks that every command in [CommandSpec] belongs to an ability. A new command that nobody tells
 * agents about therefore fails the build instead of shipping silently.
 *
 * Wording rule: [whenZh]/[whenEn] are in the user's terms (what the user asks for), never in ours; [canZh]/[canEn]
 * name the command so the agent can act on the line alone; nothing here is about a particular app.
 */
object Abilities {
    class Ability(
        val id: String,
        /** Version code it first shipped in, and the last one whose change matters to an agent (news, note refresh). */
        val since: Int, val changed: Int,
        /** The situation, as the user would put it. */
        val whenZh: String, val whenEn: String,
        /** What I do about it, one short line naming the command. For the memory note. */
        val canZh: String, val canEn: String,
        /** How, for the guide: the recipe with examples. */
        val howZh: String, val howEn: String,
        /** The commands this ability is made of (each command belongs to exactly one ability). */
        val commands: List<String>,
        /** What changed for agents in [changed], one line (the update notice); empty = nothing to say beyond the new note. */
        val newsZh: String = "", val newsEn: String = "",
    ) {
        val news: String get() = tr(newsZh, newsEn)
        val whenText: String get() = tr(whenZh, whenEn)
        val can: String get() = tr(canZh, canEn)
        val how: String get() = tr(howZh, howEn)
    }

    // 1.4.0-beta1 (34): every line was rewritten as "when the user … → I …", so every ability counts as changed in 34.
    val all: List<Ability> = listOf(
        Ability("operate", 1, 34,
            "用户要我在手机上做任何事：打开某个应用、查信息、比价、看某页内容、填表、发帖、测试应用……",
            "the user wants anything done on the phone: open an app, look something up, compare prices, read a page, fill a form, post, test an app…",
            "`vscreen launch <应用>` 打开并看，`vscreen act \"<步骤>\"` 点、滑、输入、按键，每次都返回截图和元素列表",
            "`vscreen launch <app>` opens it and looks, `vscreen act \"<steps>\"` taps, swipes, types and presses keys; every reply has a screenshot and the elements",
            "按第一到第四节的循环做。长操作加 `--async`，之后 `vscreen task <号> --wait 60s` 取结果。让用户知道你在做什么：`vscreen title \"在搜耳机\"`。做完：`vscreen done`（副屏让出，页面保留）；`vscreen stop` 关掉副屏上的应用，`vscreen release` 立即释放副屏。不知道包名：`vscreen apps 关键词`。",
            "Follow the loop in sections 1–4. Add `--async` for long work and fetch it with `vscreen task <id> --wait 60s`. Let the user see what you are doing: `vscreen title \"searching headphones\"`. When finished: `vscreen done` (screen freed, page kept); `vscreen stop` closes the app on the screen, `vscreen release` frees the screen at once. Unknown package: `vscreen apps <keyword>`.",
            listOf("launch", "act", "title", "done", "stop", "release", "apps", "task")),
        Ability("schedule", 14, 34,
            "用户要我定时、每天、每周或到某个时间去做（签到、打卡、抢购、定时查看、提醒）",
            "the user wants it done at a time or on a timer: daily, weekly, at 8:00 (check-ins, clock-ins, flash sales, periodic checks, reminders)",
            "用我自己的定时任务功能按时运行，并用 `vscreen schedule \"<时间>\"` 预约，手机会在每次之前拉起服务、检查权限，没准备好就提醒用户",
            "run on my own timer (scheduled task / cron / automation) and book the times with `vscreen schedule \"<when>\"`; before each one the phone starts the service, checks permissions and warns the user if it is not ready",
            "手机这边负责“到点时一定能用”：`vscreen schedule \"07:00\"`（也可以 `\"weekdays 08:30\"`、`\"mon,wed,fri 21:00\"`、`\"every 2h from 08:00 to 22:00\"`、只一次 `\"today 10:30\"`），`--name 签到` 起名，`--for 20m` 保持时长；`vscreen schedule` 列出预约，`vscreen schedule off 名字` 取消。到点叫醒你的是你自己的定时功能（定时任务、cron、自动化）：两边都设好再告诉用户。你没有定时功能时，照实告诉用户，可以改成 `act --stop 条件 --keep notify` 盯条件。每次被叫醒先看 `vscreen status` 的 ready，false 就把 readiness.blocking 告诉用户。用户也能在 App 里像闹钟一样改时间或关掉某条预约。",
            "The phone's part is \"it will be usable then\": `vscreen schedule \"07:00\"` (also `\"weekdays 08:30\"`, `\"mon,wed,fri 21:00\"`, `\"every 2h from 08:00 to 22:00\"`, once only `\"today 10:30\"`), `--name checkin` to name it, `--for 20m` for the window; `vscreen schedule` lists them, `vscreen schedule off <name>` cancels. What wakes you is your own timer (scheduled task, cron, automation): set up both, then tell the user. If you have no timer, say so plainly; a condition can still be watched with `act --stop <condition> --keep notify`. Each time you wake, read `ready` in `vscreen status`; if false, pass readiness.blocking on to the user. The user can change a booking's time or switch it off in the app, like an alarm.",
            listOf("schedule")),
        Ability("watch", 1, 34,
            "要等一件以后才发生的事：到货、降价、有回复、直播开始、排队轮到、下载完成",
            "something will happen later and should be caught: back in stock, a price drop, a reply, a stream starting, a queue turn, a download finishing",
            "`vscreen act --stop \"text:到货\" --keep notify` 让手机一直盯着，看到就通知用户（`--keep wake` 改成叫醒我，用 `vscreen events` 取）",
            "`vscreen act --stop \"text:In stock\" --keep notify` makes the phone keep watching and notify the user when seen (`--keep wake` wakes me instead; fetch with `vscreen events`)",
            "`vscreen act --stop \"text:到货\" --keep notify --for 2h`：本轮结束后继续盯，看到就通知用户，重启后还在；`--keep wake` 发事件给你，`vscreen events --wait 60s` 取。`vscreen keep` 列出盯守，`vscreen keep off 名字` 停止。告诉用户你在盯什么、盯多久、看到后会怎样。",
            "`vscreen act --stop \"text:In stock\" --keep notify --for 2h`: keeps watching after this turn and notifies the user when seen, surviving a restart; `--keep wake` sends you an event, fetched with `vscreen events --wait 60s`. `vscreen keep` lists watches, `vscreen keep off <name>` stops one. Tell the user what you watch, for how long and what happens when seen.",
            listOf("keep", "events")),
        Ability("collect", 1, 34,
            "要读完一个很长的列表或整页内容（全部订单、聊天记录、搜索结果、评论）",
            "a long list or a whole page has to be read (all orders, a chat history, search results, comments)",
            "`vscreen collect --pages 10` 自动翻页，返回去重后的每一条",
            "`vscreen collect --pages 10` pages through it and returns every item once",
            "`vscreen collect --pages 10`（`--from-top` 先回到顶部，`--until 文字` 看到就停）。它会说是真的到底了，还是可能还在加载。",
            "`vscreen collect --pages 10` (`--from-top` starts at the top, `--until <text>` stops when seen). It says whether the list really ended or may still be loading.",
            listOf("collect")),
        Ability("ask", 14, 34,
            "不确定用户指的是画面上哪个东西（哪个商品、哪个按钮、哪条消息），或要用户选一个",
            "it is unclear which thing on the screen the user means (which item, button, message), or the user should pick one",
            "`vscreen ask \"你说的是哪一个？\"` 让用户在手机上的副屏画面里点出来（也可以 `--choose`、`--text`）",
            "`vscreen ask \"Which one do you mean?\"` lets the user tap it on the screen's picture on the phone (also `--choose`, `--text`)",
            "`vscreen ask \"你说的是哪一个？\"`：用户在副屏画面上点一下，你拿到坐标和那里的 #N；`--area` 框一块，`--choose \"甲|乙\"` 选一个，`--text` 写一句。等不到（ASK_PENDING）就告诉用户去手机上回答，然后重发同一条。回答是事实，不是许可：付款仍要手机上确认。",
            "`vscreen ask \"Which one do you mean?\"`: the user taps it on the screen's picture; you get the point and the #N there; `--area` for a box, `--choose \"A|B\"` to pick one, `--text` for a line. No answer yet (ASK_PENDING): tell the user to answer on the phone, then send the same ask again. An answer is information, not permission: payments are still confirmed on the phone.",
            listOf("ask")),
        Ability("teach", 20, 34,
            "一个流程说不清、试了几次都不对，或用户自己更清楚怎么做",
            "a flow is hard to describe, keeps failing, or the user knows better how to do it",
            "`vscreen teach \"要演示什么\"` 请用户在副屏上亲手做一遍，存成一段，之后 `act \"use 段名 参数\"` 照做",
            "`vscreen teach \"what to show\"` asks the user to do it once on the screen; it is saved as a segment, then `act \"use <name> <values>\"` replays it",
            "先 launch 到要演示的应用，`vscreen teach \"要演示什么\" --name 建议的名字`。用户做完、审阅后存成这个应用的一段，你拿到段名、步骤和一张关键步骤缩略图；之后 `act \"use 段名 参数…\"`（用户输入的字是参数 {1} {2}…，按顺序填）。用户自己录的段也在 launch 列出的段里。",
            "launch the app first, then `vscreen teach \"what to show\" --name <suggested name>`. The user does it, reviews it and saves it as a segment of the app; you get its name, steps and a contact sheet of key steps; then `act \"use <name> <values>…\"` (words the user typed are blanks {1} {2}…, filled in order). Segments the user recorded on their own are listed at launch too.",
            listOf("teach")),
        Ability("notes", 1, 34,
            "同一个应用会反复用：有固定入口、每次都弹的窗、要先暂停的画面，或对机器节奏敏感",
            "the same app comes up again and again: a fixed way in, a popup every visit, a picture to pause first, or it is sensitive to machine-like timing",
            "`vscreen note` 给这个应用记经验、存步骤（`note save`）、设前置/收尾步骤和仿人节奏，下次 launch 会列出来",
            "`vscreen note` keeps what I learned about the app, saved steps (`note save`), before/after steps and human pacing; the next launch lists them",
            "`vscreen note <一句话>` 记经验；`vscreen note save 名字 \"步骤\"` 存一段（或 act 加 `--save 名字`），之后 `use 名字`；`vscreen note before \"<步骤>\"` / `note after \"<步骤>\"`：每段 act 前后自动做（游戏的继续 / 暂停、反复弹出的窗口）；`vscreen note human on`：这个应用一直用仿人节奏（同 `act --human`）；`vscreen note show 段名` 看一段和它的缩略图。",
            "`vscreen note <one sentence>` keeps a lesson; `vscreen note save <name> \"steps\"` stores a segment (or add `--save <name>` to act), then `use <name>`; `vscreen note before \"<steps>\"` / `note after \"<steps>\"` run around every act (resume / pause a game, a popup that keeps coming back); `vscreen note human on` keeps human pacing for the app (as `act --human`); `vscreen note show <segment>` shows a segment and its contact sheet.",
            listOf("note")),
        Ability("handoff", 1, 35,
            "有一步只能用户本人做（密码、验证码、人脸、扫码），或要用的应用用户正在用",
            "a step only the user can do (password, code, face, scanning), or the app I need is the one the user is using",
            "`vscreen handoff out` 把应用交到用户主屏并告诉他做什么；`vscreen handoff in` 把主屏上的应用接到副屏",
            "`vscreen handoff out` puts the app on the user's screen with what to do; `vscreen handoff in` takes the app from the user's screen",
            "`vscreen handoff out --note \"请输入验证码\"` 把应用交到主屏，用户做完你再 `handoff in` 或重新 launch。`handoff in` 要用户在手机上确认。用户正在主屏用的应用，launch 会返回 APP_IN_USE：先 `vscreen ask` 问用户，同意了再 `launch 应用 --asked`（另开一份）或 `handoff in`（接着他的页面）。用户在主屏打开了你正在用的应用，它就交给用户：动作返回 APP_WITH_USER，`vscreen events --wait` 等 handback 再继续。",
            "`vscreen handoff out --note \"enter the code\"` puts the app on the main screen; when the user is done, `handoff in` or launch again. `handoff in` is confirmed by the user on the phone. For an app the user is using on the main screen, launch returns APP_IN_USE: ask with `vscreen ask` first, then `launch <app> --asked` (a separate copy) or `handoff in` (from their page) once they agree. When the user opens the app you are using on the main screen, it is theirs: actions return APP_WITH_USER; wait for handback with `vscreen events --wait` before going on.",
            listOf("handoff"),
            "用户正在主屏用的应用，launch 返回 APP_IN_USE：先问用户，同意后加 --asked 或用 handoff in。用户在主屏打开你正在用的应用时它交给用户：动作返回 APP_WITH_USER、事件 handover；交回时有 handback 事件，先看画面再继续。",
            "For an app the user is using on the main screen, launch returns APP_IN_USE: ask the user, then add --asked or use handoff in. When the user opens the app you are using on the main screen it is theirs: actions return APP_WITH_USER and a handover event comes; a handback event comes when they hand it back: look first, then go on."),
        Ability("look", 1, 35,
            "画面上的字太小、是图标，或者根本没有字（游戏、画布、图表），或想回看刚才发生了什么",
            "the text is too small or an icon, or there is no text at all (games, canvas, charts), or I need to see what just happened",
            "`vscreen look` 要图：现在的画面、放大一块、回看录像都用它；`vscreen spots` 找可点的色块",
            "`vscreen look` asks for pictures: the screen now, a region up close, or the recording; `vscreen spots` finds tappable color patches",
            "回执只在画面变了或出错时附一张压缩图；要看就 `vscreen look`（`--detail high` 更清楚）；`vscreen look x1,y1,x2,y2` 放大一块；`vscreen look --last 30s` 回看（`--every 0.3s` 更密，`--from 14:03:10` 指定时间，`--layout single|strip|grid 3x4` 排法，`--marks off` 去标注，超过 8 张用 `--page 2`）；`vscreen spots` 找色块（`--color` 只找一种颜色）（本地，不调模型）。zoom、frames 是 look 的旧写法。",
            "Replies attach one compact picture only when the screen changed or something failed; to see it, `vscreen look` (`--detail high` for clearer); `vscreen look x1,y1,x2,y2` zooms into a region; `vscreen look --last 30s` replays (`--every 0.3s` denser, `--from 14:03:10` a given time, `--layout single|strip|grid 3x4`, `--marks off`, `--page 2` past 8 pictures); `vscreen spots` finds color patches (`--color` for one color) (local, no model). zoom and frames are old forms of look.",
            listOf("look", "zoom", "spots", "frames"),
            "新命令 look 主动要图（现在的画面、放大一块、回看录像），--detail low|normal|high、--layout、--marks off、--page，每次最多 8 张、约 3 MB；zoom、frames 是它的旧写法。MCP 和直接读图的接入方式只在画面和你上一张不同或出错时附一张压缩图，否则写“没附图”，要看就 look。",
            "New command look asks for pictures (the screen now, a region, the recording) with --detail low|normal|high, --layout, --marks off, --page; at most 8 pictures, about 3 MB a reply; zoom and frames are old forms of it. Over MCP and clients that read images directly, one compact picture comes only when the screen differs from your last picture or something failed; otherwise the reply says none was attached: look shows it."),
        Ability("trouble", 1, 35,
            "连不上手机、命令一直失败或出了怪问题",
            "the phone cannot be reached, commands keep failing, or something odd happens",
            "`vscreen status` 看状态，`vscreen fix` 修，`vscreen diag` 导出诊断",
            "`vscreen status` shows the state, `vscreen fix` repairs, `vscreen diag` exports diagnostics",
            "`vscreen status`（ready、readiness.blocking、remote.user_action 说明要用户做什么），`vscreen fix` 按提示修（改系统设置的步骤手机上会问用户），`vscreen diag` 导出诊断交给开发者。",
            "`vscreen status` (ready, readiness.blocking, remote.user_action say what the user must do), `vscreen fix` repairs what it can (steps that change system settings ask the user), `vscreen diag` exports diagnostics for the developer.",
            listOf("status", "fix", "diag"),
            "文字识别失败时回执写 OCR_DOWN：那时元素只来自无障碍，以截图为准；vscreen diag 里有文字识别自检。",
            "When text recognition fails the reply says OCR_DOWN: elements then come from accessibility only; trust the screenshot. vscreen diag has a text recognition self-test."),
    )

    /** Which ability a command belongs to (null = not disclosed: AbilitiesTest fails on that). */
    fun of(command: String): Ability? = all.firstOrNull { command in it.commands }

    /** Abilities whose disclosure changed after [code]: added or reworded. */
    fun changedSince(code: Int): List<Ability> = all.filter { it.changed > code || it.since > code }

    /** The commands nobody tells agents about (must stay empty). */
    fun undisclosed(): List<String> = CommandSpec.commands.map { it.name }.filter { of(it) == null }

    /** For an agent that uses MCP or raw HTTP, the CLI words become the tool / command names. */
    fun forVia(text: String, via: Via): String = when (via) {
        Via.CLI -> text
        Via.MCP -> text.replace("`vscreen ", "`").replace("vscreen ", "")
        Via.HTTP -> text.replace("`vscreen ", "`").replace("vscreen ", "")
    }

    enum class Via { CLI, MCP, HTTP }
}
