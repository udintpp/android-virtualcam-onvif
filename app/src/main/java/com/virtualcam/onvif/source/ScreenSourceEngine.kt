package com.virtualcam.onvif.source

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager

class ScreenSourceEngine(private val context: Context) {

    private val tag = "ScreenSourceEngine"
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var currentSurface: Surface? = null

    fun start(
        projection: MediaProjection,
        surface: Surface,
        width: Int,
        height: Int
    ) {
        stop()
        this.mediaProjection = projection
        this.currentSurface = surface

        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
            val density = metrics.densityDpi

            virtualDisplay = projection.createVirtualDisplay(
                "VirtualCam_ScreenDisplay",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
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
        mediaProjection = null
        currentSurface = null
    }
}
