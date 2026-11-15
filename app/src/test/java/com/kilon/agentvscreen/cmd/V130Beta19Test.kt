package com.kilon.agentvscreen.cmd

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

/** 1.3.0-beta19: human pacing stays within its bounds. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V130Beta19Test {

    @Test fun pausesHoldsAndGapsStayInRange() {
        val h = Human(Random(7))
        val pauses = (1..2000).map { h.pauseMs() }
        assertTrue(pauses.all { it in 150L..2_600L })
        assertTrue(pauses.average() in 350.0..800.0)
        assertTrue(pauses.distinct().size > 300)
        assertTrue((1..500).map { h.holdMs() }.all { it in 55..140 })
        assertTrue((1..500).map { h.keyGapMs() }.all { it in 70L..260L })
        assertTrue((1..500).map { h.vary(1000L, 0.2) }.all { it in 800L..1200L })
    }

    @Test fun tapsLandInsideTheElementAndOnTheScreen() {
        val h = Human(Random(3))
        val box = Rect(100, 200, 140, 230)
        repeat(1000) { val (x, y) = h.near(120, 215, box, 1080, 2400); assertTrue(x in 102..138 && y in 202..228) }
        repeat(1000) { val (x, y) = h.near(2, 2398, null, 1080, 2400); assertTrue(x in 0..12 && y in 2388..2399) }
        val moved = (1..200).count { h.near(540, 1200, Rect(400, 1100, 680, 1300), 1080, 2400) != (540 to 1200) }
        assertTrue(moved > 150)
    }

    @Test fun typingPiecesJoinBackAndKeepEmojiWhole() {
        val h = Human(Random(11))
        val text = "蓝牙耳机😀 abc"
        repeat(50) {
            val p = h.pieces(text)
            assertEquals(text, p.joinToString(""))
            assertTrue(p.all { it.isNotEmpty() && !Character.isHighSurrogate(it.last()) })
        }
    }
}
