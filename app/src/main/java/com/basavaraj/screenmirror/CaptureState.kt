package com.basavaraj.screenmirror

import android.os.Handler
import android.os.Looper

/** Shared live status between the capture service and the screen UI. */
object CaptureState {
    data class Status(
        val running: Boolean = false,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Int = 0,
        val message: String = "Ready"
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

    fun listen(l: (Status) -> Unit) {
        listeners += l
        l(current)
    }

    fun unlisten(l: (Status) -> Unit) {
        listeners -= l
    }
}
