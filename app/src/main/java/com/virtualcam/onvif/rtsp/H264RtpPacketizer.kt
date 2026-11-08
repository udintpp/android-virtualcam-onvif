package com.virtualcam.onvif.rtsp

import java.nio.ByteBuffer

class H264RtpPacketizer(private val ssrc: Int = 0x12345678) {

    private var sequenceNumber = 0
    private val maxPayloadSize = 1400

    fun packetize(
        naluData: ByteArray,
        ptsUs: Long,
        sendPacket: (packet: ByteArray) -> Unit
    ) {
        // Strip 3 or 4 byte start code (0x00 0x00 0x00 0x01 or 0x00 0x00 0x01)
        var offset = 0
        if (naluData.size >= 4 && naluData[0] == 0.toByte() && naluData[1] == 0.toByte() &&
            naluData[2] == 0.toByte() && naluData[3] == 1.toByte()
        ) {
            offset = 4
        } else if (naluData.size >= 3 && naluData[0] == 0.toByte() && naluData[1] == 0.toByte() &&
            naluData[2] == 1.toByte()
        ) {
            offset = 3
        }

        val length = naluData.size - offset
        if (length <= 0) return

        // Timestamp in 90kHz clock units
        val rtpTimestamp = (ptsUs * 90 / 1000).toInt()

        if (length <= maxPayloadSize) {
            // Single NAL unit packet
            val packet = ByteArray(12 + length)
            fillRtpHeader(packet, marker = true, timestamp = rtpTimestamp)
            System.arraycopy(naluData, offset, packet, 12, length)
            sendPacket(packet)
        } else {
            // FU-A Fragmentation Unit (RFC 6184)
            val nalHeader = naluData[offset]
            val nalType = (nalHeader.toInt() and 0x1F)
            val nalRefIdc = (nalHeader.toInt() and 0x60)

            var dataIndex = offset + 1
            var remaining = length - 1
            var isStart = true

            while (remaining > 0) {
                val chunkSize = if (remaining > maxPayloadSize) maxPayloadSize else remaining
                val isEnd = (chunkSize == remaining)

                val packet = ByteArray(12 + 2 + chunkSize)
                fillRtpHeader(packet, marker = isEnd, timestamp = rtpTimestamp)

                // FU indicator: NRI | Type 28 (FU-A)
                packet[12] = (nalRefIdc or 28).toByte()

                // FU header: S (start) | E (end) | R (0) | Type
                var fuHeader = nalType
                if (isStart) fuHeader = fuHeader or 0x80
                if (isEnd) fuHeader = fuHeader or 0x40
                packet[13] = fuHeader.toByte()

                System.arraycopy(naluData, dataIndex, packet, 14, chunkSize)
                sendPacket(packet)

                dataIndex += chunkSize
                remaining -= chunkSize
                isStart = false
            }
        }
    }

    private fun fillRtpHeader(buffer: ByteArray, marker: Boolean, timestamp: Int) {
        // V=2, P=0, X=0, CC=0 -> 0x80
        buffer[0] = 0x80.toByte()
        // M (marker bit) and PT=96 (H.264 dynamic payload type)
        buffer[1] = ((if (marker) 0x80 else 0x00) or 96).toByte()

        // Sequence number (16 bit)
        buffer[2] = ((sequenceNumber shr 8) and 0xFF).toByte()
        buffer[3] = (sequenceNumber and 0xFF).toByte()
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF

        // Timestamp (32 bit)
        buffer[4] = ((timestamp shr 24) and 0xFF).toByte()
        buffer[5] = ((timestamp shr 16) and 0xFF).toByte()
        buffer[6] = ((timestamp shr 8) and 0xFF).toByte()
        buffer[7] = (timestamp and 0xFF).toByte()

        // SSRC (32 bit)
        buffer[8] = ((ssrc shr 24) and 0xFF).toByte()
        buffer[9] = ((ssrc shr 16) and 0xFF).toByte()
        buffer[10] = ((ssrc shr 8) and 0xFF).toByte()
        buffer[11] = (ssrc and 0xFF).toByte()
    }
}
