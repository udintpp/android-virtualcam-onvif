package com.virtualcam.onvif

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.virtualcam.onvif.databinding.ActivityCaptureBinding
import com.virtualcam.onvif.service.VirtualCamService
import com.virtualcam.onvif.source.CameraSourceEngine
import com.virtualcam.onvif.source.TestPatternEngine
import com.virtualcam.onvif.source.VideoSourceEngine
import com.virtualcam.onvif.util.Prefs

class VirtualCameraCaptureActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var binding: ActivityCaptureBinding
    private lateinit var prefs: Prefs
    private var currentSurface: Surface? = null
    private var cameraEngine: CameraSourceEngine? = null
    private var videoEngine: VideoSourceEngine? = null
    private var testPatternEngine: TestPatternEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        cameraEngine = CameraSourceEngine(this)
        videoEngine = VideoSourceEngine(this)
        testPatternEngine = TestPatternEngine()

        val action = intent?.action
        if (MediaStore.ACTION_VIDEO_CAPTURE == action) {
            binding.tvCaptureMode.text = "MODE VIDEO"
            binding.btnSnapPhoto.visibility = android.view.View.GONE
        } else {
            binding.tvCaptureMode.text = "MODE FOTO"
        }

        binding.captureTextureView.surfaceTextureListener = this

        binding.btnCancel.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        binding.btnSnapPhoto.setOnClickListener {
            capturePhoto()
        }

        binding.btnRecordVideo.setOnClickListener {
            finishWithVideo()
        }
    }

    private fun capturePhoto() {
        val bitmap = binding.captureTextureView.bitmap
        if (bitmap == null) {
            Toast.makeText(this, "Gagal mengambil frame", Toast.LENGTH_SHORT).show()
            return
        }

        val outputUri = intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
        if (outputUri != null) {
            try {
                contentResolver.openOutputStream(outputUri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                setResult(Activity.RESULT_OK)
            } catch (e: Exception) {
                e.printStackTrace()
                setResult(Activity.RESULT_CANCELED)
            }
        } else {
            // Return thumbnail bitmap via intent data
            val resultIntent = Intent().apply {
                putExtra("data", Bitmap.createScaledBitmap(bitmap, 320, 240, true))
            }
            setResult(Activity.RESULT_OK, resultIntent)
        }
        finish()
    }

    private fun finishWithVideo() {
        val videoUriStr = prefs.videoUri
        if (videoUriStr != null) {
            val resultIntent = Intent().apply {
                data = Uri.parse(videoUriStr)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        } else {
            Toast.makeText(this, "Pilih file video di aplikasi utama terlebih dahulu", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        currentSurface = Surface(surfaceTexture)
        currentSurface?.let { surface ->
            val service = VirtualCamService.currentInstance
            if (service != null && VirtualCamService.isRunning) {
                service.attachPreviewSurface(surface, width, height)
            } else {
                when (prefs.sourceMode) {
                    Prefs.SOURCE_CAMERA_BACK -> {
                        val camId = cameraEngine?.getDefaultCameraId(CameraCharacteristics.LENS_FACING_BACK) ?: "0"
                        cameraEngine?.start(camId, listOf(surface))
                    }
                    Prefs.SOURCE_CAMERA_FRONT -> {
                        val camId = cameraEngine?.getDefaultCameraId(CameraCharacteristics.LENS_FACING_FRONT) ?: "1"
                        cameraEngine?.start(camId, listOf(surface))
                    }
                    Prefs.SOURCE_VIDEO -> {
                        val uriStr = prefs.videoUri
                        if (!uriStr.isNullOrEmpty()) {
                            videoEngine?.setSurface(surface)
                            videoEngine?.start(Uri.parse(uriStr), prefs.isLooping)
                        } else {
                            testPatternEngine?.start(surface, width, height)
                        }
                    }
                    else -> {
                        testPatternEngine?.start(surface, width, height)
                    }
                }
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        cameraEngine?.stop()
        videoEngine?.stop()
        testPatternEngine?.stop()
        currentSurface?.release()
        currentSurface = null
        return true
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun onDestroy() {
        cameraEngine?.stop()
        videoEngine?.stop()
        testPatternEngine?.stop()
        super.onDestroy()
    }
}
