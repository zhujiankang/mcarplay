package com.mcarplay.app.carlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.Signature
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Locally generated client, never OEM interoperability evidence. */
internal class CarlinkTestClient(
    private val pin: String = "123456",
) {
    private val identity = CarlinkCrypto.keyPair()
    private val ephemeral = CarlinkCrypto.keyPair()
    private val nonce = ByteArray(32) { (it + 1).toByte() }

    fun request(
        modes: Long = 1,
        quick: Boolean = false,
    ): CarlinkPacket {
        val fields = CarlinkFields().put(1, 0x0901).put(10, modes)
        if (!quick) {
            val public = CarlinkCrypto.publicBytes(identity.public)
            val agreement = CarlinkCrypto.publicBytes(ephemeral.public)
            fields
                .put(2, public)
                .put(3, mac(sha(nonce + pin.toByteArray()), public))
                .put(4, agreement)
                .put(5, sign(agreement + nonce))
                .put(6, nonce)
                .put(7, "test-client".toByteArray())
                .put(8, "synthetic".toByteArray())
        }
        return CarlinkPacket(0xffffffff, 1, 1, 1, 1, 3, 1, fields.encode())
    }

    fun key(response: CarlinkPacket): ByteArray {
        val fields = CarlinkFields.decode(response.payload)
        assertEquals(0L, fields.number(7))
        assertEquals(1L, fields.number(8))
        val remote = fields.bytes(2)
        val random = fields.bytes(6)
        assertArrayEquals(
            mac(sha(random + nonce + pin.toByteArray()), remote + CarlinkCrypto.publicBytes(identity.public)),
            fields.bytes(3),
        )
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(CarlinkCrypto.publicKey(remote))
        verifier.update(fields.bytes(4) + CarlinkCrypto.publicBytes(ephemeral.public) + random + nonce)
        assertTrue(verifier.verify(fields.bytes(5)))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(ephemeral.private)
        agreement.doPhase(CarlinkCrypto.publicKey(fields.bytes(4)), true)
        return mac(mac(sha(nonce + random), agreement.generateSecret()), "session_key".toByteArray() + byteArrayOf(1)).copyOf(16)
    }

    fun confirmation(
        key: ByteArray,
        endpoint: String,
    ): CarlinkPacket =
        CarlinkPacket(
            0,
            1,
            1,
            1,
            0,
            3,
            3,
            CarlinkFields().put(1, CarlinkCipher(key).seal(endpoint.toByteArray())).encode(),
        )

    private fun sign(value: ByteArray) =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(identity.private)
            update(value)
            sign()
        }

    private fun sha(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)

    private fun mac(
        key: ByteArray,
        value: ByteArray,
    ) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value)
    }
}

class CarlinkPairingTest {
    @Test
    fun pinHandshakeConfirmsDynamicEndpointAndWrapsSequence() {
        val client = CarlinkTestClient()
        CarlinkPairing("123456").use { receiver ->
            val reply = receiver.respond(client.request(), 2)
            assertEquals(0xffffffff, reply.sequence)
            val key = client.key(reply)
            val result = receiver.confirm(client.confirmation(key, "127.0.0.1:40123"), setOf("127.0.0.1"))
            assertEquals(40123, result.first.controlPort)
            assertArrayEquals(key, result.second)
            assertThrows(IllegalStateException::class.java) { receiver.respond(client.request(), 3) }
        }
    }

    @Test
    fun wrongPinAndTamperedSignatureAreRejected() {
        CarlinkPairing("654321").use { receiver ->
            assertThrows(IllegalArgumentException::class.java) { receiver.respond(CarlinkTestClient().request(), 1) }
        }
        val request = CarlinkTestClient().request()
        val fields = CarlinkFields.decode(request.payload).put(5, byteArrayOf(0x30, 0))
        CarlinkPairing("123456").use { receiver ->
            assertThrows(Exception::class.java) { receiver.respond(request.copy(payload = fields.encode()), 1) }
        }
    }

    @Test
    fun unknownQuickRequiresPinAndFusionOnlyIsRefused() {
        val client = CarlinkTestClient()
        CarlinkPairing("123456").use { receiver ->
            assertEquals(1L, CarlinkFields.decode(receiver.respond(client.request(quick = true), 1).payload).number(7))
            assertEquals(4L, CarlinkFields.decode(receiver.respond(client.request(modes = 4), 2).payload).number(7))
            client.key(receiver.respond(client.request(), 3))
        }
    }

    @Test
    fun invalidCurveCannotEnterAgreement() {
        assertThrows(IllegalArgumentException::class.java) { CarlinkCrypto.publicKey(ByteArray(64)) }
    }

    @Test
    fun gcmRejectsTamperingReplayAndForgedLengths() {
        val key = ByteArray(16) { it.toByte() }
        val wire = CarlinkCipher(key).seal("private".toByteArray())
        val cipher = CarlinkCipher(key)
        val corrupt = wire.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { cipher.open(corrupt) }
        assertArrayEquals("private".toByteArray(), cipher.open(wire))
        assertThrows(IllegalArgumentException::class.java) { cipher.open(wire) }
        assertThrows(IllegalArgumentException::class.java) { CarlinkCipher(key).open(wire + byteArrayOf(0)) }
    }

    @Test
    fun confirmationMustMatchSequenceAndLocalAddress() {
        val client = CarlinkTestClient()
        CarlinkPairing("123456").use { receiver ->
            val key = client.key(receiver.respond(client.request(), 1))
            val confirm = client.confirmation(key, "127.0.0.1:57219")
            assertThrows(IllegalArgumentException::class.java) { receiver.confirm(confirm.copy(sequence = 1), setOf("127.0.0.1")) }
            assertThrows(IllegalArgumentException::class.java) {
                receiver.confirm(client.confirmation(key, "10.0.0.1:57219"), setOf("127.0.0.1"))
            }
        }
    }
}
