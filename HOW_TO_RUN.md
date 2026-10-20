# How to Run Bridge

Bridge is a zero-idle-cost, native LAN clipboard synchronization tool connecting your Mac and your Android phone.

---

## 1. Quick Architecture Overview

- **Mac App**: Native Swift + SwiftUI app (`LSUIElement = false` with standard window + Dock icon + menu bar utility). Features drag-and-drop AirDrop-style sharing, Finder right-click Quick Action, customizable download destination (defaults to `~/Downloads`), and AES-256-GCM encryption on TCP port `48250`.
- **Android App**: Native Kotlin app with a modern Apple-style UI hiding backend clutters into settings. Supports bi-directional generic file sharing (saves directly into public Downloads with tap-to-open notifications) and clipboard sync.

---

## 2. Building & Running the Mac App

### Step 2.1 — Build the Mac App
Open Terminal and navigate to the project directory:
```bash
cd "/Users/satyam/Satyam/passion projects/Bridge/mac"
xcodegen generate
xcodebuild -scheme Bridge -configuration Debug build
```

### Step 2.2 — Run the Mac App
Launch the compiled app:
```bash
cp -R "/Users/satyam/Library/Developer/Xcode/DerivedData/Bridge-gvcxcocaxmfeyoctxmmxalsbbseq/Build/Products/Debug/Bridge.app" /Applications/
open /Applications/Bridge.app
```

The app opens as a full native macOS window with Dock presence and a menu bar item:
- **Drag & Drop Zone**: Drag any files from Finder directly into the Bridge window or click "Select Files..." to send immediately to your phone.
- **Save Location Settings**: Click "Change..." to set any custom folder (defaults to `~/Downloads`).
- **Finder Right-Click**: Right-click any file in Finder ➔ **Services** / **Quick Actions** ➔ **Send to Android via Bridge**.
- **Menu Bar Icon**: Quick access to pairing QR code, diagnostics, and settings.

---

## 3. Building & Installing the Android App

### Step 3.1 — Build the APK
The pre-built APK is located at:
```
android/app/build/outputs/apk/debug/app-debug.apk
```

To recompile at any time:
```bash
cd "/Users/satyam/Satyam/passion projects/Bridge/android"
export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
export ANDROID_HOME="/Users/satyam/Library/Android/sdk"
./gradlew assembleDebug
```

