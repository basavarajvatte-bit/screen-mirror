package com.basavaraj.screenmirror

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * INTERNET mode: works when the iPad is anywhere in the world.
 * The phone's hardware H.264 encoder compresses the screen; frames travel over a secure
 * WebSocket (WSS) through our own Cloudflare server to the iPad, which decodes them.
 * No third-party relay, no card. Nothing is recorded or stored.
 */
class RelayCastService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_RESULT = "result"
        const val EXTRA_DATA = "data"
        const val EXTRA_CODE = "pairCode"
        private const val CHANNEL = "casting"
        private const val NOTIF_ID = 3
        private const val SHORT_SIDE = 720
        private const val FPS = 30
        private const val START_BITRATE = 1_500_000
        private const val MIN_BITRATE = 300_000
        private const val MAX_BITRATE = 4_000_000
        private const val QUEUE_HIGH = 1_000_000L   // bytes waiting to upload: network is too slow
        private const val QUEUE_LOW = 200_000L
        private const val FLAG_KEY = 1
        private const val FLAG_AUDIO = 2
    }

    private var audio: AudioCapture? = null
    private var soundOn = false
    private var soundProblem: String? = null

    private var thread: HandlerThread? = null
    private var h: Handler? = null

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var config: ByteArray? = null      // SPS/PPS, sent in front of every key frame

    private var signaling: Signaling? = null
    private var pairCode = ""
    private var token: String? = null
    private var receiverOnline = false
    private var stopped = true
    private var reconnects = 0

    private var width = 0
    private var height = 0
    private var dpi = 0
    private var bitrate = START_BITRATE
    private var needKey = false
    private var sentFrames = 0
    private var sentBytes = 0L
    private var dropped = 0
    private var ipadFps = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> post { finish("Stopped", sendBye = true) }
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                val result = intent.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                val code = intent.getStringExtra(EXTRA_CODE) ?: ""
                if (thread == null) {
                    thread = HandlerThread("relay-cast").also { it.start() }
                    h = Handler(thread!!.looper)
                }
                post { begin(result, data, code) }
            }
        }
        return START_NOT_STICKY
    }

    private fun post(f: () -> Unit) { h?.post(f) ?: f() }

    // ---------------- Session ----------------

    private fun begin(result: Int, data: Intent?, code: String) {
        if (!stopped) return
        if (result != Activity.RESULT_OK || data == null) { finish("Screen permission denied"); return }
        stopped = false
        pairCode = code
        val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(result, data)
        if (p == null) { finish("Screen capture not allowed"); return }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { post { finish("Screen sharing ended", sendBye = true) } }
        }, h)
        status { it.copy(running = true, connected = false, message = "Connecting to server…", route = "relay") }
        connect()
        h?.postDelayed(statsTick, 1000)
    }

    private fun connect() {
        signaling?.close()
        signaling = Signaling(
            onMessage = { m -> post { onSignal(m) } },
            onClosed = { byUs -> post { if (!byUs) onLost() } }
        ).also { it.connect(pairCode, token) }
    }

    private fun onLost() {
        if (stopped) return
        if (reconnects >= Config.MAX_RECONNECTS) { finish("Lost connection to server. Tap Start to try again."); return }
        reconnects++
        val delay = minOf(1000L shl (reconnects - 1), 15000L)
        status { it.copy(connected = false, message = "Internet dropped. Retrying ($reconnects/${Config.MAX_RECONNECTS})…") }
        h?.postDelayed({ if (!stopped) connect() }, delay)
    }

    private fun onSignal(m: JSONObject) {
        if (stopped) return
        when (m.optString("type")) {
            "joined" -> {
                reconnects = 0
                token = m.optString("token")
                receiverOnline = m.optBoolean("receiverOnline", true)
                try { ensureEncoder() } catch (e: Exception) { finish("Encoder error: ${e.message}", sendBye = true); return }
                startAudio()
                announce()
            }
            "peer" -> if (m.optString("role") == "receiver") {
                receiverOnline = m.optString("state") == "online"
                if (receiverOnline) announce()
                else status { it.copy(connected = false, message = "iPad went offline. Waiting…") }
            }
            "keyframe" -> requestKey()
            "stats" -> { ipadFps = m.optInt("fps", -1) }
            "bye" -> finish("iPad disconnected")
            "error" -> finish(errorText(m.optString("reason")))
        }
    }

    private fun startAudio() {
        if (audio != null) return
        val a = AudioCapture { pcm -> post { sendAudio(pcm) } }
        soundOn = a.start(this, projection ?: return)
        soundProblem = a.problem
        audio = if (soundOn) a else null
    }

    private fun sendAudio(pcm: ByteArray) {
        val s = signaling ?: return
        if (!receiverOnline || stopped || s.queued() > QUEUE_HIGH) return
        val out = ByteArray(9 + pcm.size)
        out[0] = FLAG_AUDIO.toByte()
        val t = System.nanoTime() / 1000
        for (i in 0 until 8) out[1 + i] = (t ushr (56 - 8 * i)).toByte()
        System.arraycopy(pcm, 0, out, 9, pcm.size)
        if (s.sendBytes(out)) sentBytes += out.size
    }

    /** Tell the iPad this is a relay stream, and start with a fresh key frame. */
    private fun announce() {
        signaling?.send(JSONObject().put("type", "mode").put("mode", "relay"))
        val snd = if (soundOn) "with sound" else "NO SOUND: ${soundProblem ?: "unknown reason"}"
        status { it.copy(connected = receiverOnline, message = if (receiverOnline) "Casting over internet, $snd" else "Waiting for iPad…") }
        requestKey()
    }

    private fun errorText(reason: String) = when (reason) {
        "no-session" -> "Code not found. Check the code on the iPad."
        "code-expired" -> "Code expired. Get a new code on the iPad."
        "already-paired" -> "This code is already used by another phone."
        "receiver-offline" -> "iPad page is not open."
        "session-expired" -> "Session expired. Get a new code on the iPad."
        else -> "Error: $reason"
    }

    // ---------------- Encoder ----------------

    private fun ensureEncoder() {
        if (codec != null) return
        computeSize()
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
            // When the screen is still, repeat the picture a few times a second so a new viewer gets an image.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 250_000L)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0) onEncoded(buf, info)
                } catch (_: Exception) {
                } finally {
                    try { codec.releaseOutputBuffer(index, false) } catch (_: Exception) {}
                }
            }
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                post { status { it.copy(message = "Encoder error: ${e.diagnosticInfo}") } }
            }
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        }, h)
        c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = c.createInputSurface()
        c.start()
        codec = c
        val old = inputSurface
        inputSurface = surface
        val d = display
        if (d == null) {
            display = projection!!.createVirtualDisplay("ScreenMirrorRelay", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, h)
        } else {
            d.resize(width, height, dpi)
            d.surface = surface
        }
        old?.release()
    }

    private fun onEncoded(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        buf.position(info.offset); buf.limit(info.offset + info.size)
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            config = ByteArray(info.size).also { buf.get(it) }
            return
        }
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val s = signaling ?: return
        if (!receiverOnline) return

        // Too much waiting to upload = slow network. Drop pictures, ask for a fresh key frame later.
        val queued = s.queued()
        if (!key && (needKey || queued > QUEUE_HIGH)) {
            dropped++
            if (queued > QUEUE_HIGH) needKey = true
            if (needKey && queued < QUEUE_LOW) requestKey()
            return
        }
        val cfg = if (key) config else null
        val body = info.size + (cfg?.size ?: 0)
        val out = ByteArray(9 + body)
        out[0] = (if (key) FLAG_KEY else 0).toByte()
        val pts = info.presentationTimeUs
        for (i in 0 until 8) out[1 + i] = (pts ushr (56 - 8 * i)).toByte()
        var pos = 9
        if (cfg != null) { System.arraycopy(cfg, 0, out, pos, cfg.size); pos += cfg.size }
        buf.get(out, pos, info.size)
        if (s.sendBytes(out)) {
            if (key) needKey = false
            sentFrames++
            sentBytes += out.size
        }
    }

    private fun requestKey() {
        try { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (_: Exception) {}
    }

    private fun setBitrate(b: Int) {
        bitrate = b.coerceIn(MIN_BITRATE, MAX_BITRATE)
        try { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) }) } catch (_: Exception) {}
    }

    /** Every second: show numbers, and adapt quality to the network (adaptive bitrate). */
    private val statsTick = object : Runnable {
        override fun run() {
            if (stopped) return
            val q = signaling?.queued() ?: 0
            if (dropped > 0 || q > QUEUE_HIGH / 2) setBitrate((bitrate * 0.7).toInt())
            else if (q < QUEUE_LOW && receiverOnline && bitrate < MAX_BITRATE) setBitrate((bitrate * 1.1).toInt())
            val kbps = (sentBytes * 8 / 1000).toInt()
            status {
                it.copy(
                    connected = receiverOnline && sentFrames > 0,
                    width = width, height = height,
                    fps = if (ipadFps >= 0) ipadFps else sentFrames,
                    kbps = kbps, rttMs = -1, route = "relay"
                )
            }
            sentFrames = 0; sentBytes = 0; dropped = 0
            h?.postDelayed(this, 1000)
        }
    }

    // ---------------- Size & rotation ----------------

    private fun computeSize() {
        val wm = getSystemService(WindowManager::class.java)
        val w: Int; val hh: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds; w = b.width(); hh = b.height()
        } else {
            val m = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            w = m.widthPixels; hh = m.heightPixels
        }
        dpi = resources.displayMetrics.densityDpi
        val short = minOf(w, hh)
        val scale = if (short > SHORT_SIDE) SHORT_SIDE.toFloat() / short else 1f
        // Hardware encoders like sizes divisible by 16.
        width = ((w * scale).toInt() / 16) * 16
        height = ((hh * scale).toInt() / 16) * 16
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        post {
            if (stopped || codec == null) return@post
            val oldW = width
            computeSize()
            if (oldW == width) return@post
            // Portrait <-> landscape: restart the encoder at the new size.
            val old = codec
            codec = null
            config = null
            try { ensureEncoder() } catch (_: Exception) {}
            try { old?.stop() } catch (_: Exception) {}
            try { old?.release() } catch (_: Exception) {}
        }
    }

    // ---------------- Cleanup ----------------

    private fun finish(message: String, sendBye: Boolean = false) {
        h?.removeCallbacks(statsTick)
        if (sendBye) signaling?.send(JSONObject().put("type", "bye"))
        signaling?.close(); signaling = null
        audio?.stop(); audio = null; soundOn = false; soundProblem = null
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        inputSurface?.release(); inputSurface = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        config = null; token = null; receiverOnline = false; reconnects = 0
        bitrate = START_BITRATE; ipadFps = -1
        stopped = true
        CaptureState.update(CaptureState.Status(message = message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        val t = thread
        post { if (!stopped) finish("Stopped", sendBye = true) }
        t?.quitSafely()
        thread = null
        super.onDestroy()
    }

    private fun status(f: (CaptureState.Status) -> CaptureState.Status) = CaptureState.edit(f)

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen casting", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 3, Intent(this, RelayCastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle("Screen Mirror is sharing your screen (internet)")
            .setContentText("Tap Stop to end")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}

fun Context.startRelayCast(result: Int, data: Intent, pairCode: String) {
    startForegroundService(
        Intent(this, RelayCastService::class.java)
            .setAction(RelayCastService.ACTION_START)
            .putExtra(RelayCastService.EXTRA_RESULT, result)
            .putExtra(RelayCastService.EXTRA_DATA, data)
            .putExtra(RelayCastService.EXTRA_CODE, pairCode)
    )
}

fun Context.stopRelayCast() {
    startService(Intent(this, RelayCastService::class.java).setAction(RelayCastService.ACTION_STOP))
}
