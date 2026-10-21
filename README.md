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

### Android Setup (APK)

#### Option 1: Install Pre-built APK via Phone Browser
1. Download `Bridge.apk` directly to your phone.
2. Open your phone's **Files** or **Downloads** app and tap `Bridge.apk`.
3. If prompted with *"Install unknown apps"*, allow permission for your browser or file manager.
4. Tap **Install** and open **Bridge**.

#### Option 2: Install via USB / ADB
```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

#### Android Configuration & Permissions
To enable seamless background synchronization, grant the following permissions when prompted:
1. **Notification Access**: Required to mirror Android notifications and incoming call alerts to your Mac.
2. **Battery Optimization**: Set to unrestricted so Bridge can receive files and clipboard updates while the device screen is off.
3. **Storage / Media Access**: Required to save received files into your public `Downloads` directory.
4. **Homescreen Widget**: Add the **Bridge Quick Toggle** widget to your home screen to enable or disable device discovery at any time.

---

### macOS Setup (.app)

#### Option 1: Using Pre-compiled Bridge.app
1. Download or move `Bridge.app` into your `/Applications` directory:
   ```bash
   cp -R Bridge.app /Applications/
   ```
2. Launch Bridge from `/Applications` or via Spotlight (`Cmd + Space` -> `Bridge`).
3. If macOS displays a Gatekeeper prompt on first launch:
   - Right-click `Bridge.app` -> click **Open** -> click **Open**.
   - Or clear the quarantine attribute via Terminal:
     ```bash
     xattr -cr /Applications/Bridge.app
     ```

#### macOS Configuration
1. **Notifications**: Allow Bridge to deliver banner alerts for incoming files, notifications, and calls.
2. **Launch at Login**: Enable the toggle in Bridge settings to automatically launch the background service at login.
3. **Save Location**: Received files automatically save to `~/Downloads` (customizable in Settings).

---

## Pairing Your Devices

Pairing takes less than 5 seconds using an encrypted local QR code exchange:

1. **On macOS**:
   - Click the **Pairing QR...** button in the main window (or click the menu bar icon -> **Pair New Phone...**).
   - An ephemeral pairing QR code will be generated.
2. **On Android**:
   - Open Bridge on your phone and tap **Scan QR Code**.
   - Point your camera at the Mac screen.
   *(Fallback: You can also tap "Copy Pairing Code" on Mac and paste it into the Android app).*
3. **Connected**: Both devices authenticate immediately, negotiate session keys, and display a **Connected** state.

---

## Building From Source

### Prerequisites
- **macOS**: Xcode 15+, Command Line Tools, macOS 13.0+
- **Android**: Android Studio or JDK 17 with Android SDK (API 34)

### Building macOS App
```bash
cd mac
xcodegen generate
xcodebuild -project Bridge.xcodeproj -scheme Bridge -configuration Release clean build CODE_SIGN_IDENTITY="" CODE_SIGNING_REQUIRED=NO CODE_SIGN_ENTITLEMENTS="" CODE_SIGNING_ALLOWED=NO -derivedDataPath build
cp -R build/Build/Products/Release/Bridge.app /Applications/
```

### Building Android APK
```bash
cd android
./gradlew assembleDebug
# Generated output: android/app/build/outputs/apk/debug/app-debug.apk
```

---

## Security & Architecture

- **Authenticated Encryption**: Every transmission is encrypted with `AES-256-GCM` using ephemeral session keys derived from pairing secrets.
- **Local Network Only**: Bridge operates strictly on your LAN (TCP port 48250 with UDP discovery). No packets leave your local network.
- **Privacy First**: Zero telemetry, zero analytics, and zero external network calls.

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
