package com.virtualcam.onvif.onvif

import android.graphics.Bitmap
import android.util.Log
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class OnvifHttpServer(
    private val localIp: String,
    private val port: Int = 8080,
    private val rtspPort: Int = 8554,
    private val getDimensions: () -> Pair<Int, Int>,
    private val getLatestSnapshot: () -> Bitmap?
) {

    private val tag = "OnvifHttpServer"
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private val threadPool = Executors.newCachedThreadPool()

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
            val path = parts[1]

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

            when {
                path.startsWith("/onvif/device_service") -> {
                    handleDeviceService(out, requestBody)
                }
                path.startsWith("/onvif/media_service") -> {
                    handleMediaService(out, requestBody)
                }
                path.startsWith("/snapshot.jpg") -> {
                    handleSnapshot(out)
                }
                path.startsWith("/status") -> {
                    val json = """{"status":"running","ip":"$localIp","rtsp":"rtsp://$localIp:$rtspPort/live","onvif":"http://$localIp:$port/onvif/device_service"}"""
                    sendHttpResponse(out, "application/json", json.toByteArray())
                }
                else -> {
                    val notFound = "<html><body><h1>VirtualCam ONVIF Server</h1><p>Running on Android</p></body></html>"
                    sendHttpResponse(out, "text/html", notFound.toByteArray())
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

    private fun handleDeviceService(out: OutputStream, body: String) {
        val (width, height) = getDimensions()
        val responseXml = when {
            body.contains("GetDeviceInformation", ignoreCase = true) -> {
                OnvifXmlTemplates.getDeviceInformationResponse()
            }
            body.contains("GetCapabilities", ignoreCase = true) -> {
                OnvifXmlTemplates.getCapabilitiesResponse(localIp, port)
            }
            body.contains("GetSystemDateAndTime", ignoreCase = true) -> {
                OnvifXmlTemplates.getSystemDateAndTimeResponse()
            }
            body.contains("GetServices", ignoreCase = true) -> {
                OnvifXmlTemplates.getServicesResponse(localIp, port)
            }
            body.contains("GetScopes", ignoreCase = true) -> {
                OnvifXmlTemplates.getScopesResponse()
            }
            else -> {
                OnvifXmlTemplates.getCapabilitiesResponse(localIp, port)
            }
        }
        sendHttpResponse(out, "application/soap+xml; charset=utf-8", responseXml.toByteArray())
    }

    private fun handleMediaService(out: OutputStream, body: String) {
        val (width, height) = getDimensions()
        val rtspUri = "rtsp://$localIp:$rtspPort/live"

        val responseXml = when {
            body.contains("GetProfiles", ignoreCase = true) -> {
                OnvifXmlTemplates.getProfilesResponse(width, height)
            }
            body.contains("GetStreamUri", ignoreCase = true) -> {
                OnvifXmlTemplates.getStreamUriResponse(rtspUri)
            }
            else -> {
                OnvifXmlTemplates.getProfilesResponse(width, height)
            }
        }
        sendHttpResponse(out, "application/soap+xml; charset=utf-8", responseXml.toByteArray())
    }

    private fun handleSnapshot(out: OutputStream) {
        val bitmap = getLatestSnapshot()
        val stream = ByteArrayOutputStream()
        if (bitmap != null) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        } else {
            // Fallback 1x1 black bitmap
            val emptyBitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            emptyBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        }
        sendHttpResponse(out, "image/jpeg", stream.toByteArray())
    }

    private fun sendHttpResponse(out: OutputStream, contentType: String, data: ByteArray) {
        val header = "HTTP/1.1 200 OK\r\n" +
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
    }
}
