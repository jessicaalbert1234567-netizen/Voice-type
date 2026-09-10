package com.example.floating

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists settings and coordinates for the Wispr-style Floating Voice Typing feature.
 */
class FloatingPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "floating_voice_typing_prefs"
        private const val KEY_ENABLED = "floating_enabled"
        private const val KEY_POS_X = "floating_pos_x"
        private const val KEY_POS_Y = "floating_pos_y"
        private const val KEY_AUTO_HIDE = "floating_auto_hide"

        // Default invalid coordinate indicating center-right initial placement
        const val DEFAULT_COORDINATE = -1
    }

    var isFloatingEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var posX: Int
        get() = prefs.getInt(KEY_POS_X, DEFAULT_COORDINATE)
        set(value) = prefs.edit().putInt(KEY_POS_X, value).apply()

    var posY: Int
        get() = prefs.getInt(KEY_POS_Y, DEFAULT_COORDINATE)
        set(value) = prefs.edit().putInt(KEY_POS_Y, value).apply()

    fun savePosition(x: Int, y: Int) {
        prefs.edit()
            .putInt(KEY_POS_X, x)
            .putInt(KEY_POS_Y, y)
            .apply()
    }
}
