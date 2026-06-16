package com.example.plohoystream.stream

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

/**
 * Lightweight preview-time microphone level monitor. Reads PCM from the mic and emits a 0..1
 * [rms16] level via [onLevel] at ~10 Hz, WITHOUT encoding — so the audio meter is alive during
 * preview, not only while streaming.
 *
 * There is one mic: when streaming starts, [AudioEncoder] owns it and publishes its own level, so
 * this MUST be stopped first. [start]/[stop] are idempotent and synchronized; if the mic can't be
 * acquired (e.g. already in use) [start] is a no-op rather than a crash.
 */
class MicMonitor(
    private val sampleRate: Int = 44100,
    private val channels: Int = 2,
    private val onLevel: (Float) -> Unit,
) {
    private val channelMask =
        if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
    private val minBuf = AudioRecord.getMinBufferSize(
        sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT,
    ).coerceAtLeast(4096)

    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO gated by UI before any capture begins
    @Synchronized
    fun start() {
        if (running) return
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate, channelMask,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * 2,
            )
        }.getOrNull() ?: return
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            return
        }
        record = rec
        running = true
        runCatching { rec.startRecording() }
        worker = thread(name = "MicMonitor") { loop(rec) }
    }

    private fun loop(rec: AudioRecord) {
        val pcm = ByteArray(minBuf)
        var lastNs = 0L
        while (running) {
            val read = rec.read(pcm, 0, pcm.size)
            if (read <= 0) continue
            val now = System.nanoTime()
            if (now - lastNs >= 100_000_000L) {   // ~10 Hz, matching AudioEncoder
                lastNs = now
                onLevel(rms16(pcm, read))
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!running && record == null) return
        running = false
        runCatching { record?.stop(); record?.release() }
        record = null
        runCatching { worker?.join(500) }
        worker = null
        onLevel(0f)   // reset the meter so it doesn't freeze at the last value
    }
}
