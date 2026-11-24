package com.basavaraj.screenmirror

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection

/**
 * Captures the sound that apps on the phone are playing (YouTube, games, music...).
 * Android 10+ feature. Some apps block it on purpose (e.g. Netflix, WhatsApp calls).
 * Phones differ in which formats they allow, so several are tried; the result is always
 * converted to 24 kHz, mono, 16-bit PCM in 100 ms pieces. Nothing is recorded or stored.
 */
class AudioCapture(private val onPcm: (ByteArray) -> Unit) {

    companion object {
        const val OUT_RATE = 24000
        private const val OUT_CHUNK_SAMPLES = OUT_RATE / 10 // 100 ms

        fun allowed(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    /** Why sound is not working (shown in the app), or null when it works. */
    var problem: String? = null
        private set

    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(ctx: Context, projection: MediaProjection): Boolean {
        if (!allowed(ctx)) { problem = "Microphone/Record audio permission not given"; return false }
        val cfg = try {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        } catch (e: Exception) { problem = "Sound capture not supported: ${e.message}"; return false }

        // Most phones mix sound at 48 kHz stereo, so try that first.
        val tries = listOf(48000 to 2, 44100 to 2, 48000 to 1, 24000 to 1)
        var lastErr = ""
        for ((rate, ch) in tries) {
            try {
                val mask = if (ch == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
                val fmt = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(mask)
                    .build()
                val min = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                val r = AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(maxOf(min, rate * ch * 2 / 5) * 2)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build()
                if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); lastErr = "init failed"; continue }
                r.startRecording()
                if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); lastErr = "could not start"; continue }
                record = r
                running = true
                problem = null
                thread = Thread({ loop(r, rate, ch) }, "audio-capture").apply { isDaemon = true; start() }
                return true
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
            }
        }
        problem = "Sound capture failed ($lastErr)"
        return false
    }

    /** Reads sound, mixes to mono, converts to 24 kHz, sends 100 ms pieces. */
    private fun loop(r: AudioRecord, rate: Int, ch: Int) {
        val inFrames = rate / 10
        val inBuf = ShortArray(inFrames * ch)
        val step = rate.toDouble() / OUT_RATE
        while (running) {
            var got = 0
            while (running && got < inBuf.size) {
                val n = r.read(inBuf, got, inBuf.size - got)
                if (n <= 0) break
                got += n
            }
            if (!running || got <= 0) continue
            val frames = got / ch
            val outN = minOf(OUT_CHUNK_SAMPLES, (frames / step).toInt())
            val out = ByteArray(outN * 2)
            for (i in 0 until outN) {
                val src = minOf((i * step).toInt(), frames - 1)
                var v = 0
                for (c in 0 until ch) v += inBuf[src * ch + c]
                v /= ch
                out[i * 2] = (v and 0xff).toByte()
                out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
            }
            onPcm(out)
        }
    }

    fun stop() {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        thread = null
    }
}
