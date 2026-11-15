package com.mcarplay.app.bt

import android.content.Context

/** 配对前发布能力及可选 Wireless iAP v2 探针；不改变蓝牙硬件身份。 */
object CarPlayDiscoveryPrefs {
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("early_carplay_discovery", true)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("early_carplay_discovery", enabled)
            .apply()
    }
}
