package com.example.plohoystream.stream

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * JNI facade for the native [FragmentedMp4Writer] local recorder. One instance == one native
 * writer handle == one .mp4 file. Mirrors [NativeRtmpStreamer]'s handle/lock style: all calls
 * are serialized so the encoder callback threads (video/audio) can fan into the same recorder
 * safely. The recorder is fed the SAME already-encoded onConfig/onFrame taps as the streamer
 * (no second encoder).
 */
class NativeRecorder {
    private var handle: Long = 0L
    private val lock = ReentrantLock()

    /** Opens [path] for fMP4 recording. codec: 0=AVC, 1=HEVC (matches VideoCodecType.nativeFlag). */
    fun start(path: String, codec: Int, width: Int, height: Int, fps: Int, sampleRate: Int, channels: Int): Boolean =
        lock.withLock {
            if (handle == 0L) handle = nativeCreate()
            if (handle != 0L) nativeStart(handle, path, codec, width, height, fps, sampleRate, channels) else false
        }

    fun writeVideoConfig(csd: ByteArray) = lock.withLock { if (handle != 0L) nativeWriteVideoConfig(handle, csd) }
    fun writeVideo(annexb: ByteArray, keyframe: Boolean, ptsMs: Long) = lock.withLock {
        if (handle != 0L) nativeWriteVideo(handle, annexb, keyframe, ptsMs)
    }
    fun writeAudioConfig(sampleRate: Int, channels: Int) = lock.withLock {
        if (handle != 0L) nativeWriteAudioConfig(handle, sampleRate, channels)
    }
    fun writeAudio(aac: ByteArray, ptsMs: Long) = lock.withLock { if (handle != 0L) nativeWriteAudio(handle, aac, ptsMs) }

    fun stop() = lock.withLock {
        if (handle != 0L) {
            nativeStop(handle)
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeStart(
        handle: Long, path: String, codec: Int, width: Int, height: Int, fps: Int, sampleRate: Int, channels: Int,
    ): Boolean
    private external fun nativeWriteVideoConfig(handle: Long, csd: ByteArray)
    private external fun nativeWriteVideo(handle: Long, annexb: ByteArray, keyframe: Boolean, ptsMs: Long)
    private external fun nativeWriteAudioConfig(handle: Long, sampleRate: Int, channels: Int)
    private external fun nativeWriteAudio(handle: Long, aac: ByteArray, ptsMs: Long)
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)

    companion object {
        init { System.loadLibrary("plohoystream") }
    }
}
