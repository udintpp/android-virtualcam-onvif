package com.virtualcam.onvif.hook

import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

class CameraHook : IXposedHookLoadPackage {

    private val tag = "VirtualCamHook"
    private var hookedMediaPlayer: MediaPlayer? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Skip self
        if (lpparam.packageName == "com.virtualcam.onvif" ||
            lpparam.packageName == "com.virtualcam.onvif.debug"
        ) {
            return
        }

        XposedBridge.log("$tag: Loaded into target package: ${lpparam.packageName}")

        try {
            hookCamera1(lpparam)
            hookCamera2(lpparam)
        } catch (e: Throwable) {
            XposedBridge.log("$tag: Error hooking camera: ${e.message}")
        }
    }

    private fun hookCamera1(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook Camera 1 setPreviewTexture
            XposedHelpers.findAndHookMethod(
                "android.hardware.Camera",
                lpparam.classLoader,
                "setPreviewTexture",
                SurfaceTexture::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val surfaceTexture = param.args[0] as? SurfaceTexture
                        if (surfaceTexture != null) {
                            val surface = Surface(surfaceTexture)
                            playVirtualFeedToSurface(surface)
                        }
                    }
                }
            )

            // Hook Camera 1 setPreviewDisplay
            XposedHelpers.findAndHookMethod(
                "android.hardware.Camera",
                lpparam.classLoader,
                "setPreviewDisplay",
                SurfaceHolder::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val holder = param.args[0] as? SurfaceHolder
                        if (holder?.surface != null) {
                            playVirtualFeedToSurface(holder.surface)
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            // Class or method not found or already handled
        }
    }

    private fun hookCamera2(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook CameraDeviceImpl createCaptureSession
            val cameraDeviceImplClass = XposedHelpers.findClassIfExists(
                "android.hardware.camera2.impl.CameraDeviceImpl",
                lpparam.classLoader
            )

            if (cameraDeviceImplClass != null) {
                XposedBridge.hookAllMethods(
                    cameraDeviceImplClass,
                    "createCaptureSession",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val outputs = param.args.firstOrNull { it is List<*> } as? List<*>
                            if (outputs != null) {
                                for (output in outputs) {
                                    val surface = when (output) {
                                        is Surface -> output
                                        else -> {
                                            // OutputConfiguration in newer APIs
                                            try {
                                                XposedHelpers.callMethod(output, "getSurface") as? Surface
                                            } catch (t: Throwable) {
                                                null
                                            }
                                        }
                                    }
                                    if (surface != null && surface.isValid) {
                                        playVirtualFeedToSurface(surface)
                                    }
                                }
                            }
                        }
                    }
                )
            }
        } catch (e: Throwable) {
            // Ignore camera2 hook error
        }
    }

    private fun playVirtualFeedToSurface(surface: Surface) {
        try {
            hookedMediaPlayer?.release()
            hookedMediaPlayer = null

            // Look for default virtual video in shared storage or internal storage
            val candidates = listOf(
                File("/sdcard/virtualcam.mp4"),
                File("/sdcard/Movies/virtualcam.mp4"),
                File("/storage/emulated/0/virtualcam.mp4"),
                File("/storage/emulated/0/Movies/virtualcam.mp4"),
                File("/storage/emulated/0/DCIM/virtualcam.mp4")
            )

            val videoFile = candidates.firstOrNull { it.exists() && it.canRead() }
            if (videoFile != null) {
                hookedMediaPlayer = MediaPlayer().apply {
                    setDataSource(videoFile.absolutePath)
                    setSurface(surface)
                    isLooping = true
                    setVolume(0f, 0f)
                    setOnPreparedListener { it.start() }
                    prepareAsync()
                }
                XposedBridge.log("$tag: Streaming virtual video ${videoFile.absolutePath} to target Surface")
            } else {
                XposedBridge.log("$tag: No virtualcam.mp4 found in standard storage paths")
            }
        } catch (e: Throwable) {
            XposedBridge.log("$tag: Failed to feed virtual video: ${e.message}")
        }
    }
}
