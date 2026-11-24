package com.basavaraj.screenmirror

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tiny web server that runs on the phone itself.
 * The iPad (on the phone's hotspot or the same Wi-Fi) opens http://PHONE-IP:8080/?k=KEY
 * and sees the screen as a live picture stream. No internet, no mobile data, no outside server.
 */
class LocalServer(private val key: String, private val port: Int = 8080) {

    private var server: ServerSocket? = null
    @Volatile private var running = false

    // Latest JPEG frame, shared with all viewers.
    private val lock = Object()
    private var frame: ByteArray? = null
    private var frameNo = 0L

    val viewers = AtomicInteger(0)

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        running = true
        Thread({
            while (running) {
                val c = try { s.accept() } catch (_: SocketException) { break }
                Thread({ handle(c) }, "viewer").apply { isDaemon = true }.start()
            }
        }, "local-server").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        synchronized(lock) { lock.notifyAll() }
    }

    fun publish(jpeg: ByteArray) {
        synchronized(lock) {
            frame = jpeg
            frameNo++
            lock.notifyAll()
        }
    }

    private fun handle(sock: Socket) {
        sock.use { c ->
            c.tcpNoDelay = true
            c.soTimeout = 15000
            val line = c.getInputStream().bufferedReader().readLine() ?: return
            // Drain is not needed; we only read the first line ("GET /path HTTP/1.1").
            val path = line.split(" ").getOrNull(1) ?: "/"
            val out = BufferedOutputStream(c.getOutputStream(), 64 * 1024)
            val okKey = path.contains("k=$key")
            when {
                !okKey -> text(out, 403, "Wrong or missing key. Open the exact address shown in the phone app.")
                path.startsWith("/stream") -> stream(out)
                else -> html(out)
            }
        }
    }

    private fun text(out: OutputStream, code: Int, msg: String) {
        val b = msg.toByteArray()
        out.write("HTTP/1.1 $code X\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b); out.flush()
    }

    private fun html(out: OutputStream) {
        val b = Receiver.page(key).toByteArray()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b); out.flush()
    }

    /** MJPEG: one HTTP response that keeps sending new JPEG pictures. Every browser can show it. */
    private fun stream(out: OutputStream) {
        viewers.incrementAndGet()
        try {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            out.flush()
            var last = -1L
            while (running) {
                var data: ByteArray? = null
                synchronized(lock) {
                    while (running && frameNo == last) lock.wait(2000)
                    last = frameNo
                    data = frame
                }
                if (!running) break
                val d = data ?: continue
                out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${d.size}\r\n\r\n".toByteArray())
                out.write(d)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
            // viewer closed the page
        } finally {
            viewers.decrementAndGet()
        }
    }

    companion object {
        /** IPv4 addresses of this phone (hotspot and Wi-Fi first), so the user knows what to type. */
        fun addresses(): List<String> {
            val list = mutableListOf<Pair<Int, String>>()
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    val name = ni.name.lowercase()
                    for (a in ni.inetAddresses) {
                        if (a !is Inet4Address) continue
                        val ip = a.hostAddress ?: continue
                        val rank = when {
                            name.startsWith("ap") || name.startsWith("swlan") || name.contains("softap") -> 0
                            name.startsWith("wlan") -> 1
                            ip.startsWith("192.168.") -> 2
                            name.startsWith("rmnet") || name.startsWith("ccmni") -> 9 // mobile data: not reachable
                            else -> 5
                        }
                        list += rank to ip
                    }
                }
            } catch (_: Exception) {}
            return list.sortedBy { it.first }.filter { it.first < 9 }.map { it.second }.distinct()
        }
    }
}
