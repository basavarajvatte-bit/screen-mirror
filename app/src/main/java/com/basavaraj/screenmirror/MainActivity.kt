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
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private companion object {
        const val REQ_CAPTURE = 10
        const val REQ_NOTIF = 11
        var localMode = false // remembered while the app process lives
    }

    private lateinit var codeInput: EditText
    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var qualityText: TextView
    private lateinit var resText: TextView
    private lateinit var fpsText: TextView
    private lateinit var netText: TextView
    private lateinit var button: Button
    private var pendingCode = ""

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
            if (localMode) stopLocalCast() else stopCasting()
            return
        }
        val code = codeInput.text.toString().trim()
        // Empty code = LOCAL mode (hotspot / same Wi-Fi, phone serves the iPad itself).
        if (code.isNotEmpty() && !Regex("\\d{6}").matches(code)) {
            CaptureState.update(CaptureState.Status(message = "Code must be 6 digits, or leave it empty for hotspot / Wi-Fi mode"))
            return
        }
        localMode = code.isEmpty()
        pendingCode = code
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(codeInput.windowToken, 0)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF); return
        }
        askCapture()
    }

    private fun askCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_NOTIF) askCapture() // notification is helpful, not required
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            if (localMode) startLocalCast(resultCode, data) else startCasting(resultCode, data, pendingCode)
        } else {
            CaptureState.update(CaptureState.Status(message = "Permission denied. Tap START and choose 'Start now'."))
        }
    }

    private fun render(s: CaptureState.Status) {
        statusText.text = s.message
        statusText.setTextColor(Color.parseColor(if (s.connected) "#34C759" else "#B0B0B8"))
        codeInput.isEnabled = !s.running
        deviceText.text = if (s.connected) "iPad (Safari)" else if (s.running) "Waiting…" else "—"
        resText.text = if (s.connected && s.width > 0) "${s.width} × ${s.height}" else "—"
        fpsText.text = if (s.connected) "${s.fps} FPS" else "—"
        netText.text = if (s.connected) when (s.route) {
            "same Wi-Fi" -> "Same Wi-Fi (direct)"
            "internet direct" -> "Internet (direct)"
            "via relay" -> "Internet (relay)"
            "local" -> "Hotspot / Wi-Fi (direct)"
            else -> "Connecting…"
        } else "—"
        qualityText.text = if (!s.connected) "—" else {
            val mbps = String.format("%.1f Mbps", s.kbps / 1000f)
            val q = when {
                s.rttMs in 0..250 && s.fps >= 12 -> "Good"
                s.rttMs > 600 || (s.fps in 1..5) -> "Poor"
                else -> "OK"
            }
            "$q · $mbps" + (if (s.rttMs >= 0) " · ${s.rttMs} ms" else "")
        }
        button.text = if (s.running) "STOP CASTING" else "START CASTING"
        (button.background as GradientDrawable).setColor(Color.parseColor(if (s.running) "#FF3B30" else "#0A84FF"))
    }

    // ---------- Simple UI built in code (no extra libraries) ----------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun label(text: String, size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size
        setTextColor(Color.parseColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: String, r: Int) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(Color.parseColor(color))
    }

    private fun row(title: String, value: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        addView(label(title, 15f, "#8E8E93"), LinearLayout.LayoutParams(0, -2, 1f))
        value.gravity = Gravity.END
        addView(value)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        root.addView(label("Screen Mirror", 28f, "#FFFFFF", bold = true))
        statusText = label("Ready", 16f, "#B0B0B8").apply { setTextIsSelectable(true) }
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        root.addView(label("Hotspot / same Wi-Fi: leave code EMPTY and tap Start.\nDifferent networks: type the code from the iPad website.", 14f, "#8E8E93"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        codeInput = EditText(this).apply {
            hint = "Code (optional)"
            setHintTextColor(Color.parseColor("#48484A"))
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.3f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            background = rounded("#1C1C22", 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        root.addView(codeInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        deviceText = label("—", 15f, "#FFFFFF", true)
        qualityText = label("—", 15f, "#FFFFFF", true)
        resText = label("—", 15f, "#FFFFFF", true)
        fpsText = label("—", 15f, "#FFFFFF", true)
        netText = label("—", 15f, "#FFFFFF", true)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
            background = rounded("#1C1C22", 16)
            addView(row("Connected device", deviceText))
            addView(row("Connection quality", qualityText))
            addView(row("Streaming resolution", resText))
            addView(row("Frame rate", fpsText))
            addView(row("Network", netText))
        }
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        button = Button(this).apply {
            text = "START CASTING"; textSize = 18f
            setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; isAllCaps = false
            background = rounded("#0A84FF", 28)
            setOnClickListener { onButton() }
        }
        root.addView(button, LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(28) })

        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0E0E12"))
            isFillViewport = true
            addView(root)
        }
    }
}
