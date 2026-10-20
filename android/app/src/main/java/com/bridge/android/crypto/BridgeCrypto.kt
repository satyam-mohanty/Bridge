package com.bridge.android.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BridgeCrypto(private val key: ByteArray) {

    init {
        require(key.size == 32) { "Key must be exactly 32 bytes for AES-256" }
    }

    companion object {
        const val DIRECTION_MAC_TO_ANDROID: Byte = 0x01
        const val DIRECTION_ANDROID_TO_MAC: Byte = 0x02
        const val TAG_LENGTH_BITS = 128
        const val TAG_LENGTH_BYTES = 16
        const val NONCE_LENGTH_BYTES = 12

        fun buildNonce(direction: Byte, counter: Long): ByteArray {
            val buffer = ByteBuffer.allocate(NONCE_LENGTH_BYTES).order(ByteOrder.BIG_ENDIAN)
            buffer.put(direction)
            buffer.put(0.toByte())
            buffer.put(0.toByte())
            buffer.put(0.toByte())
            buffer.putLong(counter)
            return buffer.array()
        }
    }

    fun encrypt(plaintext: ByteArray, counter: Long, direction: Byte): ByteArray {
        val nonce = buildNonce(direction, counter)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(TAG_LENGTH_BITS, nonce)
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        // Standard Java AES/GCM/NoPadding appends the 16-byte authentication tag
        return cipher.doFinal(plaintext)
    }

    fun decrypt(ciphertextWithTag: ByteArray, counter: Long, direction: Byte): ByteArray {
        require(ciphertextWithTag.size >= TAG_LENGTH_BYTES) { "Ciphertext too short to contain auth tag" }
        val nonce = buildNonce(direction, counter)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(TAG_LENGTH_BITS, nonce)
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(ciphertextWithTag)
    }
}
