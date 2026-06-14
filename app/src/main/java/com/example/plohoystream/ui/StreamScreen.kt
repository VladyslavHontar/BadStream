package com.example.plohoystream.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.camera.Camera2Controller
import com.example.plohoystream.camera.CameraCapabilities
import com.example.plohoystream.camera.CameraEnumerator
import com.example.plohoystream.camera.CameraControls
import com.example.plohoystream.camera.Facing
import com.example.plohoystream.stream.StreamState
import com.example.plohoystream.stream.StreamViewModel

@Composable
fun StreamScreen(viewModel: StreamViewModel) {
    val context = LocalContext.current
    val perms = remember {
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
    fun hasAll() = perms.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var granted by remember { mutableStateOf(hasAll()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = hasAll() }

    if (granted) {
        Viewfinder(viewModel)
    } else {
        PermissionGate(onRequest = { launcher.launch(perms) })
    }
}

@Composable
private fun Viewfinder(viewModel: StreamViewModel) {
    val context = LocalContext.current
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val encoderSurface by viewModel.encoderSurface.collectAsStateWithLifecycle()
    val activeHdr by viewModel.activeHdr.collectAsStateWithLifecycle()

    val cameras = remember { CameraEnumerator.enumerate(context) }
    val controller = remember { Camera2Controller(context) }

    var facing by remember { mutableStateOf(Facing.BACK) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var showSettings by remember { mutableStateOf(true) }

    val config = remember(cameras, facing) { CameraCapabilities.select(cameras, facing) }

    DisposableEffect(Unit) { onDispose { controller.stop() } }

    // (Re)start the session when the camera config, preview surface, encoder surface, or
    // negotiated HDR flag changes. hdr is driven by the engine's resolved format so a server
    // HEVC->AVC downgrade clears it even if the user's toggle is still on.
    LaunchedEffect(config, surface, encoderSurface, activeHdr) {
        val c = config
        val preview = surface
        if (c != null && preview != null) {
            controller.start(c, listOfNotNull(preview, encoderSurface), hdr = activeHdr)
            controller.setZoom(zoom)
        }
    }

    // Camera output size (sensor/landscape). Portrait display aspect is height/width.
    val bufferW = config?.previewSize?.width ?: 1920
    val bufferH = config?.previewSize?.height ?: 1080
    val previewAspect = bufferH.toFloat() / bufferW

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            aspectRatio = previewAspect,
            bufferWidth = bufferW,
            bufferHeight = bufferH,
            onSurface = { surface = it },
        )

        // Top status bar.
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = statusText(ui.stream),
                color = if (ui.stream is StreamState.Live) Color(0xFFFF4040) else Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .background(Color(0x66000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            OutlinedButton(onClick = {
                facing = CameraControls.opposite(facing)
                zoom = 1f
            }) { Text(if (facing == Facing.BACK) "Front" else "Back") }
        }

        // Bottom control stack.
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val lenses = config?.lenses.orEmpty()
            if (lenses.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    lenses.forEach { lens ->
                        FilterChip(
                            selected = zoom == lens.zoomRatio,
                            onClick = {
                                zoom = lens.zoomRatio
                                controller.setLens(lens)
                            },
                            label = { Text(lens.label) },
                        )
                    }
                }
            }

            config?.let { cfg ->
                if (cfg.maxZoom > cfg.minZoom) {
                    Slider(
                        value = zoom.coerceIn(cfg.minZoom, cfg.maxZoom),
                        onValueChange = {
                            zoom = it
                            controller.setZoom(it)
                        },
                        valueRange = cfg.minZoom..cfg.maxZoom,
                    )
                }
            }

            if (ui.hdrAvailable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("HDR", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = ui.hdrEnabled,
                        onCheckedChange = viewModel::setHdr,
                        enabled = !ui.isActive,
                    )
                }
            }

            if (showSettings) {
                OutlinedTextField(
                    value = ui.url, onValueChange = viewModel::setUrl,
                    label = { Text("RTMP URL") }, singleLine = true,
                    enabled = !ui.isActive, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ui.key, onValueChange = viewModel::setKey,
                    label = { Text("Stream key") }, singleLine = true,
                    enabled = !ui.isActive, modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (ui.isActive) {
                    Button(onClick = viewModel::stop, modifier = Modifier.weight(1f)) { Text("Stop") }
                } else {
                    Button(
                        onClick = viewModel::goLive,
                        enabled = ui.canGoLive,
                        modifier = Modifier.weight(1f),
                    ) { Text("Go Live") }
                }
                OutlinedButton(onClick = { showSettings = !showSettings }) {
                    Text(if (showSettings) "Hide" else "Settings")
                }
            }
        }
    }
}

private fun statusText(s: StreamState): String = when (s) {
    StreamState.Idle -> "Idle"
    StreamState.Connecting -> "Connecting…"
    StreamState.Live -> "● LIVE"
    StreamState.Stopping -> "Stopping…"
    is StreamState.Error -> "Error: ${s.reason}"
}
