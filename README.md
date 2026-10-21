# Bridge

> Seamless, zero-cloud peer-to-peer ecosystem bridging macOS and Android over your local Wi-Fi network. Built with end-to-end AES-256-GCM encryption, native Liquid Glass design on macOS, and material refinement on Android.

---

## ⚡ What is Bridge?

Bridge brings the native Apple Continuity / AirDrop experience to macOS and Android:
- 📋 **Bi-directional Clipboard Sync**: Instant sync for plain text, links, and images (PNG/JPEG).
- 📁 **AirDrop-style File Transfers**: Drag-and-drop any file on Mac to send to your phone, or share from Android straight into your Mac's `~/Downloads`.
- 🔔 **Notification Forwarding**: View phone notifications directly on macOS with dismiss controls.
- 📞 **Incoming Call Alerts**: Get notified of incoming phone calls right on your Mac desktop with full caller ID.
- 🔒 **Zero Cloud & E2E Encrypted**: No third-party servers, accounts, or telemetry. All communication stays strictly on your local network secured with authenticated AES-256-GCM cryptography.
- 🔋 **Zero-Idle Battery Drain**: Smart LAN discovery with background search controls and homescreen toggles.

---

## 🚀 Quick Start Guide

### 📱 Android Setup (APK)

#### Option 1: Install Pre-built APK via Phone Browser
1. Download `Bridge.apk` directly to your phone.
2. Open your phone's **Files** or **Downloads** app and tap `Bridge.apk`.
3. If prompted with *"Install unknown apps"*, allow permission for your browser/file manager.
4. Tap **Install** and open **Bridge**.

#### Option 2: Install via USB / ADB
```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

#### Android First-Run Permissions:
To enable seamless background sync, grant the following permissions when prompted:
1. **Notification Access**: Needed to mirror Android notifications and call alerts to your Mac.
2. **Battery Optimization (Ignore)**: Allows Bridge to receive files and clipboard data while your screen is locked.
3. **Storage / Media Access**: Allows saving received files to your public `Downloads` directory.
4. **Homescreen Widget**: Add the **Bridge Quick Toggle** widget to your home screen to easily enable or disable device search anytime.

---

### 💻 macOS Setup (.app)

#### Option 1: Using Pre-compiled Bridge.app
1. Download or move `Bridge.app` into your `/Applications` folder:
   ```bash
   cp -R Bridge.app /Applications/
   ```
2. Double-click **Bridge** in `/Applications` or launch from Spotlight (`Cmd + Space` ➔ `Bridge`).
3. If macOS displays a gatekeeper prompt on first launch:
   - Right-click `Bridge.app` ➔ click **Open** ➔ click **Open**.
   - Or run in Terminal: `xattr -cr /Applications/Bridge.app`

#### macOS First-Run Configuration:
1. **Notifications**: Allow Bridge to show banner alerts for incoming files, notifications, and calls.
2. **Launch at Login**: Enable the toggle in Bridge settings so your Mac connects automatically when you start work.
3. **Default Download Location**: Files sent from Android land in `~/Downloads` automatically (customizable in Settings).

---

## 🔗 Pairing Your Devices (One-Time Setup)

Pairing takes less than 5 seconds using a local QR code exchange:

1. **Open Bridge on Mac**:
   - Click the **Pairing QR...** button in the main window (or click the menu bar icon ➔ **Pair New Phone...**).
   - A QR code containing an ephemeral AES key and device identifier will appear.
2. **Scan on Android**:
   - Open Bridge on your phone and tap **Scan QR Code**.
   - Point your camera at your Mac screen.
   *(Manual Fallback: You can also tap "Copy Pairing Code" on Mac and paste it into the Android app).*
3. **Connected!**: Both devices instantly pair, negotiate secure keys, and the connection badge switches to **Connected**.

---

## 🛠️ How to Build From Source

### Prerequisites
- **macOS**: Xcode 15+, Command Line Tools, macOS Sonoma / Sequoia (14.0+)
- **Android**: Android Studio Hedgehog+ or JDK 17 with Android SDK (API 34)

### Building the macOS App
```bash
cd mac
xcodegen generate # (if modifying project spec)
xcodebuild -project Bridge.xcodeproj -scheme Bridge -configuration Release clean build CODE_SIGN_IDENTITY="" CODE_SIGNING_REQUIRED=NO CODE_SIGN_ENTITLEMENTS="" CODE_SIGNING_ALLOWED=NO -derivedDataPath build
cp -R build/Build/Products/Release/Bridge.app /Applications/
```

### Building the Android APK
```bash
cd android
./gradlew assembleDebug
# Generated APK: android/app/build/outputs/apk/debug/app-debug.apk
```

---

## 🔒 Security & Privacy

- **Authenticated Encryption**: Every packet is encrypted with `AES-256-GCM` using ephemeral session keys derived from pairing secrets.
- **Local Network Only**: Bridge operates strictly on your LAN (TCP port 48250 + UDP discovery). No data ever leaves your router.
- **No Analytics / No Tracking**: Zero telemetry or remote logging.

---

## ☕ Support & Author

Built with ❤️ by **Satyam Mohanty**
- 🌐 [Website](https://www.satyammohanty.com)
- 𝕏 [x.com/isatymm](https://x.com/isatymm)
- 💼 [LinkedIn](https://www.linkedin.com/in/satyam-mohanty)
- 🐙 [GitHub](https://github.com/satyam-mohanty)
- 📸 [Instagram](https://www.instagram.com/isatymm)
- ☕ [Buy Me a Coffee](https://buymeacoffee.com/satyammohanty)

© 2026 Bridge. All rights reserved.
