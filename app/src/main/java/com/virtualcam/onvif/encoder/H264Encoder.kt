package com.virtualcam.onvif.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    private val bitrate: Int = 2_500_000
) {

    private val tag = "H264Encoder"
    private var mediaCodec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    @Volatile
    private var isEncoding = false
    private var workerThread: Thread? = null

    var sps: ByteArray? = null
        private set
    var pps: ByteArray? = null
        private set

    var onNalUnitListener: ((data: ByteArray, isKeyFrame: Boolean, timestampUs: Long) -> Unit)? = null

    fun start() {
        stop()
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second keyframe interval
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            isEncoding = true
            workerThread = Thread({ processOutput() }, "H264EncoderThread").apply { start() }
            Log.d(tag, "H264Encoder started successfully: ${width}x$height @ $fps fps, $bitrate bps")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start H264Encoder", e)
        }
    }

    private fun processOutput() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isEncoding) {
            val codec = mediaCodec ?: break
            try {
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val outData = ByteArray(bufferInfo.size)
                        outputBuffer.get(outData)

                        // Check for CONFIG buffer (SPS / PPS)
                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        if (isConfig) {
                            parseSpsPps(outData)
                        } else {
                            onNalUnitListener?.invoke(outData, isKey, bufferInfo.presentationTimeUs)
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    val csd0 = newFormat.getByteBuffer("csd-0")
                    val csd1 = newFormat.getByteBuffer("csd-1")
                    if (csd0 != null) {
                        sps = ByteArray(csd0.remaining()).also { csd0.get(it) }
                    }
                    if (csd1 != null) {
                        pps = ByteArray(csd1.remaining()).also { csd1.get(it) }
                    }
                    Log.d(tag, "Output format changed. SPS=${sps?.size} bytes, PPS=${pps?.size} bytes")
                }
            } catch (e: Exception) {
                if (isEncoding) {
                    Log.e(tag, "Error processing encoder output", e)
                }
                break
            }
        }
    }

    private fun parseSpsPps(data: ByteArray) {
        // Look for NAL delimiters (0x00 0x00 0x00 0x01)
        val nalIndices = mutableListOf<Int>()
        for (i in 0 until data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                nalIndices.add(i + 4)
            }
        }

        for (idx in 0 until nalIndices.size) {
            val start = nalIndices[idx]
            val end = if (idx + 1 < nalIndices.size) nalIndices[idx + 1] - 4 else data.size
            val nalType = (data[start].toInt() and 0x1F)
            val nalData = data.copyOfRange(start, end)
            if (nalType == 7) {
                sps = nalData
                Log.d(tag, "Discovered SPS (size: ${sps?.size})")
            } else if (nalType == 8) {
                pps = nalData
                Log.d(tag, "Discovered PPS (size: ${pps?.size})")
            }
        }
    }

    fun stop() {
        isEncoding = false
        workerThread?.interrupt()
        workerThread = null

        try {
            mediaCodec?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error stopping MediaCodec", e)
        } finally {
            mediaCodec = null
            inputSurface = null
        }
    }
}
