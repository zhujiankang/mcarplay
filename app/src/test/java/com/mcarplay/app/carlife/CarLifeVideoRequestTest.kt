package com.mcarplay.app.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifeVideoRequestTest {
    @Test fun receiverFpsCapIsAppliedBeforeInitAndCannotMutateARunningSource() {
        val request = CarLifeVideoRequest(null, true, 60)
        assertEquals(60, request.requestedFps)
        assertTrue(request.update(1624, 1150, 30))
        request.takeInitialSize(true)
        assertEquals(30, request.requestedFps)
        assertFalse(request.update(1624, 1150, 60))
        assertEquals(30, request.requestedFps)
    }

    @Test
    fun huInfoCarriesTheDisplayDomainBeforeVideoInit() {
        val request = CarLifeVideoRequest(null, true)
        request.update(1624, 1150)
        val state = CarLifeStateMachine(null, huInfoPayload = { CarLifeProtocol.huInfoPayload(request.requestedSize) })
        state.onConnected()
        state.onProtocolMatched()
        val actions =
            state.onInfo(
                CarLifeProtocol.pbString(4, "Xiaomi") + CarLifeProtocol.pbString(14, "test") + CarLifeProtocol.pbString(20, "36"),
            )
        val info = actions.single { it.kind == CarLifeProtocol.HU_INFO }
        assertEquals("1624x1150", CarLifeProtocol.firstStringField(info.payload, 8))
    }

    @Test
    fun prewarmMayAuthenticateBeforeDisplayArrivesWithoutLockingIn720p() {
        val request = CarLifeVideoRequest(null, waitForDisplaySize = true)
        assertNull(request.takeInitialSize(encryptionReady = true))
        assertTrue(request.update(1624, 1150))
        assertEquals(1624 to 1150, request.takeInitialSize(encryptionReady = true))
        assertNull(request.takeInitialSize(encryptionReady = true))
    }

    @Test
    fun displayMayArriveFirstButCannotBypassEncryption() {
        val request = CarLifeVideoRequest(null, waitForDisplaySize = true)
        assertTrue(request.update(1624, 1150))
        assertNull(request.takeInitialSize(encryptionReady = false))
        assertEquals(1624 to 1150, request.takeInitialSize(encryptionReady = true))
    }

    @Test
    fun runningSourceDoesNotReceiveASecondInitOnRepeatedSetupOrUiReturn() {
        val request = CarLifeVideoRequest(1624 to 1150, waitForDisplaySize = true)
        assertEquals(1624 to 1150, request.takeInitialSize(true))
        assertFalse(request.update(1280, 720))
        assertNull(request.takeInitialSize(true))
        assertEquals(1624 to 1150, request.requestedSize)
    }

    @Test
    fun standaloneProbeKeepsDefaultAndInvalidDisplayDoesNotOpenGate() {
        assertEquals(1280 to 720, CarLifeVideoRequest(null, false).takeInitialSize(true))
        val request = CarLifeVideoRequest(null, true)
        assertFalse(request.update(0, 1150))
        assertNull(request.takeInitialSize(true))
        assertTrue(request.update(1625, 1151))
        assertEquals(1624 to 1150, request.takeInitialSize(true))
    }

    @Test
    fun huInfoCarriesDisplayAsAStringInTheCorrectProtobufField() {
        val payload = CarLifeProtocol.huInfoPayload(1624 to 1150)
        assertEquals("1624x1150", CarLifeProtocol.firstStringField(payload, 8))
        assertEquals("Android", CarLifeProtocol.firstStringField(payload, 1))
    }
}
