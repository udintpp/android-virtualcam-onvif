package com.virtualcam.onvif.rtsp

import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.regex.Pattern

class RtspClientSession(
    private val socket: Socket,
    private val localIp: String,
    private val serverPort: Int,
    private val getSps: () -> ByteArray?,
    private val getPps: () -> ByteArray?,
    private val onClose: (session: RtspClientSession) -> Unit
) : Runnable {

    private val tag = "RtspClientSession"
    private val outputStream: OutputStream = socket.getOutputStream()
    private val inputStream = socket.getInputStream()
    private val reader = BufferedReader(InputStreamReader(inputStream))

    @Volatile
    var isPlaying = false
        private set

    private var isTcpTransport = true
    private var tcpChannel = 0
    private var clientUdpPort = 0
    private var udpSocket: DatagramSocket? = null
    private val clientAddress: InetAddress = socket.inetAddress

    private val sessionId = "VIRTUALCAM_${System.currentTimeMillis() % 100000}"

    override fun run() {
        try {
            while (!socket.isClosed) {
                val requestLines = mutableListOf<String>()
                var line = reader.readLine()
                if (line == null) break

                while (!line.isNullOrEmpty()) {
                    requestLines.add(line)
                    line = reader.readLine()
                }

                if (requestLines.isNotEmpty()) {
                    handleRequest(requestLines)
                }
            }
        } catch (e: Exception) {
            // Client disconnected
        } finally {
            close()
        }
    }

    private fun handleRequest(lines: List<String>) {
        val firstLine = lines[0]
        val parts = firstLine.split(" ")
        if (parts.size < 3) return

        val method = parts[0]
        val uri = parts[1]
        val cSeq = getHeaderValue(lines, "CSeq") ?: "1"

        when (method.uppercase()) {
            "OPTIONS" -> {
                val response = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $cSeq\r\n" +
                        "Public: OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE\r\n\r\n"
                sendResponse(response)
            }
            "DESCRIBE" -> {
                val sdp = generateSdp()
                val response = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $cSeq\r\n" +
                        "Content-Type: application/sdp\r\n" +
                        "Content-Base: rtsp://$localIp:$serverPort/live/\r\n" +
                        "Content-Length: ${sdp.toByteArray().size}\r\n\r\n" +
                        sdp
                sendResponse(response)
            }
            "SETUP" -> {
                val transportHeader = getHeaderValue(lines, "Transport") ?: ""
                val isInterleaved = transportHeader.contains("interleaved", ignoreCase = true)
                var transportResponse = ""

                if (isInterleaved || transportHeader.contains("TCP", ignoreCase = true)) {
                    isTcpTransport = true
                    tcpChannel = 0
                    transportResponse = "RTP/AVP/TCP;unicast;interleaved=0-1"
                } else {
                    isTcpTransport = false
                    val portMatcher = Pattern.compile("client_port=(\\d+)-(\\d+)").matcher(transportHeader)
                    if (portMatcher.find()) {
                        clientUdpPort = portMatcher.group(1)?.toIntOrNull() ?: 50000
                    } else {
                        clientUdpPort = 50000
                    }
                    udpSocket = DatagramSocket()
                    val serverUdpPort = udpSocket?.localPort ?: 55500
                    transportResponse = "RTP/AVP;unicast;client_port=$clientUdpPort-${clientUdpPort + 1};server_port=$serverUdpPort-${serverUdpPort + 1}"
                }

                val response = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $cSeq\r\n" +
                        "Session: $sessionId;timeout=60\r\n" +
                        "Transport: $transportResponse\r\n\r\n"
                sendResponse(response)
            }
            "PLAY" -> {
                isPlaying = true
                val response = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $cSeq\r\n" +
                        "Session: $sessionId\r\n" +
                        "Range: npt=0.000-\r\n" +
                        "RTP-Info: url=rtsp://$localIp:$serverPort/live/trackID=0;seq=0\r\n\r\n"
                sendResponse(response)
                Log.d(tag, "Client started PLAY session: $sessionId (${if (isTcpTransport) "TCP" else "UDP"})")
            }
            "TEARDOWN" -> {
                isPlaying = false
                val response = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $cSeq\r\n" +
                        "Session: $sessionId\r\n\r\n"
                sendResponse(response)
                close()
            }
            else -> {
                val response = "RTSP/1.0 501 Not Implemented\r\nCSeq: $cSeq\r\n\r\n"
                sendResponse(response)
            }
        }
    }

    private fun generateSdp(): String {
        val spsBytes = getSps() ?: byteArrayOf(0x67, 0x42, 0x00, 0x1f.toByte())
        val ppsBytes = getPps() ?: byteArrayOf(0x68, 0xce.toByte(), 0x38, 0x80.toByte())

        val spsBase64 = Base64.encodeToString(spsBytes, Base64.NO_WRAP)
        val ppsBase64 = Base64.encodeToString(ppsBytes, Base64.NO_WRAP)

        return "v=0\r\n" +
                "o=- 0 0 IN IP4 $localIp\r\n" +
                "s=VirtualCam ONVIF Live Stream\r\n" +
                "c=IN IP4 $localIp\r\n" +
                "t=0 0\r\n" +
                "a=range:npt=now-\r\n" +
                "m=video 0 RTP/AVP 96\r\n" +
                "a=rtpmap:96 H264/90000\r\n" +
                "a=fmtp:96 packetization-mode=1;profile-level-id=42001f;sprop-parameter-sets=$spsBase64,$ppsBase64\r\n" +
                "a=control:trackID=0\r\n"
    }

    fun sendRtpPacket(packet: ByteArray) {
        if (!isPlaying) return

        try {
            if (isTcpTransport) {
                // Interleaved frame header: $ <channel> <length 2 bytes>
                synchronized(outputStream) {
                    val length = packet.size
                    outputStream.write('$'.code)
                    outputStream.write(tcpChannel)
                    outputStream.write((length shr 8) and 0xFF)
                    outputStream.write(length and 0xFF)
                    outputStream.write(packet)
                    outputStream.flush()
                }
            } else {
                // Send via UDP
                val udp = udpSocket
                if (udp != null && clientUdpPort > 0) {
                    val dPacket = DatagramPacket(packet, packet.size, clientAddress, clientUdpPort)
                    udp.send(dPacket)
                }
            }
        } catch (e: Exception) {
            close()
        }
    }

    private fun getHeaderValue(lines: List<String>, headerName: String): String? {
        val prefix = "$headerName:".lowercase()
        for (line in lines) {
            if (line.lowercase().startsWith(prefix)) {
                return line.substring(prefix.length).trim()
            }
        }
        return null
    }

    private fun sendResponse(response: String) {
        synchronized(outputStream) {
            outputStream.write(response.toByteArray())
            outputStream.flush()
        }
    }

    fun close() {
        isPlaying = false
        try {
            socket.close()
        } catch (ignored: Exception) {}
        try {
            udpSocket?.close()
        } catch (ignored: Exception) {}
        onClose(this)
    }
}
