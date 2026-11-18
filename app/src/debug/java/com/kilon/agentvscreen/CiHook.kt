package com.kilon.agentvscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.host.HostService
import java.io.File

/**
 * 1.4.2, debug builds only: lets the CI emulator tests (tools/android-smoke/agent_e2e.py) reach the app the way an agent
 * does, without tapping through the UI to copy the token. `adb shell am broadcast -a com.kilon.agentvscreen.CI_SEED`
 * turns the service on and writes "<main token>\n<port>" to the app's external files dir, which adb can read.
 * Release builds have neither this class nor its receiver (src/debug), and only the shell may send it (DUMP).
 */
class CiHook : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread({
            try {
                com.kilon.agentvscreen.host.Prefs.setServiceOn(app, true)
                HostService.start(app)
                var port = 0
                repeat(60) { if (port == 0) { port = HostService.server?.boundPort ?: 0; if (port == 0) Thread.sleep(500) } }
                val key = Tokens.list.value.firstOrNull { it.id == Tokens.MAIN }?.key.orEmpty()
                File(app.getExternalFilesDir(null), "ci.txt").writeText("$key\n$port\n")
            } finally { pending.finish() }
        }, "vscreen-ci-seed").start()
    }
}
