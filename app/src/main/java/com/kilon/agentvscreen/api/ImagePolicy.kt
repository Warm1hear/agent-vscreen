package com.kilon.agentvscreen.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.cmd.ShotStore
import com.kilon.agentvscreen.tr
import java.io.File
import java.io.FileOutputStream

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
 *  - attached pictures are a compact copy (long side [SIDE], JPEG [QUALITY]); the full one stays on the phone
 *    (images[].full_url, or the CLI's local file), and at most [MAX] per reply ([MAX_ASKED] for a command that asked for pictures).
 * The CLI only prints paths (nothing enters the model unless the agent opens a file), so it gets every picture.
 */
object ImagePolicy {
    const val SIDE = 1024
    const val QUALITY = 60
    /** Per reply: pictures that come by themselves, and pictures a command asked for (frames, zoom…). */
    const val MAX = 1
    const val MAX_ASKED = 8

    /** Pictures of [res] to put into the model context, by name (compact copies made on demand). */
    fun pick(res: NativeOffloadResult, shots: List<String>): List<String> {
        if (shots.isEmpty()) return emptyList()
        val d = res.data
        // A command that exists to show pictures (no four-block data): what it asked for, up to MAX_ASKED.
        if (d == null || !d.has("picture_changed")) return shots.take(MAX_ASKED)
        val looks = (d.optJSONArray("steps")?.length() ?: 0) == 0 || d.optBoolean("picture_changed")
        return if (res.exitCode != 0 || looks) shots.take(MAX) else emptyList()
    }

    /** The line that stands where a left-out picture was. */
    fun leftOut(): String = tr("[没附图：这段画面没有变化，和你上一张图一样；要再看用 act（不带步骤）]", "[no picture attached: the screen did not change in this stretch, same as your last picture; act with no steps shows it again]")

    /** A smaller copy of picture [name] for a model context: its name (made once, kept beside the original). */
    fun compact(ctx: Context, name: String): String {
        val src = ShotStore.file(ctx, name) ?: return name
        val cname = "vscreen_c" + name.removePrefix("vscreen")
        if (name.startsWith("vscreen_c_")) return name
        val out = File(src.parentFile, cname)
        if (out.isFile) return cname
        return runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.path, o)
            val long = maxOf(o.outWidth, o.outHeight)
            var sample = 1
            while (long / (sample * 2) >= SIDE) sample *= 2
            val b0 = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return name
            val k = SIDE.toFloat() / maxOf(b0.width, b0.height)
            val b = if (k < 1f) Bitmap.createScaledBitmap(b0, (b0.width * k).toInt().coerceAtLeast(1), (b0.height * k).toInt().coerceAtLeast(1), true).also { if (it !== b0) b0.recycle() } else b0
            FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            b.recycle()
            if (out.length() >= src.length()) { out.delete(); name } else cname
        }.getOrDefault(name)
    }
}
