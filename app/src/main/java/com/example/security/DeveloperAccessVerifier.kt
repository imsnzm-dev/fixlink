package com.example.security

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies developer access using PBKDF2-HMAC-SHA256 (12,000 iterations) against
 * obfuscated salt and digest bytes so the secret string is never stored in the binary.
 */
object DeveloperAccessVerifier {

    private const val ITERATIONS = 12_000
    private const val KEY_LENGTH_BYTES = 32

    private val saltMask = intArrayOf(0x5A, 0x3C, 0x71, 0x19)
    private val digestMask = intArrayOf(0xA5, 0x4F, 0x92, 0x17)

    private val maskedSalt = intArrayOf(
        0x1C, 0x44, 0x3D, 0x38, 0x63, 0x4D, 0x52, 0x2B,
        0x6A, 0x0E, 0x47, 0x3D, 0x2C, 0x66, 0x03,
    )

    private val maskedDigest = intArrayOf(
        0x3E, 0xC0, 0x50, 0xD2, 0x64, 0x86, 0x0F, 0xBA,
        0x63, 0x5E, 0xD3, 0xD8, 0xD2, 0x96, 0xF9, 0xDD,
        0x1F, 0x41, 0x2F, 0x62, 0xBB, 0x07, 0x58, 0xC0,
        0xDB, 0xBF, 0x55, 0x14, 0x50, 0x42, 0xFF, 0xA4,
    )

    fun verify(candidate: String): Boolean {
        if (candidate.isEmpty()) return false
        val salt = unmask(maskedSalt, saltMask)
        val expected = unmask(maskedDigest, digestMask)
        val derived = pbkdf2HmacSha256(
            password = candidate.toByteArray(StandardCharsets.UTF_8),
            salt = salt,
            iterations = ITERATIONS,
            keyLengthBytes = KEY_LENGTH_BYTES,
        )
        return MessageDigest.isEqual(derived, expected)
    }

    private fun unmask(encoded: IntArray, mask: IntArray): ByteArray {
        val out = ByteArray(encoded.size)
        for (i in encoded.indices) {
            out[i] = (encoded[i] xor mask[i % mask.size]).toByte()
        }
        return out
    }

    private fun pbkdf2HmacSha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))

        val hashLen = mac.macLength
        val blocks = (keyLengthBytes + hashLen - 1) / hashLen
        val output = ByteArray(blocks * hashLen)
        var offset = 0

        for (blockIndex in 1..blocks) {
            val blockSalt = ByteArray(salt.size + 4)
            System.arraycopy(salt, 0, blockSalt, 0, salt.size)
            ByteBuffer.wrap(blockSalt, salt.size, 4).putInt(blockIndex)

            var u = mac.doFinal(blockSalt)
            val t = u.copyOf()

            for (iter in 2..iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) {
                    t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                }
            }
            System.arraycopy(t, 0, output, offset, hashLen)
            offset += hashLen
        }

        return output.copyOf(keyLengthBytes)
    }
}
