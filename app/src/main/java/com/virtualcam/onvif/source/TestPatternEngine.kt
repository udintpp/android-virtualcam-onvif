package com.virtualcam.onvif.source

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TestPatternEngine {

    private val tag = "TestPatternEngine"
    private var renderThread: Thread? = null
    @Volatile
    private var isRunning = false

    private val colors = intArrayOf(
        Color.rgb(192, 192, 192), // White/Grey
        Color.rgb(192, 192, 0),   // Yellow
        Color.rgb(0, 192, 192),   // Cyan
        Color.rgb(0, 192, 0),     // Green
        Color.rgb(192, 0, 192),   // Magenta
        Color.rgb(192, 0, 0),     // Red
        Color.rgb(0, 0, 192)      // Blue
    )

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun start(surface: Surface, width: Int, height: Int, fps: Int = 30) {
        stop()
        isRunning = true

        renderThread = Thread({
            val barPaint = Paint()
            val numBars = colors.size
            val barWidth = width / numBars.toFloat()
            var boxX = 0f
            var boxSpeed = 6f
            val frameDelay = (1000 / fps).toLong()

            while (isRunning && surface.isValid) {
                var canvas: Canvas? = null
                try {
                    canvas = surface.lockCanvas(null)
                    if (canvas != null) {
                        // Draw SMPTE Color Bars
                        for (i in 0 until numBars) {
                            barPaint.color = colors[i]
                            canvas.drawRect(
                                i * barWidth,
                                0f,
                                (i + 1) * barWidth,
                                height.toFloat(),
                                barPaint
                            )
                        }

                        // Bottom black strip for clock
                        val bannerPaint = Paint().apply { color = Color.argb(180, 0, 0, 0) }
                        canvas.drawRect(0f, height - 120f, width.toFloat(), height.toFloat(), bannerPaint)

                        val timeStr = "VirtualCam | ${dateFormat.format(Date())}"
                        canvas.drawText(timeStr, 40f, height - 50f, textPaint)

                        // Draw bouncing moving square to verify live video feed
                        val boxPaint = Paint().apply { color = Color.WHITE }
                        canvas.drawRect(boxX, 100f, boxX + 60f, 160f, boxPaint)
                        boxX += boxSpeed
                        if (boxX > width - 60f || boxX < 0f) {
                            boxSpeed = -boxSpeed
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Render error: ${e.message}")
                    break
                } finally {
                    if (canvas != null) {
                        try {
                            surface.unlockCanvasAndPost(canvas)
                        } catch (e: Exception) {
                            // surface might be released
                        }
                    }
                }

                try {
                    Thread.sleep(frameDelay)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "TestPatternThread").apply { start() }
    }

    fun stop() {
        isRunning = false
        renderThread?.interrupt()
        renderThread = null
    }
}
