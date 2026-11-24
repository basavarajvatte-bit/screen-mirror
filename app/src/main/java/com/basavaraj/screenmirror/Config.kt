package com.basavaraj.screenmirror

object Config {
    /** Address of the signaling server + iPad website (Cloudflare Worker). */
    const val SERVER = "https://screen-mirror.basavarajvatte.workers.dev"
    const val MAX_FPS = 30
    const val MAX_SHORT_SIDE = 1080
    const val MAX_BITRATE_BPS = 8_000_000
    const val MIN_BITRATE_BPS = 300_000
    const val MAX_RECONNECTS = 6
    const val MAX_ICE_RESTARTS = 5
}
