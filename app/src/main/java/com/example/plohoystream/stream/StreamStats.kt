package com.example.plohoystream.stream

/**
 * Smooths egress kbps from successive (bytesSent, timestampMs) samples via an EMA.
 * [alpha] is the new-sample weight (1.0 = no smoothing). Not thread-safe; call from one loop.
 */
class BitrateMeter(private val alpha: Double = 0.3) {
    private var lastBytes: Long = -1
    private var lastMs: Long = 0
    private var ema: Double = 0.0

    /** Returns the current smoothed bitrate in kbps (integer). */
    fun update(bytesSent: Long, timestampMs: Long): Int {
        if (lastBytes < 0) { lastBytes = bytesSent; lastMs = timestampMs; return 0 }
        val dt = timestampMs - lastMs
        if (dt <= 0) return ema.toInt()
        val db = bytesSent - lastBytes
        lastBytes = bytesSent; lastMs = timestampMs
        val rawKbps = (db.coerceAtLeast(0) * 8.0) / dt   // bytes*8 / ms == kbits/s == kbps
        ema = alpha * rawKbps + (1 - alpha) * ema
        return ema.toInt()
    }
}

enum class ConnectionHealth { Good, Warn, Bad }

/**
 * Derives connection health from egress backpressure (queue depth vs capacity) and the
 * actual-vs-target bitrate ratio. Thresholds:
 *  - queue > 60% capacity OR actual < 50% target  -> Bad
 *  - queue > 25% capacity OR actual < 80% target  -> Warn
 *  - else                                         -> Good
 * When [targetKbps] <= 0 the bitrate ratio is not evaluated (judge by queue only).
 */
fun deriveHealth(queueDepth: Int, queueCapacity: Int, actualKbps: Int, targetKbps: Int): ConnectionHealth {
    val queueRatio = if (queueCapacity > 0) queueDepth.toDouble() / queueCapacity else 0.0
    val bitrateRatio = if (targetKbps > 0) actualKbps.toDouble() / targetKbps else 1.0
    return when {
        queueRatio > 0.60 || bitrateRatio < 0.50 -> ConnectionHealth.Bad
        queueRatio > 0.25 || bitrateRatio < 0.80 -> ConnectionHealth.Warn
        else -> ConnectionHealth.Good
    }
}

/** Formats elapsed [millis] as `H:MM:SS` (>= 1h) or `MM:SS` (< 1h). Negative clamps to 0. */
fun formatElapsed(millis: Long): String {
    val totalSec = (millis.coerceAtLeast(0L)) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
