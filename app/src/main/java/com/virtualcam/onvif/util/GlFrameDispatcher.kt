package com.virtualcam.onvif.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors

class GlFrameDispatcher(
    private val defaultWidth: Int = 1280,
    private val defaultHeight: Int = 720
) {

    private val tag = "GlFrameDispatcher"

    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var previewEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var previewSurface: Surface? = null
    private var encoderSurface: Surface? = null
    private var previewWidth = defaultWidth
    private var previewHeight = defaultHeight
    private var encoderWidth = defaultWidth
    private var encoderHeight = defaultHeight

    // Source geometry and aspect ratio correction
    @Volatile
    var sourceWidth: Int = defaultWidth
        private set
    @Volatile
    var sourceHeight: Int = defaultHeight
        private set
    @Volatile
    var sourceRotation: Int = 0
        private set
    @Volatile
    var sourceMirror: Boolean = false
        private set
    @Volatile
    var scaleMode: Int = 0 // 0 = FIT, 1 = CROP
        private set

    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    var inputSurface: Surface? = null
        private set

    // Test Pattern Generator Mode
    @Volatile
    private var isTestPatternMode = false
    private var patternTick = 0
    private val patternRunnable = object : Runnable {
        override fun run() {
            if (isTestPatternMode && renderHandler != null) {
                drawPatternFrame()
                renderHandler?.postDelayed(this, 33) // ~30 FPS
            }
        }
    }

    // MJPEG Web Streaming capture
    @Volatile
    var latestJpegBytes: ByteArray? = null
        private set
    @Volatile
    var hasActiveWebClients: Boolean = false

    private val mjpegExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var isCompressing = false
    private var lastMjpegCaptureTime = 0L

    private var pixelBuffer: ByteBuffer? = null

    private var program = 0
    private var aPositionLoc = 0
    private var aTextureCoordLoc = 0
    private var uMvpMatrixLoc = 0
    private var uTexMatrixLoc = 0
    private var uTextureLoc = 0

    private val transformMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Full screen quad vertices
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    private val texCoordBuffer: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply {
            put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
            position(0)
        }

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        uniform mat4 uMvpMatrix;
        uniform mat4 uTexMatrix;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = uMvpMatrix * aPosition;
            vTextureCoord = (uTexMatrix * aTextureCoord).xy;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vTextureCoord);
        }
    """.trimIndent()

    fun start() {
        stop()

        renderThread = HandlerThread("GlRenderThread").apply {
            start()
            renderHandler = Handler(looper)
        }

        val initLatch = java.util.concurrent.CountDownLatch(1)
        renderHandler?.post {
            try {
                initEgl()
                initGl()

                textureId = createOesTexture()
                surfaceTexture = SurfaceTexture(textureId).apply {
                    setDefaultBufferSize(defaultWidth, defaultHeight)
                    setOnFrameAvailableListener({
                        renderHandler?.post {
                            if (!isTestPatternMode) {
                                drawFrames()
                            }
                        }
                    }, renderHandler)
                }
                inputSurface = Surface(surfaceTexture)
                Log.d(tag, "GlFrameDispatcher initialized with inputSurface: $inputSurface")
            } catch (e: Exception) {
                Log.e(tag, "Error during GlFrameDispatcher init", e)
            } finally {
                initLatch.countDown()
            }
        }

        try {
            initLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (ignored: Exception) {}
    }

    fun setSourceGeometry(width: Int, height: Int, rotation: Int, mirror: Boolean, mode: Int = 0) {
        sourceWidth = if (width > 0) width else defaultWidth
        sourceHeight = if (height > 0) height else defaultHeight
        sourceRotation = ((rotation % 360) + 360) % 360
        sourceMirror = mirror
        scaleMode = mode
        Log.d(tag, "Source geometry updated: ${sourceWidth}x${sourceHeight}, rot=$sourceRotation, mirror=$sourceMirror, mode=$scaleMode")
    }

    fun setTestPatternMode(enabled: Boolean) {
        renderHandler?.post {
            isTestPatternMode = enabled
            if (enabled) {
                renderHandler?.removeCallbacks(patternRunnable)
                renderHandler?.post(patternRunnable)
            } else {
                renderHandler?.removeCallbacks(patternRunnable)
            }
        }
    }

    fun setPreviewSurface(surface: Surface?, width: Int = defaultWidth, height: Int = defaultHeight) {
        renderHandler?.post {
            previewSurface = surface
            previewWidth = if (width > 0) width else defaultWidth
            previewHeight = if (height > 0) height else defaultHeight

            if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
                previewEglSurface = EGL14.EGL_NO_SURFACE
            }

            if (surface != null && surface.isValid) {
                val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                previewEglSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, surface, surfaceAttribs, 0
                )
                Log.d(tag, "Preview EGL surface created: ${previewWidth}x$previewHeight")
            }
        }
    }

    fun setEncoderSurface(surface: Surface?, width: Int = defaultWidth, height: Int = defaultHeight) {
        renderHandler?.post {
            encoderSurface = surface
            encoderWidth = if (width > 0) width else defaultWidth
            encoderHeight = if (height > 0) height else defaultHeight

            if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
                encoderEglSurface = EGL14.EGL_NO_SURFACE
            }

            if (surface != null && surface.isValid) {
                val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                encoderEglSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, surface, surfaceAttribs, 0
                )
                Log.d(tag, "Encoder EGL surface created: ${encoderWidth}x$encoderHeight")
            }
        }
    }

    private fun computeMvpMatrix(targetW: Int, targetH: Int) {
        android.opengl.Matrix.setIdentityM(mvpMatrix, 0)

        val srcW = if (sourceWidth > 0) sourceWidth else targetW
        val srcH = if (sourceHeight > 0) sourceHeight else targetH
        val rot = ((sourceRotation % 360) + 360) % 360

        // Effective dimensions of content in upright orientation:
        val effW = if (rot == 90 || rot == 270) srcH else srcW
        val effH = if (rot == 90 || rot == 270) srcW else srcH

        val contentAspect = effW.toFloat() / effH.toFloat()
        val viewAspect = targetW.toFloat() / targetH.toFloat()

        var scaleX = 1.0f
        var scaleY = 1.0f

        if (scaleMode == 1) { // CROP: fill viewport, maintain proportions
            if (contentAspect > viewAspect) {
                scaleX = contentAspect / viewAspect
            } else {
                scaleY = viewAspect / contentAspect
            }
        } else { // FIT: whole frame visible, maintain proportions (letterbox/pillarbox)
            if (contentAspect > viewAspect) {
                scaleY = viewAspect / contentAspect
            } else {
                scaleX = contentAspect / viewAspect
            }
        }

        // 1. Scale quad in target viewport
        android.opengl.Matrix.scaleM(mvpMatrix, 0, scaleX, scaleY, 1.0f)

        // 2. Rotate to upright orientation
        if (rot != 0) {
            android.opengl.Matrix.rotateM(mvpMatrix, 0, rot.toFloat(), 0f, 0f, 1f)
        }

        // 3. Mirror horizontally if front selfie camera
        if (sourceMirror) {
            android.opengl.Matrix.scaleM(mvpMatrix, 0, -1.0f, 1.0f, 1.0f)
        }
    }

    private fun drawFrames() {
        val st = surfaceTexture ?: return

        // Ensure an active surface is current BEFORE calling updateTexImage
        val currentTargetSurface = when {
            encoderEglSurface != EGL14.EGL_NO_SURFACE -> encoderEglSurface
            previewEglSurface != EGL14.EGL_NO_SURFACE -> previewEglSurface
            pbufferSurface != EGL14.EGL_NO_SURFACE -> pbufferSurface
            else -> EGL14.EGL_NO_SURFACE
        }
        if (currentTargetSurface == EGL14.EGL_NO_SURFACE) return

        EGL14.eglMakeCurrent(eglDisplay, currentTargetSurface, currentTargetSurface, eglContext)

        try {
            st.updateTexImage()
            st.getTransformMatrix(transformMatrix)
        } catch (e: Exception) {
            Log.e(tag, "Error during updateTexImage", e)
            return
        }

        val frameTimeNs = st.timestamp

        // 1. Render to Preview (Phone Display)
        if (previewEglSurface != EGL14.EGL_NO_SURFACE && previewSurface?.isValid == true) {
            EGL14.eglMakeCurrent(eglDisplay, previewEglSurface, previewEglSurface, eglContext)
            GLES20.glViewport(0, 0, previewWidth, previewHeight)
            drawQuad(previewWidth, previewHeight)
            captureMjpegFrameIfNeeded(previewWidth, previewHeight)
            EGL14.eglSwapBuffers(eglDisplay, previewEglSurface)
        }

        // 2. Render to Encoder (H.264 RTSP)
        if (encoderEglSurface != EGL14.EGL_NO_SURFACE && encoderSurface?.isValid == true) {
            EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)
            GLES20.glViewport(0, 0, encoderWidth, encoderHeight)
            drawQuad(encoderWidth, encoderHeight)
            if (previewEglSurface == EGL14.EGL_NO_SURFACE) {
                captureMjpegFrameIfNeeded(encoderWidth, encoderHeight)
            }
            EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, frameTimeNs)
            EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)
        }
    }

    private fun drawPatternFrame() {
        patternTick++
        val nowNs = System.nanoTime()

        // 1. Render to Preview
        if (previewEglSurface != EGL14.EGL_NO_SURFACE && previewSurface?.isValid == true) {
            EGL14.eglMakeCurrent(eglDisplay, previewEglSurface, previewEglSurface, eglContext)
            GLES20.glViewport(0, 0, previewWidth, previewHeight)
            renderColorBars(previewWidth, previewHeight, patternTick)
            captureMjpegFrameIfNeeded(previewWidth, previewHeight)
            EGL14.eglSwapBuffers(eglDisplay, previewEglSurface)
        }

        // 2. Render to Encoder
        if (encoderEglSurface != EGL14.EGL_NO_SURFACE && encoderSurface?.isValid == true) {
            EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)
            GLES20.glViewport(0, 0, encoderWidth, encoderHeight)
            renderColorBars(encoderWidth, encoderHeight, patternTick)
            if (previewEglSurface == EGL14.EGL_NO_SURFACE) {
                captureMjpegFrameIfNeeded(encoderWidth, encoderHeight)
            }
            EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, nowNs)
            EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)
        }
    }

    private fun renderColorBars(width: Int, height: Int, tick: Int) {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        val barColors = arrayOf(
            floatArrayOf(0.75f, 0.75f, 0.75f), // White/Grey
            floatArrayOf(0.75f, 0.75f, 0f),     // Yellow
            floatArrayOf(0f, 0.75f, 0.75f),     // Cyan
            floatArrayOf(0f, 0.75f, 0f),        // Green
            floatArrayOf(0.75f, 0f, 0.75f),     // Magenta
            floatArrayOf(0.75f, 0f, 0f),        // Red
            floatArrayOf(0f, 0f, 0.75f)         // Blue
        )
        val barW = width / barColors.size
        for (i in barColors.indices) {
            val c = barColors[i]
            GLES20.glScissor(i * barW, (height * 0.25).toInt(), barW + 1, (height * 0.75).toInt())
            GLES20.glClearColor(c[0], c[1], c[2], 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }

        // Moving box at bottom
        val boxX = ((tick * 8) % width)
        GLES20.glScissor(boxX, (height * 0.05).toInt(), 60, (height * 0.15).toInt())
        GLES20.glClearColor(1f, 1f, 1f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    private fun captureMjpegFrameIfNeeded(w: Int, h: Int) {
        val now = System.currentTimeMillis()
        if (!hasActiveWebClients || isCompressing || (now - lastMjpegCaptureTime < 45L)) {
            return
        }
        lastMjpegCaptureTime = now
        isCompressing = true

        if (pixelBuffer == null || pixelBuffer?.capacity() != w * h * 4) {
            pixelBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        }
        val buf = pixelBuffer ?: return
        buf.position(0)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)

        mjpegExecutor.execute {
            try {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                buf.position(0)
                bmp.copyPixelsFromBuffer(buf)

                // Downsample to 640x360 and flip vertically (OpenGL Y-inversion)
                val targetW = 640
                val targetH = 360
                val matrix = Matrix().apply {
                    postScale(targetW.toFloat() / w, -targetH.toFloat() / h)
                }
                val finalBmp = Bitmap.createBitmap(bmp, 0, 0, w, h, matrix, true)

                val stream = ByteArrayOutputStream()
                finalBmp.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                latestJpegBytes = stream.toByteArray()

                bmp.recycle()
                if (finalBmp != bmp) finalBmp.recycle()
            } catch (e: Exception) {
                Log.e(tag, "Error compressing MJPEG", e)
            } finally {
                isCompressing = false
            }
        }
    }

    private fun drawQuad(targetW: Int, targetH: Int) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTextureLoc, 0)

        computeMvpMatrix(targetW, targetH)
        GLES20.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, transformMatrix, 0)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(aTextureCoordLoc)
        GLES20.glVertexAttribPointer(aTextureCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTextureCoordLoc)
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val eglRecordableAndroid = 0x3142
        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            eglRecordableAndroid, 1,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        if (numConfigs[0] == 0 || configs[0] == null) {
            val fallbackAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            EGL14.eglChooseConfig(eglDisplay, fallbackAttribs, 0, configs, 0, 1, numConfigs, 0)
        }
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        // Make dummy pbuffer current to init shaders and act as fallback surface
        val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
    }

    private fun initGl() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureCoordLoc = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMvpMatrixLoc = GLES20.glGetUniformLocation(program, "uMvpMatrix")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
    }

    private fun createOesTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val id = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }

    fun stop() {
        hasActiveWebClients = false
        isTestPatternMode = false
        val handler = renderHandler
        handler?.removeCallbacks(patternRunnable)
        handler?.post {
            if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
                previewEglSurface = EGL14.EGL_NO_SURFACE
            }
            if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
                encoderEglSurface = EGL14.EGL_NO_SURFACE
            }
            if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
                pbufferSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT

            inputSurface?.release()
            inputSurface = null
            surfaceTexture?.release()
            surfaceTexture = null
        }

        renderThread?.quitSafely()
        try {
            renderThread?.join(500)
        } catch (ignored: Exception) {}
        renderThread = null
        renderHandler = null
    }
}
