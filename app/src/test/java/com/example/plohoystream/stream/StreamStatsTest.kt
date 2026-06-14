package com.example.plohoystream.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamStatsTest {
    @Test fun bitrateMeter_firstSampleIsZero() {
        val m = BitrateMeter()
        assertEquals(0, m.update(bytesSent = 0L, timestampMs = 0L))
    }

    @Test fun bitrateMeter_computesKbpsFromDelta() {
        val m = BitrateMeter(alpha = 1.0) // no smoothing: raw rate
        m.update(bytesSent = 0L, timestampMs = 0L)
        // 125_000 bytes over 1000 ms = 1_000_000 bits/s = 1000 kbps.
        assertEquals(1000, m.update(bytesSent = 125_000L, timestampMs = 1000L))
    }

    @Test fun bitrateMeter_emaSmoothsTowardNewRate() {
        val m = BitrateMeter(alpha = 0.5)
        m.update(0L, 0L)
        val first = m.update(125_000L, 1000L)   // raw 1000, ema 0.5*1000 = 500
        assertEquals(500, first)
        val second = m.update(250_000L, 2000L)  // raw 1000, ema 0.5*500 + 0.5*1000 = 750
        assertEquals(750, second)
    }

    @Test fun bitrateMeter_ignoresNonPositiveTimeDelta() {
        val m = BitrateMeter(alpha = 1.0)
        m.update(0L, 1000L)
        assertEquals(0, m.update(125_000L, 1000L)) // same ts -> no division
    }

    @Test fun deriveHealth_goodWhenQueueLowAndBitrateNearTarget() {
        assertEquals(ConnectionHealth.Good, deriveHealth(queueDepth = 10, queueCapacity = 256, actualKbps = 5800, targetKbps = 6000))
    }

    @Test fun deriveHealth_warnWhenQueueOver25Percent() {
        assertEquals(ConnectionHealth.Warn, deriveHealth(queueDepth = 70, queueCapacity = 256, actualKbps = 6000, targetKbps = 6000))
    }

    @Test fun deriveHealth_warnWhenBitrateUnder80Percent() {
        assertEquals(ConnectionHealth.Warn, deriveHealth(queueDepth = 0, queueCapacity = 256, actualKbps = 4500, targetKbps = 6000))
    }

    @Test fun deriveHealth_badWhenQueueOver60Percent() {
        assertEquals(ConnectionHealth.Bad, deriveHealth(queueDepth = 160, queueCapacity = 256, actualKbps = 6000, targetKbps = 6000))
    }

    @Test fun deriveHealth_badWhenBitrateUnder50Percent() {
        assertEquals(ConnectionHealth.Bad, deriveHealth(queueDepth = 0, queueCapacity = 256, actualKbps = 2900, targetKbps = 6000))
    }

    @Test fun deriveHealth_goodWhenTargetUnknown() {
        // targetKbps <= 0 -> bitrate ratio not evaluated, judge by queue only.
        assertEquals(ConnectionHealth.Good, deriveHealth(queueDepth = 5, queueCapacity = 256, actualKbps = 100, targetKbps = 0))
    }

    @Test fun formatElapsed_underOneHourIsMmSs() {
        assertEquals("00:00", formatElapsed(0L))
        assertEquals("00:09", formatElapsed(9_000L))
        assertEquals("01:05", formatElapsed(65_000L))
        assertEquals("59:59", formatElapsed(3_599_000L))
    }

    @Test fun formatElapsed_oneHourPlusIsHMmSs() {
        assertEquals("1:00:00", formatElapsed(3_600_000L))
        assertEquals("2:03:04", formatElapsed((2 * 3600 + 3 * 60 + 4) * 1000L))
    }

    @Test fun formatElapsed_negativeClampsToZero() {
        assertTrue(formatElapsed(-5L).startsWith("00:00"))
    }
}
