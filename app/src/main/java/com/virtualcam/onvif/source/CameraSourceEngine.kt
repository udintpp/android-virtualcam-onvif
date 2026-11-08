package com.virtualcam.onvif.source

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

data class PhysicalCameraInfo(
    val id: String,
    val displayName: String,
    val facing: Int,
    val sensorOrientation: Int
)

class CameraSourceEngine(private val context: Context) {

    private val tag = "CameraSourceEngine"
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var currentCameraDevice: CameraDevice? = null
    private var currentCaptureSession: CameraCaptureSession? = null
    private var activeSurfaces = mutableListOf<Surface>()
    private var activeCameraId: String? = null

    fun getAvailableCameras(): List<PhysicalCameraInfo> {
        val list = mutableListOf<PhysicalCameraInfo>()
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
                val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

                val name = when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "Kamera Belakang #$id (Utama)"
                    CameraCharacteristics.LENS_FACING_FRONT -> "Kamera Depan #$id (Selfie)"
                    else -> "Kamera Eksternal #$id"
                }
                list.add(PhysicalCameraInfo(id, name, facing, orientation))
            }
        } catch (e: Exception) {
            Log.e(tag, "Error enumerating physical cameras", e)
        }
        return list
    }

    fun getDefaultCameraId(facingTarget: Int): String? {
        val list = getAvailableCameras()
        return list.firstOrNull { it.facing == facingTarget }?.id ?: list.firstOrNull()?.id
    }

    @SuppressLint("MissingPermission")
    fun start(cameraId: String, surfaces: List<Surface>) {
        stop()
        if (surfaces.isEmpty()) {
            Log.w(tag, "No surfaces provided to CameraSourceEngine")
            return
        }

        activeCameraId = cameraId
        activeSurfaces.clear()
        activeSurfaces.addAll(surfaces.filter { it.isValid })

        startBackgroundThread()

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    currentCameraDevice = camera
                    startCaptureSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    currentCameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(tag, "CameraDevice error: $error on camera $cameraId")
                    try { camera.close() } catch (ignored: Exception) {}
                    currentCameraDevice = null
                    if (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                        error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE) {
                        cameraHandler?.postDelayed({
                            if (activeCameraId == cameraId && currentCameraDevice == null) {
                                Log.d(tag, "Retrying openCamera $cameraId after CAMERA_IN_USE...")
                                start(cameraId, surfaces)
                            }
                        }, 500)
                    }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(tag, "Failed to open camera $cameraId", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun startCaptureSession(camera: CameraDevice) {
        val validSurfaces = activeSurfaces.filter { it.isValid }
        if (validSurfaces.isEmpty()) {
            Log.w(tag, "No valid surfaces for capture session")
            return
        }

        try {
            camera.createCaptureSession(
                validSurfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        currentCaptureSession = session
                        try {
                            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                for (surface in validSurfaces) {
                                    addTarget(surface)
                                }
                                set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )
                                set(
                                    CaptureRequest.CONTROL_AE_MODE,
                                    CaptureRequest.CONTROL_AE_MODE_ON
                                )
                            }
                            session.setRepeatingRequest(builder.build(), null, cameraHandler)
                            Log.d(tag, "Camera capture session configured with ${validSurfaces.size} surfaces")
                        } catch (e: Exception) {
                            Log.e(tag, "Error setting repeating request", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(tag, "Camera session configuration failed")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(tag, "Error creating capture session", e)
        }
    }

    fun updateSurfaces(surfaces: List<Surface>) {
        val device = currentCameraDevice
        val camId = activeCameraId
        if (device != null && camId != null) {
            activeSurfaces.clear()
            activeSurfaces.addAll(surfaces.filter { it.isValid })
            try {
                currentCaptureSession?.stopRepeating()
                currentCaptureSession?.close()
                currentCaptureSession = null
                startCaptureSession(device)
            } catch (e: Exception) {
                Log.e(tag, "Error updating surfaces, restarting camera", e)
                start(camId, surfaces)
            }
        }
    }

    fun stop() {
        try {
            currentCaptureSession?.stopRepeating()
            currentCaptureSession?.close()
        } catch (ignored: Exception) {}
        currentCaptureSession = null

        try {
            currentCameraDevice?.close()
        } catch (ignored: Exception) {}
        currentCameraDevice = null

        activeSurfaces.clear()
        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("CameraBackgroundThread").apply {
                start()
                cameraHandler = Handler(looper)
            }
        }
    }

    private fun stopBackgroundThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join(500)
        } catch (ignored: Exception) {}
        cameraThread = null
        cameraHandler = null
    }
}
