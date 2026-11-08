package com.virtualcam.onvif.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjection
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
import com.virtualcam.onvif.source.ScreenSourceEngine
import com.virtualcam.onvif.source.TestPatternEngine
import com.virtualcam.onvif.source.VideoSourceEngine
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

    private lateinit var videoEngine: VideoSourceEngine
    private lateinit var screenEngine: ScreenSourceEngine
    private lateinit var testPatternEngine: TestPatternEngine

    companion object {
        const val ACTION_START = "com.virtualcam.onvif.action.START"
        const val ACTION_STOP = "com.virtualcam.onvif.action.STOP"
        const val NOTIFICATION_ID = 1001

        var isRunning = false
            private set
        var currentInstance: VirtualCamService? = null
            private set
        var mediaProjection: MediaProjection? = null
    }

    inner class LocalBinder : Binder() {
        fun getService(): VirtualCamService = this@VirtualCamService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        prefs = Prefs(this)

        videoEngine = VideoSourceEngine(this)
        screenEngine = ScreenSourceEngine(this)
        testPatternEngine = TestPatternEngine()

        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopStreaming()
            ACTION_START -> startStreaming()
        }
        return START_STICKY
    }

    fun startStreaming() {
        if (isRunning) return
        isRunning = true
        prefs.isStreaming = true

        val localIp = NetworkUtils.getLocalIpAddress()
        startForeground(NOTIFICATION_ID, createNotification(localIp))

        val width = prefs.width
        val height = prefs.height
        val fps = prefs.fps
        val bitrate = prefs.bitrate

        // 1. Initialize Hardware H.264 Encoder
        h264Encoder = H264Encoder(width, height, fps, bitrate).apply {
            onNalUnitListener = { data, _, timestampUs ->
                rtspServer?.broadcastNalUnit(data, timestampUs)
            }
            start()
        }

        // 2. Initialize RTSP Server (Port 8554)
        rtspServer = RtspServer(
            localIp = localIp,
            port = 8554,
            getSps = { h264Encoder?.sps },
            getPps = { h264Encoder?.pps }
        ).apply { start() }

        // 3. Initialize ONVIF SOAP HTTP Server (Port 8080)
        onvifServer = OnvifHttpServer(
            localIp = localIp,
            port = 8080,
            rtspPort = 8554,
            getDimensions = { Pair(width, height) },
            getLatestSnapshot = { null }
        ).apply { start() }

        // 4. Initialize WS-Discovery Server (UDP 3702)
        wsDiscoveryServer = WsDiscoveryServer(this, localIp, 8080).apply { start() }

        // 5. Connect Video Source to Encoder Input Surface
        val encoderSurface = h264Encoder?.inputSurface
        if (encoderSurface != null) {
            attachSourceToSurface(encoderSurface, width, height, fps)
        }

        Log.d(tag, "All streaming services started on $localIp (RTSP: 8554, ONVIF: 8080)")
    }

    fun attachSourceToSurface(surface: Surface, width: Int, height: Int, fps: Int = 30) {
        when (prefs.sourceMode) {
            Prefs.SOURCE_VIDEO -> {
                screenEngine.stop()
                testPatternEngine.stop()
                val uriStr = prefs.videoUri
                if (!uriStr.isNullOrEmpty()) {
                    videoEngine.setSurface(surface)
                    videoEngine.start(Uri.parse(uriStr), prefs.isLooping)
                } else {
                    // Fallback to pattern if no video selected
                    testPatternEngine.start(surface, width, height, fps)
                }
            }
            Prefs.SOURCE_SCREEN -> {
                videoEngine.stop()
                testPatternEngine.stop()
                val projection = mediaProjection
                if (projection != null) {
                    screenEngine.start(projection, surface, width, height)
                } else {
                    testPatternEngine.start(surface, width, height, fps)
                }
            }
            Prefs.SOURCE_PATTERN -> {
                videoEngine.stop()
                screenEngine.stop()
                testPatternEngine.start(surface, width, height, fps)
            }
        }
    }

    fun stopStreaming() {
        isRunning = false
        prefs.isStreaming = false

        videoEngine.stop()
        screenEngine.stop()
        testPatternEngine.stop()

        wsDiscoveryServer?.stop()
        wsDiscoveryServer = null

        onvifServer?.stop()
        onvifServer = null

        rtspServer?.stop()
        rtspServer = null

        h264Encoder?.stop()
        h264Encoder = null

        releaseLocks()
        stopForeground(true)
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
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VirtualCam::WakeLock")?.apply {
            acquire(12 * 60 * 60 * 1000L) // 12 hours timeout
        }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VirtualCam::WifiLock")?.apply {
            acquire()
        }
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
