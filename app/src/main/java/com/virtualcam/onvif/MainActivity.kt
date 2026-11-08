package com.virtualcam.onvif

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
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
import com.virtualcam.onvif.source.TestPatternEngine
import com.virtualcam.onvif.source.VideoSourceEngine
import com.virtualcam.onvif.util.NetworkUtils
import com.virtualcam.onvif.util.Prefs

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var previewSurface: Surface? = null
    private var videoEngine: VideoSourceEngine? = null
    private var testPatternEngine: TestPatternEngine? = null

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            VirtualCamService.mediaProjection = projectionManager.getMediaProjection(result.resultCode, result.data!!)
            startStreamService()
        } else {
            Toast.makeText(this, "Izin tangkap layar ditolak", Toast.LENGTH_SHORT).show()
            binding.rbPattern.isChecked = true
            prefs.sourceMode = Prefs.SOURCE_PATTERN
        }
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                // Persist read permission across reboots
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
        // Permissions handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        videoEngine = VideoSourceEngine(this)
        testPatternEngine = TestPatternEngine()

        requestAppPermissions()
        setupUI()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateNetworkInfo()
        updateServiceStateUI()
    }

    private fun setupUI() {
        binding.previewTextureView.surfaceTextureListener = this

        // Restore Source Mode
        when (prefs.sourceMode) {
            Prefs.SOURCE_VIDEO -> binding.rbVideo.isChecked = true
            Prefs.SOURCE_SCREEN -> binding.rbScreen.isChecked = true
            Prefs.SOURCE_PATTERN -> binding.rbPattern.isChecked = true
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
                R.id.rbVideo -> {
                    prefs.sourceMode = Prefs.SOURCE_VIDEO
                    binding.layoutVideoPicker.visibility = android.view.View.VISIBLE
                }
                R.id.rbScreen -> {
                    prefs.sourceMode = Prefs.SOURCE_SCREEN
                    binding.layoutVideoPicker.visibility = android.view.View.GONE
                }
                R.id.rbPattern -> {
                    prefs.sourceMode = Prefs.SOURCE_PATTERN
                    binding.layoutVideoPicker.visibility = android.view.View.GONE
                }
            }
            restartPreview()
        }

        binding.btnSelectVideo.setOnClickListener {
            videoPickerLauncher.launch("video/*")
        }

        binding.switchLoop.setOnCheckedChangeListener { _, isChecked ->
            prefs.isLooping = isChecked
            restartPreview()
        }

        binding.btnToggleStream.setOnClickListener {
            if (VirtualCamService.isRunning) {
                stopStreamService()
            } else {
                if (prefs.sourceMode == Prefs.SOURCE_SCREEN) {
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
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("URL", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }

    private fun restartPreview() {
        val surface = previewSurface ?: return
        val width = binding.previewTextureView.width.let { if (it <= 0) 1280 else it }
        val height = binding.previewTextureView.height.let { if (it <= 0) 720 else it }

        videoEngine?.stop()
        testPatternEngine?.stop()

        when (prefs.sourceMode) {
            Prefs.SOURCE_VIDEO -> {
                val uriStr = prefs.videoUri
                if (!uriStr.isNullOrEmpty()) {
                    videoEngine?.setSurface(surface)
                    videoEngine?.start(Uri.parse(uriStr), prefs.isLooping)
                } else {
                    testPatternEngine?.start(surface, width, height)
                }
            }
            Prefs.SOURCE_SCREEN -> {
                // If service is running, it streams screen; otherwise show test pattern in preview
                val proj = VirtualCamService.mediaProjection
                if (proj != null) {
                    VirtualCamService.currentInstance?.attachSourceToSurface(surface, width, height)
                } else {
                    testPatternEngine?.start(surface, width, height)
                }
            }
            Prefs.SOURCE_PATTERN -> {
                testPatternEngine?.start(surface, width, height)
            }
        }
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        previewSurface = Surface(surfaceTexture)
        restartPreview()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        videoEngine?.stop()
        testPatternEngine?.stop()
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
        videoEngine?.stop()
        testPatternEngine?.stop()
        previewSurface?.release()
        previewSurface = null
        super.onDestroy()
    }
}
