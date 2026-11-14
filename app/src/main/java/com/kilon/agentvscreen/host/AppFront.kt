package com.kilon.agentvscreen.host

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock

/**
 * 1.3 beta7: is one of the app's own pages on screen? Logged when it changes, so a problem that only happens "after
 * I leave the app" (the built-in Tailscale going offline) can be lined up with the moment the user left.
 */
object AppFront : Application.ActivityLifecycleCallbacks {
    @Volatile private var started = 0
    @Volatile var visible = false; private set
    /** uptimeMillis when the app last went to the background (0 = never). */
    @Volatile var leftAt = 0L; private set

    fun init(app: Application) = app.registerActivityLifecycleCallbacks(this)

    /** How long the app has been in the background, or 0 while one of its pages is on screen. */
    fun awayMs(): Long = if (visible || leftAt == 0L) 0L else SystemClock.uptimeMillis() - leftAt

    override fun onActivityStarted(a: Activity) {
        started++
        if (!visible) { visible = true; AppLogger.info("AppFront", "app in front (${a.javaClass.simpleName})") }
    }

    override fun onActivityStopped(a: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && visible) { visible = false; leftAt = SystemClock.uptimeMillis(); AppLogger.info("AppFront", "app left the front") }
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityResumed(a: Activity) {}
    override fun onActivityPaused(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}
