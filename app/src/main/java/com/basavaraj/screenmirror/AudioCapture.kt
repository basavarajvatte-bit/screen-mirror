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
 * Android 10+ feature. Some apps block it on purpose (e.g. Netflix, WhatsApp calls): their sound stays silent.
 * Output: 24 kHz, mono, 16-bit PCM, in 100 ms pieces. Nothing is recorded or stored.
 */
class AudioCapture(private val onPcm: (ByteArray) -> Unit) {

    companion object {
        const val SAMPLE_RATE = 24000
        private const val CHUNK_BYTES = SAMPLE_RATE / 10 * 2 // 100 ms of mono 16-bit

        fun allowed(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Returns false if sound capture is not possible (no permission or not supported). */
    @SuppressLint("MissingPermission")
    fun start(ctx: Context, projection: MediaProjection): Boolean {
        if (!allowed(ctx)) return false
        return try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(min, CHUNK_BYTES) * 4)
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
            record = r
            r.startRecording()
            running = true
            thread = Thread({
                val buf = ByteArray(CHUNK_BYTES)
                while (running) {
                    var got = 0
                    while (running && got < CHUNK_BYTES) {
                        val n = r.read(buf, got, CHUNK_BYTES - got)
                        if (n <= 0) break
                        got += n
                    }
                    if (got > 0 && running) onPcm(buf.copyOf(got))
                }
            }, "audio-capture").apply { isDaemon = true; start() }
            true
        } catch (_: Exception) {
            stop(); false
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
