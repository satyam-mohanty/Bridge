# Bridge Android Self-Update System: Architecture & Publishing Guide

This document describes the production-grade self-update system implemented in the Bridge Android client, including cryptographic design, multi-layer verification, UI integration, background scheduling, and the step-by-step release publishing workflow.

---

## 1. Architecture Overview

Bridge uses GitHub Releases for hosting APK binaries and a static, cryptographically signed JSON manifest (`update-manifest.json`) served over HTTPS to advertise new versions.

```
+-----------------------------------------------------------------------------------+
|                              GITHUB INFRASTRUCTURE                                |
|                                                                                   |
|  +-------------------------------------+   +------------------------------------+ |
|  |       Static Update Manifest        |   |           GitHub Release           | |
|  |     (Signed with Ed25519)           |   |       v1.1.0 (APK Asset)           | |
|  |  https://.../update-manifest.json   |   | https://.../bridge-v1.1.0.apk      | |
|  +-------------------------------------+   +------------------------------------+ |
+-----------------------------------------------------------------------------------+
                                   |                                     |
                          1. Fetch & Verify                     2. Safe HTTPS Stream
                             Manifest Signature                    & SHA-256 Digest
                                   v                                     v
+-----------------------------------------------------------------------------------+
|                              BRIDGE ANDROID CLIENT                                |
|                                                                                   |
|  [Background WorkManager (24h)] <---> [UpdateManager] <---> [Dashboard UI Banner] |
|  [App Launch Check]           <---> [UpdatePreferences] <-> [Settings Row Action] |
|                                              |                                    |
|                      3. Multi-Layer Pre-Install Verification                      |
|                         - Package Name matches "com.bridge.android"               |
|                         - Version Code is strictly newer than installed           |
|                         - Signing Certificate matches installed app               |
|                                              |                                    |
|                      4. Native Android PackageInstaller Flow                      |
|                         - FileProvider Content URI                                |
|                         - Unknown Sources Permission Check & Dialog               |
|                         - System Package Confirmation Dialog                      |
+-----------------------------------------------------------------------------------+
```

---

## 2. Multi-Layer Security Model

The update pipeline enforces defense-in-depth at every stage:

| Security Layer | Mechanism | Protection |
|---|---|---|
| **1. Transport Security** | HTTPS TLS Enforced | Rejects unencrypted HTTP; protects against plain-text interception and MITM tampering. |
| **2. Manifest Authenticity** | Ed25519 Digital Signature | Only holders of the offline Ed25519 private key can issue valid update manifests. Tampering with version code, URL, SHA-256, or notes invalidates the signature. |
| **3. Embedded Public Key** | Public key embedded in `UpdateConfig.kt` | Only the public key is distributed with the client. The private key is never committed to git. |
| **4. Binary Integrity** | Streaming SHA-256 Digest | While downloading the APK, bytes are piped into `MessageDigest`. If the final SHA-256 doesn't match the manifest, the file is immediately purged. |
| **5. DoS / Disk Protection** | 150 MB Max Limit & Temp Files | Prevents malicious unbounded stream downloads and disk exhaustion. Uses `.tmp` files with atomic file replacement. |
| **6. Package ID Verification** | `PackageManager.getPackageArchiveInfo` | Guarantees the downloaded APK has package name `com.bridge.android`, blocking malicious third-party apps. |
| **7. Downgrade Protection** | Version Code Monotonicity Check | Requires `apkVersionCode > currentVersionCode`, preventing rollback attacks to older vulnerable builds. |
| **8. Certificate Pinning** | Signing Certificate SHA-256 Match | Compares the cryptographic signing certificates in the downloaded APK against the currently running app's certificates. Rejects any APK signed with an unauthorized developer key. |
| **9. Native Consent** | FileProvider & Unknown Sources Flow | Respects Android's OS-level installation flow and requires explicit user consent in Android's system package installer. |

---

## 3. Key Management & Secrets Policy

### Secrets Stored Outside Git
The repository's `.gitignore` explicitly excludes:
- `*.pem`
- `*.key`
- `*_private_key.*`
- `scripts/release/keys/`
- `secrets/`

### Generating the Ed25519 Keypair
To generate a new signing keypair:
```bash
python3 scripts/release/generate_keypair.py
```
This produces:
- Private Key: `scripts/release/keys/bridge_update_private.key` (with restricted permissions `0600`). **Keep this secret!**
- Public Key (Hex): `91d145812cbfe99c0b16389746afe5e20ec637b5fb491318b8b1261dc1ef53e5` (embedded in `com.bridge.android.update.data.UpdateConfig`).

For automated CI/CD builds, store the private key hex string in your repository secrets as `BRIDGE_UPDATE_PRIVATE_KEY`.

