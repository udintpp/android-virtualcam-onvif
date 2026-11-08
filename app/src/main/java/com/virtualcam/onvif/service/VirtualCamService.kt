package com.virtualcam.onvif.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.virtualcam.onvif.MainActivity
import com.virtualcam.onvif.R
import com.virtualcam.onvif.VirtualCamApp
import com.virtualcam.onvif.encoder.H264Encoder
import com.virtualcam.onvif.onvif.OnvifHttpServer
import com.virtualcam.onvif.onvif.WsDiscoveryServer
import com.virtualcam.onvif.rtsp.RtspServer
import com.virtualcam.onvif.source.CameraSourceEngine
import com.virtualcam.onvif.source.ScreenSourceEngine
import com.virtualcam.onvif.source.VideoSourceEngine
import com.virtualcam.onvif.util.GlFrameDispatcher
import com.virtualcam.onvif.util.NetworkUtils
import com.virtualcam.onvif.util.Prefs

class VirtualCamService : Service() {

    private val tag = "VirtualCamService"
    private val binder = LocalBinder()

    private lateinit var prefs: Prefs
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var h264Encoder: H264Encoder? = null
    private var rtspServer: RtspServer? = null
    private var onvifServer: OnvifHttpServer? = null
    private var wsDiscoveryServer: WsDiscoveryServer? = null

    lateinit var cameraEngine: CameraSourceEngine
        private set
    lateinit var videoEngine: VideoSourceEngine
        private set
    lateinit var screenEngine: ScreenSourceEngine
        private set
    lateinit var glDispatcher: GlFrameDispatcher
        private set

    private var currentPreviewSurface: Surface? = null
    private var currentPreviewWidth = 1280
    private var currentPreviewHeight = 720

    companion object {
        const val ACTION_START = "com.virtualcam.onvif.action.START"
        const val ACTION_START_SCREEN = "com.virtualcam.onvif.action.START_SCREEN"
        const val ACTION_STOP = "com.virtualcam.onvif.action.STOP"
        const val EXTRA_RESULT_CODE = "com.virtualcam.onvif.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.virtualcam.onvif.extra.RESULT_DATA"
        const val NOTIFICATION_ID = 1001

        var isRunning = false
            private set
        var currentInstance: VirtualCamService? = null
            private set
        var mediaProjection: MediaProjection? = null

        var pendingPreviewSurface: Surface? = null
        var pendingPreviewWidth: Int = 1280
        var pendingPreviewHeight: Int = 720
    }