### Step 3.2 — Install onto Your Phone
Connect your Android phone via USB (with USB Debugging enabled in Developer Options):
```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

---

## 4. Pairing Mac and Android

1. On your **Mac**, click the Bridge menu bar icon and select **"Pair New Phone..."**. A window appears showing a QR code.
2. On your **Phone**, open the **Bridge** app.
3. Tap **"Scan QR Code"**. Point your camera at the Mac screen.
4. *Fallback*: If camera scanning is not preferred, click **"Copy Pairing Code"** on your Mac, send it to your phone, and tap **"Paste Pairing Code"** in the Android app.
5. Once paired, the status indicator on both devices turns **Green ("Connected")** within 1–2 seconds!

---

## 5. Manual Test Checklist (Exact Taps)

### Test A: Text Sync (Mac ➔ Android)
1. On your Mac, highlight any text in Safari, Notes, or Terminal and press `Cmd + C`.
2. Look at your phone: the Bridge persistent notification or open app updates instantly.
3. Long-press any text field on your phone and tap **Paste**.
4. **Expected Result**: The exact text from your Mac appears on your phone.

### Test B: Real-Time Text Sync (Android ➔ Mac)
1. Ensure Bridge is enabled in **Settings ➔ Accessibility ➔ Downloaded Apps ➔ Bridge** (or run `adb shell settings put secure enabled_accessibility_services com.bridge.android/.service.BridgeAccessibilityService && adb shell settings put secure accessibility_enabled 1`).
2. Copy any text anywhere on your phone (e.g. in Chrome, WhatsApp, Notes, Twitter).
3. The copied text is automatically detected and sent to your Mac in real time!
4. Switch to your Mac and press `Cmd + V`.
5. **Expected Result**: The text copied on your phone appears immediately on your Mac.

### Test B2: Text Sync (Android ➔ Mac via Quick Settings Tile or Notification)
1. Pull down the notification shade and tap **"Send to Mac"** directly from the persistent Bridge notification, or use the **"Send to Mac"** Quick Settings tile.
2. Switch to your Mac and press `Cmd + V`.

### Test C: Image Sync (Mac ➔ Android)
1. On your Mac, copy an image to your clipboard (e.g., right-click an image in a browser and choose "Copy Image", or take a screenshot to clipboard with `Cmd + Ctrl + Shift + 4`).
2. Bridge automatically downscales if over 4096 px, compresses to PNG (or JPEG if > 10 MB), and streams it in 64 KB encrypted chunks with a SHA-256 check.
3. On your phone, open Google Messages, WhatsApp, or Google Keep and tap **Paste**.
4. **Expected Result**: The image is pasted directly from your clipboard!

### Test D: Image Sync (Android ➔ Mac via Share Sheet)
### Test D: File Sharing (Android ➔ Mac via Share Sheet or App)
1. In any Android app (Files, Gallery, Chrome, Google Drive, WhatsApp): select any file (PDF, image, video, zip, document) and tap **Share**.
2. Select **Bridge** in the system Share sheet.
   *(Alternatively, open Bridge on your phone and tap **"Send Files to Mac"** to pick any file).*
3. **Expected Result**: 
   - Android shows a smooth transfer progress without freezing.
   - On Mac, the file is saved automatically into `~/Downloads` (or your configured custom folder).
   - Mac displays a native macOS system notification: *"Received [filename] from Android"* and plays a system chime!
   - In the Bridge Mac window, the file appears in "Recent Transfers" with a "Show in Finder" button.

### Test E: File Sharing (Mac ➔ Android via Drag & Drop, Finder Right-Click, or Window)
1. On your Mac, you can share files in 3 ways:
   - **Drag and Drop**: Drag any file(s) into the Bridge macOS window drop zone.
   - **Window Picker**: Click "Select Files to Send..." in the Bridge window.
   - **Finder Right-Click**: Right-click any file in Finder ➔ **Services** / **Quick Actions** ➔ **Send to Android via Bridge**.
2. **Expected Result**:
   - The file is encrypted with AES-256-GCM and transferred in chunks to your phone.
   - On Android, the file is saved directly into your phone's public **Downloads** directory.
   - A high-priority Android notification appears: *"Received [filename] from Mac • Tap to open"*.
   - Tapping the notification immediately opens the file in your preferred viewer!
1. With both devices connected (Green status), turn Wi-Fi OFF on your phone for 5 seconds, then turn it back ON.
2. **Expected Result**: Bridge automatically detects the network change via `ConnectivityManager` and reconnects to your Mac within 2–3 seconds.
3. Put your Mac to sleep (close the lid or `Apple Menu ➔ Sleep`) for 10 seconds, then wake it up.
4. **Expected Result**: Bridge instantly restarts its network listeners and reconnects cleanly without needing any manual restart.

### Test F: Hotspot Test
1. Disconnect your Mac from home Wi-Fi.
2. Turn on **Personal Hotspot** on your Android phone.
3. Connect your Mac's Wi-Fi to your phone's Hotspot.
4. **Expected Result**: Bridge's subnet scanner (`192.168.43.x`) automatically discovers your Mac on the hotspot subnet and establishes the secure link!

### Test G: Optional "Auto Mode" (Background Capture via ADB)
Android blocks background apps from reading the clipboard unless triggered by the user (Tile or Share Sheet). If you want 100% automatic phone-to-Mac copy without tapping anything:
1. Connect phone to Mac via USB and run:
   ```bash
   adb shell pm grant com.bridge.android android.permission.READ_LOGS
   ```
2. In Android settings, allow Bridge's **Display over other apps** permission. Bridge uses a transparent reader because Android 10+ prevents background apps from reading clipboard content.
3. In the Android Bridge app, toggle **"Auto-Sync Clipboard"** to ON.
4. Copy any text anywhere on your phone.
5. **Expected Result**: Bridge detects the system clipboard copy event in logcat, briefly reads it, and syncs it to your Mac clipboard automatically.

### Test H: Android Phone Notifications on Mac (Stage A)

1. **Permission Setup**:
   - On Android: Open Bridge ➔ Tap Settings gear ➔ Tap **Notification Sync**.
   - Tap **Notification access** (shows "Not allowed") ➔ In system settings, find **Bridge** and switch it **ON** (Allow). Return to Bridge: status changes to **Allowed**.
   - Turn **Send notifications to Mac** master switch **ON**.
2. **App Whitelist Selection**:
   - Scroll through "All apps" or search for apps you want to receive on your Mac (e.g. WhatsApp, Messages, Slack).
   - Toggle the switch **ON** for those apps (all other apps remain OFF by default).
3. **Notification Forwarding Test**:
   - Send yourself a test message or have someone message you on the enabled app.
   - Look at your Mac: a native macOS system notification banner appears immediately!
   - **Title**: Sender or conversation title.
   - **Subtitle**: App name.
   - **Body**: Message content.
   - **Attachment**: Contact photo (if present in the notification) or app icon.
4. **Notification Replacement & Debounce**:
   - Send consecutive messages in the same conversation on phone.
   - The existing notification on macOS is updated in-place (same key) rather than creating duplicates.
5. **Notification Dismissal / Dismiss-on-Phone**:
   - Dismiss or swipe away the notification on your phone.
   - **Expected Result**: The corresponding notification is immediately removed from the Mac Notification Center.
6. **Message Preview Privacy**:
   - In Android (or Mac) Notification Settings, turn **Show message previews** **OFF**.
   - Receive a message.
   - **Expected Result**: Mac notification displays only the app name and `"New message"`.
7. **Offline Queue Test**:
   - Click "Disconnect" in the Mac menu bar.
   - Receive a notification on the phone.
   - Reconnect within 2 minutes.
   - **Expected Result**: Bridge automatically flushes the queued notification upon reconnection and displays it on Mac.
8. **Mac Test Notification Button**:
   - On Mac: Open Bridge Settings (`Cmd + ,` or Settings tab) ➔ Click **Notifications** tab ➔ Click **Send test notification**.
   - **Expected Result**: A native test notification banner appears on your Mac immediately.

### Test H: Incoming Phone Calls (Stage B)
1. **Enable Incoming Calls in Android Settings**:
   - In Bridge Android app ➔ Tap **Settings** ➔ Tap **Notifications**.
   - Toggle **"Show incoming calls on Mac"** **ON**.
   - When prompted by Android, grant **Phone**, **Call Logs**, and **Contacts** permissions.
   - Status subtext changes to **"Allowed"** (in green).
2. **Ringing Call**:
   - Have someone call your phone or place a test call from another number.
   - Look at your Mac: a native macOS notification banner appears immediately with a ring sound!
   - **Title**: `"Incoming call"`.
   - **Subtitle**: Resolved contact name (from your contacts) or phone number.
   - **Body**: Phone number or incoming call indicator.
3. **Answered Call**:
   - Answer the call on your phone.
   - **Expected Result**: The "Incoming call" banner disappears immediately from your Mac screen.
4. **Missed Call (Ended without Answer)**:
   - Call your phone again, let it ring, and let the caller hang up (or let it ring out).
   - **Expected Result**: The "Incoming call" banner clears and is replaced with a **"Missed call from <name or number>"** notification in your macOS Notification Center!
5. **Answered Call Hang Up**:
   - When a call that was answered is hung up, no missed call notification is posted.

---

## 6. Power & Reliability Settings (One-Time Setup)

To prevent aggressive Android battery savers from killing Bridge:
1. In the Bridge Android app, tap **"Disable Battery Optimization"** and select **Allow / Unrestricted**.
2. Tap **"OEM Background Settings"** for step-by-step instructions for Samsung, Xiaomi, and OnePlus devices.

---

## 7. Diagnostics

If you ever encounter an issue:
- **On Mac**: Click Bridge menu bar icon ➔ **Copy Diagnostics**.
- **On Android**: Open Bridge ➔ Tap **Copy Diagnostics**.
Both copy a timestamped log of the last 200 connection, encryption, discovery, and transfer events.
