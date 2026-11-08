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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OnvifHttpServer(
    private val localIp: String,
    private val port: Int = 8080,
    private val rtspPort: Int = 8554,
    private val getDimensions: () -> Pair<Int, Int>,
    private val getLatestJpeg: () -> ByteArray?,
    private val setWebActive: (Boolean) -> Unit,
    private val getAudioModeName: () -> String = { "Nonaktif" },
    private val isAudioEnabled: () -> Boolean = { false }
) {

    private val tag = "OnvifHttpServer"
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private val threadPool = Executors.newCachedThreadPool()
    private val activeWebClientsCount = AtomicInteger(0)

    private val pcmClients = CopyOnWriteArrayList<ArrayBlockingQueue<ByteArray>>()
    private val aacClients = CopyOnWriteArrayList<ArrayBlockingQueue<ByteArray>>()

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

    fun broadcastPcmAudio(pcmBytes: ByteArray) {
        if (!isRunning || pcmClients.isEmpty()) return
        val copy = pcmBytes.copyOf()
        for (queue in pcmClients) {
            if (!queue.offer(copy)) {
                queue.poll() // Drop oldest frame to keep latency low
                queue.offer(copy)
            }
        }
    }

    fun broadcastAacAdtsAudio(adtsBytes: ByteArray) {
        if (!isRunning || aacClients.isEmpty()) return
        val copy = adtsBytes.copyOf()
        for (queue in aacClients) {
            if (!queue.offer(copy)) {
                queue.poll()
                queue.offer(copy)
            }
        }
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
            val path = rawPath.replace("//", "/")

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

            if (method == "POST") {
                when {
                    path.contains("device_service", ignoreCase = true) -> handleDeviceService(out, requestBody)
                    path.contains("media_service", ignoreCase = true) -> handleMediaService(out, requestBody)
                    else -> handleDeviceService(out, requestBody)
                }
            } else {
                when {
                    path.startsWith("/video") || path.startsWith("/mjpeg") -> {
                        handleMjpegStream(out)
                    }
                    path.startsWith("/audio.pcm") -> {
                        handleAudioPcmStream(out)
                    }
                    path.startsWith("/audio.aac") -> {
                        handleAudioAacStream(out)
                    }
                    path.startsWith("/snapshot.jpg") -> {
                        handleSnapshot(out)
                    }
                    path == "/status" -> {
                        val audioLabel = getAudioModeName()
                        val json = """{"status":"running","ip":"$localIp","rtsp":"rtsp://$localIp:$rtspPort/live","onvif":"http://$localIp:$port/onvif/device_service","audio":"$audioLabel"}"""
                        sendHttpResponse(out, "application/json", json.toByteArray())
                    }
                    else -> {
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
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n"
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

    private fun handleAudioPcmStream(out: OutputStream) {
        val queue = ArrayBlockingQueue<ByteArray>(60)
        pcmClients.add(queue)
        try {
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Connection: close\r\n" +
                    "Server: VirtualCam-ONVIF\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Cache-Control: no-store, no-cache\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n"
            out.write(header.toByteArray())
            out.flush()

            while (isRunning) {
                val chunk = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                out.write(chunk)
                out.flush()
            }
        } catch (e: Exception) {
            // Audio client disconnected
        } finally {
            pcmClients.remove(queue)
        }
    }

    private fun handleAudioAacStream(out: OutputStream) {
        val queue = ArrayBlockingQueue<ByteArray>(60)
        aacClients.add(queue)
        try {
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Connection: close\r\n" +
                    "Server: VirtualCam-ONVIF\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Cache-Control: no-store, no-cache\r\n" +
                    "Content-Type: audio/aac\r\n\r\n"
            out.write(header.toByteArray())
            out.flush()

            while (isRunning) {
                val adtsFrame = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                out.write(adtsFrame)
                out.flush()
            }
        } catch (e: Exception) {
            // AAC client disconnected
        } finally {
            aacClients.remove(queue)
        }
    }

    private fun handleSnapshot(out: OutputStream) {
        val jpeg = getLatestJpeg() ?: createFallbackJpeg()
        sendHttpResponse(out, "image/jpeg", jpeg)
    }

    private fun serveWebPlayerHtml(out: OutputStream) {
        val rtspUrl = "rtsp://$localIp:$rtspPort/live"
        val onvifUrl = "http://$localIp:$port/onvif/device_service"
        val aacUrl = "http://$localIp:$port/audio.aac"
        val audioStatus = getAudioModeName()

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
        .header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; flex-wrap: wrap; gap: 8px; }
        .title { font-size: 20px; font-weight: bold; color: #00E676; }
        .badges { display: flex; gap: 8px; }
        .badge { background: #1B5E20; color: #00E676; padding: 4px 10px; border-radius: 12px; font-size: 12px; font-weight: bold; }
        .badge-audio { background: #0D47A1; color: #82B1FF; }
        .video-card { background: #000; border: 2px solid #2C2C2C; border-radius: 16px; overflow: hidden; box-shadow: 0 8px 30px rgba(0,0,0,0.7); position: relative; }
        .video-stream { width: 100%; height: auto; display: block; aspect-ratio: 16/9; object-fit: contain; background: #000; }
        .audio-bar { background: #181818; border-top: 1px solid #2C2C2C; padding: 12px 16px; display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; }
        .controls-card { background: #1E1E1E; border-radius: 16px; padding: 18px; margin-top: 16px; border: 1px solid #333; }
        .info-row { display: flex; flex-direction: column; margin-bottom: 12px; }
        .info-label { font-size: 12px; color: #9E9E9E; margin-bottom: 4px; }
        .info-val { font-family: monospace; font-size: 13px; background: #2A2A2A; padding: 8px 12px; border-radius: 8px; color: #00E676; word-break: break-all; }
        .btn-group { display: flex; gap: 8px; margin-top: 14px; flex-wrap: wrap; }
        .btn { background: #00C853; color: #121212; border: none; padding: 10px 16px; border-radius: 8px; font-weight: bold; cursor: pointer; text-decoration: none; font-size: 13px; display: inline-flex; align-items: center; gap: 6px; }
        .btn-audio { background: #2979FF; color: #FFF; }
        .btn-audio-active { background: #FF5252; color: #FFF; }
        .btn-secondary { background: #2C2C2C; color: #FFF; }
        .guide-box { background: #181818; border-left: 4px solid #00E676; padding: 12px; margin-top: 16px; border-radius: 4px; font-size: 13px; line-height: 1.5; color: #CCCCCC; }
    </style>
</head>
<body>
    <div class="wrapper">
        <div class="header">
            <div class="title">📷 VirtualCam ONVIF Live Web View</div>
            <div class="badges">
                <div class="badge badge-audio" id="audioBadge">🔊 Audio: $audioStatus</div>
                <div class="badge">● LIVE</div>
            </div>
        </div>

        <div class="video-card">
            <img class="video-stream" src="/video" alt="Live Camera Video Stream" onerror="this.src='/snapshot.jpg?t='+Date.now()" />
            <div class="audio-bar">
                <button id="btnWebAudio" class="btn btn-audio" onclick="toggleWebAudio()">🔊 Putar Audio Live</button>
                <span id="audioStateText" style="font-size:12px; color:#9E9E9E;">Klik tombol untuk mendengarkan suara langsung</span>
            </div>
        </div>

        <div class="controls-card">
            <div class="info-row">
                <span class="info-label">RTSP Stream URL (Video + Audio AAC untuk VLC / OBS / NVR):</span>
                <span class="info-val" id="rtspText">$rtspUrl</span>
            </div>

            <div class="info-row">
                <span class="info-label">ONVIF Device Service URL:</span>
                <span class="info-val" id="onvifText">$onvifUrl</span>
            </div>

            <div class="info-row">
                <span class="info-label">HTTP Audio AAC Stream URL:</span>
                <span class="info-val">$aacUrl</span>
            </div>

            <div class="btn-group">
                <a href="/snapshot.jpg" target="_blank" class="btn btn-secondary">📸 Unduh Snapshot</a>
                <button class="btn" onclick="navigator.clipboard.writeText('$rtspUrl'); alert('RTSP URL disalin!');">📋 Salin RTSP URL</button>
                <button class="btn btn-secondary" onclick="location.reload();">🔄 Muat Ulang</button>
            </div>

            <div class="guide-box">
                <b>💡 Tips Menonton &amp; Mendengar di Perangkat Lain:</b><br/>
                1. <b>Browser:</b> Ketuk tombol biru <b>"Putar Audio Live"</b> di bawah video untuk mendengar suara secara real-time.<br/>
                2. <b>VLC / OBS Studio (Video + Audio):</b> Buka <i>Media ➔ Open Network Stream</i> ➔ Masukkan <code>$rtspUrl</code>.<br/>
                3. <b>ONVIF Device Manager:</b> Masukkan URL: <code>$onvifUrl</code>.
            </div>
        </div>
    </div>

    <script>
        let audioCtx = null;
        let abortCtrl = null;
        let isAudioPlaying = false;

        async function toggleWebAudio() {
            const btn = document.getElementById('btnWebAudio');
            const label = document.getElementById('audioStateText');
            if (isAudioPlaying) {
                isAudioPlaying = false;
                if (abortCtrl) abortCtrl.abort();
                if (audioCtx) { try { audioCtx.close(); } catch(e) {} audioCtx = null; }
                btn.textContent = '🔊 Putar Audio Live';
                btn.className = 'btn btn-audio';
                label.textContent = 'Audio dijeda';
                return;
            }

            try {
                isAudioPlaying = true;
                btn.textContent = '🔇 Matikan Audio';
                btn.className = 'btn btn-audio-active';
                label.textContent = 'Menghubungkan stream audio...';

                const AudioContextClass = window.AudioContext || window.webkitAudioContext;
                audioCtx = new AudioContextClass({ sampleRate: 44100 });
                if (audioCtx.state === 'suspended') await audioCtx.resume();

                abortCtrl = new AbortController();
                const resp = await fetch('/audio.pcm', { signal: abortCtrl.signal });
                if (!resp.body) throw new Error('Streaming fetch unsupported');

                const reader = resp.body.getReader();
                let nextTime = audioCtx.currentTime + 0.08;
                let leftoverByte = null;
                label.textContent = 'Streaming audio aktif (44.1 kHz)';

                while (isAudioPlaying) {
                    const { done, value } = await reader.read();
                    if (done) break;

                    let bytes = value;
                    if (leftoverByte !== null) {
                        const combined = new Uint8Array(value.length + 1);
                        combined[0] = leftoverByte;
                        combined.set(value, 1);
                        bytes = combined;
                        leftoverByte = null;
                    }
                    const evenLen = bytes.length & ~1;
                    if (bytes.length > evenLen) {
                        leftoverByte = bytes[evenLen];
                    }
                    if (evenLen >= 2) {
                        const numSamples = evenLen / 2;
                        const view = new DataView(bytes.buffer, bytes.byteOffset, evenLen);
                        const audioBuf = audioCtx.createBuffer(1, numSamples, 44100);
                        const chan = audioBuf.getChannelData(0);
                        for (let i = 0; i < numSamples; i++) {
                            chan[i] = view.getInt16(i * 2, true) / 32768.0;
                        }
                        const now = audioCtx.currentTime;
                        if (nextTime < now + 0.015 || nextTime > now + 0.6) {
                            nextTime = now + 0.08;
                        }
                        const src = audioCtx.createBufferSource();
                        src.buffer = audioBuf;
                        src.connect(audioCtx.destination);
                        src.start(nextTime);
                        nextTime += audioBuf.duration;
                    }
                }
            } catch (err) {
                if (err.name !== 'AbortError') {
                    label.textContent = 'Stream audio berhenti atau mode Tanpa Audio aktif.';
                }
            } finally {
                if (isAudioPlaying) {
                    isAudioPlaying = false;
                    btn.textContent = '🔊 Putar Audio Live';
                    btn.className = 'btn btn-audio';
                }
            }
        }
    </script>
</body>
</html>"""
        sendHttpResponse(out, "text/html; charset=utf-8", html.toByteArray())
    }

    private fun handleDeviceService(out: OutputStream, body: String) {
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
        val includeAudio = isAudioEnabled()

        val responseXml = when {
            body.contains("GetProfiles", ignoreCase = true) -> OnvifXmlTemplates.getProfilesResponse(width, height, includeAudio)
            body.contains("GetStreamUri", ignoreCase = true) -> OnvifXmlTemplates.getStreamUriResponse(rtspUri)
            else -> OnvifXmlTemplates.getProfilesResponse(width, height, includeAudio)
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
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${data.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(data)
        out.flush()
    }

    fun stop() {
        isRunning = false
        pcmClients.clear()
        aacClients.clear()
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        serverSocket = null
        activeWebClientsCount.set(0)
        setWebActive(false)
    }
}