---

## 4. Release Publishing Workflow

Follow these steps when publishing a new release:

### Step 1: Bump Android Version
In `android/app/build.gradle.kts`:
```kotlin
defaultConfig {
    versionCode = 2        // Increment integer code
    versionName = "1.1.0"  // Set semantic version name
}
```

### Step 2: Build the Release APK
```bash
cd android
./gradlew assembleRelease
# The APK is generated at: android/app/build/outputs/apk/release/app-release.apk
```

### Step 3: Generate and Sign the Update Manifest
Run `publish_manifest.py` using your Ed25519 private key:
```bash
python3 scripts/release/publish_manifest.py \
  --apk android/app/build/outputs/apk/release/app-release.apk \
  --version-code 2 \
  --version-name "1.1.0" \
  --apk-url "https://github.com/<owner>/<repo>/releases/download/v1.1.0/bridge-v1.1.0.apk" \
  --notes "• Production-grade self-update system with Ed25519 digital signatures
• SHA-256 and package signature certificate verification
• Background periodic checks via WorkManager
• Performance and stability enhancements" \
  --out release/update-manifest.json
```

The script:
1. Calculates the exact SHA-256 hash and file size in bytes of the APK.
2. Canonicalizes the payload string:
   `"v1\n$versionCode\n$versionName\n$apkUrl\n$apkSha256\n$apkSizeBytes\n$releaseNotes"`
3. Signs the canonical payload using Ed25519.
4. Outputs the validated, self-tested `release/update-manifest.json`.

### Step 4: Publish to GitHub
1. Create a new GitHub Release tagged `v1.1.0`.
2. Attach `app-release.apk` (renamed to e.g. `bridge-v1.1.0.apk`).
3. Commit and push `release/update-manifest.json` to the `main` branch (or upload as a release asset / GitHub Pages), matching `UpdateConfig.DEFAULT_MANIFEST_URL`.

---

## 5. Client Behavior & User Experience

### 1. App Launch Check
- On cold launch, `MainActivity` queries `UpdateManager.checkForUpdate(isManual = false)` in the background without freezing the UI or interrupting the user.
- If an update is detected, the **Update Available Banner** appears on the Dashboard tab with release notes and a "Download & Install" button.

### 2. Periodic WorkManager Check
- `BridgeApp.onCreate()` registers `UpdateScheduler.schedulePeriodicCheck(this)`.
- Runs every 24 hours via `UpdateCheckWorker` with network connectivity constraints (`NetworkType.CONNECTED`).
- If an update is found and update notifications are enabled in Settings, posts a system notification (`UpdateNotificationHelper`).
- Tapping the notification opens `MainActivity` with the update dialog displayed.

### 3. Manual Check in Settings
- Under **Settings → Updates**, the user sees:
  - Current installed version: `Version 1.0 (Build 1)`
  - "Check for Updates" action row
  - "Update Notifications" toggle switch
- Tapping "Check for Updates" runs an immediate HTTPS query:
  - If up-to-date: displays `Bridge is up to date (Version 1.0)`.
  - If update available: opens an interactive Dialog displaying the version, release notes, APK size, and a "Download & Install" button.
  - If network or server error: gracefully displays an explanation dialog with a "Retry" option.

### 4. Download & Progress
- Downloads over HTTPS into `context.cacheDir/updates/` using temporary files.
- The Dashboard banner updates smoothly with progress percentage and downloaded megabytes (e.g. `Downloading... 45% (6.8 MB / 15.0 MB)`).

### 5. Installation & Unknown Sources Flow
- When download finishes and cryptographic verifications pass, the button changes to "Install Update".
- If Bridge does not yet have unknown-source install permission (`canRequestPackageInstalls() == false`), Bridge displays an instructional dialog explaining the requirement and launches Android's unknown sources permission screen.
- When the user enables the setting and returns, the installation automatically prompts Android's native package installer dialog.

---

## 6. Testing & Verification

### Running Automated Unit Tests
```bash
cd android
./gradlew testDebugUnitTest
```

The test suite validates:
- `PureEd25519Test`: RFC 8032 test vectors 1, 2, and 3 verifying curve arithmetic, scalar multiplication, point decoding, and signature verification.
- `Ed25519VerifierTest`: Public key decoding, Base64/Hex encoding, and tamper rejection.
- `Sha256Test`: File and stream SHA-256 calculation.
- `UpdateManifestTest`: JSON parsing, canonical signing string determinism, and verification of generated release manifests.
- `UpdateFlowEndToEndTest`: Complete end-to-end simulation of manifest signing, tamper resistance (detecting altered version codes, URLs, checksums, and notes), and APK checksum validation.
