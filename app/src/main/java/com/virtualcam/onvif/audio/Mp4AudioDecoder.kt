package com.virtualcam.onvif.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Mp4AudioDecoder(
    private val context: Context,
    private val uri: Uri,
    private val targetSampleRate: Int = 44100,
    private val isLooping: () -> Boolean,
    private val isVideoPlaying: () -> Boolean,
    private val getVideoPositionMs: () -> Int,
    private val ringBufferFreeSpace: () -> Int,
    private val clearRingBuffer: () -> Unit,
    private val onMonoSamples: (samples: ShortArray, length: Int) -> Unit
) {

    private val tag = "Mp4AudioDecoder"

    @Volatile
    private var isRunning = false
    private var decodeThread: Thread? = null

    fun start() {
        stop()
        isRunning = true
        decodeThread = Thread({ runDecodeLoop() }, "Mp4AudioDecodeThread").apply { start() }
    }

    private fun runDecodeLoop() {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var pfdToClose: android.os.ParcelFileDescriptor? = null

        try {
            extractor = MediaExtractor()
            var dataSourceSet = false
            try {
                extractor.setDataSource(context, uri, null)
                dataSourceSet = true
            } catch (e: Exception) {
                Log.w(tag, "Standard setDataSource failed, falling back to FD: ${e.message}")
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    pfdToClose = pfd
                    if (pfd.statSize > 0) {
                        extractor.setDataSource(pfd.fileDescriptor, 0, pfd.statSize)
                    } else {
                        extractor.setDataSource(pfd.fileDescriptor)
                    }
                    dataSourceSet = true
                }
            }

            if (!dataSourceSet) {
                Log.w(tag, "Could not open MP4 URI for audio extraction: $uri")
                return
            }

            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = fmt
                    break
                }
            }

            if (audioTrackIndex < 0 || trackFormat == null) {
                Log.i(tag, "No audio track found in MP4: $uri")
                return
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return

            var srcSampleRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            var srcChannels = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1
            var srcPcmEncoding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                trackFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
            ) {
                trackFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else AudioFormat.ENCODING_PCM_16BIT

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            Log.d(tag, "MP4 Audio Decoder started: mime=$mime, rate=$srcSampleRate, ch=$srcChannels")

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var resamplePhase = 0.0
            var lastMonoSample = 0.0f
            var lastSeekCheckTimeMs = System.currentTimeMillis()
            var lastAudioPtsMs = 0L

            while (isRunning) {
                // Wait cleanly while video player hasn't started / prepared yet
                if (!isVideoPlaying()) {
                    Thread.sleep(20)
                    continue
                }

                // Feed input buffers
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(5_000)
                    if (inputIndex >= 0) {
                        val inputBuf = codec.getInputBuffer(inputIndex)
                        if (inputBuf != null) {
                            inputBuf.clear()
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEos = true
                            } else {
                                val ptsUs = extractor.sampleTime
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, ptsUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // Drain output buffers
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 5_000)
                if (outputIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outputIndex)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        lastAudioPtsMs = bufferInfo.presentationTimeUs / 1000L

                        // Check A/V drift or loop reset
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastSeekCheckTimeMs > 250) {
                            lastSeekCheckTimeMs = nowMs
                            val videoPos = getVideoPositionMs()
                            if (videoPos >= 0 && (videoPos + 550 < lastAudioPtsMs || videoPos > lastAudioPtsMs + 750)) {
                                codec.releaseOutputBuffer(outputIndex, false)
                                clearRingBuffer()
                                extractor.seekTo(videoPos * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                codec.flush()
                                sawInputEos = false
                                sawOutputEos = false
                                lastAudioPtsMs = videoPos.toLong()
                                resamplePhase = 0.0
                                continue
                            }
                        }

                        // Convert PCM bytes -> Mono Float/Short samples
                        val monoInput = extractMonoSamples(outBuf, bufferInfo.size, srcChannels, srcPcmEncoding)
                        codec.releaseOutputBuffer(outputIndex, false)

                        // Resample if needed
                        val resampled: ShortArray
                        if (srcSampleRate == targetSampleRate) {
                            resampled = monoInput
                        } else {
                            val step = srcSampleRate.toDouble() / targetSampleRate.toDouble()
                            val outList = ArrayList<Short>((monoInput.size / step).toInt() + 4)
                            var p = resamplePhase
                            while (p < monoInput.size) {
                                val idx0 = p.toInt()
                                val frac = (p - idx0).toFloat()
                                val s0 = if (idx0 < 0) lastMonoSample else monoInput[idx0].toFloat()
                                val s1 = if (idx0 + 1 < monoInput.size) monoInput[idx0 + 1].toFloat() else s0
                                val interp = (s0 + frac * (s1 - s0)).toInt().coerceIn(-32768, 32767).toShort()
                                outList.add(interp)
                                p += step
                            }
                            resamplePhase = p - monoInput.size
                            if (monoInput.isNotEmpty()) {
                                lastMonoSample = monoInput[monoInput.size - 1].toFloat()
                            }
                            resampled = outList.toShortArray()
                        }

                        // Pace pushing to ring buffer so we don't decode ahead of playback
                        var offset = 0
                        while (isRunning && offset < resampled.size) {
                            val free = ringBufferFreeSpace()
                            if (free > 0) {
                                val chunk = minOf(free, resampled.size - offset)
                                val slice = if (offset == 0 && chunk == resampled.size) {
                                    resampled
                                } else {
                                    resampled.copyOfRange(offset, offset + chunk)
                                }
                                onMonoSamples(slice, chunk)
                                offset += chunk
                            } else {
                                Thread.sleep(5)
                            }
                        }
                    } else {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEos = true
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFmt = codec.outputFormat
                    if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        srcSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        srcChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        srcPcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    Log.d(tag, "Decoder format changed: rate=$srcSampleRate, ch=$srcChannels, enc=$srcPcmEncoding")
                }

                // Handle end-of-stream looping cleanly with VideoSourceEngine
                if (sawOutputEos) {
                    if (isLooping()) {
                        // Wait until video loops back near 0ms if video track is slightly longer
                        while (isRunning && isVideoPlaying() && getVideoPositionMs() > 800) {
                            Thread.sleep(20)
                        }
                        if (!isRunning) break
                        extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        codec.flush()
                        sawInputEos = false
                        sawOutputEos = false
                        resamplePhase = 0.0
                        lastAudioPtsMs = 0L
                    } else {
                        break
                    }
                }
            }
        } catch (e: InterruptedException) {
            // Stopped cleanly
        } catch (e: Exception) {
            if (isRunning) {
                Log.e(tag, "Error in Mp4AudioDecoder", e)
            }
        } finally {
            try { codec?.stop() } catch (ignored: Exception) {}
            try { codec?.release() } catch (ignored: Exception) {}
            try { extractor?.release() } catch (ignored: Exception) {}
            try { pfdToClose?.close() } catch (ignored: Exception) {}
        }
    }

    private fun extractMonoSamples(
        buf: ByteBuffer,
        sizeBytes: Int,
        channels: Int,
        pcmEncoding: Int
    ): ShortArray {
        val ch = if (channels <= 0) 1 else channels
        buf.order(ByteOrder.LITTLE_ENDIAN)

        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val totalFloats = sizeBytes / 4
                val frameCount = totalFloats / ch
                val out = ShortArray(frameCount)
                for (i in 0 until frameCount) {
                    var sum = 0f
                    for (c in 0 until ch) {
                        sum += buf.getFloat()
                    }
                    out[i] = ((sum / ch) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }
                out
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val frameCount = sizeBytes / ch
                val out = ShortArray(frameCount)
                for (i in 0 until frameCount) {
                    var sum = 0
                    for (c in 0 until ch) {
                        val b = buf.get().toInt() and 0xFF
                        sum += (b - 128) shl 8
                    }
                    out[i] = (sum / ch).coerceIn(-32768, 32767).toShort()
                }
                out
            }
            else -> { // 16-bit PCM
                val totalShorts = sizeBytes / 2
                val frameCount = totalShorts / ch
                val out = ShortArray(frameCount)
                for (i in 0 until frameCount) {
                    var sum = 0
                    for (c in 0 until ch) {
                        sum += buf.getShort().toInt()
                    }
                    out[i] = (sum / ch).coerceIn(-32768, 32767).toShort()
                }
                out
            }
        }
    }

    fun stop() {
        isRunning = false
        decodeThread?.interrupt()
        try {
            decodeThread?.join(300)
        } catch (ignored: Exception) {}
        decodeThread = null
    }
}
