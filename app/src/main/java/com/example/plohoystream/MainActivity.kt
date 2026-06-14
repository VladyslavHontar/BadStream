package com.example.plohoystream

import android.os.Bundle
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
import com.example.plohoystream.media.CodecCapabilities
import com.example.plohoystream.stream.AudioEncoder
import com.example.plohoystream.stream.CameraStreamEngine
import com.example.plohoystream.stream.CodecSelector
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
        val appCtx = applicationContext
        val cameras = CameraEnumerator.enumerate(this)
        val anyCameraHdr = cameras.any { it.supportsHdr }
        val hevcCaps = CodecCapabilities.hevc()
        val hdrAvailable = CodecSelector.hdrAvailable(hevcCaps.main10, anyCameraHdr)
        engine = run {
            var video: VideoEncoder? = null
            var audio: AudioEncoder? = null
            lateinit var eng: CameraStreamEngine
            eng = CameraStreamEngine(
                streamerFactory = { NativeRtmpStreamer() },
                hevcEncoder = hevcCaps.encoder,
                hevcMain10 = hevcCaps.main10,
                cameraHdr = anyCameraHdr,
                startMedia = { streamer, fmt ->
                    StreamForegroundService.start(appCtx)
                    val v = VideoEncoder(
                        width = 1920, height = 1080, fps = 30, bitRate = 6_000_000,
                        format = fmt,
                        onConfig = { csd -> streamer.sendVideoConfig(csd) },
                        onFrame = { annexb, key, pts -> streamer.sendVideo(annexb, key, pts, pts) },
                    )
                    val a = AudioEncoder(
                        sampleRate = 44100, channels = 2,
                        onFrame = { aac, pts -> streamer.sendAudio(aac, pts) },
                    )
                    streamer.sendAudioConfig(44100, 2)
                    v.start(); a.start()
                    video = v; audio = a
                    eng.publishEncoderSurface(v.inputSurface)
                },
                stopMedia = {
                    video?.stop(); audio?.stop(); video = null; audio = null
                    StreamForegroundService.stop(appCtx)
                },
            )
            eng
        }
        setContent {
            PlohoyTheme {
                val vm: StreamViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        StreamViewModel(engine, hdrAvailable) as T
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
