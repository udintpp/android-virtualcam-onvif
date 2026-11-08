package com.virtualcam.onvif.onvif

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.UUID

class WsDiscoveryServer(
    private val context: Context,
    private val localIp: String,
    private val onvifPort: Int = 8080
) {

    private val tag = "WsDiscoveryServer"
    private var multicastSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile
    private var isRunning = false
    private val multicastGroup = "239.255.255.250"
    private val port = 3702

    fun start() {
        stop()
        isRunning = true

        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("VirtualCamWsDiscovery")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(tag, "Could not acquire MulticastLock", e)
        }

        Thread({
            try {
                val groupAddress = InetAddress.getByName(multicastGroup)
                multicastSocket = MulticastSocket(port).apply {
                    reuseAddress = true
                    joinGroup(groupAddress)
                }

                val buffer = ByteArray(4096)
                Log.d(tag, "WS-Discovery server started on $multicastGroup:$port")

                while (isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    multicastSocket?.receive(packet) ?: break

                    val requestXml = String(packet.data, 0, packet.length)
                    if (requestXml.contains("Probe", ignoreCase = true)) {
                        handleProbe(packet, requestXml)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(tag, "WS-Discovery socket exception", e)
                }
            }
        }, "WsDiscoveryThread").start()
    }

    private fun handleProbe(packet: DatagramPacket, requestXml: String) {
        val relatesTo = extractRelatesTo(requestXml)
        val messageId = "uuid:${UUID.randomUUID()}"
        val endpointId = "urn:uuid:550e8400-e29b-41d4-a716-446655440000"

        val responseXml = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
               xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
               xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
               xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
  <soap:Header>
    <wsa:MessageID>$messageId</wsa:MessageID>
    <wsa:RelatesTo>$relatesTo</wsa:RelatesTo>
    <wsa:To>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</wsa:To>
    <wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</wsa:Action>
  </soap:Header>
  <soap:Body>
    <d:ProbeMatches>
      <d:ProbeMatch>
        <wsa:EndpointReference>
          <wsa:Address>$endpointId</wsa:Address>
        </wsa:EndpointReference>
        <d:Types>dn:NetworkVideoTransmitter</d:Types>
        <d:Scopes>onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/hardware/AndroidVirtualCam onvif://www.onvif.org/name/VirtualCam-ONVIF</d:Scopes>
        <d:XAddrs>http://$localIp:$onvifPort/onvif/device_service</d:XAddrs>
        <d:MetadataVersion>1</d:MetadataVersion>
      </d:ProbeMatch>
    </d:ProbeMatches>
  </soap:Body>
</soap:Envelope>"""

        val data = responseXml.toByteArray()
        val replyPacket = DatagramPacket(data, data.size, packet.address, packet.port)
        try {
            multicastSocket?.send(replyPacket)
            Log.d(tag, "Replied ProbeMatches to ${packet.address}:${packet.port}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to send ProbeMatches", e)
        }
    }

    private fun extractRelatesTo(xml: String): String {
        val startTag = "<wsa:MessageID>"
        val endTag = "</wsa:MessageID>"
        val startIdx = xml.indexOf(startTag)
        if (startIdx != -1) {
            val endIdx = xml.indexOf(endTag, startIdx)
            if (endIdx != -1) {
                return xml.substring(startIdx + startTag.length, endIdx).trim()
            }
        }
        return "uuid:${UUID.randomUUID()}"
    }

    fun stop() {
        isRunning = false
        try {
            multicastSocket?.close()
        } catch (ignored: Exception) {}
        multicastSocket = null

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (ignored: Exception) {}
        multicastLock = null
    }
}
