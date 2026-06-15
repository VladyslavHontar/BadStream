package com.example.plohoystream.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraTargetsTest {
    // Generic over the surface type so it can be tested without android.view.Surface.
    @Test fun previewAndEncoder_bothPresent_previewFirst() {
        assertEquals(listOf("p", "e"), CameraTargets.select("p", "e"))
    }

    @Test fun backgrounded_encoderOnly_keepsStreaming() {
        assertEquals(listOf("e"), CameraTargets.select(null, "e"))
    }

    @Test fun idlePreview_only() {
        assertEquals(listOf("p"), CameraTargets.select("p", null))
    }

    @Test fun nothing_empty() {
        assertEquals(emptyList<String>(), CameraTargets.select(null, null))
    }
}
