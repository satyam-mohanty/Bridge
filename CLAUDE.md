# Bridge Project

**Bridge** — Cross-device connectivity between Android and macOS.

Bridge is a zero-idle-cost, native LAN clipboard synchronization tool connecting your Mac and Android phone. Features:
- Native Swift (macOS) + Kotlin (Android) apps
- AES-256-GCM encryption on TCP port 48250
- Real-time bidirectional clipboard synchronization
- File transfer with checksums and progress tracking
- Automatic peer discovery and reconnection
- System sleep/wake handling
- Network change detection and recovery

## Technologies Used in Bridge

- **Backend & Core:** Swift, Kotlin, Java (Android), NWNetworking (Swift), Java/Kotlin coroutines
- **Infrastructure:** Git/GitHub, DNS/mDNS (_bridge._tcp), TCP connections
- **Security:** AES-256-GCM encryption, secure key storage (Keychain), message authentication codes

## Current Bridge Development Status

All core components are implemented and functional:
- macOS app: Complete with native UI, menu bar integration, drag-and-drop
- Android app: Complete with Apple-style UI, notification system
- Network layer: Full TCP implementation with heartbeat and timeout handling
- Encryption: Robust cryptographic layer with secure key management
- Pairings: QR code and manual code pairing system
- Cross-compatibility: Verified with Android 10+ and macOS 12+

However, improvements and maintenance work may introduce changes that affect build processes or runtime behavior.

For detailed build instructions, see the bridge_menu_connected.png in the root directory or visit the project's documentation.