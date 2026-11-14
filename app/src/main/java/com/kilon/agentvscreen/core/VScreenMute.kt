package com.kilon.agentvscreen.core

import android.content.Context
import com.kilon.agentvscreen.host.AppLogger
import org.json.JSONObject
import java.io.File

/**
 * 1.3: apps on the background screen are silent. A video played there came out of the phone's speaker while the user
 * was doing something else on the main screen (field report, 2026-11-14), against the one promise of the product.
 *
 * The audio of an app is turned off with its PLAY_AUDIO app-op (`appops set <pkg> PLAY_AUDIO ignore`, as the shell
 * through the privileged connection): the system mutes that app's players. What it was before is written down
 * (files/vscreen/muted.json) and put back when the app leaves the background screen: handed over to the main screen,
 * the screen released, or the app shown on the main screen by the user. The file survives a crash: the next start puts
 * every app back first ([restoreAll]).
 *
 * Not muted: an app that is in front on the main screen (the user is using it; the app-op is per app, not per screen).
 * UNVERIFIED on MagicOS: that PLAY_AUDIO ignore silences every player there (some apps play through paths that do
 * not check it); the reply says when an app on the screen was muted, so a tester can tell.
 */
object VScreenMute {
    private const val TAG = "VScreenMute"
    private const val PREFS = "vscreen_mute"
    private const val K_ON = "on"
    private var ctx: Context? = null
    /** package -> the PLAY_AUDIO mode it had before we muted it. */
    private val muted = LinkedHashMap<String, String>()

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        load()
    }

    var enabled: Boolean
        get() = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(K_ON, true) ?: true
        set(v) {
            ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(K_ON, v)?.apply()
            if (!v) Thread { restoreAll() }.apply { isDaemon = true }.start()
        }

    private fun file(): File? = ctx?.let { File(File(it.filesDir, "vscreen").apply { mkdirs() }, "muted.json") }

    private fun load() = synchronized(muted) {
        muted.clear()
        runCatching { file()?.takeIf { it.isFile }?.readText()?.let { t -> JSONObject(t).let { o -> o.keys().forEach { k -> muted[k] = o.optString(k, "default") } } } }
    }

    private fun save() = synchronized(muted) {
        runCatching { file()?.writeText(JSONObject().apply { muted.forEach { (k, v) -> put(k, v) } }.toString()) }
    }

    /** "allow" / "ignore" / "default" … as `appops get` says it, or null when it cannot be read. */
    internal fun parseMode(out: String): String? =
        Regex("PLAY_AUDIO:\\s*([a-z_]+)").find(out)?.groupValues?.get(1) ?: if (out.contains("No operations", true)) "default" else null

    private fun mode(pkg: String): String? = runCatching { parseMode(VScreenShell.run(arrayOf("appops", "get", pkg, "PLAY_AUDIO"), 4_000L).combined) }.getOrNull()

    private fun set(pkg: String, mode: String): Boolean = runCatching { VScreenShell.run(arrayOf("appops", "set", pkg, "PLAY_AUDIO", mode), 4_000L).exitCode == 0 }.getOrDefault(false)

    /**
     * Make what is on [displayId] silent and give back what left it. [mainTop] = the package in front on the main screen
     * (never muted, given back if it was). Returns the packages newly muted (for the reply).
     */
    fun sync(displayId: Int, mainTop: String?): List<String> {
        val c = ctx ?: return emptyList()
        if (!enabled) return emptyList()
        val here = runCatching { VScreenHandoff.tasksOn(displayId).map { it.pkg } }.getOrNull() ?: return emptyList()
        val want = here.filter { it != c.packageName && it != mainTop && !VScreenShell.isProtected(it) }.toSet()
        val newly = ArrayList<String>()
        synchronized(muted) {
            for (p in want) if (p !in muted) {
                val before = mode(p) ?: continue
                if (before == "ignore") continue // already silent by someone else's choice: not ours to give back
                if (set(p, "ignore")) { muted[p] = before; newly += p; AppLogger.info(TAG, "muted $p (was $before)") }
            }
            val gone = muted.keys.filter { it !in want }
            for (p in gone) give(p)
            save()
        }
        return newly
    }

    private fun give(p: String) {
        val before = muted[p] ?: return
        if (set(p, if (before == "allow" || before == "default" || before == "foreground") before else "default")) { muted.remove(p); AppLogger.info(TAG, "gave back $p ($before)") }
        else AppLogger.warning(TAG, "could not give back $p")
    }

    /** Give [pkg] its sound back (handed over to the main screen). */
    fun restore(pkg: String) = synchronized(muted) { if (pkg in muted) { give(pkg); save() } }

    /** Give every app its sound back (screen released, feature turned off, app start after a crash). */
    fun restoreAll() = synchronized(muted) { muted.keys.toList().forEach { give(it) }; save() }

    fun mutedNow(): Set<String> = synchronized(muted) { muted.keys.toSet() }
}
