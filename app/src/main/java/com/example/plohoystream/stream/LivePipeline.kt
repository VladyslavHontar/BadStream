package com.example.plohoystream.stream

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import com.example.plohoystream.camera.CameraController
import com.example.plohoystream.camera.CameraEnumerator
import com.example.plohoystream.data.DataStoreSettingsStore
import com.example.plohoystream.data.SettingsStore
import com.example.plohoystream.media.CodecCapabilities
import com.example.plohoystream.service.StreamForegroundService
import com.example.plohoystream.stream.obs.ObsWebSocketController

/**
 * Process-scoped owner of the live pipeline (engine + camera + settings store). Survives
 * Activity destruction; the foreground service keeps the process alive while streaming, so the
 * stream continues when the UI is backgrounded or the Activity is recreated. Initialized once
 * with the application context.
 */
object LivePipeline {
    @Volatile private var initialized = false

    lateinit var engine: CameraStreamEngine
        private set
    lateinit var camera: CameraController
        private set
    lateinit var store: SettingsStore
        private set
    var hdrAvailable: Boolean = false
        private set
    lateinit var encoderGate: com.example.plohoystream.camera.EncoderGate
        private set
    lateinit var codecOptions: List<com.example.plohoystream.camera.VideoCodecOption>
        private set
    lateinit var obs: ObsWebSocketController
        private set
    lateinit var micMonitor: MicMonitor
        private set

    fun ensureInit(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext
            camera = com.example.plohoystream.camera.CameraControllerFactory.create(appCtx)
            store = DataStoreSettingsStore(appCtx)

            val cameras = CameraEnumerator.enumerate(appCtx)
            val anyCameraHdr = cameras.any { it.supportsHdr }
            val hevcCaps = CodecCapabilities.hevc()
            hdrAvailable = CodecSelector.hdrAvailable(hevcCaps.main10, anyCameraHdr)
            encoderGate = com.example.plohoystream.camera.EncoderGateFactory.fromDevice()
            codecOptions = com.example.plohoystream.camera.CaptureMenu.codecOptions(encoderGate)

            engine = run {
                var video: VideoEncoder? = null
                var audio: AudioEncoder? = null
                var recorder: NativeRecorder? = null
                lateinit var eng: CameraStreamEngine
                eng = CameraStreamEngine(
                    streamerFactory = { scheme ->
                        if (scheme == EndpointScheme.SRT) NativeSrtStreamer() else NativeRtmpStreamer()
                    },
                    applyBitrate = { bps -> video?.setTargetBitrate(bps) },
                    hevcEncoder = hevcCaps.encoder,
                    hevcMain10 = hevcCaps.main10,
                    cameraHdr = anyCameraHdr,
                    startMedia = { streamer, fmt, quality, record ->
                        StreamForegroundService.start(appCtx)
                        micMonitor.stop()   // release the mic so the encoder's AudioRecord can open it
                        // Capture both clocks at the same instant as the shared epoch (M2-B A/V sync).
                        val nanoT0 = System.nanoTime()
                        val bootT0 = SystemClock.elapsedRealtimeNanos()
                        // When recording, tap the SAME encoded callbacks (no second encoder): fan
                        // onConfig/onFrame out to both the RTMP streamer and the native fMP4 recorder.
                        // The recorder gets the already-rebased shared-epoch PTS, so the file is A/V-aligned too.
                        val rec: NativeRecorder? = if (record) {
                            val dir = appCtx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                            val path = "${dir?.absolutePath}/Recording_${System.currentTimeMillis()}.mp4"
                            NativeRecorder().also {
                                it.start(path, fmt.codec.nativeFlag, quality.width, quality.height, quality.fps, 44100, 2)
                                it.writeAudioConfig(44100, 2)
                            }
                        } else null
                        val v = VideoEncoder(
                            width = quality.width, height = quality.height, fps = quality.fps,
                            bitRate = quality.videoBitrate,
                            format = fmt,
                            nanoT0 = nanoT0,
                            bootT0 = bootT0,
                            onConfig = { csd -> streamer.sendVideoConfig(csd); rec?.writeVideoConfig(csd) },
                            onFrame = { annexb, key, pts ->
                                streamer.sendVideo(annexb, key, pts, pts); rec?.writeVideo(annexb, key, pts)
                            },
                        )
                        val a = AudioEncoder(
                            sampleRate = 44100, channels = 2, bitRate = quality.audioBitrate,
                            nanoT0 = nanoT0,
                            onFrame = { aac, pts -> streamer.sendAudio(aac, pts); rec?.writeAudio(aac, pts) },
                            onLevel = { lvl -> eng.publishAudioLevel(lvl) },
                        )
                        streamer.sendAudioConfig(44100, 2)
                        v.start(); a.start()
                        video = v; audio = a; recorder = rec
                        eng.publishEncoderSurface(v.inputSurface)
                    },
                    stopMedia = {
                        video?.stop(); audio?.stop(); video = null; audio = null
                        recorder?.stop(); recorder = null
                        StreamForegroundService.stop(appCtx)
                    },
                )
                eng
            }
            obs = ObsWebSocketController(engine.state, store.data)
            // Preview-time mic level → same channel the streaming encoder publishes to, so the
            // audio meter is alive before go-live. Driven (start/stop) by the Viewfinder lifecycle.
            micMonitor = MicMonitor(onLevel = { lvl -> engine.publishAudioLevel(lvl) })
            initialized = true
        }
    }
}
