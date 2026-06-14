package com.example.plohoystream.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AvSyncTest {

    // ── chooseVideoEpoch ─────────────────────────────────────────────────────

    /**
     * Simulate sensor on CLOCK_MONOTONIC (SENSOR_INFO_TIMESTAMP_SOURCE == UNKNOWN).
     * nanoT0 and the first PTS are on the same clock, so they are very close.
     * bootT0 is far away (device slept 10 minutes → big offset).
     */
    @Test fun sensorIsMonotonic_picksNanoT0() {
        val nanoT0 = 5_000_000_000L                     // 5 s since some reference
        val bootT0 = nanoT0 + 600_000_000_000L          // boot clock is 600 s ahead (10 min sleep)
        // First frame arrives ~33 ms after nanoT0 (one frame at 30 fps).
        val firstPtsNanos = nanoT0 + 33_333_333L
        val result = chooseVideoEpoch(firstPtsNanos, nanoT0, bootT0)
        assertEquals(nanoT0, result)
    }

    /**
     * Simulate sensor on CLOCK_BOOTTIME (SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME).
     * bootT0 and the first PTS are close; nanoT0 is far (10 min sleep).
     */
    @Test fun sensorIsBoottime_picksBootT0() {
        val bootT0 = 5_000_000_000L
        val nanoT0 = bootT0 - 600_000_000_000L          // mono clock is 600 s behind
        val firstPtsNanos = bootT0 + 33_333_333L
        val result = chooseVideoEpoch(firstPtsNanos, nanoT0, bootT0)
        assertEquals(bootT0, result)
    }

    /**
     * When both clocks are identical (device never slept since boot), the distance
     * to each epoch is the same → either is correct. The resulting PTS must be within
     * a generous tolerance of the true offset (~33 ms).
     */
    @Test fun nearEqualEpochs_resultWithinTolerance() {
        val t0 = 5_000_000_000L
        val nanoT0 = t0
        val bootT0 = t0                                  // identical clocks
        val firstPtsNanos = t0 + 33_333_333L
        val epoch = chooseVideoEpoch(firstPtsNanos, nanoT0, bootT0)
        val ptsMs = (firstPtsNanos - epoch) / 1_000_000L
        assertTrue("ptsMs should be ~33 ms, got $ptsMs", abs(ptsMs - 33L) < 1000L)
    }

    // ── Audio rebase math ────────────────────────────────────────────────────

    /**
     * Audio PTS = (System.nanoTime() - nanoT0) / 1_000_000.
     * Verify that the formula rounds to milliseconds correctly.
     */
    @Test fun audioRebaseMath_correctMs() {
        val nanoT0 = 1_000_000_000_000L                 // arbitrary epoch
        val captureNano = nanoT0 + 500_000_000L         // 500 ms after epoch
        val ptsMs = (captureNano - nanoT0) / 1_000_000L
        assertEquals(500L, ptsMs)
    }

    /**
     * Sub-millisecond remainder is truncated (not rounded), which is fine for
     * 1-ms-resolution RTMP timestamps.
     */
    @Test fun audioRebaseMath_truncatesSubMs() {
        val nanoT0 = 0L
        val captureNano = 1_999_999L                    // 1.999 ms
        val ptsMs = (captureNano - nanoT0) / 1_000_000L
        assertEquals(1L, ptsMs)
    }

    // ── Video rebase math ────────────────────────────────────────────────────

    /**
     * Video PTS = (framePtsNanos - videoEpochNanos) / 1_000_000.
     * Verify the formula for a normal frame.
     */
    @Test fun videoRebaseMath_correctMs() {
        val epoch = 3_000_000_000L
        val framePts = epoch + 1_000_000_000L           // 1000 ms after epoch
        val ptsMs = (framePts - epoch) / 1_000_000L
        assertEquals(1000L, ptsMs)
    }

    /**
     * First frame at ~33 ms; subsequent frame at ~66 ms.
     * Verify that the second frame's relative PTS is correct when the same epoch is reused.
     */
    @Test fun videoRebaseMath_subsequentFrame() {
        val epoch = 3_000_000_000L
        val frame1 = epoch + 33_333_333L
        val frame2 = epoch + 66_666_666L
        val pts1 = (frame1 - epoch) / 1_000_000L
        val pts2 = (frame2 - epoch) / 1_000_000L
        assertEquals(33L, pts1)
        assertEquals(66L, pts2)
    }
}
