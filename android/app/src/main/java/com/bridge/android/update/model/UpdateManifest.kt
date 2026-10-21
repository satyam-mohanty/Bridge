package com.bridge.android.update.model

import com.bridge.android.update.crypto.Ed25519Verifier
import org.json.JSONObject

data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val minSupportedVersionCode: Long = 0,
    val apkUrl: String,
    val apkSha256: String,
    val apkSizeBytes: Long,
    val releaseNotes: String,
    val publishedAt: String? = null,
    val mandatory: Boolean = false,
    val signature: String? = null
) {
    /**
     * Constructs the canonical byte sequence signed by Ed25519.
     * Must match scripts/release/publish_manifest.py exactly.
     */
    fun getCanonicalPayload(): ByteArray {
        val payloadStr = "v1\n$versionCode\n$versionName\n$apkUrl\n${apkSha256.lowercase()}\n$apkSizeBytes\n$releaseNotes"
        return payloadStr.toByteArray(Charsets.UTF_8)
    }

    /**
     * Verifies the manifest's Ed25519 signature against the embedded public key.
     */
    fun verifySignature(verifier: Ed25519Verifier): Boolean {
        if (signature.isNullOrBlank()) {
            return false
        }
        val canonicalBytes = getCanonicalPayload()
        return verifier.verifyEncodedSignature(canonicalBytes, signature)
    }

    companion object {
        fun fromJson(jsonStr: String): UpdateManifest {
            val json = JSONObject(jsonStr)
            return UpdateManifest(
                versionCode = json.getLong("versionCode"),
                versionName = json.getString("versionName"),
                minSupportedVersionCode = json.optLong("minSupportedVersionCode", 0L),
                apkUrl = json.getString("apkUrl"),
                apkSha256 = json.getString("apkSha256").trim().lowercase(),
                apkSizeBytes = json.getLong("apkSizeBytes"),
                releaseNotes = json.optString("releaseNotes", ""),
                publishedAt = json.optString("publishedAt").takeIf { it.isNotBlank() },
                mandatory = json.optBoolean("mandatory", false),
                signature = json.optString("signature").takeIf { it.isNotBlank() }
            )
        }
    }
}
