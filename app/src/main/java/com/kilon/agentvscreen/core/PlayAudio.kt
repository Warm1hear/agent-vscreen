package com.kilon.agentvscreen.core

/**
 * 1.4.0-beta13 (rainbow: the beta12 repair did not give the sound back). How an app's PLAY_AUDIO app-op is read, judged
 * and set, in one place for the screen service (which mutes) and the app (which repairs). Pure: tests cover it.
 *
 * What the field showed (Honor, MagicOS):
 *  - `cmd appops get` prints TWO records, the UID's first: `Uid mode: PLAY_AUDIO: default` then `PLAY_AUDIO: ignore`.
 *    Reading the first match read the UID line and missed the package's mute.
 *  - An EXPLICIT UID record of `default` mutes too (the audio service marks players `mutedState:opPlayAudio`), so
 *    "set it back to default" never brought sound back. Only `allow`, set on both records, does.
 *  - `cmd appops query-op` ignores `--user` and does not see UID records, so it cannot find silenced apps.
 *  - `--uid` takes a numeric uid on AOSP (a package name only on some ROMs).
 *  - Apps cloned into other users (twin apps, private space) have records of their own.
 *  - Players that already exist stay muted until the app starts playing again.
 */
object PlayAudio {
    const val OP = "PLAY_AUDIO"

    /** The package's record and the UID's record; null = none (the system default, which plays). */
    data class Modes(val pkg: String?, val uid: String?)

    private val MODE = Regex("PLAY_AUDIO:\\s*([a-z_]+)")

    fun parse(out: String): Modes {
        var pkg: String? = null; var uid: String? = null
        for (line in out.lines()) {
            val m = MODE.find(line)?.groupValues?.get(1) ?: continue
            if (line.contains("Uid mode", ignoreCase = true)) uid = m else if (pkg == null) pkg = m
        }
        return Modes(pkg, uid)
    }

    private val BLOCKS = setOf("ignore", "deny", "errored")

    /** The system will not let this app play: the package record blocks, or the UID record blocks or is an explicit default. */
    fun silent(m: Modes): Boolean = m.pkg in BLOCKS || m.uid in BLOCKS || m.uid == "default"

    /** Read output that says the shell may not change app-ops (Xiaomi without "USB debugging (security settings)", others). */
    fun denied(out: String): Boolean = Regex("SecurityException|Permission denial|not allowed", RegexOption.IGNORE_CASE).containsMatchIn(out)

    /** Shell lines that give [pkg] (user [user], numeric [uid] when known) its sound back on both records, then read them. */
    fun giveBack(user: Int, pkg: String, uid: Int?): String {
        val u = "--user $user"
        return "cmd appops set $u $pkg $OP allow 2>&1; " +
            "cmd appops set $u --uid ${uid ?: pkg} $OP allow 2>&1; " +
            "echo '@@after $user $pkg'; cmd appops get $u $pkg $OP 2>&1"
    }

    /**
     * A shell script that reads PLAY_AUDIO of every package of every user it may read (`pm list users`; a user the
     * shell may not list prints nothing). Each package's reading follows a line `@@ <user> <package> <uid>`.
     */
    const val SCAN = "for u in \$(pm list users 2>/dev/null | sed -n 's/.*UserInfo{\\([0-9]*\\):.*/\\1/p'); do " +
        "pm list packages -U --user \$u 2>/dev/null | while read l; do p=\${l#package:}; p=\${p%% *}; " +
        "i=\$(echo \"\$l\" | sed -n 's/.*uid:\\([0-9]*\\).*/\\1/p'); echo \"@@ \$u \$p \$i\"; cmd appops get --user \$u \$p PLAY_AUDIO 2>&1; done; done"

    data class Hit(val user: Int, val pkg: String, val uid: Int?, val modes: Modes)

    /** The readings of [SCAN] (or of [giveBack]'s `@@after` lines), one per package. */
    fun parseScan(out: String): List<Hit> {
        val hits = ArrayList<Hit>()
        var head: List<String>? = null
        val body = StringBuilder()
        fun flush() {
            val h = head ?: return
            val user = h.getOrNull(1)?.toIntOrNull() ?: return
            val pkg = h.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: return
            hits += Hit(user, pkg, h.getOrNull(3)?.toIntOrNull(), parse(body.toString()))
        }
        for (line in out.lines()) {
            if (line.startsWith("@@")) {
                flush(); body.setLength(0)
                head = line.removePrefix("@@after").removePrefix("@@").trim().split(Regex("\\s+")).let { listOf("@@") + it }
            } else body.append(line).append('\n')
        }
        flush()
        return hits
    }
}
