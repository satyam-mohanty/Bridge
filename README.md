# Bridge

> Seamless, zero-cloud peer-to-peer ecosystem bridging macOS and Android over your local Wi-Fi network. Built with end-to-end AES-256-GCM encryption, native Liquid Glass design on macOS, and material refinement on Android.

---

## Overview

Bridge brings a native Continuity and AirDrop experience to macOS and Android:
- **Bi-directional Clipboard Sync**: Instant synchronization for plain text, links, and images (PNG/JPEG).
- **AirDrop-style File Transfers**: Drag-and-drop any file on Mac to send to your phone, or share from Android straight into your Mac's `~/Downloads`.
- **Notification Forwarding**: View phone notifications directly on macOS with dismiss controls.
- **Incoming Call Alerts**: Get notified of incoming phone calls right on your Mac desktop with full caller ID.
- **Zero Cloud & E2E Encrypted**: No third-party servers, accounts, or telemetry. All communication stays strictly on your local network secured with authenticated AES-256-GCM cryptography.
- **Zero-Idle Battery Drain**: Smart LAN discovery with background search controls and homescreen toggles.

---

## Installation & Setup

### Android Setup (.apk)

1. **Download the APK**: Download `Bridge.apk` directly on your Android device.
2. **Open and Install**:
   - Open your device's **Downloads** or **Files** manager and tap `Bridge.apk`.
   - If prompted with *"Install unknown apps"*, toggle **Allow from this source** for your browser or file manager.
   - Tap **Install**, then tap **Open**.
3. **Grant Required Permissions**:
   - **Notification Access**: Needed to mirror phone notifications and incoming call alerts to your Mac.
   - **Battery Optimization**: Set Bridge to **Unrestricted** (or "Don't optimize") so it continues syncing when the screen is locked.
   - **Storage / Media Access**: Needed to save received files into your public `Downloads` folder.
4. **Homescreen Quick Toggle Widget** (Optional):
   - Long-press your home screen, tap **Widgets**, find **Bridge**, and add the toggle widget to instantly turn background device search on or off anytime.

---

### macOS Setup (.dmg)

1. **Download the DMG**: Download `Bridge.dmg` onto your Mac.
2. **Install**:
   - Double-click `Bridge.dmg` to open it.
   - Drag the **Bridge** app icon into your **Applications** folder.
   - Eject the disk image.
3. **Open the App**:
   - Open your **Applications** folder and double-click **Bridge**.
   - *Note on Gatekeeper*: Because the app is distributed directly outside the App Store, macOS may show a prompt on first launch. Right-click (or Control-click) `Bridge.app`, select **Open**, and click **Open** in the dialog.
4. **Permissions & Preferences**:
   - **Notifications**: Click **Allow** when prompted so incoming calls and file transfers display banners.
   - **Launch at Login**: Enable the toggle inside Bridge settings so it connects automatically whenever your Mac boots up.
   - **Save Location**: Received files automatically save directly to your `~/Downloads` folder.

---

## Pairing Your Devices

Pairing takes less than 5 seconds using an encrypted local QR code exchange:

1. **On macOS**:
   - Click the **Pairing QR...** button in the main window (or click the menu bar icon -> **Pair New Phone...**).
   - An ephemeral pairing QR code will appear on screen.
2. **On Android**:
   - Open Bridge on your phone and tap **Scan QR Code**.
   - Point your phone camera at the Mac screen.
   *(Fallback: You can also tap "Copy Pairing Code" on Mac and paste it into the Android app).*
3. **Connected**: Both devices authenticate immediately, negotiate session keys, and display a **Connected** state.

---

## Security & Architecture

- **Authenticated Encryption**: Every transmission is encrypted with `AES-256-GCM` using ephemeral session keys derived from pairing secrets.
- **Local Network Only**: Bridge operates strictly on your LAN (TCP port 48250 with UDP discovery). No packets leave your local network.
- **Privacy First**: Zero telemetry, zero analytics, and zero external network calls.
- **Secure Self-Update**: Android releases feature production-grade self-updates with Ed25519 digitally signed manifests, SHA-256 checksums, signing certificate pinning, and background WorkManager checks. See [Release & Update Guide](docs/RELEASE_UPDATE_GUIDE.md).

---

## Author & Links

Built by **Satyam Mohanty**
- Website: [https://www.satyammohanty.com](https://www.satyammohanty.com)
- X: [https://x.com/isatymm](https://x.com/isatymm)
- LinkedIn: [https://www.linkedin.com/in/satyam-mohanty](https://www.linkedin.com/in/satyam-mohanty)
- GitHub: [https://github.com/satyam-mohanty](https://github.com/satyam-mohanty)
- Instagram: [https://www.instagram.com/isatymm](https://www.instagram.com/isatymm)
- Support: [https://buymeacoffee.com/satyammohanty](https://buymeacoffee.com/satyammohanty)

(c) 2026 Bridge. All rights reserved.
