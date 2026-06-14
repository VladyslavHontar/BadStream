package com.example.plohoystream.stream

import kotlin.math.sqrt

/**
 * Normalized 0..1 RMS of little-endian 16-bit PCM over the first [lengthBytes] bytes.
 * 1.0 == full-scale (peak ±32767). Used to drive the audio meter; pure, no Android deps.
 */
fun rms16(pcm: ByteArray, lengthBytes: Int): Float {
    val n = (lengthBytes.coerceIn(0, pcm.size)) / 2
    if (n == 0) return 0f
    var sumSq = 0.0
    var i = 0
    while (i < n) {
        val lo = pcm[i * 2].toInt() and 0xFF
        val hi = pcm[i * 2 + 1].toInt()           // signed: preserves the high byte's sign
        val sample = (hi shl 8) or lo
        val v = sample.toDouble() / 32768.0
        sumSq += v * v
        i++
    }
    return sqrt(sumSq / n).toFloat().coerceIn(0f, 1f)
}
