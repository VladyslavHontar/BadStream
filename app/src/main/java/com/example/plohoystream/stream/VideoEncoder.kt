package com.example.plohoystream.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/**
 * Surface-input encoder for H.264 or HEVC (with optional HDR) as configured by [format].
 * Camera2 renders into [inputSurface]; encoded Annex-B output is delivered via callbacks:
 * [onConfig] once (SPS+PPS for AVC, VPS+SPS+PPS for HEVC, as a csd blob), then [onFrame]
 * per frame. Timestamps are milliseconds derived from the surface frame PTS.
 */
class VideoEncoder(
    width: Int,
    height: Int,
    fps: Int,
    bitRate: Int,
    private val format: VideoFormat,
    private val onConfig: (csd: ByteArray) -> Unit,
    private val onFrame: (annexb: ByteArray, keyframe: Boolean, ptsMs: Long) -> Unit,
) {
    private val mime = if (format.codec == VideoCodecType.HEVC) MediaFormat.MIMETYPE_VIDEO_HEVC
                       else MediaFormat.MIMETYPE_VIDEO_AVC
    private val codec = MediaCodec.createEncoderByType(mime)
    val inputSurface: Surface
    private val thread = HandlerThread("VideoEnc").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var released = false

    init {
        val mediaFormat = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            if (format.codec == VideoCodecType.HEVC) {
                setInteger(MediaFormat.KEY_PROFILE,
                    if (format.main10) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            }
            if (format.dynamicRange == DynamicRange.HLG10) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
            }
        }
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) {} // surface input
            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (released) return
                val buf = c.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset); buf.get(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        onConfig(bytes)
                    } else {
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        onFrame(bytes, key, info.presentationTimeUs / 1000)
                    }
                }
                c.releaseOutputBuffer(index, false)
            }
            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {}
            override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) {}
        }, handler)
        codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
    }

    fun start() = codec.start()

    fun stop() {
        released = true
        runCatching { codec.setCallback(null) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        inputSurface.release()
        thread.quitSafely()
    }
}
