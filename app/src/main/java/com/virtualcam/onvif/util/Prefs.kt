package com.virtualcam.onvif.util

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("virtualcam_prefs", Context.MODE_PRIVATE)

    companion object {
        const val SOURCE_VIDEO = 0
        const val SOURCE_SCREEN = 1
        const val SOURCE_PATTERN = 2
    }

    var sourceMode: Int
        get() = prefs.getInt("source_mode", SOURCE_VIDEO)
        set(value) = prefs.edit().putInt("source_mode", value).apply()

    var videoUri: String?
        get() = prefs.getString("video_uri", null)
        set(value) = prefs.edit().putString("video_uri", value).apply()

    var isLooping: Boolean
        get() = prefs.getBoolean("is_looping", true)
        set(value) = prefs.edit().putBoolean("is_looping", value).apply()

    var width: Int
        get() = prefs.getInt("res_width", 1280)
        set(value) = prefs.edit().putInt("res_width", value).apply()

    var height: Int
        get() = prefs.getInt("res_height", 720)
        set(value) = prefs.edit().putInt("res_height", value).apply()

    var fps: Int
        get() = prefs.getInt("res_fps", 30)
        set(value) = prefs.edit().putInt("res_fps", value).apply()

    var bitrate: Int
        get() = prefs.getInt("res_bitrate", 2_500_000)
        set(value) = prefs.edit().putInt("res_bitrate", value).apply()

    var isFloatingEnabled: Boolean
        get() = prefs.getBoolean("is_floating", false)
        set(value) = prefs.edit().putBoolean("is_floating", value).apply()

    var isStreaming: Boolean
        get() = prefs.getBoolean("is_streaming", false)
        set(value) = prefs.edit().putBoolean("is_streaming", value).apply()
}
