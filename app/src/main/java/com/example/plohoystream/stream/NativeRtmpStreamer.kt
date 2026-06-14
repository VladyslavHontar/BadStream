package com.example.plohoystream.stream

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** JNI-backed [RtmpStreamer]. One instance == one native StreamSession handle. */
class NativeRtmpStreamer : RtmpStreamer {
    private var handle: Long = 0L
    private val lock = ReentrantLock()

    override fun start(endpoint: RtmpEndpoint, width: Int, height: Int, fps: Int, sampleRate: Int) = lock.withLock {
        if (handle == 0L) {
            handle = nativeCreate(
                endpoint.host, endpoint.app, endpoint.streamKey, endpoint.tcUrl,
                endpoint.port, width, height, fps, sampleRate,
            )
        }
        nativeStart(handle)
    }

    override fun state(): Int = lock.withLock { if (handle != 0L) nativeState(handle) else 0 }
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
        host: String, app: String, key: String, tcUrl: String,
        port: Int, width: Int, height: Int, fps: Int, sampleRate: Int,
    ): Long
    private external fun nativeStart(handle: Long)
    private external fun nativeState(handle: Long): Int
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
