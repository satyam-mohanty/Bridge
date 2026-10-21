# Bridge macOS Auto-Updates: Production Deployment & Verification Guide

This guide details the complete production architecture, security model, and operational steps for macOS auto-updates in Bridge using **Sparkle 2.x**, **EdDSA signatures**, **Developer ID signing**, **Apple Notarization**, and **GitHub Releases**.

---

## 1. Prerequisites & Required Credentials

For public distribution outside the Mac App Store with macOS Gatekeeper approval, Apple requires Developer ID code signing and Notarization:

1. **Apple Developer Program Membership**:
   - Requires an active, paid Apple Developer Account (\$99/year).
   - Sign up or log in at [developer.apple.com](https://developer.apple.com/).

2. **Developer ID Application Certificate**:
   - Issued by Apple Developer Portal.
   - Name format in Keychain: `Developer ID Application: <Your Name / Organization> (<Team ID>)`.
   - Used to sign the `.app` bundle, frameworks (`Sparkle.framework`), and nested helpers (`Autoupdate`).

3. **Apple Notarization Credentials (`notarytool`)**:
   - Apple's automated malware scan service.
   - Configure a stored keychain profile once on your Mac:
     ```bash
     xcrun notarytool store-credentials "AC_PASSWORD" \
       --apple-id "your-apple-id@example.com" \
       --team-id "YOUR_TEAM_ID" \
       --password "app-specific-password"
     ```
     *(Generate the app-specific password at [appleid.apple.com](https://appleid.apple.com).)*

4. **Sparkle EdDSA (Ed25519) Keypair**:
   - Sparkle 2 cryptographically signs update archives with modern Ed25519 signatures.
   - The **private key** is kept in your macOS Keychain (or CI secrets) and **never committed to version control**.
   - The **public key** is embedded into `Bridge.app`'s `Info.plist` under `SUPublicEDKey`.

---

## 2. Sparkle Architecture & Project Configuration

### Updater Lifecycle (`UpdateManager.swift`)
- **Controller**: `SPUStandardUpdaterController` runs the updater lifecycle on `@MainActor` with Sparkle's standard user driver.
- **UI Integrations**:
  - **Menu Bar Window**: Quick "Check for Updates…" action in `MenuBarView`.
  - **Settings Window**: Dedicated "Software Updates" section in `SettingsView` (toggles for automatic checks, automatic downloads, and check status).
  - **Application Menu**: Native macOS menu item via `CommandGroup(after: .appInfo)` in `BridgeApp`.
- **Delegates**: Conforms to `SPUUpdaterDelegate`, logging update discovery, downloads, installs, and error states via `LogManager`.

### Build & Signing Configurations
- **`mac/Config/Debug.xcconfig`**: Configured for local development (`CODE_SIGN_IDENTITY = -`, `ENABLE_HARDENED_RUNTIME = NO`).
- **`mac/Config/Release.xcconfig`**: Configured for public release (`CODE_SIGN_IDENTITY = Developer ID Application`, `ENABLE_HARDENED_RUNTIME = YES`, `OTHER_CODE_SIGN_FLAGS = --timestamp`).
- **`mac/Config/Local.xcconfig`**: Ignored in `.gitignore`. Used to locally declare your `DEVELOPMENT_TEAM` and `SPARKLE_PUBLIC_ED_KEY` without committing secrets.
- **`mac/Bridge.entitlements`**: Configured for hardened runtime compatibility.
- **`mac/project.yml`**: Configured with Sparkle SPM dependency (`https://github.com/sparkle-project/Sparkle`), release xcconfigs, and `Info.plist` properties:
  - `SUFeedURL`: `https://raw.githubusercontent.com/satyam-mohanty/Bridge/main/appcast.xml`
  - `SUPublicEDKey`: `$(SPARKLE_PUBLIC_ED_KEY)`
  - `SUEnableAutomaticChecks`: `true`
  - `SUScheduledCheckInterval`: `86400` (daily background checks)
  - `SUAllowsAutomaticUpdates`: `true`

---

## 3. Step-by-Step Setup Instructions

### Step 1: Generate Sparkle EdDSA Signing Keys
Run the included key generation helper:
```bash
./mac/scripts/generate_keys.sh
```
This tool:
1. Generates an Ed25519 private key directly into your macOS Keychain under account `ed25519`.
2. Prints your base64 public key (`SUPublicEDKey`).

Copy the public key and configure it in `mac/Config/Local.xcconfig`:
```xcconfig
SPARKLE_PUBLIC_ED_KEY = <your-base64-public-key>
DEVELOPMENT_TEAM = <your-10-character-team-id>
```

> [!IMPORTANT]
> To export the private key for GitHub Actions CI/CD (without ever checking the file into git):
> ```bash
> ~/Library/Developer/Xcode/DerivedData/Bridge-*/SourcePackages/artifacts/sparkle/Sparkle/bin/generate_keys -x sparkle_key.pem
> ```
> Paste the file content into GitHub Secret `SPARKLE_ED_KEY`, then securely delete `sparkle_key.pem`.

---

### Step 2: Build, Sign, Notarize, and Generate Appcast
Run the automated release pipeline script:
```bash
./mac/scripts/package_release.sh --version 1.0.1 --build 2 --notary-profile "AC_PASSWORD"
```

If testing locally without a paid Apple Developer certificate, pass `--skip-notarization`:
```bash
./mac/scripts/package_release.sh --version 1.0.1 --build 2 --skip-notarization
```

What the script executes:
1. Regenerates the project with `xcodegen generate`.
2. Archives Bridge in `Release` configuration with Hardened Runtime and timestamps.
3. Submits the archive to Apple Notarization via `xcrun notarytool` and staples the notarization ticket to `Bridge.app` (`xcrun stapler staple`).
4. Creates a clean distribution ZIP archive `mac/build/Release-Assets/Bridge-1.0.1.zip` using `ditto -c -k --keepParent`.
5. Uses Sparkle's `generate_appcast` to sign the ZIP with your EdDSA private key from Keychain and outputs `appcast.xml` and release notes `Bridge-1.0.1.md`.
6. Copies the updated `appcast.xml` to the repository root.

---

### Step 3: Publish to GitHub Releases

1. **Tag and push the commit**:
   ```bash
   git tag v1.0.1
   git push origin v1.0.1
   ```

2. **Create the GitHub Release**:
   - Go to `https://github.com/satyam-mohanty/Bridge/releases/new`.
   - Tag: `v1.0.1`.
   - Title: `Bridge 1.0.1`.
   - Description: Copy contents of `mac/build/Release-Assets/Bridge-1.0.1.md`.
   - Attach Binary Asset: Upload `mac/build/Release-Assets/Bridge-1.0.1.zip`.
   - Click **Publish release**.

3. **Publish Updated Appcast Feed**:
   Commit and push the generated `appcast.xml` to `main`:
   ```bash
   git add appcast.xml
   git commit -m "release: update appcast for v1.0.1"
   git push origin main
   ```
   *Clients check `https://raw.githubusercontent.com/satyam-mohanty/Bridge/main/appcast.xml` for updates.*

---

## 4. End-to-End Testing From an Older Installed Build

To verify that the update pipeline works end-to-end on your local machine:

### Test Procedure

1. **Build and install older version (v1.0.0)**:
   - Package v1.0.0:
     ```bash
     ./mac/scripts/package_release.sh --version 1.0.0 --build 1 --skip-notarization
     ```
   - Unzip `mac/build/Release-Assets/Bridge-1.0.0.zip` into `/Applications` or a test folder:
     ```bash
     ditto -x -k mac/build/Release-Assets/Bridge-1.0.0.zip /tmp/TestApp/
     ```
   - Launch `/tmp/TestApp/Bridge.app`. Verify in Settings that it reports `Version 1.0.0 (1)`.

2. **Build newer version (v1.0.1)**:
   - Package v1.0.1:
     ```bash
     ./mac/scripts/package_release.sh --version 1.0.1 --build 2 --skip-notarization
     ```
   - This generates `Bridge-1.0.1.zip` and updates `mac/build/Release-Assets/appcast.xml` with the cryptographic EdDSA signature.

3. **Start a local test feed server**:
   In a separate terminal window:
   ```bash
   cd mac/build/Release-Assets
   python3 -m http.server 8080
   ```

4. **Point the running v1.0.0 app to the test feed**:
   In your terminal, set the test feed URL override in user defaults:
   ```bash
   defaults write com.bridge.mac SUFeedURL "http://localhost:8080/appcast.xml"
   ```
   *(Ensure test appcast item enclosure points to `http://localhost:8080/Bridge-1.0.1.zip` for the local test.)*

5. **Trigger Update Check**:
   - In the running Bridge v1.0.0 app, click the menu bar extra and select **Check for Updates…** (or open Settings > Software Updates > **Check Now**).
   - Observe Sparkle's update dialog displaying:
     - New version `1.0.1`.
     - Release notes rendered cleanly.
     - "Install and Relaunch" button.
   - Click **Install and Relaunch**.
   - Sparkle downloads `Bridge-1.0.1.zip`, validates its Ed25519 signature against `SUPublicEDKey`, replaces the application bundle safely via its helper service, and relaunches the app.
   - Check the relaunched app version in Settings: it will now report `Version 1.0.1 (2)`.

6. **Clean up test defaults**:
   ```bash
   defaults delete com.bridge.mac SUFeedURL
   ```
