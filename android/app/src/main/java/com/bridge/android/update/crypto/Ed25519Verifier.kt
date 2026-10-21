package com.bridge.android.update.crypto

import android.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class Ed25519Verifier(val publicKeyBytes: ByteArray) {

    init {
        require(publicKeyBytes.size == 32) {
            "Ed25519 public key must be exactly 32 bytes, was ${publicKeyBytes.size}"
        }
    }

    companion object {
        // ASN.1 DER SubjectPublicKeyInfo prefix for Ed25519 (RFC 8410)
        private val ED25519_X509_PREFIX = byteArrayOf(
            0x30.toByte(), 0x2a.toByte(), 0x30.toByte(), 0x05.toByte(),
            0x06.toByte(), 0x03.toByte(), 0x2b.toByte(), 0x65.toByte(),
            0x70.toByte(), 0x03.toByte(), 0x21.toByte(), 0x00.toByte()
        )

        fun fromHex(hex: String): Ed25519Verifier {
            return Ed25519Verifier(hex.hexToByteArray())
        }

        fun fromBase64(base64Str: String): Ed25519Verifier {
            val bytes = decodeBase64Safe(base64Str)
            return Ed25519Verifier(bytes)
        }

        private fun decodeBase64Safe(str: String): ByteArray {
            return try {
                android.util.Base64.decode(str.trim(), android.util.Base64.DEFAULT)
            } catch (_: Throwable) {
                java.util.Base64.getDecoder().decode(str.replace("\n", "").replace("\r", "").trim())
            }
        }

        private fun String.hexToByteArray(): ByteArray {
            val clean = trim()
            val len = clean.length
            require(len % 2 == 0) { "Hex string must have an even length" }
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }
    }

    /**
     * Verifies signature over message bytes.
     * Tries Java platform Ed25519 provider first, falling back to pure Kotlin RFC 8032 implementation.
     */
    fun verify(message: ByteArray, signatureBytes: ByteArray): Boolean {
        if (signatureBytes.size != 64) {
            return false
        }

        // Attempt platform verification (available on JDK 15+ and Android 13+)
        try {
            val derKey = ByteArray(ED25519_X509_PREFIX.size + publicKeyBytes.size)
            System.arraycopy(ED25519_X509_PREFIX, 0, derKey, 0, ED25519_X509_PREFIX.size)
            System.arraycopy(publicKeyBytes, 0, derKey, ED25519_X509_PREFIX.size, publicKeyBytes.size)

            val spec = X509EncodedKeySpec(derKey)
            val kf = KeyFactory.getInstance("Ed25519")
            val pub = kf.generatePublic(spec)
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(pub)
            sig.update(message)
            return sig.verify(signatureBytes)
        } catch (_: Throwable) {
            // Platform provider not available or threw on this Android OS version.
            // Fall back to pure Kotlin RFC 8032 verifier.
            return PureEd25519.verify(publicKeyBytes, message, signatureBytes)
        }
    }

    /**
     * Verifies signature provided in Base64 or Hex encoding.
     */
    fun verifyEncodedSignature(message: ByteArray, encodedSignature: String): Boolean {
        val clean = encodedSignature.trim()
        val sigBytes = try {
            if (clean.length == 128 && clean.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                clean.hexToByteArray()
            } else {
                decodeBase64Safe(clean)
            }
        } catch (_: Exception) {
            return false
        }
        return verify(message, sigBytes)
    }
}
