package com.example.plohoystream.stream

import kotlin.math.abs

/**
 * Selects the correct epoch to subtract from video sensor timestamps.
 *
 * Camera2 surface encoder PTS values (`MediaCodec.BufferInfo.presentationTimeUs`) come from
 * the camera sensor clock, which is either `CLOCK_MONOTONIC` (`System.nanoTime()` domain) when
 * [android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE] is `UNKNOWN`,
 * or `CLOCK_BOOTTIME` (`SystemClock.elapsedRealtimeNanos()` domain) when it is `REALTIME`.
 *
 * Rather than plumbing `CameraCharacteristics` into the encoder, we detect the clock domain
 * empirically on the first encoded frame: the epoch captured on the **same** clock as the
 * sensor will be very close to the first PTS; the other epoch will be far off by the device's
 * accumulated deep-sleep time.
 *
 * @param firstPtsNanos  `info.presentationTimeUs * 1000` of the first encoded video frame.
 * @param nanoT0         `System.nanoTime()` captured at stream start.
 * @param bootT0         `SystemClock.elapsedRealtimeNanos()` captured at stream start.
 * @return Either [nanoT0] or [bootT0] — whichever is closer to [firstPtsNanos].
 */
fun chooseVideoEpoch(firstPtsNanos: Long, nanoT0: Long, bootT0: Long): Long =
    if (abs(firstPtsNanos - nanoT0) <= abs(firstPtsNanos - bootT0)) nanoT0 else bootT0
