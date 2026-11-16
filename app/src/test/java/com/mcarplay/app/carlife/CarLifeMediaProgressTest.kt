package com.mcarplay.app.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarLifeMediaProgressTest {
    @Test fun protobufMillisecondsAreNotReadAsARawBigEndianInteger() {
        assertEquals(12345L, CarLifeMediaProgress.parse(CarLifeProtocol.pbInt(1, 12345)))
        assertEquals(0L, CarLifeMediaProgress.parse(CarLifeProtocol.pbInt(1, 0)))
    }

    @Test fun malformedDuplicateWrongTypeAndOutOfRangeValuesAreRejected() {
        for (body in listOf(
            byteArrayOf(),
            byteArrayOf(8, 0x80.toByte()),
            CarLifeProtocol.pbInt(2, 5),
            CarLifeProtocol.pbInt(1, 5) + CarLifeProtocol.pbInt(1, 6),
            CarLifeProtocol.pbInt(1, 86400001),
        )) {
            assertNull(CarLifeMediaProgress.parse(body))
        }
    }
}
