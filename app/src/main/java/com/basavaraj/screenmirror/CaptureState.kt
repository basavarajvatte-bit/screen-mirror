package com.basavaraj.screenmirror

import android.os.Handler
import android.os.Looper

/** Shared live status between the casting service and the screen UI. */
object CaptureState {
    data class Status(
        val running: Boolean = false,
        val message: String = "Ready",
        val connected: Boolean = false,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Int = 0,
        val kbps: Int = 0,
        val rttMs: Int = -1,
        val route: String = ""
    )

    @Volatile
    var current = Status()
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(Status) -> Unit>()

    fun update(s: Status) {
        current = s
        main.post { listeners.toList().forEach { it(s) } }
    }

    fun edit(f: (Status) -> Status) = update(f(current))

    fun listen(l: (Status) -> Unit) {
        listeners += l
        l(current)
    }

    fun unlisten(l: (Status) -> Unit) {
        listeners -= l
    }
}
