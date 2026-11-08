package com.virtualcam.onvif.rtsp

import android.util.Log
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class RtspServer(
    private val localIp: String,
    private val port: Int = 8554,
    private val getSps: () -> ByteArray?,
    private val getPps: () -> ByteArray?
) {

    private val tag = "RtspServer"
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private val clientSessions = CopyOnWriteArrayList<RtspClientSession>()
    private val threadPool = Executors.newCachedThreadPool()
    private val rtpPacketizer = H264RtpPacketizer()

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

        rtpPacketizer.packetize(naluData, ptsUs) { rtpPacket ->
            for (session in clientSessions) {
                if (session.isPlaying) {
                    session.sendRtpPacket(rtpPacket)
                }
            }
        }
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
