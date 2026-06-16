package com.example.plohoystream.ui.viewfinder

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * A frozen, blurred snapshot of the last preview frame, shown over the preview during a lens/camera
 * switch to hide the black reopen gap. Appears instantly when [bitmap] is set + [visible], and
 * fades out when [visible] goes false (the new feed is up by then). Blur needs API 31+ (minSdk 35).
 */
@Composable
fun FreezeBlurOverlay(bitmap: Bitmap?, visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible && bitmap != null,
        enter = EnterTransition.None,
        exit = fadeOut(tween(280)),
        modifier = modifier,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(28.dp),
            )
        }
    }
}
