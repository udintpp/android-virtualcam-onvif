package com.virtualcam.onvif.rtsp

import android.util.Log
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class RtspServer(
    private val localIp: String,
    private val port: Int = 8554,
    private val getSps: () -> ByteArray?,
    private val getPps: () -> ByteArray?,
    private val isAudioEnabled: () -> Boolean,
    private val getAudioConfigHex: () -> String
) {

    private val tag = "RtspServer"
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private val clientSessions = CopyOnWriteArrayList<RtspClientSession>()
    private val threadPool = Executors.newCachedThreadPool()
    private val videoPacketizer = H264RtpPacketizer(ssrc = 0x12345678)
    private val audioPacketizer = AacRtpPacketizer(ssrc = 0x12345679, payloadType = 97)

    private val videoPacketCount = AtomicInteger(0)
    private val videoOctetCount = AtomicInteger(0)
    private val audioPacketCount = AtomicInteger(0)
    private val audioOctetCount = AtomicInteger(0)

    @Volatile
    private var lastVideoPtsUs = 0L
    @Volatile
    private var lastAudioPtsUs = 0L
    @Volatile
    private var lastSrSentMs = 0L

    fun start() {
        stop()
        isRunning = true

        Thread({
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                Log.d(tag, "RTSP Server listening on $localIp:$port")

                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    Log.d(tag, "RTSP Client connected from ${socket.remoteSocketAddress}")

                    val session = RtspClientSession(
                        socket = socket,
                        localIp = localIp,
                        serverPort = port,
                        getSps = getSps,
                        getPps = getPps,
                        isAudioEnabled = isAudioEnabled,
                        getAudioConfigHex = getAudioConfigHex,
                        onClose = { deadSession ->
                            clientSessions.remove(deadSession)
                            Log.d(tag, "RTSP Session closed. Active clients: ${clientSessions.size}")
                        }
                    )
                    clientSessions.add(session)
                    threadPool.execute(session)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(tag, "RTSP Server exception", e)
                }
            }
        }, "RtspServerAcceptThread").start()
    }

    fun broadcastNalUnit(naluData: ByteArray, ptsUs: Long) {
        if (!isRunning || clientSessions.isEmpty()) return
        lastVideoPtsUs = ptsUs

        videoPacketizer.packetize(naluData, ptsUs) { rtpPacket ->
            videoPacketCount.incrementAndGet()
            videoOctetCount.addAndGet((rtpPacket.size - 12).coerceAtLeast(0))
            for (session in clientSessions) {
                if (session.isPlaying) {
                    session.sendVideoRtpPacket(rtpPacket)
                }
            }
        }
        maybeSendRtcpSenderReports()
    }

    fun broadcastAudioFrame(aacData: ByteArray, ptsUs: Long) {
        if (!isRunning || clientSessions.isEmpty()) return
        lastAudioPtsUs = ptsUs

        audioPacketizer.packetize(aacData, ptsUs) { rtpPacket ->
            audioPacketCount.incrementAndGet()
            audioOctetCount.addAndGet((rtpPacket.size - 12).coerceAtLeast(0))
            for (session in clientSessions) {
                if (session.isPlaying) {
                    session.sendAudioRtpPacket(rtpPacket)
                }
            }
        }
    }

    private fun maybeSendRtcpSenderReports() {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSrSentMs < 2000L) return
        lastSrSentMs = nowMs

        val videoRtpTs = (lastVideoPtsUs * 90L / 1000L).toInt()
        val videoSr = buildRtcpSr(
            ssrc = 0x12345678,
            ntpMs = nowMs,
            rtpTimestamp = videoRtpTs,
            packetCount = videoPacketCount.get(),
            octetCount = videoOctetCount.get()
        )

        val audioEnabled = isAudioEnabled()
        val audioSr = if (audioEnabled && lastAudioPtsUs > 0) {
            val audioRtpTs = (lastAudioPtsUs * 441L / 10_000L).toInt()
            buildRtcpSr(
                ssrc = 0x12345679,
                ntpMs = nowMs,
                rtpTimestamp = audioRtpTs,
                packetCount = audioPacketCount.get(),
                octetCount = audioOctetCount.get()
            )
        } else null

        for (session in clientSessions) {
            if (session.isPlaying) {
                session.sendRtcpSrPacket(isAudio = false, videoSr)
                if (audioSr != null) {
                    session.sendRtcpSrPacket(isAudio = true, audioSr)
                }
            }
        }
    }

    private fun buildRtcpSr(
        ssrc: Int,
        ntpMs: Long,
        rtpTimestamp: Int,
        packetCount: Int,
        octetCount: Int
    ): ByteArray {
        val sr = ByteArray(28)
        sr[0] = 0x80.toByte() // V=2, P=0, RC=0
        sr[1] = 200.toByte()  // PT=200 (Sender Report)
        sr[2] = 0
        sr[3] = 6             // Length = 6 (28 bytes / 4 - 1)

        writeInt32(sr, 4, ssrc)

        val ntpSec = (ntpMs / 1000L) + 2208988800L
        val ntpFrac = ((ntpMs % 1000L) * 0x100000000L) / 1000L
        writeInt32(sr, 8, ntpSec.toInt())
        writeInt32(sr, 12, ntpFrac.toInt())

        writeInt32(sr, 16, rtpTimestamp)
        writeInt32(sr, 20, packetCount)
        writeInt32(sr, 24, octetCount)
        return sr
    }

    private fun writeInt32(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value shr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        serverSocket = null

        for (session in clientSessions) {
            session.close()
        }
        clientSessions.clear()
    }
}
