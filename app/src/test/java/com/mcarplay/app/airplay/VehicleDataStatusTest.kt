package com.mcarplay.app.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleDataStatusTest {
    @Test fun declarationsAndActualRequestsRemainDistinctFromImplementedForwarding() {
        val status = VehicleDataStatus()
        status.sends = setOf(0xFFFB, 0x5200, 0x5203)
        assertTrue(status.summary().contains("gpsDeclared=true"))
        assertTrue(status.summary().contains("navigationDeclared=false"))
        status.receives = setOf(0x5201, 0x5202)
        assertTrue(status.summary().contains("navigationDeclared=true"))
        assertTrue(status.summary().contains("locationMessages=0"))
        assertTrue(status.summary().contains("navigationSubscriptions=0"))
        assertTrue(status.received(0x5200)!!.contains("navigationRequested=true"))
        assertTrue(status.received(0xFFFB)!!.contains("locationMessages=1"))
        assertTrue(status.received(0x5203)!!.contains("navigationRequested=false"))
        assertTrue(status.summary().contains("gpsForwarding=false vehicleDataForwarding=false clusterNavigationForwarding=false"))
        assertNull(status.received(0x10030))
        assertTrue(VehicleDataStatus.privatePayload(0xFFFB))
        assertTrue(VehicleDataStatus.privatePayload(0x5201))
        assertFalse(VehicleDataStatus.privatePayload(0x1D01))
    }
}
