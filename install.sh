#!/usr/bin/env bash
# =============================================================================
# Bridge for macOS — One-Line Installer
#
# Downloads the latest Bridge release archive, installs Bridge.app directly into
# ~/Applications, clears macOS Gatekeeper quarantine restrictions, and launches
# the application.
#
# No administrator (sudo) access required.
# =============================================================================

set -euo pipefail

# 1. Set up a secure temporary directory and ensure it is cleaned up on exit
TMP_DIR="$(mktemp -d -t bridge-install-XXXXXX)"
cleanup() {
    rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

# 2. Determine archive source
# Default download URL points to the latest GitHub Release archive
# Can be overridden via BRIDGE_ZIP_URL (supports local file paths for testing)
DEFAULT_URL="https://github.com/satyam-mohanty/Bridge/releases/latest/download/Bridge.zip"
ZIP_SOURCE="${BRIDGE_ZIP_URL:-${DEFAULT_URL}}"
ZIP_DEST="${TMP_DIR}/Bridge.zip"

echo ""
echo "  ⚡ Installing Bridge for macOS"
echo "  --------------------------------"

# 3. Fetch the release archive
if [[ -f "${ZIP_SOURCE}" ]]; then
    # Local file path provided (useful for offline testing)
    echo "  [1/5] Using local archive..."
    cp "${ZIP_SOURCE}" "${ZIP_DEST}"
elif [[ "${ZIP_SOURCE}" == file://* ]]; then
    # file:// URI provided
    LOCAL_PATH="${ZIP_SOURCE#file://}"
    echo "  [1/5] Using local archive..."
    cp "${LOCAL_PATH}" "${ZIP_DEST}"
else
    # Download over HTTPS with clear visual progress
    echo "  [1/5] Downloading Bridge..."
    # If stdout is piped or non-tty, curl -# or --progress-bar prints dynamic hash/percentage
    if ! curl -# -fL "${ZIP_SOURCE}" -o "${ZIP_DEST}"; then
        echo "" >&2
        echo "  ❌ Error: Failed to download Bridge from ${ZIP_SOURCE}" >&2
        echo "  Please check your internet connection or download directly from GitHub Releases." >&2
        exit 1
    fi
fi

# 4. Extract Bridge.app from the zip archive with live unpack indicator
echo "  [2/5] Unpacking Bridge.app..."
unzip -o -q "${ZIP_DEST}" -d "${TMP_DIR}"

# 5. Check that Bridge.app was extracted properly
if [[ ! -d "${TMP_DIR}/Bridge.app" ]]; then
    echo "  ❌ Error: Bridge.app was not found in the downloaded archive." >&2
    exit 1
fi

# 6. Install Bridge.app to ~/Applications
echo "  [3/5] Installing to ~/Applications..."
mkdir -p "${HOME}/Applications"
rm -rf "${HOME}/Applications/Bridge.app"
ditto "${TMP_DIR}/Bridge.app" "${HOME}/Applications/Bridge.app"

# 7. Remove Gatekeeper quarantine attribute so Bridge opens seamlessly
echo "  [4/5] Configuring system permissions..."
xattr -dr com.apple.quarantine "${HOME}/Applications/Bridge.app" 2>/dev/null || true

# 8. Launch Bridge
echo "  [5/5] Launching Bridge..."
open "${HOME}/Applications/Bridge.app"

# 9. Output final confirmation message
echo "  ✅ Done! Bridge installed successfully and is now running."
echo ""
