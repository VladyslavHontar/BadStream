package com.example.plohoystream.ui.theme

import androidx.compose.ui.graphics.Color

// Semantic color: reserved for data that is changing, never decoration.
val LiveRed = Color(0xFFFF3B30)        // LIVE indicator + Stop
val HealthGood = Color(0xFF34C759)     // green
val HealthWarn = Color(0xFFFFCC00)     // amber
val HealthBad = Color(0xFFFF3B30)      // red

// Dark surfaces (letterbox + panels).
val SurfaceBlack = Color(0xFF000000)
val SurfaceDark = Color(0xFF0E0E10)
val SurfaceElevated = Color(0xFF1C1C1E)
val OnSurfaceWhite = Color(0xFFFFFFFF)
val OnSurfaceMuted = Color(0xFFB0B0B5)

// Glass tints (translucent dark scrim + hairline).
val GlassScrim = Color(0x66101012)     // ~40% dark scrim over letterbox
val GlassOverVideo = Color(0x99000000) // stronger scrim for the LIVE pill over the feed
val GlassHairline = Color(0x33FFFFFF)  // 20% white 1dp border
