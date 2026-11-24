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
import android.media.projection.MediaProjection
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import okhttp3.Request
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Captures the whole screen and streams it to the iPad with WebRTC.
 * Video is encrypted end-to-end by WebRTC (DTLS-SRTP). Nothing is recorded or stored.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_RESULT = "result"
        const val EXTRA_DATA = "data"
        const val EXTRA_CODE = "pairCode"
        private const val CHANNEL = "casting"
        private const val NOTIF_ID = 1

        // All state changes happen on this one thread, so nothing races. Lives for the whole app.
        private val worker = Executors.newSingleThreadScheduledExecutor()
    }

    private var projectionData: Intent? = null
    private var pairCode = ""
    private var token: String? = null
    private var signaling: Signaling? = null
    private var reconnects = 0
    private var iceRestarts = 0
    private var stopped = false

    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var pc: PeerConnection? = null
    private var iceServers: List<PeerConnection.IceServer> = defaultIce()
    private val pendingIce = mutableListOf<IceCandidate>()
    private var wifiLock: WifiManager.WifiLock? = null
    private var width = 0
    private var height = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> worker.execute { finish("Stopped", sendBye = true) }
            ACTION_START -> {
                // Must be foreground BEFORE using the screen-capture permission.
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                val result = intent.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                val code = intent.getStringExtra(EXTRA_CODE) ?: ""
                worker.execute { begin(result, data, code) }
            }
        }
        return START_NOT_STICKY
    }

    // ---------------- Session ----------------

    private fun begin(result: Int, data: Intent?, code: String) {
        if (projectionData != null) return // already running
        if (result != Activity.RESULT_OK || data == null) {
            finish("Screen permission denied"); return
        }
        projectionData = data
        pairCode = code
        stopped = false
        status { it.copy(running = true, message = "Connecting to iPad…") }
        acquireWifiLock()
        loadIceServers()
        connectSignaling()
    }

    private fun connectSignaling() {
        signaling?.close()
        signaling = Signaling(
            onMessage = { m -> worker.execute { onSignal(m) } },
            onClosed = { byUs -> worker.execute { if (!byUs) onSignalingLost() } }
        ).also { it.connect(pairCode, token) }
    }

    private fun onSignalingLost() {
        if (stopped) return
        if (reconnects >= Config.MAX_RECONNECTS) {
            finish("Lost connection to server. Tap Start to try again."); return
        }
        reconnects++
        val delay = minOf(1000L shl (reconnects - 1), 15000L)
        status { it.copy(message = "Connection lost. Retrying ($reconnects/${Config.MAX_RECONNECTS})…") }
        worker.schedule({ if (!stopped) connectSignaling() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun onSignal(m: JSONObject) {
        if (stopped) return
        when (m.optString("type")) {
            "joined" -> {
                reconnects = 0
                token = m.optString("token")
                status { it.copy(message = "Paired with iPad") }
                if (m.optBoolean("receiverOnline", true)) startStreaming(fresh = pc == null)
            }
            "peer" -> if (m.optString("role") == "receiver") {
                if (m.optString("state") == "online") {
                    status { it.copy(message = "iPad is back") }
                    startStreaming(fresh = true)
                } else {
                    status { it.copy(connected = false, message = "iPad went offline. Waiting…") }
                }
            }
            "answer" -> pc?.setRemoteDescription(
                Sdp { drainIce() },
                SessionDescription(SessionDescription.Type.ANSWER, m.getString("sdp"))
            )
            "ice" -> {
                val c = m.optJSONObject("candidate") ?: return
                val cand = c.optString("candidate")
                if (cand.isEmpty()) return
                val ice = IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), cand)
                val p = pc
                if (p?.remoteDescription != null) p.addIceCandidate(ice) else pendingIce += ice
            }
            "restart" -> iceRestart()
            "stats" -> status {
                it.copy(
                    connected = true,
                    width = m.optInt("width"), height = m.optInt("height"),
                    fps = m.optInt("fps"), kbps = m.optInt("kbps"),
                    rttMs = if (m.isNull("rtt")) -1 else m.optInt("rtt", -1),
                    route = m.optString("route")
                )
            }
            "bye" -> finish("iPad disconnected")
            "error" -> finish(errorText(m.optString("reason")))
        }
    }

    private fun errorText(reason: String) = when (reason) {
        "no-session" -> "Code not found. Check the code on the iPad."
        "code-expired" -> "Code expired. Get a new code on the iPad."
        "already-paired" -> "This code is already used by another phone."
        "receiver-offline" -> "iPad page is not open."
        "session-expired" -> "Session expired. Get a new code on the iPad."
        else -> "Error: $reason"
    }

    // ---------------- WebRTC ----------------

    private fun ensureCapture() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
        )
        val e = EglBase.create().also { egl = it }
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(e.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(e.eglBaseContext))
            .createPeerConnectionFactory()

        computeSize()
        textureHelper = SurfaceTextureHelper.create("capture", e.eglBaseContext)
        source = factory!!.createVideoSource(true)
        capturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() {
                worker.execute { finish("Screen sharing ended", sendBye = true) }
            }
        }).also {
            it.initialize(textureHelper, applicationContext, source!!.capturerObserver)
            it.startCapture(width, height, Config.MAX_FPS)
        }
        track = factory!!.createVideoTrack("screen", source)
    }

    private fun startStreaming(fresh: Boolean) {
        try {
            ensureCapture()
        } catch (e: Exception) {
            finish("Could not start capture: ${e.message}", sendBye = true); return
        }
        if (fresh || pc == null) {
            pc?.dispose()
            pc = null
            pendingIce.clear()
            iceRestarts = 0
            createPeer()
        }
        makeOffer(restart = false)
    }

    private fun createPeer() {
        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val p = factory!!.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                signaling?.send(JSONObject().put("type", "ice").put("candidate",
                    JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)))
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                worker.execute {
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            iceRestarts = 0
                            status { it.copy(connected = true, message = "Casting to iPad") }
                            tuneSender()
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED ->
                            status { it.copy(message = "Weak connection… recovering") }
                        PeerConnection.PeerConnectionState.FAILED -> iceRestart()
                        else -> {}
                    }
                }
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
        }) ?: throw IllegalStateException("PeerConnection failed")
        pc = p

        val tr = p.addTransceiver(
            track,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("screen"))
        )
        preferH264(tr)
    }

    /** H.264 is hardware-encoded on the phone and hardware-decoded on the iPad: smoother and less battery. */
    private fun preferH264(tr: RtpTransceiver) {
        try {
            val caps = factory!!.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
            val (h264, rest) = caps.codecs.partition { it.name.equals("H264", true) }
            if (h264.isNotEmpty()) tr.setCodecPreferences(h264 + rest)
        } catch (_: Throwable) { /* fall back to default order */ }
    }

    private fun tuneSender() {
        val sender = pc?.senders?.firstOrNull() ?: return
        try {
            val p = sender.parameters
            // Screens have text: keep it sharp, drop frames instead of blurring when network is weak.
            p.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            p.encodings.forEach {
                it.maxBitrateBps = Config.MAX_BITRATE_BPS
                it.minBitrateBps = Config.MIN_BITRATE_BPS
                it.maxFramerate = Config.MAX_FPS
            }
            sender.parameters = p
        } catch (_: Throwable) {}
    }

    private fun makeOffer(restart: Boolean) {
        val p = pc ?: return
        val mc = MediaConstraints()
        if (restart) mc.mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        p.createOffer(Sdp(onCreate = { sdp ->
            p.setLocalDescription(Sdp {
                worker.execute {
                    signaling?.send(JSONObject().put("type", "offer").put("sdp", sdp.description).put("restart", restart))
                }
            }, sdp)
        }), mc)
    }

    private fun iceRestart() {
        if (stopped || pc == null) return
        if (iceRestarts >= Config.MAX_ICE_RESTARTS) {
            status { it.copy(connected = false, message = "Network too weak. Rebuilding connection…") }
            startStreaming(fresh = true)
            return
        }
        iceRestarts++
        status { it.copy(message = "Reconnecting video ($iceRestarts/${Config.MAX_ICE_RESTARTS})…") }
        makeOffer(restart = true)
    }

    private fun drainIce() = worker.execute {
        val p = pc ?: return@execute
        pendingIce.forEach { p.addIceCandidate(it) }
        pendingIce.clear()
    }

    private fun loadIceServers() {
        try {
            val body = Signaling.http.newCall(Request.Builder().url("${Config.SERVER}/api/ice").build())
                .execute().use { it.body?.string() } ?: return
            val arr = JSONObject(body).getJSONArray("iceServers")
            val list = mutableListOf<PeerConnection.IceServer>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val urls = o.get("urls").let { u ->
                    if (u is org.json.JSONArray) (0 until u.length()).map { u.getString(it) } else listOf(u.toString())
                }
                val b = PeerConnection.IceServer.builder(urls)
                if (o.has("username")) b.setUsername(o.getString("username"))
                if (o.has("credential")) b.setPassword(o.getString("credential"))
                list += b.createIceServer()
            }
            if (list.isNotEmpty()) iceServers = list
        } catch (_: Exception) { /* keep defaults */ }
    }

    private fun defaultIce() = listOf(
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
    )

    // ---------------- Screen size & rotation ----------------

    private fun computeSize() {
        val wm = getSystemService(WindowManager::class.java)
        val w: Int
        val h: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            w = b.width(); h = b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            w = m.widthPixels; h = m.heightPixels
        }
        val short = minOf(w, h)
        val scale = if (short > Config.MAX_SHORT_SIDE) Config.MAX_SHORT_SIDE.toFloat() / short else 1f
        width = (w * scale).toInt() and 1.inv()
        height = (h * scale).toInt() and 1.inv()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        worker.execute {
            val c = capturer ?: return@execute
            val oldW = width
            computeSize()
            if (oldW != width) c.changeCaptureFormat(width, height, Config.MAX_FPS) // portrait <-> landscape
        }
    }

    // ---------------- Cleanup ----------------

    private fun finish(message: String, sendBye: Boolean = false) {
        if (stopped && projectionData == null) {
            status { CaptureState.Status(message = message) }
            stopSelfSafely(); return
        }
        stopped = true
        if (sendBye) signaling?.send(JSONObject().put("type", "bye"))
        signaling?.close(); signaling = null
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose(); capturer = null
        pc?.dispose(); pc = null
        track?.dispose(); track = null
        source?.dispose(); source = null
        textureHelper?.dispose(); textureHelper = null
        factory?.dispose(); factory = null
        egl?.release(); egl = null
        projectionData = null
        token = null
        pendingIce.clear()
        reconnects = 0; iceRestarts = 0
        wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null
        status { CaptureState.Status(message = message) }
        stopSelfSafely()
    }

    private fun stopSelfSafely() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        worker.execute { if (!stopped) finish("Stopped", sendBye = true) }
        super.onDestroy()
    }

    private fun acquireWifiLock() {
        val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ScreenMirror").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun status(f: (CaptureState.Status) -> CaptureState.Status) = CaptureState.edit(f)

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen casting", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle("Screen Mirror is sharing your screen")
            .setContentText("Tap Stop to end")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    /** Small helper so WebRTC's 4-method callback is less noisy. */
    private inner class Sdp(
        private val onCreate: ((SessionDescription) -> Unit)? = null,
        private val onSet: (() -> Unit)? = null
    ) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) { onCreate?.invoke(sdp) }
        override fun onSetSuccess() { onSet?.invoke() }
        override fun onCreateFailure(e: String?) { worker.execute { status { it.copy(message = "Video setup error: $e") } } }
        override fun onSetFailure(e: String?) { worker.execute { status { it.copy(message = "Video setup error: $e") } } }
    }
}

fun Context.startCasting(result: Int, data: Intent, pairCode: String) {
    startForegroundService(
        Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_RESULT, result)
            .putExtra(CaptureService.EXTRA_DATA, data)
            .putExtra(CaptureService.EXTRA_CODE, pairCode)
    )
}

fun Context.stopCasting() {
    startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
}
