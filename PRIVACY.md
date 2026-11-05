# Privacy Policy for Bridge

**Last updated:** November 2026

Bridge is designed from the ground up on one foundational principle: **your data belongs exclusively to you.** 

Bridge does not have servers, does not have user accounts, and does not collect any analytics or telemetry whatsoever.

---

## 1. No External Servers or Cloud Storage

Bridge has **no backend servers**, **no cloud databases**, and **no relays**. 

When Bridge transfers clipboard text, images, notifications, incoming call alerts, or files:
- All data travels **strictly between your Mac and your Android device** over your **local Wi-Fi network** (or direct personal hotspot).
- Data never leaves your local network. It is never routed through an external server or cloud proxy.

## 2. No Accounts or Identity Tracking

- **No sign-ups or logins:** You never provide an email address, phone number, password, or account name to use Bridge.
- **No profiling or advertising:** We do not track who you are, what devices you own, or how you use the app.
- Device pairing is performed peer-to-peer using an encrypted QR code or local pairing code stored securely on your devices (in the macOS Keychain and Android KeyStore).

## 3. Zero Telemetry, Tracking, or Analytics

Bridge contains **zero third-party tracking SDKs**:
- No Google Analytics / Firebase
- No advertising SDKs
- No crash-reporting services that send your logs to the cloud
- No telemetry pings or heartbeat trackers

## 4. End-to-End Encryption

Every payload transmitted over your local network is encrypted using industry-standard **AES-256-GCM** (Galois/Counter Mode) authenticated encryption. Even if other devices are connected to the same Wi-Fi network, your data cannot be intercepted or modified in transit.

## 5. What Data is Handled Locally

Bridge only handles the data required for the specific features you use:

| Feature | Data Processed | Where It Goes |
| :--- | :--- | :--- |
| **Clipboard Sync** | Text, URLs, or images copied to your clipboard | Encrypted and sent directly to your paired device. |
| **File Sharing** | Files you explicitly drag-and-drop or share | Streamed in encrypted chunks directly to your other device's storage. |
| **Notification Forwarding** | Notification titles, text, and app names for apps you select | Encrypted and sent to your Mac for display. You can turn off preview contents or disable specific apps anytime. |
| **Incoming Call Alerts** | Caller phone number and contact name | Displayed only on your Mac desktop while the phone is ringing. Never recorded or uploaded anywhere. |

## 6. Open Source Transparency

Bridge is free and open-source software under the [MIT License](LICENSE). You can inspect every single line of code in the public repository to verify that these privacy commitments are strictly upheld.

## 7. Contact

If you have questions about Bridge's privacy architecture:
- GitHub: [https://github.com/satyam-mohanty/Bridge](https://github.com/satyam-mohanty/Bridge)
- Website: [https://www.satyammohanty.com](https://www.satyammohanty.com)
