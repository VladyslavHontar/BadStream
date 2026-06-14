package com.example.plohoystream.stream

/** Egress endpoint parsed from an `rtmp://host[:port]/app` URL plus a stream key. */
data class RtmpEndpoint(
    val host: String,
    val port: Int,
    val app: String,
    val streamKey: String,
) {
    val tcUrl: String get() = "rtmp://$host:$port/$app".let {
        // Twitch et al. accept tcUrl without the default port; keep it canonical without :1935.
        if (port == 1935) "rtmp://$host/$app" else it
    }

    companion object {
        fun parse(url: String, streamKey: String): RtmpEndpoint {
            require(url.startsWith("rtmp://")) { "Only rtmp:// URLs are supported: $url" }
            val rest = url.removePrefix("rtmp://").trim('/')
            val slash = rest.indexOf('/')
            require(slash > 0) { "URL must include an app path: $url" }
            val authority = rest.substring(0, slash)
            val app = rest.substring(slash + 1).trim('/')
            require(app.isNotEmpty()) { "URL must include an app path: $url" }
            val host: String
            val port: Int
            val colon = authority.indexOf(':')
            if (colon >= 0) {
                host = authority.substring(0, colon)
                port = authority.substring(colon + 1).toIntOrNull()
                    ?: throw IllegalArgumentException("Bad port in $url")
            } else {
                host = authority; port = 1935
            }
            require(host.isNotEmpty()) { "URL must include a host: $url" }
            return RtmpEndpoint(host, port, app, streamKey)
        }
    }
}
