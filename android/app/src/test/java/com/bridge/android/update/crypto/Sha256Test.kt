package com.bridge.android.update.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class Sha256Test {

    @Test
    fun testSha256Digest() {
        val input = "Hello, Bridge Update System!".toByteArray(Charsets.UTF_8)
        val hash = Sha256.digest(input)
        assertEquals(64, hash.length)

        val streamHash = Sha256.digest(ByteArrayInputStream(input))
        assertEquals(hash, streamHash)
    }

    @Test
    fun testEmptySha256() {
        val emptyHash = Sha256.digest(ByteArray(0))
        // SHA-256 of empty string is e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", emptyHash)
    }
}
