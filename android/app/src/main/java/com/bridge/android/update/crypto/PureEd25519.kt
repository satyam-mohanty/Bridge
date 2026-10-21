package com.bridge.android.update.crypto

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Pure Kotlin Ed25519 (RFC 8032) signature verifier.
 *
 * Implements Ed25519 verification without external cryptographic libraries or JNI,
 * ensuring reliable execution across all Android API versions (including API 29-32
 * where standard Conscrypt does not expose the EdDSA/Ed25519 provider).
 */
object PureEd25519 {

    private val ZERO = BigInteger.ZERO
    private val ONE = BigInteger.ONE
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)
    private val EIGHT = BigInteger.valueOf(8)

    // p = 2^255 - 19
    val P: BigInteger = TWO.pow(255).subtract(BigInteger.valueOf(19))

    // L = 2^252 + 27742317777372353535851937790883648493
    val L: BigInteger = TWO.pow(252).add(BigInteger("27742317777372353535851937790883648493"))

    // d = -121665 / 121666 mod p
    val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P))
        .mod(P)

    // I = sqrt(-1) mod p = 2^((p-1)/4) mod p
    val I: BigInteger = TWO.modPow(P.subtract(ONE).divide(BigInteger.valueOf(4)), P)

    // Base point By = 4/5 mod p
    val By: BigInteger = BigInteger.valueOf(4)
        .multiply(BigInteger.valueOf(5).modInverse(P))
        .mod(P)

    // Base point Bx (positive, i.e. even)
    val Bx: BigInteger = recoverX(By, 0) ?: error("Failed to compute base point Bx")

    // Base point in extended coordinates
    val B = Point(Bx, By, ONE, Bx.multiply(By).mod(P))

    /**
     * Point on Edwards curve in extended coordinates: (X, Y, Z, T)
     * corresponding to affine coordinates x = X/Z, y = Y/Z with T = X*Y/Z.
     */
    data class Point(val X: BigInteger, val Y: BigInteger, val Z: BigInteger, val T: BigInteger) {
        companion object {
            val IDENTITY = Point(ZERO, ONE, ONE, ZERO)
        }

        fun add(other: Point): Point {
            // Extended coordinates point addition (Hisil et al., 2008)
            // A = (Y1 - X1) * (Y2 - X2)
            // B = (Y1 + X1) * (Y2 + X2)
            // C = 2 * d * T1 * T2
            // D = 2 * Z1 * Z2
            // E = B - A
            // F = D - C
            // G = D + C
            // H = B + A
            // X3 = E * F
            // Y3 = G * H
            // T3 = E * H
            // Z3 = F * G
            val y1MinusX1 = Y.subtract(X).mod(P)
            val y2MinusX2 = other.Y.subtract(other.X).mod(P)
            val a = y1MinusX1.multiply(y2MinusX2).mod(P)

            val y1PlusX1 = Y.add(X).mod(P)
            val y2PlusX2 = other.Y.add(other.X).mod(P)
            val b = y1PlusX1.multiply(y2PlusX2).mod(P)

            val c = TWO.multiply(D).mod(P)
                .multiply(T).mod(P)
                .multiply(other.T).mod(P)

            val dVal = TWO.multiply(Z).mod(P).multiply(other.Z).mod(P)

            val e = b.subtract(a).mod(P)
            val f = dVal.subtract(c).mod(P)
            val g = dVal.add(c).mod(P)
            val h = b.add(a).mod(P)

            val x3 = e.multiply(f).mod(P)
            val y3 = g.multiply(h).mod(P)
            val t3 = e.multiply(h).mod(P)
            val z3 = f.multiply(g).mod(P)

            return Point(x3, y3, z3, t3)
        }

        fun dbl(): Point {
            // Extended coordinates point doubling
            // A = X1^2, B = Y1^2, C = 2 * Z1^2, D = a * A = -A
            // E = (X1 + Y1)^2 - A - B
            // G = D + B = B - A
            // F = G - C
            // H = D - B = -A - B
            // X3 = E * F
            // Y3 = G * H
            // T3 = E * H
            // Z3 = F * G
            val a = X.multiply(X).mod(P)
            val b = Y.multiply(Y).mod(P)
            val c = TWO.multiply(Z).mod(P).multiply(Z).mod(P)

            val xPlusY = X.add(Y).mod(P)
            val e = xPlusY.multiply(xPlusY).subtract(a).subtract(b).mod(P)
            val g = b.subtract(a).mod(P)
            val f = g.subtract(c).mod(P)
            val h = a.negate().subtract(b).mod(P)

            val x3 = e.multiply(f).mod(P)
            val y3 = g.multiply(h).mod(P)
            val t3 = e.multiply(h).mod(P)
            val z3 = f.multiply(g).mod(P)

            return Point(x3, y3, z3, t3)
        }

        fun multiply(scalar: BigInteger): Point {
            var result = IDENTITY
            var current = this
            var s = scalar
            while (s > ZERO) {
                if (s.testBit(0)) {
                    result = result.add(current)
                }
                current = current.dbl()
                s = s.shiftRight(1)
            }
            return result
        }

        fun toAffineX(): BigInteger = X.multiply(Z.modInverse(P)).mod(P)
        fun toAffineY(): BigInteger = Y.multiply(Z.modInverse(P)).mod(P)
    }

    /**
     * Recovers the x-coordinate from y and x_0 (the parity bit).
     * x^2 = (y^2 - 1) / (d y^2 + 1) mod p.
     */
    private fun recoverX(y: BigInteger, x0: Int): BigInteger? {
        if (y >= P) return null
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(ONE).mod(P)
        val v = D.multiply(y2).add(ONE).mod(P)

        // u * v^3 * (u * v^7)^((p-5)/8) mod p
        val v3 = v.pow(3).mod(P)
        val v7 = v3.multiply(v3).mod(P).multiply(v).mod(P)
        val uv7 = u.multiply(v7).mod(P)
        val pMinus5Div8 = P.subtract(BigInteger.valueOf(5)).divide(EIGHT)
        var x = u.multiply(v3).mod(P).multiply(uv7.modPow(pMinus5Div8, P)).mod(P)

        val vx2 = v.multiply(x).mod(P).multiply(x).mod(P)
        if (vx2 != u) {
            if (vx2 == u.negate().mod(P)) {
                x = x.multiply(I).mod(P)
            } else {
                return null
            }
        }

        if (x.mod(TWO).toInt() != x0) {
            x = P.subtract(x).mod(P)
        }
        return x
    }

    /**
     * Decodes a 32-byte little-endian encoded point on the curve.
     */
    fun decodePoint(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val copy = bytes.copyOf()
        val msb = copy[31].toInt() and 0xFF
        val x0 = (msb shr 7) and 1
        copy[31] = (msb and 0x7F).toByte()

        val y = decodeLittleEndian(copy)
        if (y >= P) return null
        val x = recoverX(y, x0) ?: return null
        return Point(x, y, ONE, x.multiply(y).mod(P))
    }

    /**
     * Decodes little-endian bytes to BigInteger.
     */
    fun decodeLittleEndian(bytes: ByteArray): BigInteger {
        val reversed = bytes.reversedArray()
        // Ensure positive BigInteger
        return BigInteger(1, reversed)
    }

    private fun sha512(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        return md.digest(data)
    }

    /**
     * Verifies an Ed25519 signature according to RFC 8032 Section 5.1.7.
     *
     * @param publicKey 32-byte public key
     * @param message arbitrary message bytes
     * @param signature 64-byte signature (R || S)
     * @return true if valid, false otherwise
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) {
            return false
        }

        val rBytes = signature.copyOfRange(0, 32)
        val sBytes = signature.copyOfRange(32, 64)

        val s = decodeLittleEndian(sBytes)
        if (s >= L) {
            return false
        }

        val A = decodePoint(publicKey) ?: return false
        val R = decodePoint(rBytes) ?: return false

        // Compute k = SHA-512(R || A || M) mod L
        val hashInput = ByteArray(32 + 32 + message.size)
        System.arraycopy(rBytes, 0, hashInput, 0, 32)
        System.arraycopy(publicKey, 0, hashInput, 32, 32)
        System.arraycopy(message, 0, hashInput, 64, message.size)

        val kHash = sha512(hashInput)
        val k = decodeLittleEndian(kHash).mod(L)

        // Check [8][s]B = [8]R + [8][k]A  (or [s]B = R + [k]A)
        // With cofactor 8 check to reject small-order points
        val sB = B.multiply(s.multiply(EIGHT).mod(L))
        val kA = A.multiply(k.multiply(EIGHT).mod(L))
        val r8 = R.multiply(EIGHT)
        val rPlusKa = r8.add(kA)

        val sBx = sB.toAffineX()
        val sBy = sB.toAffineY()
        val rPlusKax = rPlusKa.toAffineX()
        val rPlusKay = rPlusKa.toAffineY()

        return sBx == rPlusKax && sBy == rPlusKay
    }
}
