package com.kilon.agentvscreen.core

import android.content.Context
import com.kilon.agentvscreen.host.AppLogger
import org.json.JSONObject
import java.io.File

/**
 * 1.3: apps on the background screen are silent. A video played there came out of the phone's speaker while the user
 * was doing something else on the main screen (field report, 2026-11-14), against the one promise of the product.
 *
 * The audio of an app is turned off with its PLAY_AUDIO app-op (the system mutes that app's players). That is a system
 * setting that outlives whoever set it, so since 1.4.0-beta11 it is owned by the screen service, which runs as the
 * shell beside the screens and outlives this app ([VScreenDisplayService], `Sound`). It gives an app its sound back as
 * soon as the app leaves our screens or comes to the front of the main screen, when a screen is released, when this app
 * dies or is uninstalled, and on its own next start after it was killed. Before, this object owned it and gave sound
 * back only while the app ran: an uninstall or an abnormal exit left apps silent until a command fixed them (rainbow,
 * 2026-11-17).
 *
 * This object decides WHAT should be silent ([sync]); the service does it and keeps the record.
 * Not muted: an app that is in front on the main screen (the user is using it; the app-op is per app, not per screen).
 */
object VScreenMute {
    private const val TAG = "VScreenMute"
    private const val PREFS = "vscreen_mute"
    private const val K_ON = "on"
    private var ctx: Context? = null
    /** What the service said it holds, last time we asked (for the reply and the start-up check). */
    @Volatile private var known: Set<String> = emptySet()

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        // Before beta11 the app kept its own list (files/vscreen/muted.json): give those back once, through the shell.
        legacy()?.takeIf { it.isNotEmpty() }?.let { old ->
            Thread {
                val left = old.filterNot { (p, _) ->
                    runCatching { VScreenShell.run(arrayOf("sh", "-c", PlayAudio.giveBack(0, p, null)), 6_000L).let { r ->
                        PlayAudio.parseScan(r.combined).firstOrNull()?.let { !PlayAudio.silent(it.modes) } == true } }.getOrDefault(false)
                }
                if (left.isEmpty()) legacyFile()?.delete()
                else AppLogger.warning(TAG, "could not give back ${left.keys}")
            }.apply { isDaemon = true; name = "vscreen-unmute-legacy" }.start()
        }
    }

    var enabled: Boolean
        get() = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(K_ON, true) ?: true
        set(v) {
            ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(K_ON, v)?.apply()
            if (!v) Thread { repair() }.apply { isDaemon = true }.start()
        }

    private fun legacyFile(): File? = ctx?.let { File(File(it.filesDir, "vscreen"), "muted.json") }

    private fun legacy(): Map<String, String>? = runCatching {
        legacyFile()?.takeIf { it.isFile }?.readText()?.let { t -> JSONObject(t).let { o -> o.keys().asSequence().associateWith { k -> o.optString(k, "default") } } }
    }.getOrNull()

    /** "allow" / "ignore" / "default" … as `appops get` says it, or null when it cannot be read. */
    internal fun parseMode(out: String): String? = VScreenDisplayService.parsePlayAudio(out)

    /**
     * Make what is on [displayId] silent and give back what left it. [mainTop] = the package in front on the main screen
     * (never muted, given back if it was). Returns the packages newly muted (for the reply).
     */
    fun sync(displayId: Int, mainTop: String?): List<String> {
        val c = ctx ?: return emptyList()
        if (!enabled) return emptyList()
        val here = runCatching { VScreenHandoff.tasksOn(displayId).map { it.pkg } }.getOrNull() ?: return emptyList()
        val want = here.filter { it != c.packageName && it != mainTop && !VScreenShell.isProtected(it) }.toSet()
        val held = VScreenRemoteDisplay.soundList().getOrNull()?.toSet() ?: return emptyList()
        val newly = want.filter { it !in held && VScreenRemoteDisplay.soundMute(it).getOrNull() == 1 }
        newly.forEach { AppLogger.info(TAG, "muted $it") }
        held.filter { it !in want }.forEach { VScreenRemoteDisplay.soundGive(it) }
        known = VScreenRemoteDisplay.soundList().getOrNull()?.toSet() ?: (held + newly)
        return newly
    }

    /** Give [pkg] its sound back (handed over to the main screen). */
    fun restore(pkg: String) {
        if (VScreenRemoteDisplay.isConnected()) VScreenRemoteDisplay.soundGive(pkg)
        known = known - pkg
    }

    /** Give every app its sound back (screen released). */
    fun restoreAll() {
        if (VScreenRemoteDisplay.isConnected()) VScreenRemoteDisplay.soundGiveAll()
        known = emptySet()
    }

    /** What a repair found and did. [denied]: the system refused the shell (a ROM setting the user must turn on). */
    data class Repair(val fixed: List<PlayAudio.Hit>, val failed: List<PlayAudio.Hit>, val denied: Boolean)

    /** Every silenced app of every user the shell can read (both records, read one by one: query-op misses them). */
    fun silenced(): List<PlayAudio.Hit>? = runCatching {
        val r = VScreenShell.run(arrayOf("sh", "-c", PlayAudio.SCAN), 90_000L)
        PlayAudio.parseScan(r.combined).filter { PlayAudio.silent(it.modes) }.takeIf { r.combined.contains("@@") }
    }.getOrNull()

    /**
     * 1.4.0-beta12, rebuilt in beta13 (rainbow: it reported success and the app stayed silent). Gives back EVERY app the
     * system has silenced, whoever did it: the service's own list first, then a full reading of every package of every
     * user, and each silenced one gets `allow` on its package and UID records, read again to confirm. Called by
     * turning 副屏静音 off and by 恢复被静音的应用. Null without the privileged connection. A player that was already
     * playing stays quiet until the app plays again (the caller says so). An app on the background screen right now is
     * muted again by the next action while the switch is on.
     */
    fun repair(): Repair? {
        runCatching { VScreenRemoteDisplay.soundGiveAll() }
        known = emptySet()
        val bad = silenced() ?: return null
        if (bad.isEmpty()) return Repair(emptyList(), emptyList(), false)
        val out = runCatching { VScreenShell.run(arrayOf("sh", "-c", bad.joinToString("; ") { PlayAudio.giveBack(it.user, it.pkg, it.uid) }), 60_000L).combined }.getOrDefault("")
        val after = PlayAudio.parseScan(out).associateBy { it.user to it.pkg }
        val (ok, no) = bad.partition { h -> after[h.user to h.pkg]?.let { !PlayAudio.silent(it.modes) } == true }
        if (ok.isNotEmpty()) AppLogger.info(TAG, "repair gave back ${ok.map { "${it.user}/${it.pkg}" }}")
        if (no.isNotEmpty()) AppLogger.warning(TAG, "repair could not give back ${no.map { "${it.user}/${it.pkg}" }}: ${out.take(300)}")
        return Repair(ok, no, PlayAudio.denied(out))
    }

    fun mutedNow(): Set<String> = known
}
