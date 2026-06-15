package com.example.plohoystream.camera

/**
 * Camera output targets for the capture session, in order: preview (when the UI is attached)
 * first, then the encoder input surface (when streaming).
 *
 * The key background-streaming invariant: when the preview is gone (app backgrounded) but the
 * encoder is present, the result is `[encoder]` — capture keeps feeding the encoder, so the
 * stream never stops. Generic over the surface type so the selection logic is unit-testable
 * without `android.view.Surface`.
 */
object CameraTargets {
    fun <T> select(preview: T?, encoder: T?): List<T> = listOfNotNull(preview, encoder)
}
