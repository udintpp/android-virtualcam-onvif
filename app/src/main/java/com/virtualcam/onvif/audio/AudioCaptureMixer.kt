package com.virtualcam.onvif.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.virtualcam.onvif.util.Prefs

class ShortRingBuffer(private val capacity: Int) {
    private val buffer = ShortArray(capacity)
    private var readPos = 0
    private var writePos = 0
    private var count = 0

    @Synchronized
    fun write(data: ShortArray, length: Int) {
        for (i in 0 until length) {
            if (count == capacity) {
                // Overwrite oldest sample if full to maintain live latency
                readPos = (readPos + 1) % capacity
                count--
            }
            buffer[writePos] = data[i]
            writePos = (writePos + 1) % capacity
            count++
        }
    }

    @Synchronized
    fun read(dest: ShortArray, length: Int): Int {
        val toRead = minOf(length, count)
        for (i in 0 until toRead) {
            dest[i] = buffer[readPos]
            readPos = (readPos + 1) % capacity
        }
        count -= toRead
        // Pad remaining with silence if underrun
        for (i in toRead until length) {
            dest[i] = 0
        }
        return toRead
    }

    @Synchronized
    fun freeSpace(): Int = capacity - count

    @Synchronized
    fun available(): Int = count

    @Synchronized
    fun clear() {
        readPos = 0
        writePos = 0
        count = 0
    }
}

