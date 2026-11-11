package com.mcarplay.app.carlink

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom

data class CarlinkPhoneEndpoint(
    val address: String,
    val controlPort: Int,
) {
    companion object {
        /** CarWith 4.0.9 confirms "IPv4:port"; older peers can confirm just an IPv4. */
        fun parse(
            value: ByteArray,
            allowedAddresses: Set<String>,
        ): CarlinkPhoneEndpoint {
            require(value.size in 1..80) { "ucar_confirm_endpoint_size" }
            val text =
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString()
            val parts = text.split(':')
            require(parts.size in 1..2) { "ucar_confirm_endpoint" }
            val address = parts[0]
            require(
                address.split('.').let { octets ->
                    octets.size == 4 && octets.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..255 }
                },
            ) { "ucar_confirm_address" }
            require(address in allowedAddresses) { "ucar_confirm_nonlocal_endpoint" }
            val port = if (parts.size == 1) 57219 else parts[1].toIntOrNull() ?: error("ucar_confirm_port")
            require(port in 1024..65535) { "ucar_confirm_port" }
            return CarlinkPhoneEndpoint(address, port)
        }
    }
}

/** One receiver-owned PIN exchange. Byte field numbers come from the original generated protobuf. */
class CarlinkPairing(
    pin: String,
    private val identity: KeyPair = CarlinkCrypto.keyPair(),
) : AutoCloseable {
    private val pinBytes = pin.toByteArray()
    private var expectedConfirmation: Long? = null
    private var secret: ByteArray? = null
    private var nonce: ByteArray? = null
    private var completed = false

    init {
        require(pin.matches(Regex("[0-9]{6}")))
    }

    fun respond(
        request: CarlinkPacket,
        now: Long,
    ): CarlinkPacket {
        check(!completed && expectedConfirmation == null) { "ucar_pairing_state" }
        require(request.source == 1 && request.format == 1 && request.kind == 1 && request.category == 3 && request.method == 1) {
            "ucar_auth_header"
        }
        val fields = CarlinkFields.decode(request.payload)
        require(fields.number(1) in setOf(1L, 0x0501L, 0x0701L, 0x0901L)) { "ucar_auth_version" }
        val workModes = if (fields.contains(10)) fields.number(10) else 1L
        if (workModes and 1L == 0L) return reply(request, now, CarlinkFields().put(1, 0x0901).put(7, 4).put(8, 1))
        // Trust is not borrowed from another receiver. Ask unknown quick peers to perform normal PIN pairing.
        if (!fields.contains(2) || fields.bytes(2).isEmpty()) {
            return reply(request, now, CarlinkFields().put(1, 0x0901).put(7, 1).put(8, 1))
        }
        val clientIdentity = fields.bytes(2)
        val clientEphemeral = fields.bytes(4)
        val clientNonce = fields.bytes(6)
        require(clientNonce.size == 32 && clientEphemeral.size == 64 && clientIdentity.size == 64) { "ucar_auth_sizes" }
        require(fields.bytes(7).size in 1..512 && fields.bytes(8).size in 1..512) { "ucar_auth_identity_size" }
        val proof = CarlinkCrypto.hmac(CarlinkCrypto.hash(clientNonce + pinBytes), clientIdentity)
        require(MessageDigest.isEqual(proof, fields.bytes(3))) { "ucar_auth_pin" }
        require(CarlinkCrypto.verify(clientIdentity, clientEphemeral + clientNonce, fields.bytes(5))) { "ucar_auth_signature" }
        val ephemeral = CarlinkCrypto.keyPair()
        val localEphemeral = CarlinkCrypto.publicBytes(ephemeral.public)
        val localIdentity = CarlinkCrypto.publicBytes(identity.public)
        val localNonce = ByteArray(32).also(SecureRandom()::nextBytes)
        val response =
            CarlinkFields()
                .put(1, 0x0901)
                .put(2, localIdentity)
                .put(3, CarlinkCrypto.hmac(CarlinkCrypto.hash(localNonce + clientNonce + pinBytes), localIdentity + clientIdentity))
                .put(4, localEphemeral)
                .put(5, CarlinkCrypto.sign(identity, localEphemeral + clientEphemeral + localNonce + clientNonce))
                .put(6, localNonce)
                .put(7, 0)
                .put(8, 1)
        secret = CarlinkCrypto.derive(ephemeral, clientEphemeral, clientNonce + localNonce)
        nonce = localNonce
        expectedConfirmation = (request.sequence + 1) and 0xffffffffL
        return reply(request, now, response)
    }

    fun confirm(
        packet: CarlinkPacket,
        allowedAddresses: Set<String>,
    ): Pair<CarlinkPhoneEndpoint, ByteArray> {
        check(!completed && expectedConfirmation != null) { "ucar_pairing_state" }
        require(
            packet.source == 1 && packet.kind == 0 && packet.format == 1 && packet.category == 3 && packet.method == 3 &&
                packet.sequence == expectedConfirmation,
        ) { "ucar_auth_confirm_header" }
        val key = checkNotNull(secret)
        val plain = CarlinkCipher(key).open(CarlinkFields.decode(packet.payload).bytes(1))
        val endpoint = CarlinkPhoneEndpoint.parse(plain, allowedAddresses)
        completed = true
        return endpoint to key.copyOf()
    }

    private fun reply(
        request: CarlinkPacket,
        now: Long,
        fields: CarlinkFields,
    ): CarlinkPacket = CarlinkPacket(request.sequence, now, 0, 1, 2, 3, 2, fields.encode())

    override fun close() {
        completed = true
        pinBytes.fill(0)
        nonce?.fill(0)
        secret?.fill(0)
        secret = null
    }
}
