package com.virtualcam.onvif

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.virtualcam.onvif.databinding.ActivityMainBinding
import com.virtualcam.onvif.service.VirtualCamService
import com.virtualcam.onvif.source.CameraSourceEngine
import com.virtualcam.onvif.source.TestPatternEngine
import com.virtualcam.onvif.source.VideoSourceEngine
import com.virtualcam.onvif.util.NetworkUtils
import com.virtualcam.onvif.util.Prefs

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var previewSurface: Surface? = null

    private var localCameraEngine: CameraSourceEngine? = null
    private var localVideoEngine: VideoSourceEngine? = null
    private var localTestPatternEngine: TestPatternEngine? = null

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            VirtualCamService.mediaProjection = projectionManager.getMediaProjection(result.resultCode, result.data!!)
            startStreamService()
            restartPreview()
        } else {
            Toast.makeText(this, "Izin tangkap layar ditolak", Toast.LENGTH_SHORT).show()
            binding.rbPattern.isChecked = true
            prefs.sourceMode = Prefs.SOURCE_PATTERN
            restartPreview()
        }
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (ignored: Exception) {}

            prefs.videoUri = uri.toString()
            binding.tvSelectedVideo.text = uri.lastPathSegment ?: uri.toString()
            restartPreview()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            restartPreview()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        localCameraEngine = CameraSourceEngine(this)
        localVideoEngine = VideoSourceEngine(this)
        localTestPatternEngine = TestPatternEngine()

        requestAppPermissions()
        setupUI()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateNetworkInfo()
        updateServiceStateUI()
        if (previewSurface != null && previewSurface!!.isValid) {
            restartPreview()
        }
    }

    private fun setupUI() {
        binding.previewTextureView.surfaceTextureListener = this

        // Restore Source Mode
        when (prefs.sourceMode) {
            Prefs.SOURCE_CAMERA_BACK -> {
                binding.rbCameraBack.isChecked = true
                binding.layoutVideoPicker.visibility = android.view.View.GONE
            }
            Prefs.SOURCE_CAMERA_FRONT -> {
                binding.rbCameraFront.isChecked = true
                binding.layoutVideoPicker.visibility = android.view.View.GONE
            }
            Prefs.SOURCE_VIDEO -> {
                binding.rbVideo.isChecked = true
                binding.layoutVideoPicker.visibility = android.view.View.VISIBLE
            }
            Prefs.SOURCE_SCREEN -> {
                binding.rbScreen.isChecked = true
                binding.layoutVideoPicker.visibility = android.view.View.GONE
            }
            Prefs.SOURCE_PATTERN -> {
                binding.rbPattern.isChecked = true
                binding.layoutVideoPicker.visibility = android.view.View.GONE
            }
        }

        binding.switchLoop.isChecked = prefs.isLooping
        binding.switchFloating.isChecked = prefs.isFloatingEnabled

        val uriStr = prefs.videoUri
        if (!uriStr.isNullOrEmpty()) {
            binding.tvSelectedVideo.text = Uri.parse(uriStr).lastPathSegment ?: uriStr
        } else {
            binding.tvSelectedVideo.text = getString(R.string.no_video_selected)
        }

        updateNetworkInfo()
        updateServiceStateUI()
    }

    private fun setupListeners() {
        binding.rgSource.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbCameraBack -> {
                    prefs.sourceMode = Prefs.SOURCE_CAMERA_BACK
                    binding.layoutVideoPicker.visibility = android.view.View.GONE
                    ensureCameraPermissionAndRestart()
                }
                R.id.rbCameraFront -> {
                    prefs.sourceMode = Prefs.SOURCE_CAMERA_FRONT
                    binding.layoutVideoPicker.visibility = android.view.View.GONE
                    ensureCameraPermissionAndRestart()
                }
                R.id.rbVideo -> {
                    prefs.sourceMode = Prefs.SOURCE_VIDEO
                    binding.layoutVideoPicker.visibility = android.view.View.VISIBLE
                    restartPreview()
                }
                R.id.rbScreen -> {
                    prefs.sourceMode = Prefs.SOURCE_SCREEN
                    binding.layoutVideoPicker.visibility = android.view.View.GONE
                    if (VirtualCamService.mediaProjection == null) {
                        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                    } else {
                        restartPreview()
                    }
                }
                R.id.rbPattern -> {
                    prefs.sourceMode = Prefs.SOURCE_PATTERN
                    binding.layoutVideoPicker.visibility = android.view.View.GONE
                    restartPreview()
                }
            }
            // If service is running, update active source dynamically
            VirtualCamService.currentInstance?.startActiveSource()
        }

        binding.btnSelectVideo.setOnClickListener {
            videoPickerLauncher.launch(arrayOf("video/*"))
        }

        binding.switchLoop.setOnCheckedChangeListener { _, isChecked ->
            prefs.isLooping = isChecked
            restartPreview()
        }

        binding.btnToggleStream.setOnClickListener {
            if (VirtualCamService.isRunning) {
                stopStreamService()
            } else {
                if (prefs.sourceMode == Prefs.SOURCE_SCREEN && VirtualCamService.mediaProjection == null) {
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                } else {
                    startStreamService()
                }
            }
        }

        binding.btnCopyRtsp.setOnClickListener {
            copyToClipboard(binding.tvRtspUrl.text.toString())
        }

        binding.btnCopyOnvif.setOnClickListener {
            copyToClipboard(binding.tvOnvifUrl.text.toString())
        }

        binding.btnCopyWeb.setOnClickListener {
            copyToClipboard(binding.tvWebStreamUrl.text.toString())
        }

        binding.btnTestIntent.setOnClickListener {
            val intent = Intent(this, VirtualCameraCaptureActivity::class.java)
            startActivity(intent)
        }

        binding.switchFloating.setOnCheckedChangeListener { _, isChecked ->
            prefs.isFloatingEnabled = isChecked
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    binding.switchFloating.isChecked = false
                } else {
                    startService(Intent(this, FloatingCamService::class.java))
                }
            } else {
                stopService(Intent(this, FloatingCamService::class.java))
            }
        }
    }

    private fun ensureCameraPermissionAndRestart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        } else {
            restartPreview()
        }
    }

    private fun startStreamService() {
        val serviceIntent = Intent(this, VirtualCamService::class.java).apply {
            action = VirtualCamService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        updateServiceStateUI()
    }

    private fun stopStreamService() {
        val serviceIntent = Intent(this, VirtualCamService::class.java).apply {
            action = VirtualCamService.ACTION_STOP
        }
        startService(serviceIntent)
        updateServiceStateUI()
        restartPreview()
    }

    private fun updateServiceStateUI() {
        val running = VirtualCamService.isRunning
        if (running) {
            binding.tvStatusBadge.text = getString(R.string.status_running)
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            binding.btnToggleStream.text = getString(R.string.btn_stop_stream)
            binding.btnToggleStream.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red))
            binding.btnToggleStream.setIconResource(R.drawable.ic_stop)
        } else {
            binding.tvStatusBadge.text = getString(R.string.status_stopped)
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            binding.btnToggleStream.text = getString(R.string.btn_start_stream)
            binding.btnToggleStream.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            binding.btnToggleStream.setIconResource(R.drawable.ic_play)
        }
    }

    private fun updateNetworkInfo() {
        val ip = NetworkUtils.getLocalIpAddress()
        binding.tvIpAddress.text = ip
        binding.tvRtspUrl.text = "rtsp://$ip:8554/live"
        binding.tvOnvifUrl.text = "http://$ip:8080/onvif/device_service"
        binding.tvWebStreamUrl.text = "http://$ip:8080/"
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("URL", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }

    fun restartPreview() {
        val surface = previewSurface ?: return
        if (!surface.isValid) return

        val width = binding.previewTextureView.width.let { if (it <= 0) 1280 else it }
        val height = binding.previewTextureView.height.let { if (it <= 0) 720 else it }

        val service = VirtualCamService.currentInstance
        if (service != null && VirtualCamService.isRunning) {
            // Service is active: attach preview surface to service's unified dispatcher
            service.attachPreviewSurface(surface, width, height)
            return
        }

        // Service is not running: use local preview engines
        localCameraEngine?.stop()
        localVideoEngine?.stop()
        localTestPatternEngine?.stop()

        when (prefs.sourceMode) {
            Prefs.SOURCE_CAMERA_BACK -> {
                val camId = localCameraEngine?.getDefaultCameraId(CameraCharacteristics.LENS_FACING_BACK) ?: "0"
                localCameraEngine?.start(camId, listOf(surface))
            }
            Prefs.SOURCE_CAMERA_FRONT -> {
                val camId = localCameraEngine?.getDefaultCameraId(CameraCharacteristics.LENS_FACING_FRONT) ?: "1"
                localCameraEngine?.start(camId, listOf(surface))
            }
            Prefs.SOURCE_VIDEO -> {
                val uriStr = prefs.videoUri
                if (!uriStr.isNullOrEmpty()) {
                    localVideoEngine?.setSurface(surface)
                    localVideoEngine?.start(Uri.parse(uriStr), prefs.isLooping)
                } else {
                    localTestPatternEngine?.start(surface, width, height)
                }
            }
            Prefs.SOURCE_SCREEN -> {
                val proj = VirtualCamService.mediaProjection
                if (proj != null) {
                    val se = localVideoEngine // fallback
                    localTestPatternEngine?.start(surface, width, height)
                } else {
                    localTestPatternEngine?.start(surface, width, height)
                }
            }
            Prefs.SOURCE_PATTERN -> {
                localTestPatternEngine?.start(surface, width, height)
            }
        }
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        previewSurface = Surface(surfaceTexture)
        restartPreview()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        restartPreview()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        localCameraEngine?.stop()
        localVideoEngine?.stop()
        localTestPatternEngine?.stop()
        VirtualCamService.currentInstance?.attachPreviewSurface(null, 0, 0)
        previewSurface?.release()
        previewSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private fun requestAppPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissions.add(Manifest.permission.CAMERA)

        val ungranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isNotEmpty()) {
            permissionLauncher.launch(ungranted.toTypedArray())
        }
    }

    override fun onDestroy() {
        localCameraEngine?.stop()
        localVideoEngine?.stop()
        localTestPatternEngine?.stop()
        previewSurface?.release()
        previewSurface = null
        super.onDestroy()
    }
}