class AudioCaptureMixer(
    private val context: Context,
    private val sampleRate: Int = 44100,
    private val isVideoPlaying: () -> Boolean,
    private val getVideoPositionMs: () -> Int
) {

    private val tag = "AudioCaptureMixer"
    private val micBuffer = ShortRingBuffer(sampleRate / 2)   // 500ms buffer
    private val mediaBuffer = ShortRingBuffer(sampleRate / 2) // 500ms buffer

    @Volatile
    private var isRunning = false
    val isActive: Boolean
        get() = isRunning
    private var mixerThread: Thread? = null

    @Volatile
    private var currentAudioMode = Prefs.AUDIO_NONE
    @Volatile
    private var currentSourceMode = Prefs.SOURCE_CAMERA_BACK
    private var currentVideoUri: Uri? = null
    private var currentIsLooping = true
    private var currentProjection: MediaProjection? = null

    // Capture workers
    @Volatile
    private var isMicRunning = false
    private var micThread: Thread? = null

    @Volatile
    private var isScreenAudioRunning = false
    private var screenAudioThread: Thread? = null

    private var mp4AudioDecoder: Mp4AudioDecoder? = null
    private var activeMediaKey: String? = null

    var onPcmFrameListener: ((pcmBytes: ByteArray, ptsUs: Long) -> Unit)? = null

    fun start(
        audioMode: Int,
        sourceMode: Int,
        videoUri: Uri?,
        isLooping: Boolean,
        mediaProjection: MediaProjection?
    ) {
        stop()
        if (audioMode == Prefs.AUDIO_NONE) return

        currentAudioMode = audioMode
        currentSourceMode = sourceMode
        currentVideoUri = videoUri
        currentIsLooping = isLooping
        currentProjection = mediaProjection

        isRunning = true
        micBuffer.clear()
        mediaBuffer.clear()

        reconfigure(audioMode, sourceMode, videoUri, isLooping, mediaProjection)
        mixerThread = Thread({ runMixerLoop() }, "AudioMixerThread").apply { start() }
        Log.d(tag, "AudioCaptureMixer started (audioMode=$audioMode, sourceMode=$sourceMode)")
    }

    @Synchronized
    fun reconfigure(
        audioMode: Int,
        sourceMode: Int,
        videoUri: Uri?,
        isLooping: Boolean,
        mediaProjection: MediaProjection?
    ) {
        currentAudioMode = audioMode
        currentSourceMode = sourceMode
        currentVideoUri = videoUri
        currentIsLooping = isLooping
        currentProjection = mediaProjection

        if (!isRunning) return

        val wantMic = (audioMode == Prefs.AUDIO_MIC || audioMode == Prefs.AUDIO_BOTH)
        val wantMedia = (audioMode == Prefs.AUDIO_MEDIA || audioMode == Prefs.AUDIO_BOTH)

        // 1. Microphone source management
        if (wantMic) {
            if (!isMicRunning) {
                startMicCapture()
            }
        } else {
            stopMicCapture()
        }

        // 2. Media source management (MP4 file OR Screen Capture)
        val desiredMediaKey: String? = if (wantMedia) {
            when (sourceMode) {
                Prefs.SOURCE_VIDEO -> if (videoUri != null) "VIDEO:$videoUri" else null
                Prefs.SOURCE_SCREEN -> if (mediaProjection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "SCREEN:${System.identityHashCode(mediaProjection)}"
                } else null
                else -> null
            }
        } else null

        if (desiredMediaKey != activeMediaKey) {
            stopMp4Decoder()
            stopScreenAudioCapture()
            mediaBuffer.clear()
            activeMediaKey = desiredMediaKey

            if (wantMedia) {
                when (sourceMode) {
                    Prefs.SOURCE_VIDEO -> {
                        if (videoUri != null) {
                            mp4AudioDecoder = Mp4AudioDecoder(
                                context = context,
                                uri = videoUri,
                                targetSampleRate = sampleRate,
                                isLooping = { currentIsLooping },
                                isVideoPlaying = isVideoPlaying,
                                getVideoPositionMs = getVideoPositionMs,
                                ringBufferFreeSpace = { mediaBuffer.freeSpace() },
                                clearRingBuffer = { mediaBuffer.clear() },
                                onMonoSamples = { samples, len -> mediaBuffer.write(samples, len) }
                            ).apply { start() }
                        }
                    }
                    Prefs.SOURCE_SCREEN -> {
                        if (mediaProjection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            startScreenAudioCapture(mediaProjection)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startMicCapture() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(tag, "RECORD_AUDIO permission not granted; microphone audio skipped")
            return
        }
        stopMicCapture()
        isMicRunning = true
        micThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var record: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufSize = maxOf(minBuf * 2, 4096)
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(tag, "Mic AudioRecord failed to initialize")
                    return@Thread
                }
                record.startRecording()
                Log.d(tag, "Mic AudioRecord started")

                val temp = ShortArray(1024)
                while (isMicRunning && isRunning) {
                    val read = record.read(temp, 0, temp.size)
                    if (read > 0) {
                        micBuffer.write(temp, read)
                    } else if (read < 0) {
                        Thread.sleep(10)
                    }
                }
            } catch (e: Exception) {
                if (isMicRunning) Log.e(tag, "Mic capture error", e)
            } finally {
                try { record?.stop() } catch (ignored: Exception) {}
                try { record?.release() } catch (ignored: Exception) {}
            }
        }, "MicAudioCaptureThread").apply { start() }
    }

    private fun stopMicCapture() {
        isMicRunning = false
        micThread?.interrupt()
        try { micThread?.join(250) } catch (ignored: Exception) {}
        micThread = null
        micBuffer.clear()
    }

    @SuppressLint("MissingPermission")
    private fun startScreenAudioCapture(projection: MediaProjection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(tag, "RECORD_AUDIO permission required for AudioPlaybackCapture; screen audio skipped")
            return
        }
        stopScreenAudioCapture()
        isScreenAudioRunning = true
        screenAudioThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var record: AudioRecord? = null
            try {
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()

                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufSize = maxOf(minBuf * 2, 4096)

                record = AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(tag, "PlaybackCapture AudioRecord failed to initialize")
                    return@Thread
                }
                record.startRecording()
                Log.d(tag, "Screen AudioPlaybackCapture started")

                val temp = ShortArray(1024)
                while (isScreenAudioRunning && isRunning) {
                    val read = record.read(temp, 0, temp.size)
                    if (read > 0) {
                        mediaBuffer.write(temp, read)
                    } else if (read < 0) {
                        Thread.sleep(10)
                    }
                }
            } catch (e: Exception) {
                if (isScreenAudioRunning) Log.e(tag, "Screen AudioPlaybackCapture error", e)
            } finally {
                try { record?.stop() } catch (ignored: Exception) {}
                try { record?.release() } catch (ignored: Exception) {}
            }
        }, "ScreenAudioCaptureThread").apply { start() }
    }

    private fun stopScreenAudioCapture() {
        isScreenAudioRunning = false
        screenAudioThread?.interrupt()
        try { screenAudioThread?.join(250) } catch (ignored: Exception) {}
        screenAudioThread = null
    }

    private fun stopMp4Decoder() {
        mp4AudioDecoder?.stop()
        mp4AudioDecoder = null
    }

    private fun runMixerLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val frameSamples = 1024
        val micChunk = ShortArray(frameSamples)
        val mediaChunk = ShortArray(frameSamples)
        val pcmBytes = ByteArray(frameSamples * 2)

        var anchorNs = System.nanoTime()
        var frameCount = 0L

        while (isRunning) {
            val targetNs = anchorNs + (frameCount * frameSamples.toLong() * 1_000_000_000L / sampleRate.toLong())
            val sleepNs = targetNs - System.nanoTime()

            if (sleepNs > 500_000L) {
                try {
                    Thread.sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt())
                } catch (e: InterruptedException) {
                    break
                }
            } else if (sleepNs < -500_000_000L) {
                // Reset pacing if thread fell significantly behind (>500ms)
                anchorNs = System.nanoTime() - (frameCount * frameSamples.toLong() * 1_000_000_000L / sampleRate.toLong())
            }

            if (!isRunning) break

            val mode = currentAudioMode
            val useMedia = (mode == Prefs.AUDIO_MEDIA || mode == Prefs.AUDIO_BOTH)
            val useMic = (mode == Prefs.AUDIO_MIC || mode == Prefs.AUDIO_BOTH)

            if (useMedia) {
                mediaBuffer.read(mediaChunk, frameSamples)
            }
            if (useMic) {
                micBuffer.read(micChunk, frameSamples)
            }

            for (i in 0 until frameSamples) {
                val sampleInt = when {
                    useMedia && useMic -> (mediaChunk[i].toInt() + micChunk[i].toInt()).coerceIn(-32768, 32767)
                    useMedia -> mediaChunk[i].toInt()
                    useMic -> micChunk[i].toInt()
                    else -> 0
                }
                val s = sampleInt.toShort()
                pcmBytes[i * 2] = (s.toInt() and 0xFF).toByte()
                pcmBytes[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
            }

            val ptsUs = (anchorNs / 1000L) + ((frameCount * frameSamples.toLong() * 1_000_000L) / sampleRate.toLong())
            onPcmFrameListener?.invoke(pcmBytes, ptsUs)
            frameCount++
        }
    }

    fun stop() {
        isRunning = false
        stopMicCapture()
        stopScreenAudioCapture()
        stopMp4Decoder()
        activeMediaKey = null

        mixerThread?.interrupt()
        try { mixerThread?.join(300) } catch (ignored: Exception) {}
        mixerThread = null

        micBuffer.clear()
        mediaBuffer.clear()
    }
}
