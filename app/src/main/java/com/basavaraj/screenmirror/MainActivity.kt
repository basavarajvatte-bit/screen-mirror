package com.basavaraj.screenmirror

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private companion object {
        const val REQ_CAPTURE = 10
        const val REQ_NOTIF = 11
    }

    private lateinit var statusText: TextView
    private lateinit var resText: TextView
    private lateinit var fpsText: TextView
    private lateinit var button: Button

    private val listener: (CaptureState.Status) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        CaptureState.listen(listener)
    }

    override fun onStop() {
        CaptureState.unlisten(listener)
        super.onStop()
    }

    private fun onButton() {
        if (CaptureState.current.running) {
            stopCapture()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        askCapture()
    }

    private fun askCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        // Notification permission is helpful but not required; continue either way.
        if (code == REQ_NOTIF) askCapture()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            startCapture(resultCode, data)
        } else {
            CaptureState.update(CaptureState.Status(message = "Permission denied. Tap Start and choose 'Start now'."))
        }
    }

    private fun render(s: CaptureState.Status) {
        statusText.text = s.message
        statusText.setTextColor(if (s.running) Color.parseColor("#34C759") else Color.parseColor("#B0B0B8"))
        resText.text = if (s.running) "${s.width} × ${s.height}" else "—"
        fpsText.text = if (s.running) "${s.fps} FPS" else "—"
        button.text = if (s.running) "STOP CASTING" else "START CASTING"
        (button.background as GradientDrawable).setColor(
            Color.parseColor(if (s.running) "#FF3B30" else "#0A84FF")
        )
    }

    // ---------- Simple UI built in code (no extra libraries) ----------

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun label(text: String, size: Float, color: String, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun card(title: String, value: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#1C1C22"))
        }
        addView(label(title, 13f, "#8E8E93"))
        addView(value)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    }

    private fun buildUi() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.parseColor("#0E0E12"))
        setPadding(dp(24), dp(48), dp(24), dp(24))

        addView(label("Screen Mirror", 28f, "#FFFFFF", bold = true))
        statusText = label("Ready", 16f, "#B0B0B8")
        addView(statusText)

        resText = label("—", 20f, "#FFFFFF", bold = true)
        fpsText = label("—", 20f, "#FFFFFF", bold = true)
        addView(card("Streaming resolution", resText))
        addView(card("Frame rate (screen changes per second)", fpsText))
        addView(card("Connected device", label("Not connected yet (coming in next phase)", 16f, "#FFFFFF")))

        addView(android.view.View(this@MainActivity), LinearLayout.LayoutParams(-1, 0, 1f))

        button = Button(this@MainActivity).apply {
            text = "START CASTING"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#0A84FF"))
            }
            setOnClickListener { onButton() }
        }
        addView(button, LinearLayout.LayoutParams(-1, dp(60)))
        gravity = Gravity.TOP
    }
}
