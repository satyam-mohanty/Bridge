package com.bridge.android.update

import com.bridge.android.update.crypto.Ed25519Verifier
import com.bridge.android.update.crypto.Sha256
import com.bridge.android.update.data.ApkDownloadResult
import com.bridge.android.update.data.UpdateDownloader
import com.bridge.android.update.model.UpdateManifest
import com.bridge.android.update.model.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyPairGenerator
import java.security.Signature

class UpdateFlowEndToEndTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSignatureTamperResistance() {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val kp = kpg.generateKeyPair()

        // Extract raw 32-byte public key from X509 spec (last 32 bytes)
        val x509Bytes = kp.public.encoded
        val rawPubKey = x509Bytes.copyOfRange(x509Bytes.size - 32, x509Bytes.size)

        val verifier = Ed25519Verifier(rawPubKey)

        val originalManifest = UpdateManifest(
            versionCode = 5,
            versionName = "2.0.0",
            apkUrl = "https://github.com/satyam-mohanty/Bridge/releases/download/v2.0.0/bridge.apk",
            apkSha256 = "11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff",
            apkSizeBytes = 1234567,
            releaseNotes = "Major 2.0 release!\nNew self-update flow."
        )

        // Sign canonical bytes
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(kp.private)
        signer.update(originalManifest.getCanonicalPayload())
        val sigBytes = signer.sign()
        val sigHex = sigBytes.joinToString("") { String.format("%02x", it) }

        val signedManifest = originalManifest.copy(signature = sigHex)

        // 1. Valid signature passes
        assertTrue("Authentic signed manifest should verify", signedManifest.verifySignature(verifier))

        // 2. Tampered version code fails
        val tamperedVersion = signedManifest.copy(versionCode = 6)
        assertFalse("Tampered version code must fail signature verification", tamperedVersion.verifySignature(verifier))

        // 3. Tampered APK URL fails (e.g. redirecting to malicious host)
        val tamperedUrl = signedManifest.copy(apkUrl = "https://evil.com/malicious.apk")
        assertFalse("Tampered APK URL must fail signature verification", tamperedUrl.verifySignature(verifier))

        // 4. Tampered SHA-256 checksum fails
        val tamperedHash = signedManifest.copy(apkSha256 = "0000000000000000000000000000000000000000000000000000000000000000")
        assertFalse("Tampered SHA-256 must fail signature verification", tamperedHash.verifySignature(verifier))

        // 5. Tampered release notes fail
        val tamperedNotes = signedManifest.copy(releaseNotes = "Hacked release notes")
        assertFalse("Tampered notes must fail signature verification", tamperedNotes.verifySignature(verifier))
    }

    @Test
    fun testApkChecksumIntegrityCheck() {
        val dummyApkBytes = "PK\u0003\u0004FakeAndroidPackageContentForBridgeTesting12345".toByteArray(Charsets.UTF_8)
        val correctHash = Sha256.digest(dummyApkBytes)

        val apkFile = tempFolder.newFile("test-package.apk")
        apkFile.writeBytes(dummyApkBytes)

        val computedHash = Sha256.digest(apkFile)
        assertEquals(correctHash, computedHash)

        // Test with matched checksum
        val validManifest = UpdateManifest(
            versionCode = 2,
            versionName = "1.1.0",
            apkUrl = apkFile.toURI().toURL().toString(),
            apkSha256 = computedHash,
            apkSizeBytes = apkFile.length(),
            releaseNotes = "Test"
        )

        // Corrupted file check: if someone modifies 1 byte in the file
        val corruptedApkBytes = dummyApkBytes.copyOf()
        corruptedApkBytes[0] = 0x00
        val corruptedHash = Sha256.digest(corruptedApkBytes)
        assertFalse("Corrupted file must produce different SHA-256", corruptedHash == validManifest.apkSha256)
    }

    @Test
    fun testUpdateStateTransitions() {
        var state: UpdateState = UpdateState.Idle
        assertEquals(UpdateState.Idle, state)

        state = UpdateState.Checking
        assertEquals(UpdateState.Checking, state)

        val manifest = UpdateManifest(
            versionCode = 3,
            versionName = "1.2.0",
            apkUrl = "https://example.com/app.apk",
            apkSha256 = "dummy",
            apkSizeBytes = 1000,
            releaseNotes = "Notes"
        )
        state = UpdateState.UpdateAvailable(manifest)
        assertTrue(state is UpdateState.UpdateAvailable)
        assertEquals(3L, (state as UpdateState.UpdateAvailable).manifest.versionCode)

        state = UpdateState.Downloading(50, 500, 1000)
        assertTrue(state is UpdateState.Downloading)
        assertEquals(50, (state as UpdateState.Downloading).progressPercent)

        val fakeFile = File("/tmp/test.apk")
        state = UpdateState.ReadyToInstall(fakeFile, manifest)
        assertTrue(state is UpdateState.ReadyToInstall)

        state = UpdateState.Error("Network failure", isNetworkError = true)
        assertTrue(state is UpdateState.Error)
        assertTrue((state as UpdateState.Error).isNetworkError)
    }
}
