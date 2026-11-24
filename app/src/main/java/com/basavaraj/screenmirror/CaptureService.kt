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
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Phase 2: captures the whole screen in a foreground service.
 * Frames are counted (FPS) but not sent anywhere yet. Nothing is recorded or stored.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "casting"
        private const val NOTIF_ID = 1
        private const val MAX_SHORT_SIDE = 1080
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var width = 0
    private var height = 0
    private var dpi = 0

    @Volatile private var frames = 0
    private val fpsTick = object : Runnable {
        override fun run() {
            val f = frames
            frames = 0
            if (projection != null) {
                CaptureState.update(CaptureState.Status(true, width, height, f, "Capturing screen"))
                handler?.postDelayed(this, 1000)
            }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // System or user ended sharing (e.g. from the status bar).
            cleanup("Stopped")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cleanup("Stopped")
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)

                // Must be in foreground BEFORE asking for the projection (Android 10+ rule).
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

                if (projection != null) return START_NOT_STICKY
                if (code != Activity.RESULT_OK || data == null) {
                    cleanup("Permission denied")
                    return START_NOT_STICKY
                }
                try {
                    startCapture(code, data)
                } catch (e: Exception) {
                    cleanup("Error: ${e.message}")
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("No projection")
        projection = p

        thread = HandlerThread("capture").also { it.start() }
        handler = Handler(thread!!.looper)
        p.registerCallback(projectionCallback, handler) // required before createVirtualDisplay on Android 14

        computeSize()
        reader = newReader()
        display = p.createVirtualDisplay(
            "ScreenMirror", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )
        CaptureState.update(CaptureState.Status(true, width, height, 0, "Capturing screen"))
        handler?.postDelayed(fpsTick, 1000)
    }

    private fun newReader(): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ r ->
                r.acquireLatestImage()?.let { frames++; it.close() }
            }, handler)
        }

    /** Real screen size, scaled so the short side is at most 1080, keeping aspect ratio. */
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
        dpi = resources.displayMetrics.densityDpi
        val short = minOf(w, h)
        val scale = if (short > MAX_SHORT_SIDE) MAX_SHORT_SIDE.toFloat() / short else 1f
        // Encoders need even sizes.
        width = ((w * scale).toInt()) and 1.inv()
        height = ((h * scale).toInt()) and 1.inv()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val h = handler ?: return
        h.post {
            val vd = display ?: return@post
            val oldW = width
            val oldH = height
            computeSize()
            if (oldW == width && oldH == height) return@post
            // Portrait <-> landscape: resize the capture to match.
            val old = reader
            reader = newReader()
            vd.resize(width, height, dpi)
            vd.surface = reader!!.surface
            old?.close()
        }
    }

    private fun cleanup(message: String) {
        handler?.removeCallbacks(fpsTick)
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        projection?.let {
            try { it.unregisterCallback(projectionCallback) } catch (_: Exception) {}
            try { it.stop() } catch (_: Exception) {}
        }
        projection = null
        thread?.quitSafely()
        thread = null
        handler = null
        CaptureState.update(CaptureState.Status(false, 0, 0, 0, message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (projection != null) cleanup("Stopped")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen casting", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle("Screen Mirror is sharing your screen")
            .setContentText("Tap Stop to end")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}

fun Context.startCapture(code: Int, data: Intent) {
    startForegroundService(
        Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_CODE, code)
            .putExtra(CaptureService.EXTRA_DATA, data)
    )
}

fun Context.stopCapture() {
    startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
}
