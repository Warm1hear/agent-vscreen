package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.cmd.Call
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 1.4.2-beta6 (review of takeover / Stop): control has an epoch; every input asks at the moment it is sent, in the app
 * and again in the screen service, and a cancel is never a tap.
 */
@RunWith(RobolectricTestRunner::class)
class V142Beta6Test {
    private val savedInjector = VScreenShell.injector

    @After fun tearDown() {
        VScreenShell.injector = savedInjector
        BackgroundOperation.updateStateForTest { it.copy(userStopped = false, stoppedAt = 0L, takeoverSessionId = null) }
    }

    private fun recorder(seen: MutableList<String>) = object : VScreenShell.Injector {
        override fun gesture(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int): Result<Boolean> { seen += "g"; return Result.success(true) }
        override fun key(displayId: Int, code: Int): Result<Boolean> { seen += "k"; return Result.success(true) }
        override fun text(displayId: Int, text: String): Result<Boolean> { seen += "t"; return Result.success(true) }
        override fun pointer(displayId: Int, op: Int, pointerId: Int, x: Float, y: Float): Result<Pair<Boolean, Int>> { seen += "p$op"; return Result.success(true to 0) }
    }

    private fun refused(code: String, block: () -> Unit) {
        try { block(); fail("expected $code") } catch (e: ActionError) { assertEquals(code, e.code) }
    }

    // ── the screen service's fence ──────────────────────────────────────

    @Test fun anUnfencedDisplayAdmitsEverything() {
        val f = InputFence()
        assertTrue(f.admits(4, 1L)); assertTrue(f.admits(4, InputFence.USER))
    }

    @Test fun aTakeoverFencesTheAgentOutButNotTheUser() {
        val f = InputFence()
        f.set(4, 100L, agentAllowed = false)
        assertFalse(f.admits(4, 100L)); assertFalse(f.admits(4, 101L))
        assertTrue(f.admits(4, InputFence.USER))
        // Another display is not touched.
        assertTrue(f.admits(5, 1L))
    }

    @Test fun afterTheHandBackOnlyNewPlansGetThrough() {
        val f = InputFence()
        f.set(4, 100L, agentAllowed = false)
        f.set(4, 101L, agentAllowed = true)
        assertFalse("planned before the hand-back", f.admits(4, 99L))
        assertFalse("planned while the user held it", f.admits(4, 100L))
        assertTrue(f.admits(4, 101L))
        // A later change the service did not hear of (a lost fence call) does not lock the agent out.
        assertTrue(f.admits(4, 105L))
        f.forget(4)
        assertTrue(f.admits(4, 1L))
    }

    @Test fun aCancelTakesOnlyTheCallersOwnFingers() {
        // The user's cancel (a pinch on the window) lifts only their finger; the agent's / the system's only the agent's.
        assertTrue(InputFence.cancels(InputFence.USER, InputFence.USER_FINGER))
        assertFalse(InputFence.cancels(InputFence.USER, 0))
        assertTrue(InputFence.cancels(100L, 0))
        assertFalse(InputFence.cancels(100L, InputFence.USER_FINGER))
    }

    // ── the app's gate ───────────────────────────────────────────────────

    @Test fun theRuleIsOneTable() {
        assertNull(Control.refusal(stopped = false, takenOver = false, planned = 5, current = 5))
        assertEquals("USER_STOPPED", Control.refusal(stopped = true, takenOver = false, planned = 5, current = 5))
        assertEquals("USER_TAKEOVER", Control.refusal(stopped = false, takenOver = true, planned = 5, current = 5))
        assertEquals("CONTROL_CHANGED", Control.refusal(stopped = false, takenOver = false, planned = 4, current = 5))
    }

    @Test fun everyInputAsksAtTheMomentItIsSent() {
        val seen = ArrayList<String>()
        VScreenShell.injector = recorder(seen)
        assertTrue(VScreenShell.tap(4, 1, 1).ok)
        // The user takes over while the agent is still working out its next step (a text search, a slow type):
        BackgroundOperation.updateStateForTest { it.copy(takeoverSessionId = "s1") }
        refused("USER_TAKEOVER") { VScreenShell.tap(4, 1, 1) }
        refused("USER_TAKEOVER") { VScreenShell.typeKeys(4, "abc") }
        refused("USER_TAKEOVER") { VScreenShell.key(4, "enter") }
        refused("USER_TAKEOVER") { VScreenShell.finger(4, "move", 0, 3, 3) }
        BackgroundOperation.updateStateForTest { it.copy(takeoverSessionId = null, userStopped = true) }
        refused("USER_STOPPED") { VScreenShell.swipe(4, 1, 2, 3, 4, 300) }
        assertEquals(listOf("g"), seen)
    }

