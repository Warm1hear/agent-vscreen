package com.kilon.agentvscreen.core

/**
 * 1.4.2-beta6: who may put input on a display, as the screen service sees it.
 *
 * Before, "the user took over" and "the user pressed Stop" were flags in the app process only. The screen service
 * (Shizuku / root) carried out whatever reached it: a 10 s long press it had started went on under the user's finger,
 * a call that came back late (the app had given up on it after its deadline) still landed, and the agent's gesture
 * and the user's finger were two streams on one display, interleaved by the binder threads.
 *
 * Now every injection names the control epoch its command was planned under ([Control.epoch]; [USER] for the user's
 * own input). The app sends the current epoch with every change of control (takeover, hand-back, Stop, allow) and
 * whether the agent may act in it. An agent injection is admitted only while the agent may act and its epoch is not
 * older than the last change. Long injections ask again before every event they send, and end with a cancel (never
 * a lift, which apps take as a tap) once they are fenced out. All input to one display runs under one lock, so the
 * fence returns only after the agent's gesture in flight has ended.
 *
 * Pure Kotlin (no Android types) so the rules are tested on the JVM; the service owns one instance.
 */
class InputFence {
    companion object {
        /** The epoch the user's own input carries: never fenced (the app lets it through only while the user holds the screen). */
        const val USER = -1L
        /** A display the app has not fenced yet (a fresh service, or an app older than the fence): everything is admitted. */
        const val UNSET = Long.MIN_VALUE
        /** The pointer id kept for the user's finger; agents use the low ids. */
        const val USER_FINGER = 9

        /** The rule itself. */
        fun admits(fenceEpoch: Long, agentAllowed: Boolean, epoch: Long): Boolean = when {
            epoch == USER -> true
            fenceEpoch == UNSET -> true
            else -> agentAllowed && epoch >= fenceEpoch
        }

        /** Which held fingers a cancel from [epoch] takes away: the user's own cancel lifts only their finger, anything else only the agent's. */
        fun cancels(epoch: Long, pointerId: Int): Boolean = (pointerId == USER_FINGER) == (epoch == USER)
    }

    private class Gate {
        @Volatile var epoch = UNSET
        @Volatile var agentAllowed = true
        val lock = Any()
    }

    private val gates = HashMap<Int, Gate>()

    private fun gate(displayId: Int): Gate = synchronized(gates) { gates.getOrPut(displayId) { Gate() } }

    /** May an injection that carries [epoch] reach [displayId] now? Asked before every event of a long injection. */
    fun admits(displayId: Int, epoch: Long): Boolean = gate(displayId).let { admits(it.epoch, it.agentAllowed, epoch) }

    /** The one lock all input to [displayId] runs under. */
    fun lock(displayId: Int): Any = gate(displayId).lock

    /** A change of control: from now on agent input needs [epoch] or newer, and is refused while ![agentAllowed]. */
    fun set(displayId: Int, epoch: Long, agentAllowed: Boolean) {
        val g = gate(displayId)
        // The flag first: an injection reading between the two writes sees "not allowed", never "allowed with the old epoch".
        g.agentAllowed = false
        g.epoch = epoch
        g.agentAllowed = agentAllowed
    }

    fun forget(displayId: Int) { synchronized(gates) { gates.remove(displayId) } }
}
