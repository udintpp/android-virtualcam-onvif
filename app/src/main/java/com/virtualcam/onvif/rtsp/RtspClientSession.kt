package com.virtualcam.onvif.rtsp

import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
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
    private val isAudioEnabled: () -> Boolean,
    private val getAudioConfigHex: () -> String,
    private val onClose: (session: RtspClientSession) -> Unit
) : Runnable {

    private val tag = "RtspClientSession"
    private val outputStream: OutputStream = socket.getOutputStream()
    private val inputStream = BufferedInputStream(socket.getInputStream())

    @Volatile
    var isPlaying = false
        private set

    // Video track transport
    @Volatile
    private var videoSetup = false
    private var videoIsTcp = true
    private var videoTcpChannel = 0
    private var videoClientUdpPort = 0
    private var videoUdpSocket: DatagramSocket? = null

    // Audio track transport
    @Volatile
    private var audioSetup = false
    private var audioIsTcp = true
    private var audioTcpChannel = 2
    private var audioClientUdpPort = 0
    private var audioUdpSocket: DatagramSocket? = null

    private val clientAddress: InetAddress = socket.inetAddress
    private val sessionId = "VIRTUALCAM_${System.currentTimeMillis() % 100000}"

    override fun run() {
        try {
            while (!socket.isClosed) {
                val requestLines = readNextRtspRequest() ?: break
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

    /**
     * Reads RTSP text requests while safely skipping interleaved RTCP binary frames ($ <ch> <len> <payload>)
     * that clients send over TCP connections.
     */
    private fun readNextRtspRequest(): List<String>? {
        val lines = mutableListOf<String>()
        while (!socket.isClosed) {
            inputStream.mark(4)
            val b = inputStream.read()
            if (b == -1) return null

            if (b == '$'.code) {
                // Interleaved binary RTCP frame from client: 1 byte channel + 2 byte length
                val ch = inputStream.read()
                val lenHi = inputStream.read()
                val lenLo = inputStream.read()
                if (ch == -1 || lenHi == -1 || lenLo == -1) return null
                val len = ((lenHi and 0xFF) shl 8) or (lenLo and 0xFF)
                var skipped = 0
                while (skipped < len) {
                    val r = inputStream.skip((len - skipped).toLong())
                    if (r <= 0) {
                        if (inputStream.read() == -1) return null
                        skipped++
                    } else {
                        skipped += r.toInt()
                    }
                }
                continue
            }

            if (b == '\r'.code || b == '\n'.code) {
                if (lines.isNotEmpty()) {
                    // Blank line ends RTSP request header block (after consuming \r\n)
                    if (b == '\r'.code) {
                        inputStream.mark(1)
                        if (inputStream.read() != '\n'.code) {
                            inputStream.reset()
                        }
                    }
                    return lines
                }
                continue
            }

            inputStream.reset()
            val line = readLineFromStream() ?: return null
            if (line.isEmpty()) {
                if (lines.isNotEmpty()) return lines
            } else {
                lines.add(line)
            }
        }
        return null
    }

    private fun readLineFromStream(): String? {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = inputStream.read()
            if (b == -1) {
                return if (baos.size() > 0) baos.toString("UTF-8") else null
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                baos.write(b)
            }
        }
        return baos.toString("UTF-8")
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
                        "Public: OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER\r\n\r\n"
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
                val isAudioTrack = uri.contains("trackID=1", ignoreCase = true)
                val transportHeader = getHeaderValue(lines, "Transport") ?: ""
                val isInterleaved = transportHeader.contains("interleaved", ignoreCase = true)
                val isTcp = isInterleaved || transportHeader.contains("TCP", ignoreCase = true)
                var transportResponse = ""

                if (isTcp) {
                    var ch = if (isAudioTrack) 2 else 0
                    val chMatcher = Pattern.compile("interleaved=(\\d+)-(\\d+)").matcher(transportHeader)
                    if (chMatcher.find()) {
                        ch = chMatcher.group(1)?.toIntOrNull() ?: ch
                    }
                    if (isAudioTrack) {
                        audioSetup = true
                        audioIsTcp = true
                        audioTcpChannel = ch
                    } else {
                        videoSetup = true
                        videoIsTcp = true
                        videoTcpChannel = ch
                    }
                    transportResponse = "RTP/AVP/TCP;unicast;interleaved=$ch-${ch + 1}"
                } else {
                    val portMatcher = Pattern.compile("client_port=(\\d+)-(\\d+)").matcher(transportHeader)
                    val defaultPort = if (isAudioTrack) 50002 else 50000
                    val clientPort = if (portMatcher.find()) {
                        portMatcher.group(1)?.toIntOrNull() ?: defaultPort
                    } else {
                        defaultPort
                    }

                    val sock = DatagramSocket()
                    val serverUdpPort = sock.localPort
                    if (isAudioTrack) {
                        audioSetup = true
                        audioIsTcp = false
                        audioClientUdpPort = clientPort
                        audioUdpSocket?.close()
                        audioUdpSocket = sock
                    } else {
                        videoSetup = true
                        videoIsTcp = false
                        videoClientUdpPort = clientPort
                        videoUdpSocket?.close()
                        videoUdpSocket = sock
                    }
                    transportResponse = "RTP/AVP;unicast;client_port=$clientPort-${clientPort + 1};server_port=$serverUdpPort-${serverUdpPort + 1}"
                }

                val response = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $cSeq\r\n" +
                        "Session: $sessionId;timeout=60\r\n" +
                        "Transport: $transportResponse\r\n\r\n"
                sendResponse(response)
            }
            "PLAY" -> {
                isPlaying = true
                val rtpInfo = buildString {
                    append("url=rtsp://$localIp:$serverPort/live/trackID=0;seq=0")
                    if (audioSetup) {
                        append(",url=rtsp://$localIp:$serverPort/live/trackID=1;seq=0")
                    }
                }
                val response = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $cSeq\r\n" +
                        "Session: $sessionId\r\n" +
                        "Range: npt=0.000-\r\n" +
                        "RTP-Info: $rtpInfo\r\n\r\n"
                sendResponse(response)
                Log.d(tag, "Client PLAY $sessionId (video=$videoSetup, audio=$audioSetup)")
            }
            "PAUSE", "GET_PARAMETER", "SET_PARAMETER" -> {
                val response = "RTSP/1.0 200 OK\r\n" +
                        "CSeq: $cSeq\r\n" +
                        "Session: $sessionId\r\n\r\n"
                sendResponse(response)
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

        val sb = StringBuilder()
        sb.append("v=0\r\n")
        sb.append("o=- 0 0 IN IP4 $localIp\r\n")
        sb.append("s=VirtualCam ONVIF Live Stream\r\n")
        sb.append("c=IN IP4 $localIp\r\n")
        sb.append("t=0 0\r\n")
        sb.append("a=range:npt=now-\r\n")
        sb.append("m=video 0 RTP/AVP 96\r\n")
        sb.append("a=rtpmap:96 H264/90000\r\n")
        sb.append("a=fmtp:96 packetization-mode=1;profile-level-id=42001f;sprop-parameter-sets=$spsBase64,$ppsBase64\r\n")
        sb.append("a=control:trackID=0\r\n")

        if (isAudioEnabled()) {
            val audioCfg = getAudioConfigHex()
            sb.append("m=audio 0 RTP/AVP 97\r\n")
            sb.append("a=rtpmap:97 MPEG4-GENERIC/44100/1\r\n")
            sb.append("a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=$audioCfg\r\n")
            sb.append("a=control:trackID=1\r\n")
        }

        return sb.toString()
    }

    fun sendVideoRtpPacket(packet: ByteArray) {
        if (!isPlaying || !videoSetup) return
        sendTrackPacket(packet, videoIsTcp, videoTcpChannel, videoUdpSocket, videoClientUdpPort)
    }

    fun sendAudioRtpPacket(packet: ByteArray) {
        if (!isPlaying || !audioSetup) return
        sendTrackPacket(packet, audioIsTcp, audioTcpChannel, audioUdpSocket, audioClientUdpPort)
    }

    fun sendRtcpSrPacket(isAudio: Boolean, srPacket: ByteArray) {
        if (!isPlaying) return
        if (isAudio && audioSetup) {
            sendTrackPacket(srPacket, audioIsTcp, audioTcpChannel + 1, audioUdpSocket, audioClientUdpPort + 1)
        } else if (!isAudio && videoSetup) {
            sendTrackPacket(srPacket, videoIsTcp, videoTcpChannel + 1, videoUdpSocket, videoClientUdpPort + 1)
        }
    }

    private fun sendTrackPacket(
        packet: ByteArray,
        isTcp: Boolean,
        tcpCh: Int,
        udp: DatagramSocket?,
        udpPort: Int
    ) {
        try {
            if (isTcp) {
                synchronized(outputStream) {
                    val length = packet.size
                    outputStream.write('$'.code)
                    outputStream.write(tcpCh)
                    outputStream.write((length shr 8) and 0xFF)
                    outputStream.write(length and 0xFF)
                    outputStream.write(packet)
                    outputStream.flush()
                }
            } else {
                if (udp != null && udpPort > 0) {
                    val dPacket = DatagramPacket(packet, packet.size, clientAddress, udpPort)
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
        videoSetup = false
        audioSetup = false
        try { socket.close() } catch (ignored: Exception) {}
        try { videoUdpSocket?.close() } catch (ignored: Exception) {}
        try { audioUdpSocket?.close() } catch (ignored: Exception) {}
        videoUdpSocket = null
        audioUdpSocket = null
        onClose(this)
    }
}
