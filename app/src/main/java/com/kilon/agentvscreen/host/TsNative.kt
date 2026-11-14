package com.kilon.agentvscreen.host

/**
 * 1.3.0: the built-in Tailscale node runs inside this process (libtsnode.so, Go via JNI; see tsbridge/jni.go).
 *
 * Before, it was a child process. Android does not count a child as part of the app: the foreground service protects
 * the app's own process only, and MagicOS froze the child for about two minutes at a time as soon as the user left the
 * app, while the app itself kept running. A child also outlived an app process that was killed, and kept the node's
 * key on the relay so the new node could not get on for minutes. In-process, the node lives and freezes exactly with
 * the app, and nothing is left behind.
 */
object TsNative {
    /** Null when the library could not be loaded (then the built-in node is unavailable). */
    val loadError: String? = runCatching { System.loadLibrary("tsnode") }.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
    val loaded get() = loadError == null

    /** 0 = started, 1 = a node is still running, 2 = bad configuration. */
    @JvmStatic external fun start(config: ByteArray): Int
    /** "login", "logout" or "quit" (returns at once; an "X" line follows when the node has closed). */
    @JvmStatic external fun command(cmd: String)
    @JvmStatic external fun running(): Boolean
    /** The next line within [ms], UTF-8, or null: "S{json}" state, "L text" log, "H" heartbeat, "X code" ended. */
    @JvmStatic external fun next(ms: Int): ByteArray?
}
