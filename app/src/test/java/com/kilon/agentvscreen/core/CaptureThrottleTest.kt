package com.kilon.agentvscreen.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureThrottleTest {
    @Test fun pollingDoesNotSkipTheFullSizeFrameBudget() {
        val c = CaptureThrottle()
        assertEquals(0L, c.delayMs(0, 120))
        c.onConvert(0)
        assertEquals(104L, c.delayMs(16, 120))
        assertEquals(20L, c.delayMs(100, 120))
        assertEquals(0L, c.delayMs(120, 120))
    }

    @Test fun explicitRefreshBypassesTheLimitOnceAndCoalesces() {
        val c = CaptureThrottle()
        c.onConvert(100)
        c.request()
        c.request()
        assertEquals(0L, c.delayMs(110, 120))
        c.onConvert(110)
        assertEquals(119L, c.delayMs(111, 120))
        assertEquals(0L, c.delayMs(230, 120))
    }

    @Test fun anExplicitFastCaptureStillUsesItsRequestedInterval() {
        val c = CaptureThrottle()
        c.onConvert(100)
        assertEquals(17L, c.delayMs(116, 33))
        assertEquals(0L, c.delayMs(133, 33))
    }
}
