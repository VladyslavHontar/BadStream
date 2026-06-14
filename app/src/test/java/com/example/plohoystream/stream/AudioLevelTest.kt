package com.example.plohoystream.stream

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sin

class AudioLevelTest {
    private fun le16(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            out[i * 2] = (samples[i].toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((samples[i].toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test fun silenceIsZero() {
        val pcm = le16(ShortArray(512) { 0 })
        assertEquals(0f, rms16(pcm, pcm.size), 1e-4f)
    }

    @Test fun fullScaleIsOne() {
        val pcm = le16(ShortArray(512) { Short.MAX_VALUE })
        assertEquals(1f, rms16(pcm, pcm.size), 1e-3f)
    }

    @Test fun sineIsAboutPointSevenOfPeak() {
        // A full-scale sine has RMS = peak / sqrt(2) ~= 0.707.
        val pcm = le16(ShortArray(1024) { i ->
            (Short.MAX_VALUE * sin(2.0 * Math.PI * i / 64.0)).toInt().toShort()
        })
        assertEquals(0.707f, rms16(pcm, pcm.size), 0.02f)
    }

    @Test fun honoursLengthBytes() {
        // Only the first 2 samples (loud) are counted; the tail (silent) is ignored.
        val loud = ShortArray(4) { Short.MAX_VALUE } + ShortArray(60) { 0 }
        val pcm = le16(loud)
        assertEquals(1f, rms16(pcm, 8), 1e-3f) // 8 bytes = 4 samples, all full-scale
    }
}
