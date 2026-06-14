package com.example.plohoystream.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

// One shared spring spec for physical consistency across the whole app.

/** Signature medium-bouncy spring — the preview-shrink ⇄ settings move. */
fun <T> signatureSpring() = spring<T>(
    dampingRatio = 0.65f,
    stiffness = Spring.StiffnessMediumLow,
)

/** Snappy press-feedback spring — control scale-down on touch. */
fun <T> pressSpring() = spring<T>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessHigh,
)

/** Color transitions (health dot / audio meter / LIVE) — never snap. */
fun colorSpring() = spring<Color>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow,
)

/** Convenience aliases for the common animated types. */
val SignatureDpSpring get() = signatureSpring<Dp>()
val SignatureFloatSpring get() = signatureSpring<Float>()
val PressFloatSpring get() = pressSpring<Float>()