    @Test fun aCancelIsAlwaysAllowed() {
        val seen = ArrayList<String>()
        VScreenShell.injector = recorder(seen)
        BackgroundOperation.updateStateForTest { it.copy(userStopped = true) }
        assertTrue(VScreenShell.finger(4, "cancel", 0, -1, -1).first.ok)
        assertEquals(listOf("p3"), seen)
    }

    @Test fun theUsersOwnInputIsNotFenced() {
        val seen = ArrayList<String>()
        VScreenShell.injector = recorder(seen)
        BackgroundOperation.updateStateForTest { it.copy(takeoverSessionId = "s1") }
        Control.asUser {
            assertTrue(VScreenShell.key(4, "back").ok)
            assertEquals(InputFence.USER, Control.wireEpoch())
        }
        assertEquals(listOf("k"), seen)
        assertFalse(Control.isUser())
    }

    @Test fun aCommandFromBeforeTheHandBackChangesNothing() {
        val seen = ArrayList<String>()
        VScreenShell.injector = recorder(seen)
        // Arrived, then the user took over and handed back before it acted (it was queued, or running a long stretch).
        val call = Call("s1")
        Control.bump(); Control.bump()
        Call.with(call) {
            assertEquals(call.epoch, Control.wireEpoch())
            refused("CONTROL_CHANGED") { VScreenShell.tap(4, 1, 1) }
        }
        // The next command arrives after the hand-back: it goes.
        Call.with(Call("s1")) { assertTrue(VScreenShell.tap(4, 1, 1).ok) }
        assertEquals(listOf("g"), seen)
    }

    @Test fun aLaunchThatWaitedPlansAfresh() {
        val seen = ArrayList<String>()
        VScreenShell.injector = recorder(seen)
        val call = Call("s1")
        Control.bump()
        Call.with(call) { Control.withPlan(Control.epoch) { assertTrue(VScreenShell.tap(4, 1, 1).ok) } }
        assertEquals(listOf("g"), seen)
    }

    @Test fun anAsyncTaskKeepsTheEpochItsRequestArrivedIn() {
        val parent = Call("s1")
        Control.bump()
        val child = Call("s1").also { c -> c.epoch = parent.epoch }
        Call.with(child) { assertEquals("CONTROL_CHANGED", Control.refusal(false, false, Control.planned(), Control.epoch)) }
    }

    @Test fun aGestureCutShortByTheUserSaysWhy() {
        // The user takes over while the press is held; the service ends it with a cancel and answers "not injected".
        VScreenShell.injector = object : VScreenShell.Injector {
            override fun gesture(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int): Result<Boolean> {
                BackgroundOperation.updateStateForTest { it.copy(takeoverSessionId = "s1") }
                return Result.success(false)
            }
            override fun key(displayId: Int, code: Int) = Result.success(true)
            override fun text(displayId: Int, text: String) = Result.success(true)
            override fun pointer(displayId: Int, op: Int, pointerId: Int, x: Float, y: Float) = Result.success(true to 0)
        }
        refused("USER_TAKEOVER") { VScreenShell.longPress(4, 1, 1, 6_000) }
    }

    // ── bounded memory ───────────────────────────────────────────────────

    @Test fun elementNumbersRememberOnlyTheRecentLabels() {
        val b = com.kilon.agentvscreen.cmd.Numbers.Book()
        for (n in 1..com.kilon.agentvscreen.cmd.Numbers.MAX_LABELS + 50) { b.labels[n] = "x$n"; b.trim() }
        assertEquals(com.kilon.agentvscreen.cmd.Numbers.MAX_LABELS, b.labels.size)
        assertNull(b.labels[1])
        assertEquals("x${com.kilon.agentvscreen.cmd.Numbers.MAX_LABELS + 50}", b.labels[com.kilon.agentvscreen.cmd.Numbers.MAX_LABELS + 50])
    }

    @Test fun perSessionMemoryKeepsTheRecentSessions() {
        val m: HashMap<String, Int> = com.kilon.agentvscreen.cmd.VScreenOffloadHandler.recent()
        m["first"] = 0
        for (i in 1..com.kilon.agentvscreen.cmd.VScreenOffloadHandler.RECENT + 10) { m["s$i"] = i; m["first"] }
        assertEquals(com.kilon.agentvscreen.cmd.VScreenOffloadHandler.RECENT, m.size)
        assertTrue("used every time, so kept", "first" in m)
        assertFalse("s1" in m)
    }

    @Test fun theEpochKeepsRisingAcrossRestarts() {
        // It starts at the wall clock, so a restarted app is never "older" than the fence the service kept.
        assertTrue(Control.epoch >= 1_700_000_000_000L)
    }
}
