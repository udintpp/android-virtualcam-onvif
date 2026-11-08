package com.virtualcam.onvif.source

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import java.io.File

class VideoSourceEngine(private val context: Context) {

    private val tag = "VideoSourceEngine"
    private var mediaPlayer: MediaPlayer? = null
    private var currentSurface: Surface? = null
    private var videoUri: Uri? = null
    private var isLooping: Boolean = true

    fun setSurface(surface: Surface?) {
        currentSurface = surface
        try {
            if (surface != null && surface.isValid) {
                mediaPlayer?.setSurface(surface)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error setting surface on MediaPlayer", e)
        }
    }

    fun start(uri: Uri?, looping: Boolean = true) {
        this.videoUri = uri
        this.isLooping = looping
        stop()

        if (uri == null) {
            Log.w(tag, "No URI provided to VideoSourceEngine")
            return
        }

        try {
            val surface = currentSurface
            mediaPlayer = MediaPlayer().apply {
                if (surface != null && surface.isValid) {
                    setSurface(surface)
                }

                // Robust data source setup: try FileDescriptor for content:// URIs
                if ("content".equals(uri.scheme, ignoreCase = true)) {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    if (pfd != null) {
                        setDataSource(pfd.fileDescriptor)
                    } else {
                        setDataSource(context, uri)
                    }
                } else if ("file".equals(uri.scheme, ignoreCase = true)) {
                    val path = uri.path
                    if (path != null && File(path).exists()) {
                        setDataSource(path)
                    } else {
                        setDataSource(context, uri)
                    }
                } else {
                    setDataSource(context, uri)
                }

                this.isLooping = looping
                setVolume(0f, 0f) // Keep muted for background camera feed

                setOnPreparedListener { mp ->
                    Log.d(tag, "MediaPlayer prepared (${mp.videoWidth}x${mp.videoHeight}), starting playback")
                    mp.start()
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(tag, "MediaPlayer error: what=$what, extra=$extra")
                    true
                }

                setOnCompletionListener { mp ->
                    if (isLooping) {
                        mp.seekTo(0)
                        mp.start()
                    }
                }

                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize MediaPlayer for URI: $uri", e)
        }
    }

    fun stop() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error stopping MediaPlayer", e)
        } finally {
            mediaPlayer = null
        }
    }
}
