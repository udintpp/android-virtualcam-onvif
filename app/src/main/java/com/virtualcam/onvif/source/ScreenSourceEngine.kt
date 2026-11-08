package com.virtualcam.onvif.source

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface

class ScreenSourceEngine(private val context: Context) {

    private val tag = "ScreenSourceEngine"
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var currentSurface: Surface? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(tag, "MediaProjection session stopped by system")
            stop()
        }
    }

    fun start(
        projection: MediaProjection,
        surface: Surface,
        width: Int,
        height: Int
    ) {
        stop()
        this.mediaProjection = projection
        this.currentSurface = surface

        if (!surface.isValid) {
            Log.e(tag, "Provided surface is not valid")
            return
        }

        try {
            // Android 14+ requires registering callback before createVirtualDisplay
            projection.registerCallback(projectionCallback, mainHandler)

            // Safe context-independent density resolution (avoids WindowManager.defaultDisplay UnsupportedOperationException)
            val density = context.resources.displayMetrics.densityDpi

            virtualDisplay = projection.createVirtualDisplay(
                "VirtualCam_ScreenDisplay",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                mainHandler
            )
            Log.d(tag, "VirtualDisplay created successfully: ${width}x$height @ $density dpi")
        } catch (e: Exception) {
            Log.e(tag, "Failed to create VirtualDisplay", e)
        }
    }

    fun stop() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.e(tag, "Error releasing VirtualDisplay", e)
        } finally {
            virtualDisplay = null
        }

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (ignored: Exception) {}

        mediaProjection = null
        currentSurface = null
    }
}
