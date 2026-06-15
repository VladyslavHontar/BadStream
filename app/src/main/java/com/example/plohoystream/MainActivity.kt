package com.example.plohoystream

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.plohoystream.ui.theme.PlohoyTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.plohoystream.camera.CameraEnumerator
import com.example.plohoystream.data.DataStoreSettingsStore
import com.example.plohoystream.media.CodecCapabilities
import android.os.SystemClock
import com.example.plohoystream.stream.AudioEncoder
import com.example.plohoystream.stream.CameraStreamEngine
import com.example.plohoystream.stream.CodecSelector
import com.example.plohoystream.stream.NativeRecorder
import com.example.plohoystream.stream.NativeRtmpStreamer
import com.example.plohoystream.stream.StreamEngine
import com.example.plohoystream.stream.VideoEncoder
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.service.StreamForegroundService
import com.example.plohoystream.ui.StreamScreen

class MainActivity : ComponentActivity() {
    private lateinit var engine: StreamEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Request the panel's highest refresh rate so UI motion is smooth on high-Hz displays.
        // MediaTek adaptive refresh otherwise parks the display at a low rate (e.g. 30/60 Hz)
        // when an app doesn't signal it wants high-Hz, which reads as choppy interface motion.
        display?.supportedModes?.maxByOrNull { it.refreshRate }?.let { best ->
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        }
        val appCtx = applicationContext
        val cameras = CameraEnumerator.enumerate(this)
        val anyCameraHdr = cameras.any { it.supportsHdr }
        val hevcCaps = CodecCapabilities.hevc()
        val hdrAvailable = CodecSelector.hdrAvailable(hevcCaps.main10, anyCameraHdr)
        engine = run {
            var video: VideoEncoder? = null
            var audio: AudioEncoder? = null
            var recorder: NativeRecorder? = null
            lateinit var eng: CameraStreamEngine
            eng = CameraStreamEngine(
                streamerFactory = { NativeRtmpStreamer() },
                hevcEncoder = hevcCaps.encoder,
                hevcMain10 = hevcCaps.main10,
                cameraHdr = anyCameraHdr,
                startMedia = { streamer, fmt, quality, record ->
                    StreamForegroundService.start(appCtx)
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
        val store = DataStoreSettingsStore(applicationContext)
        setContent {
            PlohoyTheme {
                val vm: StreamViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        StreamViewModel(engine, hdrAvailable, store) as T
                })
                StreamScreen(vm)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        (engine as? CameraStreamEngine)?.dispose() ?: engine.stop()
    }
}
