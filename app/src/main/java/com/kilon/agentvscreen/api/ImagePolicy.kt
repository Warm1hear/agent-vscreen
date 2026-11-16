package com.kilon.agentvscreen.api

import android.content.Context
import android.graphics.BitmapFactory
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.cmd.ShotStore
import com.kilon.agentvscreen.tr
import java.io.File

/**
 * 1.4.0-beta2: how many picture bytes go into an agent's model context.
 *
 * Hosts that put a reply's pictures straight into the model (MCP: inline images; Operit and other HTTP clients that
 * fetch images[]) resend every one of them with each later turn. A long task then grows until the host's gateway
 * refuses the request ("request entity too large"), even though each picture is small. The root fix is to send a
 * picture only when it says something new, and smaller:
 *  - a picture is attached when the command failed (the agent has to look), when the picture changed during the
 *    stretch, or when the command exists to show one (act with no steps, launch, zoom, frames, spots…);
 *  - an unchanged picture after a successful stretch is left out and the text says so and how to get it;
 *  - attached pictures are a compact copy (Pictures.Use.SENT, written when the picture was saved); the full one stays
 *    on the phone (images[].full_url, or the CLI's local file), and at most [MAX] per reply ([MAX_ASKED] for a command
 *    that asked for pictures).
 * The CLI only prints paths (nothing enters the model unless the agent opens a file), so it gets every picture.
 */
object ImagePolicy {
    /** Per reply: pictures that come by themselves, and pictures a command asked for (look, zoom, frames, spots…). */
    const val MAX = 1
    const val MAX_ASKED = 8

    /** Content signature of the last picture each session's model got (ShotStore.sig). */
    private val lastSent = HashMap<String, Long>()

    /** A command that exists to show pictures (look, zoom, frames, spots…): its pictures were already sized as asked
     *  (look's --detail, within LookBudget), so they go as they are; only pictures that come by themselves are compacted. */
    fun asked(res: NativeOffloadResult): Boolean = res.data?.has("picture_changed") != true

    /** The name to send for picture [name] of [res]. */
    fun sendName(ctx: Context, res: NativeOffloadResult, name: String): String = if (asked(res)) name else compact(ctx, name)

    /**
     * Pictures of [res] to put into [session]'s model context. A picture that comes by itself is left out only when
     * what it shows is the same as the last picture this session's model got (compared by content, Pictures.sig):
     * then "same as your last picture" is true. A failed stretch and act with no steps always get theirs.
     */
    fun pick(session: String?, res: NativeOffloadResult, shots: List<String>): List<String> {
        if (shots.isEmpty()) return emptyList()
        val d = res.data
        val out = if (d == null || asked(res)) shots.take(MAX_ASKED) else {
            val first = shots.take(MAX)
            val sig = first.firstOrNull()?.let { ShotStore.sig(it) }
            val prev = session?.let { synchronized(lastSent) { lastSent[it] } }
            val must = res.exitCode != 0 || (d.optJSONArray("steps")?.length() ?: 0) == 0
            val same = if (sig != null && prev != null) sig == prev else !d.optBoolean("picture_changed")
            if (must || !same) first else emptyList()
        }
        out.lastOrNull()?.let { ShotStore.sig(it) }?.let { sg -> session?.let { synchronized(lastSent) { lastSent[it] = sg } } }
        return out
    }

    /** The line that stands where a left-out picture was. */
    fun leftOut(): String = tr("[没附图：画面和你上一张收到的图内容一样；要再看用 look]", "[no picture attached: the screen shows the same as the last picture you got; look shows it again]")

    /** The compact copy of picture [name] (written beside it when it was saved; made here only for an older picture). */
    fun compact(ctx: Context, name: String): String {
        if (name.startsWith("vscreen_c_")) return name
        val src = ShotStore.file(ctx, name) ?: return name
        val cname = "vscreen_c" + name.removePrefix("vscreen")
        val out = File(src.parentFile, cname)
        if (out.isFile) return cname
        return runCatching {
            val b = BitmapFactory.decodeFile(src.path) ?: return name
            com.kilon.agentvscreen.core.Pictures.write(b, out, com.kilon.agentvscreen.core.Pictures.Use.SENT)
            b.recycle()
            if (out.length() >= src.length()) { out.delete(); name } else cname
        }.getOrDefault(name)
    }
}
