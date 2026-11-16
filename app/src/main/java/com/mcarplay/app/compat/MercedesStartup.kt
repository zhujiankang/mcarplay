package com.mcarplay.app.compat

import android.content.Context

/** 只对日志核对过身份的奔驰接收端开放实验策略，不据此声明车型已适配。 */
object MercedesStartup {
    enum class Mode { ORIGINAL, SINGLE_RECORD, AUDIO_FIRST }

    data class Policy(
        val mode: Mode,
        val singleRecord: Boolean,
        val audioFirst: Boolean,
    )

    fun mode(context: Context): Mode =
        Mode.entries.firstOrNull {
            it.name == context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("mercedes_startup", null)
        } ?: Mode.SINGLE_RECORD

    fun setMode(
        context: Context,
        mode: Mode,
    ) {
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putString("mercedes_startup", mode.name)
            .apply()
    }

    fun resolve(
        mode: Mode,
        manufacturer: String?,
        model: String?,
    ): Policy {
        val verifiedIdentity =
            manufacturer?.trim().equals("Mercedes-Benz", ignoreCase = true) &&
                model?.trim().equals("MB_D-M-5", ignoreCase = true)
        val selected = if (verifiedIdentity) mode else Mode.ORIGINAL
        return Policy(selected, selected != Mode.ORIGINAL, selected == Mode.AUDIO_FIRST)
    }
}
