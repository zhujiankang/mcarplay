package com.mcarplay.app.airplay

/** Capability/receipt evidence only. Does not invent GPS fixes, CAN fields or cluster frames. */
internal class VehicleDataStatus {
    var sends: Set<Int> = emptySet()
    var receives: Set<Int> = emptySet()
    private var locationMessages = 0L
    private var navigationSubscriptions = 0L
    private var navigationRequested = false

    fun received(id: Int): String? {
        when (id) {
            0xFFFB -> {
                locationMessages++
                if (locationMessages > 3 && locationMessages and (locationMessages - 1) != 0L) return null
            }
            0x5200 -> {
                navigationSubscriptions++
                navigationRequested = true
            }
            0x5203 -> navigationRequested = false
            else -> return null
        }
        return summary()
    }

    fun summary(): String =
        "VEHICLE: gpsDeclared=${0xFFFB in sends} locationMessages=$locationMessages " +
            "navigationDeclared=${sends.containsAll(setOf(0x5200, 0x5203)) && receives.containsAll(setOf(0x5201, 0x5202))} " +
            "navigationSubscriptions=$navigationSubscriptions navigationRequested=$navigationRequested " +
            "gpsForwarding=false vehicleDataForwarding=false clusterNavigationForwarding=false"

    companion object {
        fun privatePayload(id: Int): Boolean = id == 0xFFFB || id in 0x5200..0x5203
    }
}
