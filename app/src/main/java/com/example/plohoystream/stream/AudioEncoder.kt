package com.example.plohoystream.stream

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import kotlin.concurrent.thread

/**
 * Microphone → AAC-LC encoder. Emits raw AAC frames (no ADTS) via [onFrame]; the ASC is
 * built natively from [sampleRate]/[channels] (RtmpClient.SendAudioConfig), so no codec-config
 * blob is forwarded here. Permission RECORD_AUDIO is gated by the UI before construction.
 */
/**
 * Assigns PTS to PCM buffers read from AudioRecord, on the stream timeline
 * (ns since nanoT0 in, µs out). Each buffer is labeled with the capture time of its FIRST
 * sample: `read()` returns after the samples were captured, so the wall clock at return is
 * one buffer-duration late and jitters with the scheduler, while the PCM content itself is
 * gapless. PTS therefore advances by the cumulative sample count from an anchor set at the
 * first read; the wall clock only re-anchors the timeline when they diverge beyond
 * [MAX_DRIFT_NS] (crystal drift on long streams, or samples lost to a recorder overrun).
 */
class AudioPtsClock(private val sampleRate: Int, channels: Int) {
    private val bytesPerFrame = 2 * channels
    private var anchorNs = Long.MIN_VALUE
    private var frames = 0L
    private var lastPtsUs = -1L

    /** @param nowNs `System.nanoTime() - nanoT0` when `read()` returned.
     *  @param byteCount PCM16 bytes in this buffer. */
    fun ptsUs(nowNs: Long, byteCount: Int): Long {
        val bufFrames = byteCount / bytesPerFrame
        val wallStartNs = nowNs - bufFrames * NS_PER_S / sampleRate
        if (anchorNs == Long.MIN_VALUE ||
            kotlin.math.abs(anchorNs + frames * NS_PER_S / sampleRate - wallStartNs) > MAX_DRIFT_NS
        ) {
            anchorNs = wallStartNs
            frames = 0L
        }
        var pts = (anchorNs + frames * NS_PER_S / sampleRate) / 1_000L
        frames += bufFrames
        if (pts <= lastPtsUs) pts = lastPtsUs + 1   // strictly monotonic across re-anchors
        lastPtsUs = pts
        return pts
    }

    private companion object {
        const val NS_PER_S = 1_000_000_000L
        const val MAX_DRIFT_NS = 100_000_000L   // 100 ms drift budget before re-anchoring
    }
}

class AudioEncoder(
    private val sampleRate: Int = 44100,
    private val channels: Int = 2,
    bitRate: Int = 128_000,
    /** Shared epoch captured once at stream start via [System.nanoTime]. Audio PTS is
     *  computed as `(System.nanoTime() - nanoT0) / 1_000_000` so both audio and video
     *  share a common timeline anchor (M2-B A/V sync). */
    private val nanoT0: Long = 0L,
    private val onLevel: (Float) -> Unit = {},
    private val onFrame: (aac: ByteArray, ptsMs: Long) -> Unit,
) {
    private val channelMask =
        if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
    private val minBuf = AudioRecord.getMinBufferSize(
        sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT,
    ).coerceAtLeast(4096)

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private lateinit var record: AudioRecord
    @Volatile private var running = false
    private var feedThread: Thread? = null
    private var drainThread: Thread? = null
    @Volatile private var lastLevelNs: Long = 0L
    private val ptsClock = AudioPtsClock(sampleRate, channels)

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO gated by UI
    fun start() {
        record = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate, channelMask,
            AudioFormat.ENCODING_PCM_16BIT, minBuf * 2,
        )
        codec.start()
        record.startRecording()
        running = true
        feedThread = thread(name = "AudioEnc-feed") { feedLoop() }
        drainThread = thread(name = "AudioEnc-drain") { drainLoop() }
    }

    private fun feedLoop() {
        val pcm = ByteArray(minBuf)
        while (running) {
            val read = record.read(pcm, 0, pcm.size)
            if (read <= 0) continue
            val now = System.nanoTime()
            if (now - lastLevelNs >= 100_000_000L) {   // ~10 Hz
                lastLevelNs = now
                onLevel(rms16(pcm, read))
            }
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val ib = codec.getInputBuffer(idx) ?: continue
                ib.clear(); ib.put(pcm, 0, read)
                val ptsUs = ptsClock.ptsUs(System.nanoTime() - nanoT0, read)
                codec.queueInputBuffer(idx, 0, read, ptsUs, 0)
            }
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = codec.dequeueOutputBuffer(info, 10_000)
            if (idx >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val ob = codec.getOutputBuffer(idx)
                    if (ob != null) {
                        val bytes = ByteArray(info.size)
                        ob.position(info.offset); ob.get(bytes)
                        onFrame(bytes, info.presentationTimeUs / 1000)
                    }
                }
                codec.releaseOutputBuffer(idx, false)
            }
        }
    }

    fun audioConfig(): Pair<Int, Int> = sampleRate to channels

    fun stop() {
        running = false
        runCatching { record.stop(); record.release() }
        runCatching { feedThread?.join(500); drainThread?.join(500) }
        runCatching { codec.stop(); codec.release() }
        feedThread = null; drainThread = null
    }
}
