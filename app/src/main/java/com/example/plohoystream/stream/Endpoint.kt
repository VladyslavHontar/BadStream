package com.example.plohoystream.stream

import java.net.URLDecoder

/** Egress transport scheme parsed from a destination URL. */
enum class EndpointScheme { RTMP, SRT }

/**
 * A parsed egress endpoint. RTMP (`rtmp://`/`rtmps://`) wraps the existing [RtmpEndpoint]
 * (behavior unchanged); SRT (`srt://host:port?streamid=…&latency=…`) carries the SRT fields.
 */
sealed class Endpoint {
    abstract val scheme: EndpointScheme

    data class Rtmp(val endpoint: RtmpEndpoint) : Endpoint() {
        override val scheme: EndpointScheme get() = EndpointScheme.RTMP
    }

    data class Srt(
        val host: String,
        val port: Int,
        val streamid: String,
        val latencyMs: Int,
    ) : Endpoint() {
        override val scheme: EndpointScheme get() = EndpointScheme.SRT
    }

    companion object {
        const val DEFAULT_SRT_LATENCY_MS = 2000

        /** Detect the transport scheme of a destination URL. */
        fun schemeOf(url: String): EndpointScheme = when {
            url.startsWith("rtmp://") || url.startsWith("rtmps://") -> EndpointScheme.RTMP
            url.startsWith("srt://") -> EndpointScheme.SRT
            else -> throw IllegalArgumentException("Unsupported URL scheme: $url")
        }

        /**
         * Parse a destination URL into an [Endpoint]. RTMP delegates to [RtmpEndpoint.parse]
         * (existing behavior, including `rtmps://`); SRT parses host/port and query params.
         */
        fun parse(url: String, streamKey: String): Endpoint = when (schemeOf(url)) {
            EndpointScheme.RTMP -> {
                // RtmpEndpoint.parse only accepts rtmp://; normalize rtmps:// to rtmp:// for parsing.
                val normalized = if (url.startsWith("rtmps://")) "rtmp://" + url.removePrefix("rtmps://") else url
                Rtmp(RtmpEndpoint.parse(normalized, streamKey))
            }
            EndpointScheme.SRT -> parseSrt(url)
        }

        private fun parseSrt(url: String): Srt {
            val rest = url.removePrefix("srt://")
            val q = rest.indexOf('?')
            val authority = if (q >= 0) rest.substring(0, q) else rest
            val query = if (q >= 0) rest.substring(q + 1) else ""

            val colon = authority.lastIndexOf(':')
            require(colon > 0) { "SRT URL must include host:port: $url" }
            val host = authority.substring(0, colon)
            val port = authority.substring(colon + 1).toIntOrNull()
                ?: throw IllegalArgumentException("Bad port in $url")
            require(host.isNotEmpty()) { "SRT URL must include a host: $url" }

            var streamid = ""
            var latency = DEFAULT_SRT_LATENCY_MS
            if (query.isNotEmpty()) {
                for (pair in query.split('&')) {
                    if (pair.isEmpty()) continue
                    val eq = pair.indexOf('=')
                    val key = if (eq >= 0) pair.substring(0, eq) else pair
                    val value = if (eq >= 0) pair.substring(eq + 1) else ""
                    when (key) {
                        "streamid" -> streamid = decode(value)
                        "latency" -> latency = value.toIntOrNull() ?: latency
                    }
                }
            }
            return Srt(host, port, streamid, latency)
        }

        private fun decode(s: String): String =
            runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}
