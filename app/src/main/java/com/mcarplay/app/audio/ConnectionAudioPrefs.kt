package com.mcarplay.app.audio

import android.content.Context

/** 用户的声音选择；音源可用性不能覆盖这个选择。 */
object ConnectionAudioPrefs {
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("connection_audio_enabled", true)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("connection_audio_enabled", enabled)
            .apply()
    }
}
