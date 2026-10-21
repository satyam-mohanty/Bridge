package com.bridge.android.update.data

object UpdateConfig {
    /**
     * The embedded Ed25519 public key used to verify authenticity of update manifests.
     * Only the public key is embedded in the application.
     * The private key is strictly kept out of version control and stored securely in CI/CD.
     */
    const val UPDATE_PUBLIC_KEY_HEX = "91d145812cbfe99c0b16389746afe5e20ec637b5fb491318b8b1261dc1ef53e5"

    /**
     * Default HTTPS URL for the update manifest.
     * Advertises release versions hosted on GitHub.
     */
    const val DEFAULT_MANIFEST_URL = "https://raw.githubusercontent.com/satyam-mohanty/Bridge/main/release/update-manifest.json"

    /**
     * Notification Channel metadata for update alerts.
     */
    const val NOTIFICATION_CHANNEL_ID = "bridge_updates"
    const val NOTIFICATION_CHANNEL_NAME = "App Updates"
    const val NOTIFICATION_CHANNEL_DESC = "Notifications for new versions of Bridge"
    const val NOTIFICATION_ID = 2001

    /**
     * Android FileProvider authority for granting installer permission to the downloaded APK.
     */
    const val FILE_PROVIDER_AUTHORITY = "com.bridge.android.fileprovider"

    /**
     * Maximum allowed APK download size (150 MB) as a defensive boundary.
     */
    const val MAX_APK_SIZE_BYTES = 150L * 1024L * 1024L

    /**
     * Timeouts in milliseconds for network calls.
     */
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 30_000
}
