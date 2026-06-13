package com.example.plohoystream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.plohoystream.stream.FakeStreamEngine
import com.example.plohoystream.stream.StreamEngine
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.StreamScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // M1-B.1: FakeStreamEngine. M1-B.3 replaces this with CameraStreamEngine.
        val engine: StreamEngine = FakeStreamEngine()
        setContent {
            MaterialTheme {
                val vm: StreamViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        StreamViewModel(engine) as T
                })
                StreamScreen(vm)
            }
        }
    }
}
