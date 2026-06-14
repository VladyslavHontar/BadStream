package com.example.plohoystream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme
import com.example.plohoystream.ui.theme.SurfaceBlack

@Composable
fun PermissionGate(onRequest: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(SurfaceBlack).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "PlohoyStream needs camera and microphone access to preview and stream.",
                color = OnSurfaceWhite,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRequest) { Text("Grant access") }
        }
    }
}

@Preview(widthDp = 720, heightDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PermissionGatePreview() {
    PlohoyTheme { PermissionGate(onRequest = {}) }
}