    inner class LocalBinder : Binder() {
        fun getService(): VirtualCamService = this@VirtualCamService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        prefs = Prefs(this)

        cameraEngine = CameraSourceEngine(this)
        videoEngine = VideoSourceEngine(this)
        screenEngine = ScreenSourceEngine(this)
        glDispatcher = GlFrameDispatcher(prefs.width, prefs.height)

        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopStreaming()
            ACTION_START -> {
                startStreaming()
            }
            ACTION_START_SCREEN -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                prefs.sourceMode = Prefs.SOURCE_SCREEN

                // Android 14+ requires startForeground with MEDIA_PROJECTION type BEFORE getMediaProjection()
                startServiceForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

                if (resultCode != 0 && resultData != null) {
                    try {
                        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjection = pm.getMediaProjection(resultCode, resultData)
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to get MediaProjection", e)
                    }
                }
                startStreamingServices()
            }
        }
        return START_STICKY
    }

    fun startStreaming() {
        startServiceForeground()
        startStreamingServices()
    }

    private fun startServiceForeground(explicitType: Int = 0) {
        val localIp = NetworkUtils.getLocalIpAddress()
        val notification = createNotification(localIp)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (explicitType != 0) {
                explicitType
            } else {
                when (prefs.sourceMode) {
                    Prefs.SOURCE_SCREEN -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    Prefs.SOURCE_CAMERA_BACK, Prefs.SOURCE_CAMERA_FRONT -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
                    }
                    else -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
                    }
                }
            }

            try {
                if (fgsType != 0) {
                    startForeground(NOTIFICATION_ID, notification, fgsType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                Log.w(tag, "startForeground with type $fgsType failed, retrying without type", e)
                try {
                    startForeground(NOTIFICATION_ID, notification)
                } catch (ignored: Exception) {}
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startStreamingServices() {
        if (isRunning) {
            startActiveSource()
            return
        }
        isRunning = true
        prefs.isStreaming = true

        val localIp = NetworkUtils.getLocalIpAddress()
        val width = prefs.width
        val height = prefs.height
        val fps = prefs.fps
        val bitrate = prefs.bitrate

        // 1. Hardware H.264 Encoder
        try {
            h264Encoder = H264Encoder(width, height, fps, bitrate).apply {
                onNalUnitListener = { data, _, timestampUs ->
                    rtspServer?.broadcastNalUnit(data, timestampUs)
                }
                start()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error starting H264Encoder", e)
        }

        // 2. RTSP Server (Port 8554)
        try {
            rtspServer = RtspServer(
                localIp = localIp,
                port = 8554,
                getSps = { h264Encoder?.sps },
                getPps = { h264Encoder?.pps }
            ).apply { start() }
        } catch (e: Exception) {
            Log.e(tag, "Error starting RTSP Server", e)
        }

        // 3. ONVIF SOAP & Web MJPEG HTTP Server (Port 8080)
        try {
            onvifServer = OnvifHttpServer(
                localIp = localIp,
                port = 8080,
                rtspPort = 8554,
                getDimensions = { Pair(width, height) },
                getLatestJpeg = { glDispatcher.latestJpegBytes },
                setWebActive = { glDispatcher.hasActiveWebClients = it }
            ).apply { start() }
        } catch (e: Exception) {
            Log.e(tag, "Error starting ONVIF HTTP Server", e)
        }

        // 4. WS-Discovery Server (UDP 3702)
        try {
            wsDiscoveryServer = WsDiscoveryServer(this, localIp, 8080).apply { start() }
        } catch (e: Exception) {
            Log.e(tag, "Error starting WS-Discovery Server", e)
        }

        // 5. Dual Surface GL Dispatcher (Blocks synchronously until inputSurface is ready)
        try {
            glDispatcher.start()
            val encoderSurface = h264Encoder?.inputSurface
            if (encoderSurface != null) {
                glDispatcher.setEncoderSurface(encoderSurface, width, height)
            }
            val initialPreview = pendingPreviewSurface ?: currentPreviewSurface
            if (initialPreview != null && initialPreview.isValid) {
                currentPreviewSurface = initialPreview
                currentPreviewWidth = pendingPreviewWidth
                currentPreviewHeight = pendingPreviewHeight
                glDispatcher.setPreviewSurface(initialPreview, currentPreviewWidth, currentPreviewHeight)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error starting GL Dispatcher", e)
        }

        // 6. Connect Active Source
        try {
            startActiveSource()
        } catch (e: Exception) {
            Log.e(tag, "Error starting active source", e)
        }

        Log.d(tag, "Streaming services active on $localIp (RTSP: 8554, ONVIF: 8080)")
    }

    fun attachPreviewSurface(surface: Surface?, width: Int, height: Int) {
        currentPreviewSurface = surface
        currentPreviewWidth = width
        currentPreviewHeight = height

        try {
            glDispatcher.setPreviewSurface(surface, width, height)
        } catch (ignored: Exception) {}
    }

    fun startActiveSource() {
        val width = prefs.width
        val height = prefs.height
        val fps = prefs.fps
        val glInput = glDispatcher.inputSurface
        val encoderSurface = h264Encoder?.inputSurface
        val targetSurface = glInput ?: encoderSurface

        cameraEngine.stop()
        videoEngine.stop()
        screenEngine.stop()
        glDispatcher.setTestPatternMode(false)

        when (prefs.sourceMode) {
            Prefs.SOURCE_CAMERA_BACK -> {
                val camId = prefs.selectedCameraId
                    ?: cameraEngine.getDefaultCameraId(CameraCharacteristics.LENS_FACING_BACK)
                    ?: "0"
                if (targetSurface != null) {
                    cameraEngine.start(camId, listOf(targetSurface))
                }
            }
            Prefs.SOURCE_CAMERA_FRONT -> {
                val camId = prefs.selectedCameraId
                    ?: cameraEngine.getDefaultCameraId(CameraCharacteristics.LENS_FACING_FRONT)
                    ?: "1"
                if (targetSurface != null) {
                    cameraEngine.start(camId, listOf(targetSurface))
                }
            }
            Prefs.SOURCE_VIDEO -> {
                val uriStr = prefs.videoUri
                if (!uriStr.isNullOrEmpty() && targetSurface != null) {
                    videoEngine.setSurface(targetSurface)
                    videoEngine.start(Uri.parse(uriStr), prefs.isLooping)
                } else {
                    glDispatcher.setTestPatternMode(true)
                }
            }
            Prefs.SOURCE_SCREEN -> {
                val proj = mediaProjection
                if (proj != null && targetSurface != null) {
                    try {
                        screenEngine.start(proj, targetSurface, width, height)
                    } catch (e: Exception) {
                        Log.e(tag, "Error in screenEngine.start, falling back to pattern", e)
                        glDispatcher.setTestPatternMode(true)
                    }
                } else {
                    glDispatcher.setTestPatternMode(true)
                }
            }
            Prefs.SOURCE_PATTERN -> {
                glDispatcher.setTestPatternMode(true)
            }
        }
    }

    fun stopStreaming() {
        isRunning = false
        prefs.isStreaming = false

        try { cameraEngine.stop() } catch (ignored: Exception) {}
        try { videoEngine.stop() } catch (ignored: Exception) {}
        try { screenEngine.stop() } catch (ignored: Exception) {}
        try { glDispatcher.stop() } catch (ignored: Exception) {}

        try { wsDiscoveryServer?.stop() } catch (ignored: Exception) {}
        wsDiscoveryServer = null

        try { onvifServer?.stop() } catch (ignored: Exception) {}
        onvifServer = null

        try { rtspServer?.stop() } catch (ignored: Exception) {}
        rtspServer = null

        try { h264Encoder?.stop() } catch (ignored: Exception) {}
        h264Encoder = null

        currentPreviewSurface = null
        pendingPreviewSurface = null

        releaseLocks()
        try {
            stopForeground(true)
        } catch (ignored: Exception) {}
        stopSelf()
        Log.d(tag, "Streaming services stopped cleanly")
    }

    private fun createNotification(localIp: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, VirtualCamApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text, "rtsp://$localIp:8554/live"))
            .setSmallIcon(R.drawable.ic_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VirtualCam::WakeLock")?.apply {
                acquire(12 * 60 * 60 * 1000L)
            }
        } catch (ignored: Exception) {}

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VirtualCam::WifiLock")?.apply {
                acquire()
            }
        } catch (ignored: Exception) {}
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (ignored: Exception) {}
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (ignored: Exception) {}
        wifiLock = null
    }

    override fun onDestroy() {
        stopStreaming()
        currentInstance = null
        super.onDestroy()
    }
}
