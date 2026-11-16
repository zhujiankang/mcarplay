package com.mcarplay.app.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CarLifeVideoFpsTest {
    @Test fun sixtyFpsReachesTheVideoInitCommandInsteadOfDefaultingToThirty() {
        val state = CarLifeStateMachine(null)
        for ((name, value) in listOf("state" to CarLifeStateMachine.State.ENCRYPTED, "peerAuthAccepted" to true)) {
            CarLifeStateMachine::class.java
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .set(state, value)
        }
        val action = state.requestVideoInit(1624, 1150, 60).single()
        assertArrayEquals(CarLifeProtocol.videoInitPayload(1624, 1150, 60), action.payload)
    }
}
