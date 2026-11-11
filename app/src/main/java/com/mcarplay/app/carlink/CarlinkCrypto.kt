package com.mcarplay.app.carlink

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Standard JCA primitives; fresh identities belong to this receiver, never to an OEM. */
object CarlinkCrypto {
    private val parameters: ECParameterSpec =
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }

    fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

    fun publicBytes(key: PublicKey): ByteArray {
        val point = (key as ECPublicKey).w

        fun coordinate(value: BigInteger): ByteArray {
            val input = value.toByteArray()
            return ByteArray(32).also { input.copyInto(it, maxOf(0, 32 - input.size), maxOf(0, input.size - 32)) }
        }
        return coordinate(point.affineX) + coordinate(point.affineY)
    }

    fun publicKey(data: ByteArray): PublicKey {
        require(data.size == 64) { "ucar_public_key_size" }
        val x = BigInteger(1, data.copyOfRange(0, 32))
        val y = BigInteger(1, data.copyOfRange(32, 64))
        val prime = (parameters.curve.field as java.security.spec.ECFieldFp).p
        require(x < prime && y < prime) { "ucar_public_key_range" }
        require(
            y.modPow(BigInteger.valueOf(2), prime) ==
                (x.modPow(BigInteger.valueOf(3), prime) + parameters.curve.a * x + parameters.curve.b).mod(prime),
        ) {
            "ucar_public_key_curve"
        }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), parameters))
    }

    fun hash(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    fun sign(
        identity: KeyPair,
        data: ByteArray,
    ): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(identity.private)
            update(data)
            sign()
        }

    fun verify(
        identity: ByteArray,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey(identity))
            update(data)
            verify(signature)
        }

    fun derive(
        ephemeral: KeyPair,
        peer: ByteArray,
        nonces: ByteArray,
    ): ByteArray {
        val shared =
            KeyAgreement.getInstance("ECDH").run {
                init(ephemeral.private)
                doPhase(publicKey(peer), true)
                generateSecret()
            }
        return try {
            hmac(hmac(hash(nonces), shared), "session_key".toByteArray() + byteArrayOf(1)).copyOf(16)
        } finally {
            shared.fill(0)
        }
    }
}

class CarlinkCipher(
    private val secret: ByteArray,
) {
    private val seen = LinkedHashSet<String>()

    init {
        require(secret.size == 16)
    }

    fun seal(plain: ByteArray): ByteArray {
        require(plain.size <= CarlinkPacket.MAX_SIZE - 36)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val encrypted = crypt(Cipher.ENCRYPT_MODE, iv, plain)
        return ByteBuffer
            .allocate(20 + encrypted.size)
            .putInt(12)
            .put(iv)
            .putInt(encrypted.size)
            .put(encrypted)
            .array()
    }

    fun open(record: ByteArray): ByteArray {
        require(record.size in 36..CarlinkPacket.MAX_SIZE) { "ucar_cipher_size" }
        val buffer = ByteBuffer.wrap(record)
        require(buffer.int == 12) { "ucar_cipher_iv" }
        val iv = ByteArray(12).also(buffer::get)
        val size = buffer.int
        require(size >= 16 && size == buffer.remaining()) { "ucar_cipher_length" }
        val marker = iv.joinToString("") { "%02x".format(it) }
        require(marker !in seen) { "ucar_cipher_replay" }
        val result = crypt(Cipher.DECRYPT_MODE, iv, ByteArray(size).also(buffer::get))
        if (seen.size == 4096) seen.remove(seen.first())
        seen += marker
        return result
    }

    private fun crypt(
        mode: Int,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, iv))
            doFinal(data)
        }
}
