package com.virtualcam.onvif.onvif

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class OnvifHttpServer(
    private val localIp: String,
    private val port: Int = 8080,
    private val rtspPort: Int = 8554,
    private val getDimensions: () -> Pair<Int, Int>,
    private val getLatestJpeg: () -> ByteArray?,
    private val setWebActive: (Boolean) -> Unit
) {

    private val tag = "OnvifHttpServer"
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private val threadPool = Executors.newCachedThreadPool()
    private val activeWebClientsCount = AtomicInteger(0)

    fun start() {
        stop()
        isRunning = true

        Thread({
            try {
                serverSocket = ServerSocket(port).apply { reuseAddress = true }
                Log.d(tag, "ONVIF HTTP Server running on http://$localIp:$port")

                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    threadPool.execute { handleClient(socket) }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(tag, "ONVIF HTTP Server exception", e)
                }
            }
        }, "OnvifHttpServerThread").start()
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val path = rawPath.replace("//", "/") // Handle double slashes

            var contentLength = 0
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substring(15).trim().toIntOrNull() ?: 0
                }
                line = reader.readLine()
            }

            var requestBody = ""
            if (contentLength > 0) {
                val bodyChars = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = reader.read(bodyChars, readTotal, contentLength - readTotal)
                    if (r == -1) break
                    readTotal += r
                }
                requestBody = String(bodyChars, 0, readTotal)
            }

            // Route handling
            if (method == "POST") {
                // ONVIF SOAP Services
                when {
                    path.contains("device_service", ignoreCase = true) -> handleDeviceService(out, requestBody)
                    path.contains("media_service", ignoreCase = true) -> handleMediaService(out, requestBody)
                    else -> handleDeviceService(out, requestBody)
                }
            } else {
                // HTTP GET: Web browser & media requests
                when {
                    path == "/video" || path == "/mjpeg" -> {
                        handleMjpegStream(out)
                    }
                    path == "/snapshot.jpg" -> {
                        handleSnapshot(out)
                    }
                    path == "/status" -> {
                        val json = """{"status":"running","ip":"$localIp","rtsp":"rtsp://$localIp:$rtspPort/live","onvif":"http://$localIp:$port/onvif/device_service"}"""
                        sendHttpResponse(out, "application/json", json.toByteArray())
                    }
                    else -> {
                        // Any other GET (e.g. /, /onfiv/device_service, /onvif/device_service from a browser)
                        serveWebPlayerHtml(out)
                    }
                }
            }
        } catch (e: Exception) {
            // Client socket error
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {}
        }
    }

    private fun handleMjpegStream(out: OutputStream) {
        val count = activeWebClientsCount.incrementAndGet()
        setWebActive(count > 0)

        try {
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Connection: close\r\n" +
                    "Server: VirtualCam-ONVIF\r\n" +
                    "Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=--frame\r\n\r\n"
            out.write(header.toByteArray())
            out.flush()

            val fallbackJpeg = createFallbackJpeg()

            while (isRunning) {
                val frame = getLatestJpeg() ?: fallbackJpeg
                val frameHeader = "--frame\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${frame.size}\r\n\r\n"
                out.write(frameHeader.toByteArray())
                out.write(frame)
                out.write("\r\n".toByteArray())
                out.flush()

                Thread.sleep(40) // ~25 FPS
            }
        } catch (e: Exception) {
            // Web client disconnected
        } finally {
            val remaining = activeWebClientsCount.decrementAndGet()
            setWebActive(remaining > 0)
        }
    }

    private fun handleSnapshot(out: OutputStream) {
        val jpeg = getLatestJpeg() ?: createFallbackJpeg()
        sendHttpResponse(out, "image/jpeg", jpeg)
    }

    private fun serveWebPlayerHtml(out: OutputStream) {
        val (width, height) = getDimensions()
        val rtspUrl = "rtsp://$localIp:$rtspPort/live"
        val onvifUrl = "http://$localIp:$port/onvif/device_service"

        val html = """<!DOCTYPE html>
<html lang="id">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>VirtualCam ONVIF - Live Stream</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { background: #121212; color: #FFFFFF; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; display: flex; justify-content: center; padding: 20px 12px; }
        .wrapper { width: 100%; max-width: 820px; }
        .header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
        .title { font-size: 20px; font-weight: bold; color: #00E676; }
        .badge { background: #1B5E20; color: #00E676; padding: 4px 10px; border-radius: 12px; font-size: 12px; font-weight: bold; }
        .video-card { background: #000; border: 2px solid #2C2C2C; border-radius: 16px; overflow: hidden; box-shadow: 0 8px 30px rgba(0,0,0,0.7); position: relative; }
        .video-stream { width: 100%; height: auto; display: block; aspect-ratio: 16/9; object-fit: contain; background: #000; }
        .controls-card { background: #1E1E1E; border-radius: 16px; padding: 18px; margin-top: 16px; border: 1px solid #333; }
        .info-row { display: flex; flex-direction: column; margin-bottom: 12px; }
        .info-label { font-size: 12px; color: #9E9E9E; margin-bottom: 4px; }
        .info-val { font-family: monospace; font-size: 13px; background: #2A2A2A; padding: 8px 12px; border-radius: 8px; color: #00E676; word-break: break-all; }
        .btn-group { display: flex; gap: 8px; margin-top: 14px; flex-wrap: wrap; }
        .btn { background: #00C853; color: #121212; border: none; padding: 10px 16px; border-radius: 8px; font-weight: bold; cursor: pointer; text-decoration: none; font-size: 13px; display: inline-flex; align-items: center; gap: 6px; }
        .btn-secondary { background: #2C2C2C; color: #FFF; }
        .guide-box { background: #181818; border-left: 4px solid #00E676; padding: 12px; margin-top: 16px; border-radius: 4px; font-size: 13px; line-height: 1.5; color: #CCCCCC; }
    </style>
</head>
<body>
    <div class="wrapper">
        <div class="header">
            <div class="title">📷 VirtualCam ONVIF Live Web View</div>
            <div class="badge">● LIVE STREAM</div>
        </div>

        <div class="video-card">
            <img class="video-stream" src="/video" alt="Live Camera Video Stream" onerror="this.src='/snapshot.jpg?t='+Date.now()" />
        </div>

        <div class="controls-card">
            <div class="info-row">
                <span class="info-label">RTSP Stream URL (VLC Media Player, OBS Studio, NVR CCTV):</span>
                <span class="info-val" id="rtspText">$rtspUrl</span>
            </div>

            <div class="info-row">
                <span class="info-label">ONVIF Device Service URL (ONVIF Device Manager / CCTV software):</span>
                <span class="info-val" id="onvifText">$onvifUrl</span>
            </div>

            <div class="btn-group">
                <a href="/snapshot.jpg" target="_blank" class="btn btn-secondary">📸 Unduh Snapshot</a>
                <button class="btn" onclick="navigator.clipboard.writeText('$rtspUrl'); alert('RTSP URL disalin!');">📋 Salin RTSP URL</button>
                <button class="btn btn-secondary" onclick="location.reload();">🔄 Muat Ulang Stream</button>
            </div>

            <div class="guide-box">
                <b>💡 Tips Menonton di Perangkat Lain:</b><br/>
                1. <b>Browser (Chrome / Edge / Safari):</b> Halaman ini sudah otomatis menampilkan live video secara langsung!<br/>
                2. <b>VLC Media Player di Laptop/PC:</b> Buka menu <i>Media ➔ Open Network Stream</i> ➔ Tempel URL: <code>$rtspUrl</code>.<br/>
                3. <b>ONVIF Device Manager:</b> Masukkan URL: <code>$onvifUrl</code>.
            </div>
        </div>
    </div>
</body>
</html>"""
        sendHttpResponse(out, "text/html; charset=utf-8", html.toByteArray())
    }

    private fun handleDeviceService(out: OutputStream, body: String) {
        val (width, height) = getDimensions()
        val responseXml = when {
            body.contains("GetDeviceInformation", ignoreCase = true) -> OnvifXmlTemplates.getDeviceInformationResponse()
            body.contains("GetCapabilities", ignoreCase = true) -> OnvifXmlTemplates.getCapabilitiesResponse(localIp, port)
            body.contains("GetSystemDateAndTime", ignoreCase = true) -> OnvifXmlTemplates.getSystemDateAndTimeResponse()
            body.contains("GetServices", ignoreCase = true) -> OnvifXmlTemplates.getServicesResponse(localIp, port)
            body.contains("GetScopes", ignoreCase = true) -> OnvifXmlTemplates.getScopesResponse()
            else -> OnvifXmlTemplates.getCapabilitiesResponse(localIp, port)
        }
        sendHttpResponse(out, "application/soap+xml; charset=utf-8", responseXml.toByteArray())
    }

    private fun handleMediaService(out: OutputStream, body: String) {
        val (width, height) = getDimensions()
        val rtspUri = "rtsp://$localIp:$rtspPort/live"

        val responseXml = when {
            body.contains("GetProfiles", ignoreCase = true) -> OnvifXmlTemplates.getProfilesResponse(width, height)
            body.contains("GetStreamUri", ignoreCase = true) -> OnvifXmlTemplates.getStreamUriResponse(rtspUri)
            else -> OnvifXmlTemplates.getProfilesResponse(width, height)
        }
        sendHttpResponse(out, "application/soap+xml; charset=utf-8", responseXml.toByteArray())
    }

    private fun createFallbackJpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(Color.DKGRAY)
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            isAntiAlias = true
        }
        canvas.drawText("VirtualCam ONVIF - Menunggu Stream...", 80f, 180f, paint)

        val stream = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, stream)
        val bytes = stream.toByteArray()
        bmp.recycle()
        return bytes
    }

    private fun sendHttpResponse(out: OutputStream, contentType: String, data: ByteArray) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Server: VirtualCam-ONVIF\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${data.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(data)
        out.flush()
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        serverSocket = null
        activeWebClientsCount.set(0)
        setWebActive(false)
    }
}
