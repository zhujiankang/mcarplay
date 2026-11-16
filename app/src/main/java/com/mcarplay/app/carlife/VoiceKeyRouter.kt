package com.mcarplay.app.carlife

/** Siri actions: 1=prewarm, 2=button down, 3=button up. Uses a monotonic clock. */
internal class VoiceKeyRouter(
    private val now: () -> Long,
    private val send: (Int) -> Unit,
) {
    private var downAt: Long? = null
    private var prewarmAt: Long? = null
    private var lastStart: Long? = null

    @Synchronized
    fun siri(
        action: Int,
        mercedesPrewarmRelease: Boolean = false,
    ) {
        val time = now()
        if (downAt?.let { time - it > 15_000 } == true) downAt = null
        when (action) {
            1 -> prewarmAt = time
            2 -> down(time)
            3 -> {
                val pressed = downAt
                downAt = null
                if (pressed != null && time - pressed >= 1_500) send(STOP)
                // This Mercedes has previously sent prewarm+release without button-down.
                // Never trigger on prewarm alone; scope the fallback to its receiver identity.
                if (pressed == null && mercedesPrewarmRelease && prewarmAt?.let { time - it in 0..1_500 } == true) down(time)
                downAt = null
                prewarmAt = null
            }
        }
    }

    @Synchronized
    fun hid(pressed: Boolean) = siri(if (pressed) 2 else 3)

    private fun down(time: Long) {
        if (downAt != null) return
        if (lastStart?.let { time - it < 500 } == true) return
        downAt = time
        lastStart = time
        send(START)
    }

    @Synchronized
    fun reset() {
        downAt = null
        prewarmAt = null
        lastStart = null
    }

    companion object {
        const val START = 0x21
        const val STOP = 0x22
        const val HID_VOICE_COMMAND = 0xCF

        /** AirPlay uses params.siriAction. Older senders also use action or a top-level field. */
        fun actionFrom(command: Map<*, *>): Int? {
            if (command["type"] != "requestSiri") return null
            val params = command["params"] as? Map<*, *>
            val raw = params?.get("siriAction") ?: params?.get("action") ?: command["siriAction"] ?: command["action"]
            val value =
                when (raw) {
                    is Number -> raw.toInt().takeIf { it.toDouble() == raw.toDouble() }
                    is String -> raw.trim().toIntOrNull()
                    else -> null
                }
            return value?.takeIf { it in 1..3 }
        }

        fun actionName(action: Int): String =
            when (action) {
                1 -> "PREWARM"
                2 -> "BUTTON_DOWN"
                3 -> "BUTTON_UP"
                else -> "INVALID"
            }
    }
}
