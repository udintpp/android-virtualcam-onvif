package com.virtualcam.onvif.source

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface

class VideoSourceEngine(private val context: Context) {

    private val tag = "VideoSourceEngine"
    private var mediaPlayer: MediaPlayer? = null
    private var currentSurface: Surface? = null
    private var videoUri: Uri? = null
    private var isLooping: Boolean = true

    fun setSurface(surface: Surface?) {
        currentSurface = surface
        try {
            mediaPlayer?.setSurface(surface)
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
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                isLooping = looping
                setVolume(0f, 0f) // Keep muted for virtual camera background stream
                currentSurface?.let { setSurface(it) }

                setOnPreparedListener { mp ->
                    Log.d(tag, "MediaPlayer prepared, starting playback")
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
            Log.e(tag, "Failed to start MediaPlayer for URI: $uri", e)
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
