package com.bridge.android.update.crypto

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Sha256 {

    fun digest(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(data)
        return bytes.toHexString()
    }

    fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        return md.digest().toHexString()
    }

    fun digest(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            md.update(buffer, 0, bytesRead)
        }
        return md.digest().toHexString()
    }

    fun ByteArray.toHexString(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}
