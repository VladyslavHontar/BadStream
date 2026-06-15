package com.example.plohoystream.camera

import com.example.plohoystream.media.CodecCapabilities

/** Builds an [EncoderGate] from a [CodecCapabilities.VideoSnapshot]. Pure — unit-testable. */
object EncoderGateFactory {
    fun from(snap: CodecCapabilities.VideoSnapshot): EncoderGate {
        val ceiling = if (snap.hasHevc) snap.hevcMaxPixelsPerSecond else snap.avcMaxPixelsPerSecond
        return EncoderGate(
            hasHevc = snap.hasHevc,
            hasHevcMain10 = snap.hasHevcMain10,
            canEncode = { c -> c.width.toLong() * c.height * c.fps <= ceiling },
        )
    }

    /** Device convenience: snapshot the real encoders and build the gate. */
    fun fromDevice(): EncoderGate = from(CodecCapabilities.videoSnapshot())
}
