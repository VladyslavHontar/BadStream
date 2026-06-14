package com.example.plohoystream.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.glassSurface

@Composable
fun SettingsPanel(viewModel: StreamViewModel) {
    Box(Modifier.fillMaxSize().glassSurface()) { Text("Settings", color = OnSurfaceWhite) }
}
