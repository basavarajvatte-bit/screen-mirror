package com.basavaraj.screenmirror

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** WebSocket link to the signaling server. Calls back on OkHttp's thread. */
class Signaling(
    private val onMessage: (JSONObject) -> Unit,
    private val onClosed: (byUs: Boolean) -> Unit
) {
    companion object {
        val http: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS) // keeps the link alive, detects drops
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private var ws: WebSocket? = null
    @Volatile private var closing = false

    fun connect(code: String, token: String?) {
        closing = false
        val base = Config.SERVER.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val url = "$base/ws?role=sender&code=$code" + (if (token != null) "&token=$token" else "")
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== ws) return
                try { onMessage(JSONObject(text)) } catch (_: Exception) {}
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket === ws) onClosed(closing)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket === ws) onClosed(closing)
            }
        })
    }

    fun send(obj: JSONObject) {
        ws?.send(obj.toString())
    }

    fun close() {
        closing = true
        ws?.close(1000, "bye")
        ws = null
    }
}
