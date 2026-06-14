package com.example.plohoystream.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.viewfinder.Viewfinder

@Composable
fun StreamScreen(viewModel: StreamViewModel) {
    val context = LocalContext.current
    val perms = remember { arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) }
    fun hasAll() = perms.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var granted by remember { mutableStateOf(hasAll()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = hasAll() }

    if (granted) Viewfinder(viewModel) else PermissionGate(onRequest = { launcher.launch(perms) })
}
