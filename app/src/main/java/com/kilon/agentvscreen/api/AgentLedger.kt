package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.tr

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 1.1.6: does the agent still know about us in its next conversation?
 *
 * 1.1.4/1.1.5 printed a skill in the install output and asked the agent to save it. Most agents did not: a request
 * inside a tool's output is data to them (often treated as a prompt injection), not something their user asked. So the
 * request now comes from the user (the app copies a message the user sends to the agent, see [AgentGuide.onboard]), and
 * this ledger shows the user whether it worked, from facts only:
 *  - installed: the install script fetched the client;
 *  - remembered: the agent ran `vscreen remembered "<where>"` after saving the note (its own claim, shown as such);
 *  - came back: a call after ≥ [GAP_MS] of silence since install — a later conversation found vscreen on its own.
 * Names and times only; no command contents.
 */
object AgentLedger {
    const val GAP_MS = 30 * 60_000L
    private lateinit var prefs: SharedPreferences

    data class Snapshot(
        val installAt: Long = 0L,
        val rememberedAt: Long = 0L,
        val rememberedWhere: String? = null,
        val lastCallAt: Long = 0L,
        val comebacks: Int = 0,
        val lastComebackAt: Long = 0L,
        /** 1.3 beta5: the last call from an agent on this phone / from another device (the Connect tile says which is in use). */
        val lastLocalAt: Long = 0L,
        val lastRemoteAt: Long = 0L,
    ) {
        val installed get() = installAt > 0
        val remembered get() = rememberedAt >= installAt && rememberedAt > 0
        val cameBack get() = comebacks > 0 && lastComebackAt > installAt
    }

    private val _snap = MutableStateFlow(Snapshot())
    val snap: StateFlow<Snapshot> = _snap.asStateFlow()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("agent_vscreen_ledger", Context.MODE_PRIVATE)
        _snap.value = Snapshot(
            prefs.getLong("install_at", 0L), prefs.getLong("remembered_at", 0L), prefs.getString("remembered_where", null),
            prefs.getLong("last_call_at", 0L), prefs.getInt("comebacks", 0), prefs.getLong("last_comeback_at", 0L),
            prefs.getLong("last_local_at", 0L), prefs.getLong("last_remote_at", 0L),
        )
    }

    private fun save(s: Snapshot) {
        _snap.value = s
        if (!::prefs.isInitialized) return
        prefs.edit().putLong("install_at", s.installAt).putLong("remembered_at", s.rememberedAt)
            .putString("remembered_where", s.rememberedWhere).putLong("last_call_at", s.lastCallAt)
            .putInt("comebacks", s.comebacks).putLong("last_comeback_at", s.lastComebackAt)
            .putLong("last_local_at", s.lastLocalAt).putLong("last_remote_at", s.lastRemoteAt).apply()
    }

    fun installed() {
        // A fresh install starts a fresh record: the old "remembered" belonged to an earlier agent / token.
        save(Snapshot(installAt = System.currentTimeMillis(), lastCallAt = System.currentTimeMillis()))
    }

    // ── 1.3 beta4: which app version each agent's note is for (per token) ──
    private val nudged = java.util.concurrent.ConcurrentHashMap<String, Long>()
    const val NUDGE_EVERY_MS = 20 * 60_000L

    /** The version code [tokenId]'s agent last confirmed its note for; 0 = never. */
    fun ackVersion(tokenId: String): Int = if (::prefs.isInitialized) prefs.getInt("ack_$tokenId", 0) else 0

    fun ack(tokenId: String) {
        if (::prefs.isInitialized) prefs.edit().putInt("ack_$tokenId", com.kilon.agentvscreen.BuildConfig.VERSION_CODE).putLong("ack_at_$tokenId", System.currentTimeMillis()).apply()
        nudged.remove(tokenId)
        _snap.value = _snap.value.copy()
    }

    /** The update notice goes out on the first command after an update, then at most every 20 minutes or at a new stretch of use. */
    fun nudgeDue(tokenId: String, freshStretch: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val last = nudged[tokenId] ?: 0L
        if (!freshStretch && now - last < NUDGE_EVERY_MS) return false
        nudged[tokenId] = now
        return true
    }

    fun remembered(where: String) {
        com.kilon.agentvscreen.cmd.Call.current()?.tokenId.let { ack(it ?: "main") }
        save(_snap.value.copy(rememberedAt = System.currentTimeMillis(), rememberedWhere = where.trim().take(80).ifEmpty { tr("（没说）", "(not said)") }))
    }

    /**
     * Every command. Returns true when this call opens a new stretch of use after a long silence (likely a new
     * conversation): the reply then carries a one-line pointer to `vscreen guide`.
     */
    fun call(): Boolean {
        val now = System.currentTimeMillis()
        val s = _snap.value
        val fresh = s.lastCallAt == 0L || now - s.lastCallAt >= GAP_MS
        val comeback = fresh && s.installed && s.lastCallAt > 0
        val remote = com.kilon.agentvscreen.cmd.Call.current()?.peer != null
        save(s.copy(lastCallAt = now, lastLocalAt = if (remote) s.lastLocalAt else now, lastRemoteAt = if (remote) now else s.lastRemoteAt, comebacks = s.comebacks + if (comeback) 1 else 0, lastComebackAt = if (comeback) now else s.lastComebackAt))
        return fresh
    }
}
