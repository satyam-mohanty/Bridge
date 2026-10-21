package com.bridge.android.update.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ed25519VerifierTest {

    @Test
    fun testVerifierWithRfc8032Vector() {
        val pubKeyHex = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        val msg = ByteArray(0)
        val sigHex = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"

        val verifier = Ed25519Verifier.fromHex(pubKeyHex)
        assertTrue(verifier.verifyEncodedSignature(msg, sigHex))

        // Invalid signature
        val badSig = sigHex.replaceRange(0, 2, "00")
        assertFalse(verifier.verifyEncodedSignature(msg, badSig))
    }

    @Test
    fun testVerifierBase64Signature() {
        // Test vector 2: pubkey 3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c
        val pubKeyHex = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
        val msg = byteArrayOf(0x72)
        val sigHex = "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"

        val verifier = Ed25519Verifier.fromHex(pubKeyHex)
        val sigBytes = ByteArray(sigHex.length / 2) { i ->
            sigHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val sigBase64 = java.util.Base64.getEncoder().encodeToString(sigBytes)

        assertTrue(verifier.verifyEncodedSignature(msg, sigBase64))
    }
}
