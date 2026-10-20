package com.bridge.android

import android.util.Base64
import com.bridge.android.crypto.BridgeCrypto
import com.bridge.android.protocol.BridgeProtocol
import com.bridge.android.util.ImageUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets

class BridgeCryptoTest {

    private fun decodeBase64(base64: String): ByteArray {
        return java.util.Base64.getDecoder().decode(base64)
    }

    @Test
    fun testPrecomputedVectors() {
        val keyBase64 = "QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI="
        val keyBytes = decodeBase64(keyBase64)
        val crypto = BridgeCrypto(keyBytes)

        // Vector 1: Mac -> Android, counter 1
        val cipher1Base64 = "1LjwdKgHynGYtX/zQOr+w+9294txFBf4p2NW5p7iQGYjwiS9YR7ZXeo8fgyiJvl0cg=="
        val cipher1Bytes = decodeBase64(cipher1Base64)

        val decrypted1 = crypto.decrypt(
            cipher1Bytes,
            1L,
            BridgeCrypto.DIRECTION_MAC_TO_ANDROID
        )
        val text1 = String(decrypted1, StandardCharsets.UTF_8)
        assertEquals("Hello Bridge Security Test Vector", text1)

        // Vector 2: Android -> Mac, counter 42
        val cipher2Base64 = "KsalvSlI/yU5Sukg9+tluipRbye65ymDXbV7SpD3P9PcE4bdILqY/GCW"
        val cipher2Bytes = decodeBase64(cipher2Base64)

        val decrypted2 = crypto.decrypt(
            cipher2Bytes,
            42L,
            BridgeCrypto.DIRECTION_ANDROID_TO_MAC
        )
        val text2 = String(decrypted2, StandardCharsets.UTF_8)
        assertEquals("Android to Mac Bridge Ping", text2)
    }

    @Test
    fun testCryptoRoundTrip() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val crypto = BridgeCrypto(key)

        val originalText = "Cross-platform Android to Mac test message with emoji 🚀"
        val plaintext = originalText.toByteArray(StandardCharsets.UTF_8)
        val counter = 12345L

        val ciphertext = crypto.encrypt(
            plaintext,
            counter,
            BridgeCrypto.DIRECTION_ANDROID_TO_MAC
        )

        val decrypted = crypto.decrypt(
            ciphertext,
            counter,
            BridgeCrypto.DIRECTION_ANDROID_TO_MAC
        )

        assertEquals(originalText, String(decrypted, StandardCharsets.UTF_8))
    }

    @Test
    fun testReplayAndCorruptedTagRejection() {
        val key = ByteArray(32) { 0x55 }
        val crypto = BridgeCrypto(key)

        val plaintext = "Secret payload".toByteArray(StandardCharsets.UTF_8)
        val counter = 10L

        val ciphertext = crypto.encrypt(
            plaintext,
            counter,
            BridgeCrypto.DIRECTION_MAC_TO_ANDROID
        )

        // Wrong counter (replay simulation)
        try {
            crypto.decrypt(ciphertext, 9L, BridgeCrypto.DIRECTION_MAC_TO_ANDROID)
            fail("Expected exception for counter mismatch (replay)")
        } catch (_: Exception) {}

        // Wrong direction
        try {
            crypto.decrypt(ciphertext, 10L, BridgeCrypto.DIRECTION_ANDROID_TO_MAC)
            fail("Expected exception for direction mismatch")
        } catch (_: Exception) {}

        // Corrupted auth tag
        val tampered = ciphertext.clone()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()
        try {
            crypto.decrypt(tampered, 10L, BridgeCrypto.DIRECTION_MAC_TO_ANDROID)
            fail("Expected exception for corrupted GCM tag")
        } catch (_: Exception) {}
    }

    @Test
    fun testImageChunkingAndSha256() {
        // 150 KB test data
        val testData = ByteArray(153600) { (it % 256).toByte() }
        val expectedHash = ImageUtils.sha256Hex(testData)

        val chunks = ImageUtils.chunkBytes(testData)
        assertEquals(3, chunks.size) // 64K + 64K + 22K

        var totalSize = 0
        chunks.forEach { totalSize += it.size }
        assertEquals(testData.size, totalSize)

        val reassembled = ByteArray(totalSize)
        var offset = 0
        chunks.forEach { chunk ->
            System.arraycopy(chunk, 0, reassembled, offset, chunk.size)
            offset += chunk.size
        }

        val reassembledHash = ImageUtils.sha256Hex(reassembled)
        assertEquals(expectedHash, reassembledHash)
    }

    @Test
    fun testChunkSerialization() {
        val transferId = "e7b0a3c2-4819-4b62-9721-a3f7215c0a01"
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val original = BridgeProtocol.ImageChunkData(transferId, 5, payload)

        val serialized = original.serialize()
        val deserialized = BridgeProtocol.ImageChunkData.deserialize(serialized)

        assertNotNull(deserialized)
        assertEquals(transferId, deserialized!!.transferId)
        assertEquals(5, deserialized.chunkIndex)
        assertArrayEquals(payload, deserialized.chunkBytes)
    }
}
