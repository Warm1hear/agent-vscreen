package com.kilon.agentvscreen.host

import android.content.Context
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.tr

/**
 * 1.4.0-beta2: the one way to repair what Shizuku can repair. Before, turning accessibility on was written three times
 * (setup, the self-heal, `vscreen fix`), each with its own wait and its own words. Policy (when to try, how often, when
 * to stop trying) stays with the callers: A11yHeal (quietly in the background), Setup (the user tapped), fix (an agent
 * asked). The doing is here.
 */
object Heal {
    private fun comp(ctx: Context) = "${ctx.packageName}/${VScreenAccessibilityService::class.java.name}"

    private fun enabledList(): List<String> =
        VScreenShell.run(arrayOf("settings", "get", "secure", "enabled_accessibility_services")).stdout.trim()
            .split(':').filter { it.isNotBlank() && it != "null" }

    private fun connected(waitMs: Long): Boolean {
        val t0 = System.currentTimeMillis()
        while (true) {
            if (VScreenAccessibilityService.getInstance() != null) return true
            if (System.currentTimeMillis() - t0 >= waitMs) return false
            Thread.sleep(200)
        }
    }

    /**
     * Turn this app's read-only accessibility service on, keeping every other service in the list. [restart]: take it
     * out first and put it back (re-binds a service that is on but sees nothing). Needs Shizuku.
     */
    fun accessibility(ctx: Context, restart: Boolean = false): Pair<Boolean, String> {
        if (!ShizukuManager.isReady()) return false to tr("需要先修好 Shizuku（fix no_privilege），或请用户在 系统设置 › 无障碍 › 已安装的应用 › Agent-VScreen 手动打开",
            "Fix Shizuku first (fix no_privilege), or ask the user to turn it on in Settings › Accessibility › Installed apps › Agent-VScreen")
        val me = comp(ctx)
        val rest = enabledList().filter { it != me }
        if (restart) {
            if (rest.isEmpty()) VScreenShell.run(arrayOf("settings", "delete", "secure", "enabled_accessibility_services"))
            else VScreenShell.run(arrayOf("settings", "put", "secure", "enabled_accessibility_services", rest.joinToString(":")))
            Thread.sleep(500)
        }
        // Writing the list (ours last) also re-binds a crashed service.
        val w = VScreenShell.run(arrayOf("settings", "put", "secure", "enabled_accessibility_services", (rest + me).joinToString(":")))
        VScreenShell.run(arrayOf("settings", "put", "secure", "accessibility_enabled", "1"))
        if (w.exitCode != 0) return false to tr("无障碍：写系统设置失败（${w.combined.take(80)}），请在 系统设置 › 无障碍 › Agent-VScreen 手动打开",
            "Accessibility: writing system settings failed (${w.combined.take(80)}); turn it on in Settings › Accessibility › Agent-VScreen")
        return if (connected(4_000)) true to tr("无障碍已打开（其它无障碍服务保持不变）", "Accessibility turned on (other accessibility services unchanged)")
        else false to tr("无障碍：已写入设置但 4 秒内没连上，部分系统会拦截，请在 系统设置 › 无障碍 › Agent-VScreen 手动打开一次",
            "Accessibility: the setting was written but did not connect within 4 s (some systems block it); turn it on once in Settings › Accessibility › Agent-VScreen")
    }

    /** Battery exemption and background running, as a user would grant them by hand. */
    fun power(ctx: Context): Pair<Boolean, String> = com.kilon.agentvscreen.core.VScreenPower.whitelist(ctx)
}
