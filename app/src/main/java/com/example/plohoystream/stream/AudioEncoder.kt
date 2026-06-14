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
class AudioEncoder(
    private val sampleRate: Int = 44100,
    private val channels: Int = 2,
    bitRate: Int = 128_000,
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
        thread(name = "AudioEnc-feed") { feedLoop() }
        thread(name = "AudioEnc-drain") { drainLoop() }
    }

    private fun feedLoop() {
        val pcm = ByteArray(minBuf)
        while (running) {
            val read = record.read(pcm, 0, pcm.size)
            if (read <= 0) continue
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val ib = codec.getInputBuffer(idx) ?: continue
                ib.clear(); ib.put(pcm, 0, read)
                val ptsUs = System.nanoTime() / 1000
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
        runCatching { codec.stop(); codec.release() }
    }
}
