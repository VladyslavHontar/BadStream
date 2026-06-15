package com.example.plohoystream.stream

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * JNI-backed [RtmpStreamer] for the SRT scheme. One instance == one native SrtSession handle.
 * Mirrors [NativeRtmpStreamer]; the body muxes MPEG-TS and sends over a libsrt LIVE caller.
 */
class NativeSrtStreamer : RtmpStreamer {
    private var handle: Long = 0L
    private val lock = ReentrantLock()
    @Volatile private var requestedCodec: VideoCodecType = VideoCodecType.AVC

    override fun start(
        endpoint: Endpoint, codec: VideoCodecType, width: Int, height: Int, fps: Int,
        sampleRate: Int, abr: AbrParams,
    ) = lock.withLock {
        requestedCodec = codec
        val ep = endpoint as Endpoint.Srt
        if (handle == 0L) {
            handle = nativeCreate(
                ep.host, ep.port, ep.streamid, ep.latencyMs, codec.nativeFlag,
                sampleRate, 2, abr.minBps, abr.targetBps, abr.maxBps,
            )
        }
        nativeStart(handle)
    }

    // SRT does not negotiate a codec; the requested codec is used as-is (so HEVC/HDR is honoured).
    override fun negotiatedCodec(): VideoCodecType = requestedCodec

    override fun state(): Int = lock.withLock { if (handle != 0L) nativeState(handle) else 0 }
    override fun bytesSent(): Long = lock.withLock { if (handle != 0L) nativeBytesSent(handle) else 0L }
    override fun queueDepth(): Int = lock.withLock { if (handle != 0L) nativeQueueDepth(handle) else 0 }
    override fun targetBitrate(): Int = lock.withLock { if (handle != 0L) nativeTargetBitrate(handle) else 0 }

    override fun sendVideoConfig(csd: ByteArray) = lock.withLock { if (handle != 0L) nativeSendVideoConfig(handle, csd) }
    override fun sendVideo(annexb: ByteArray, keyframe: Boolean, ptsMs: Long, dtsMs: Long) = lock.withLock {
        if (handle != 0L) nativeSendVideo(handle, annexb, keyframe, ptsMs, dtsMs)
    }
    override fun sendAudioConfig(sampleRate: Int, channels: Int) = lock.withLock {
        if (handle != 0L) nativeSendAudioConfig(handle, sampleRate, channels)
    }
    override fun sendAudio(aac: ByteArray, ptsMs: Long) = lock.withLock { if (handle != 0L) nativeSendAudio(handle, aac, ptsMs) }

    override fun stop() = lock.withLock {
        if (handle != 0L) {
            nativeStop(handle)
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(
        host: String, port: Int, streamid: String, latencyMs: Int, codec: Int,
        sampleRate: Int, channels: Int, abrMinBps: Int, abrTargetBps: Int, abrMaxBps: Int,
    ): Long
    private external fun nativeStart(handle: Long)
    private external fun nativeState(handle: Long): Int
    private external fun nativeBytesSent(handle: Long): Long
    private external fun nativeQueueDepth(handle: Long): Int
    private external fun nativeTargetBitrate(handle: Long): Int
    private external fun nativeSendVideoConfig(handle: Long, csd: ByteArray)
    private external fun nativeSendVideo(handle: Long, annexb: ByteArray, keyframe: Boolean, ptsMs: Long, dtsMs: Long)
    private external fun nativeSendAudioConfig(handle: Long, sampleRate: Int, channels: Int)
    private external fun nativeSendAudio(handle: Long, aac: ByteArray, ptsMs: Long)
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)

    companion object {
        init { System.loadLibrary("plohoystream") }
    }
}
