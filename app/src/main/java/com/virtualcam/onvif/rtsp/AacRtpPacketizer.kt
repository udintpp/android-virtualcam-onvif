package com.virtualcam.onvif.rtsp

class AacRtpPacketizer(
    private val ssrc: Int = 0x12345679,
    private val payloadType: Int = 97
) {

    private var sequenceNumber = 0

    fun packetize(
        aacData: ByteArray,
        ptsUs: Long,
        sendPacket: (packet: ByteArray) -> Unit
    ) {
        val length = aacData.size
        if (length <= 0 || length > 8191) return // Max AU size for 13-bit sizelength

        // Timestamp in 44100 Hz clock units
        val rtpTimestamp = (ptsUs * 441L / 10_000L).toInt()

        // RFC 3640 AAC-hbr: 12 byte RTP header + 2 byte AU-headers-length + 2 byte AU-header + payload
        val packet = ByteArray(16 + length)

        // V=2, P=0, X=0, CC=0
        packet[0] = 0x80.toByte()
        // Marker=1 (each AAC frame is complete AU) | PT=97
        packet[1] = (0x80 or (payloadType and 0x7F)).toByte()

        // Sequence number
        packet[2] = ((sequenceNumber shr 8) and 0xFF).toByte()
        packet[3] = (sequenceNumber and 0xFF).toByte()
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF

        // Timestamp
        packet[4] = ((rtpTimestamp shr 24) and 0xFF).toByte()
        packet[5] = ((rtpTimestamp shr 16) and 0xFF).toByte()
        packet[6] = ((rtpTimestamp shr 8) and 0xFF).toByte()
        packet[7] = (rtpTimestamp and 0xFF).toByte()

        // SSRC
        packet[8] = ((ssrc shr 24) and 0xFF).toByte()
        packet[9] = ((ssrc shr 16) and 0xFF).toByte()
        packet[10] = ((ssrc shr 8) and 0xFF).toByte()
        packet[11] = (ssrc and 0xFF).toByte()

        // AU-headers-length = 16 bits (1 AU-header of 13+3 bits)
        packet[12] = 0x00.toByte()
        packet[13] = 0x10.toByte()

        // AU-header: top 13 bits = length, bottom 3 bits = AU-index (0)
        packet[14] = ((length shr 5) and 0xFF).toByte()
        packet[15] = ((length and 0x1F) shl 3).toByte()

        System.arraycopy(aacData, 0, packet, 16, length)
        sendPacket(packet)
    }
}
