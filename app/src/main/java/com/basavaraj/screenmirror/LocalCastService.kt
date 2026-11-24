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
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * LOCAL mode: the phone itself serves its screen to the iPad over the hotspot / same Wi-Fi.
 * No outside server, no relay, no mobile data. Nothing is recorded or stored.
 */
class LocalCastService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_RESULT = "result"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "casting"
        private const val NOTIF_ID = 2
        private const val SHORT_SIDE = 720     // good sharpness, light on Wi-Fi
        private const val MAX_FPS = 24
        private const val JPEG_QUALITY = 70
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var server: LocalServer? = null
    private var bitmap: Bitmap? = null
    private val jpeg = ByteArrayOutputStream(256 * 1024)
    private var width = 0
    private var height = 0
    private var dpi = 0
    private var lastFrameAt = 0L
    @Volatile private var frames = 0
    private var url = ""
    private var audio: AudioCapture? = null

    private val tick = object : Runnable {
        override fun run() {
            val f = frames; frames = 0
            val v = server?.viewers?.get() ?: 0
            CaptureState.edit {
                it.copy(
                    running = true, connected = v > 0,
                    message = if (v > 0) "Casting on hotspot / Wi-Fi\nOpen on iPad: $url"
                              else "Waiting for iPad…\nOpen on iPad: $url",
                    width = width, height = height, fps = if (v > 0) f else 0,
                    route = "local", rttMs = -1, kbps = 0
                )
            }
            if (projection != null) handler?.postDelayed(this, 1000)
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { handler?.post { cleanup("Stopped") } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> cleanup("Stopped")
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                if (projection != null) return START_NOT_STICKY
                val result = intent.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                if (result != Activity.RESULT_OK || data == null) { cleanup("Screen permission denied"); return START_NOT_STICKY }
                try { begin(result, data) } catch (e: Exception) { cleanup("Could not start: ${e.message}") }
            }
        }
        return START_NOT_STICKY
    }

    private fun begin(result: Int, data: Intent) {
        // A random key in the address: only someone who sees the phone screen can open the stream.
        val key = ByteArray(4).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val srv = LocalServer(key)
        try { srv.start() } catch (e: Exception) {
            cleanup("Port 8080 busy. Close other casting apps and try again."); return
        }
        server = srv
        val ips = LocalServer.addresses()
        url = if (ips.isEmpty()) "Turn on hotspot or Wi-Fi first" else "http://${ips.first()}:8080/?k=$key"

        thread = HandlerThread("local-capture").also { it.start() }
        handler = Handler(thread!!.looper)

        val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(result, data)
            ?: throw IllegalStateException("No projection")
        projection = p
        p.registerCallback(projectionCallback, handler)

        computeSize()
        reader = newReader()
        display = p.createVirtualDisplay("ScreenMirrorLocal", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
        audio = AudioCapture { pcm -> server?.let { if (it.audioListeners > 0) it.publishAudio(pcm) } }
            .takeIf { it.start(this, p) }
        handler?.post(tick)
    }

    private fun newReader(): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ r -> onImage(r) }, handler)
        }

    private fun onImage(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            val srv = server ?: return
            if (srv.viewers.get() == 0) return                       // nobody watching: save battery
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < 1000L / MAX_FPS) return           // cap frame rate
            lastFrameAt = now

            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * img.width
            val bw = img.width + rowPadding / pixelStride
            var bmp = bitmap
            if (bmp == null || bmp.width != bw || bmp.height != img.height) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(bw, img.height, Bitmap.Config.ARGB_8888)
                bitmap = bmp
            }
            bmp!!.copyPixelsFromBuffer(plane.buffer)
            val out = if (bw != img.width) Bitmap.createBitmap(bmp, 0, 0, img.width, img.height) else bmp
            jpeg.reset()
            out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)
            if (out !== bmp) out.recycle()
            srv.publish(jpeg.toByteArray())
            frames++
        } catch (_: Exception) {
        } finally {
            img.close()
        }
    }

    private fun computeSize() {
        val wm = getSystemService(WindowManager::class.java)
        val w: Int; val h: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds; w = b.width(); h = b.height()
        } else {
            val m = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            w = m.widthPixels; h = m.heightPixels
        }
        dpi = resources.displayMetrics.densityDpi
        val short = minOf(w, h)
        val scale = if (short > SHORT_SIDE) SHORT_SIDE.toFloat() / short else 1f
        width = (w * scale).toInt() and 1.inv()
        height = (h * scale).toInt() and 1.inv()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler?.post {
            val vd = display ?: return@post
            val oldW = width
            computeSize()
            if (oldW == width) return@post
            val old = reader                                         // portrait <-> landscape
            reader = newReader()
            vd.resize(width, height, dpi)
            vd.surface = reader!!.surface
            old?.close()
        }
    }

    private fun cleanup(message: String) {
        handler?.removeCallbacks(tick)
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        projection?.let {
            try { it.unregisterCallback(projectionCallback) } catch (_: Exception) {}
            try { it.stop() } catch (_: Exception) {}
        }
        projection = null
        audio?.stop(); audio = null
        server?.stop(); server = null
        thread?.quitSafely(); thread = null; handler = null
        bitmap?.recycle(); bitmap = null
        CaptureState.update(CaptureState.Status(message = message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (projection != null) cleanup("Stopped")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen casting", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, LocalCastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle("Screen Mirror is sharing your screen (local)")
            .setContentText("Tap Stop to end")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}

fun Context.startLocalCast(result: Int, data: Intent) {
    startForegroundService(
        Intent(this, LocalCastService::class.java)
            .setAction(LocalCastService.ACTION_START)
            .putExtra(LocalCastService.EXTRA_RESULT, result)
            .putExtra(LocalCastService.EXTRA_DATA, data)
    )
}

fun Context.stopLocalCast() {
    startService(Intent(this, LocalCastService::class.java).setAction(LocalCastService.ACTION_STOP))
}
