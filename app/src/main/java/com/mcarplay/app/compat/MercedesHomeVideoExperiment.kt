package com.mcarplay.app.compat

import com.mcarplay.app.airplay.ModeArbiter

/** Transport permission only. Never changes the real screen owner or permits hidden touch input. */
internal class MercedesHomeVideoExperiment(
    enabled: Boolean,
    receiverMatched: Boolean,
    private val modes: ModeArbiter,
    private val isCurrent: () -> Boolean = { true },
) {
    val enabled = enabled && receiverMatched

    val retainStream: Boolean get() = enabled && isCurrent()

    fun allowHiddenFrames(): Boolean = retainStream && modes.carOwnsScreenWithoutBorrow
}
