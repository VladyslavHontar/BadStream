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
import com.example.plohoystream.stream.LivePipeline
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.StreamScreen

class MainActivity : ComponentActivity() {

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
        LivePipeline.ensureInit(applicationContext)
        setContent {
            PlohoyTheme {
                val vm: StreamViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        StreamViewModel(LivePipeline.engine, LivePipeline.hdrAvailable, LivePipeline.store) as T
                })
                StreamScreen(vm)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT tear down the pipeline here: while streaming it must survive Activity
        // destruction (the foreground service keeps the process alive). The pipeline is a
        // process-scoped singleton; user-initiated Stop tears it down via the engine.
    }
}
