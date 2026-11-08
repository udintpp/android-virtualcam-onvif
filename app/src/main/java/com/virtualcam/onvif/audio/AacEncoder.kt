package com.virtualcam.onvif.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

class AacEncoder(
    val sampleRate: Int = 44100,
    val channelCount: Int = 1,
    val bitrate: Int = 64_000
) {

    private val tag = "AacEncoder"
    private var mediaCodec: MediaCodec? = null

    @Volatile
    private var isEncoding = false
    private var outputThread: Thread? = null
    private val inputLock = Any()

    // Default AudioSpecificConfig for AAC-LC, 44100 Hz (index 4), 1 ch (Mono): 0x12 0x08
    @Volatile
    var audioSpecificConfig: ByteArray = byteArrayOf(0x12, 0x08)
        private set

    var onAacFrameListener: ((aacData: ByteArray, ptsUs: Long) -> Unit)? = null

    fun start() {
        stop()
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            isEncoding = true
            outputThread = Thread({ processOutput() }, "AacEncoderOutThread").apply { start() }
            Log.d(tag, "AacEncoder started: ${sampleRate}Hz, ${channelCount}ch, ${bitrate}bps")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start AacEncoder", e)
            isEncoding = false
        }
    }

    fun encodePcm(pcmData: ByteArray, ptsUs: Long) {
        if (!isEncoding) return
        synchronized(inputLock) {
            if (!isEncoding) return
            val codec = mediaCodec ?: return
            try {
                val inputIndex = codec.dequeueInputBuffer(5_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        val toCopy = minOf(pcmData.size, inputBuffer.remaining())
                        inputBuffer.put(pcmData, 0, toCopy)
                        codec.queueInputBuffer(inputIndex, 0, toCopy, ptsUs, 0)
                    }
                }
            } catch (e: Exception) {
                if (isEncoding) {
                    Log.w(tag, "Error queueing PCM input to AacEncoder: ${e.message}")
                }
            }
        }
    }

    private fun processOutput() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isEncoding) {
            val codec = mediaCodec ?: break
            try {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val outData = ByteArray(bufferInfo.size)
                        outputBuffer.get(outData)

                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (isConfig) {
                            audioSpecificConfig = outData
                            Log.d(tag, "Captured AAC AudioSpecificConfig: ${getAudioSpecificConfigHex()}")
                        } else {
                            onAacFrameListener?.invoke(outData, bufferInfo.presentationTimeUs)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val fmt = codec.outputFormat
                    val csd0: ByteBuffer? = fmt.getByteBuffer("csd-0")
                    if (csd0 != null && csd0.remaining() > 0) {
                        val cfg = ByteArray(csd0.remaining())
                        csd0.get(cfg)
                        audioSpecificConfig = cfg
                        Log.d(tag, "Output format changed, AAC config=${getAudioSpecificConfigHex()}")
                    }
                }
            } catch (e: Exception) {
                if (isEncoding) {
                    Log.e(tag, "Error processing AAC encoder output", e)
                }
                break
            }
        }
    }

    fun getAudioSpecificConfigHex(): String {
        return audioSpecificConfig.joinToString("") { "%02x".format(it) }
    }

    fun addAdtsHeader(rawAac: ByteArray): ByteArray {
        val packetLen = rawAac.size + 7
        val packet = ByteArray(packetLen)

        val profile = 2 // AAC LC
        val freqIdx = when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000 -> 11
            else -> 4
        }
        val chanCfg = channelCount

        packet[0] = 0xFF.toByte()
        packet[1] = 0xF1.toByte()
        packet[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        packet[3] = (((chanCfg and 3) shl 6) + (packetLen shr 11)).toByte()
        packet[4] = ((packetLen and 0x7FF) shr 3).toByte()
        packet[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
        packet[6] = 0xFC.toByte()

        System.arraycopy(rawAac, 0, packet, 7, rawAac.size)
        return packet
    }

    fun stop() {
        isEncoding = false
        outputThread?.interrupt()
        try {
            outputThread?.join(300)
        } catch (ignored: Exception) {}
        outputThread = null

        synchronized(inputLock) {
            try {
                mediaCodec?.apply {
                    stop()
                    release()
                }
            } catch (e: Exception) {
                Log.w(tag, "Error stopping AacEncoder: ${e.message}")
            } finally {
                mediaCodec = null
            }
        }
    }
}
