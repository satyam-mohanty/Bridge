# Bridge

> Seamless, zero-cloud peer-to-peer clipboard sync, file sharing, and notification mirroring between macOS and Android over your local Wi-Fi.

![Bridge Screenshot](https://pro.satyammohanty.com/Bridge/bridge_screen.webp)

---

## Why Isn't Bridge on the App Store or Google Play?

Bridge is currently distributed outside the official Apple App Store and Google Play Store.

> **A note from the developer:**  
> I sincerely apologize for not having Bridge directly available on the Google Play Store and Mac App Store yet. Publishing on official app stores requires paid developer memberships ($99/year for Apple and registration fees for Google) as well as navigating complex policy review cycles.
> 
> I am actively working on bringing Bridge to the Google Play Store very soon! As an independent developer building Bridge completely free without ads, subscriptions, or tracking, your support makes a world of difference.
> 
> If you love Bridge and would like to help me fund the official Google Play and App Store releases, please consider supporting me here:  
> ☕ **[Buy Me a Coffee](https://buymeacoffee.com/satyammohanty)**

1. **Independent Passion Project:** Bridge is a free, open-source project with zero subscriptions, zero telemetry, and zero ads.
2. **Platform Restrictions:** Official app stores impose aggressive sandboxing policies that restrict local peer-to-peer networking, background clipboard synchronization, and notification mirroring.
3. **100% Free & Open Source:** The entire source code for both the macOS and Android apps is published right here under the [MIT License](LICENSE). You can inspect every line of code, verify how your data is handled, and build it from source yourself.

---

## Installation

### Mac Installation

Run this one-line command in your Mac Terminal to install Bridge:

```bash
curl --connect-timeout 3 -fsSL https://raw.githubusercontent.com/satyam-mohanty/Bridge/main/install.sh | bash
```

*(Or if your network is throttling GitHub's raw domain, use the CDN mirror:)*
```bash
curl -fsSL https://cdn.jsdelivr.net/gh/satyam-mohanty/Bridge@main/install.sh | bash
```

The script will automatically download the latest `Bridge.app`, place it cleanly into your `~/Applications` folder, configure permissions, and launch the app. After install, Bridge opens like any native macOS app—you won't need Terminal again.

---

### Android Installation

1. **Download the APK**: On your Android phone, download the latest [`Bridge.apk`](https://github.com/satyam-mohanty/Bridge/releases/latest/download/Bridge.apk) from the [Releases](https://github.com/satyam-mohanty/Bridge/releases) page.
2. **Open the APK**: Open your device's **Files** or **Downloads** app and tap `Bridge.apk`.
3. **Allow Unknown Sources**: If prompted with *"For your security, your phone is not allowed to install unknown apps from this source"*, tap **Settings** and toggle **Allow from this source**.
4. **Google Play Protect**: Because Bridge is a direct release APK not yet indexed by Google's automated scanners, Google Play Protect may show a warning or prevent the install prompt.
   * **If prompted with a warning dialog**:
     1. Tap **More details** (or the small dropdown arrow).
     2. Tap **Install anyway**.
   * **If the install button is blocked or doesn't appear**:
     1. Open the **Google Play Store** app on your phone.
     2. Tap your **profile icon** in the top-right corner.
     3. Tap **Play Protect**.
     4. Tap the **Settings (gear icon ⚙️)** in the top-right corner.
     5. Temporarily toggle **OFF** *"Scan apps with Play Protect"*.
     6. Return to your **Files/Downloads** app and tap `Bridge.apk` to install. (You can turn Play Protect back on afterward).
5. **Open Bridge**: Tap **Open** and follow the quick setup guide to pair with your Mac!

#### Android Permissions Explained (In Plain English)

Bridge only asks for permissions necessary for features you choose to use. None of your data ever leaves your devices:

| Permission | Why Bridge Asks For It | Plain English Explanation |
| :--- | :--- | :--- |
| **Notifications** | Required on Android 13+ | Shows a quiet background status indicator and download progress when transferring files. |
| **Notification Listener** | Optional | Allows Bridge to forward notification banners from apps you select to your Mac. You can filter specific apps and hide sensitive previews. |
| **Storage / Photos** | Required for files & images | Lets Bridge save files and images received from your Mac into your public `Downloads` folder. |
| **Camera** | Optional | Used only when you tap "Scan QR Code" during pairing to scan the QR code displayed on your Mac screen. |
| **Phone & Contacts** | Optional (Requested only if turned ON) | If you enable "Incoming call alerts", Bridge checks incoming call state and displays the caller's name on your Mac desktop. |

---

## Privacy: 100% Local Network

- **No Remote Servers:** Bridge operates entirely peer-to-peer. There are zero servers, zero cloud proxies, and zero relay nodes.
- **No User Accounts:** No sign-up, no login, no emails, no passwords.
- **Zero Telemetry & Analytics:** No tracking SDKs, no Google Analytics, no Firebase, no crash loggers, and no ads.
- **End-to-End Encrypted:** Every single packet sent over your Wi-Fi is encrypted using authenticated **AES-256-GCM** with ephemeral keys negotiated during pairing.

Read our full [Privacy Policy](PRIVACY.md).

---

## Troubleshooting

### 1. Notification Access Greyed Out ("Restricted setting")
On Android 13 and newer, Android automatically restricts notification listening permissions for sideloaded apps:
1. Open your phone's **Settings** > **Apps** > **Bridge**.
2. Tap the **three vertical dots (⋮)** in the top right corner.
3. Tap **Allow restricted settings**.
4. Authenticate with your phone's fingerprint, PIN, or pattern.
5. Return to Bridge or **Settings > Notifications > Device & app notifications** and enable Bridge.

### 2. Battery Optimization & Background Disconnects
Android device manufacturers (such as Samsung, Xiaomi, OnePlus, and Google) aggressively terminate background services to save battery:
- Open Bridge on Android, open the settings/info drawer, and tap **"Disable Battery Optimization"**.
- Alternatively, go to **Settings > Apps > Bridge > Battery** and choose **Unrestricted** (or "Don't optimize").
- On Xiaomi (MIUI/HyperOS), ensure **Autostart** is turned on.

### 3. Devices Cannot Find Each Other (Wi-Fi Client Isolation)
Some routers—especially public, university, hotel, or office "Guest" networks—enable **Client Isolation** (also called AP Isolation). This setting prevents Wi-Fi devices from seeing or communicating with each other:
- Verify both your Mac and Android phone are connected to the **same Wi-Fi network**.
- Check if your router has "AP Isolation" or "Client Isolation" turned ON in its admin panel; turn it OFF.
- If you have separate 2.4 GHz and 5 GHz networks, make sure both devices are on the same band or that LAN bridging is enabled on the router.

### 4. Hotspot Fallback (No Wi-Fi / Traveling)
If you are on an isolated network, traveling, or do not have access to a Wi-Fi router:
1. Turn on **Personal Hotspot** on your Android phone.
2. Connect your Mac to your phone's hotspot Wi-Fi.
3. Open Bridge on both devices.
Bridge communicates directly over the hotspot network at full local speeds with no cellular data consumed for transfers.

---

## Building from Source

### Mac App
```bash
./install-mac.sh
```

### Android App
To compile a signed release APK using your own local keystore:
```bash
./build-android-release.sh -k path/to/release.keystore -a bridge
```
The signed APK will be generated at `releases/bridge-vX.Y.apk`.

---

## 🚀 Upcoming Features & Roadmap

Bridge is constantly evolving! Here are some of the ambitious features currently planned or in development:

- 📞 **Attending & Answering Calls Directly from Mac**: Take incoming phone calls and talk straight through your Mac speakers and microphone.
- 📷 **Android as a Secondary Camera**: Turn your high-quality Android camera into an ultra-sharp wireless webcam for Zoom, Google Meet, Teams, FaceTime, and streaming on macOS.
- 🖥️ **Screen Mirroring & Mac Remote Control**: Mirror your Android display seamlessly onto your Mac desktop and control your phone with your Mac keyboard and trackpad.

---

## 🤝 Open Source & Welcoming Contributions!

**Bridge is 100% Free & Open Source.** 

We passionately believe in building community-driven, private, cloudless software. Contributions of all kinds are warmly welcomed! Whether you are:
- An Android or macOS / Swift developer excited about implementing call integration, camera streaming, or remote screen mirroring
- A UI/UX designer with ideas to refine our native interfaces
- Someone reporting edge cases or fixing localization and bugs

If you'd like to collaborate, feel free to open an issue, submit a pull request, or start a discussion. Let's make cross-platform connectivity effortless for everyone!

---

## License

This project is licensed under the [MIT License](LICENSE).
