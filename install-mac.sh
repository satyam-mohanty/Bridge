#!/usr/bin/env bash
# =============================================================================
# Bridge for macOS — Installation Script
#
# This script builds Bridge from source using Apple's Xcode tools and installs
# it into your personal Applications folder (~/Applications).
#
# No administrator (sudo) privileges required.
# =============================================================================

set -euo pipefail

# 1. Determine repository root and macOS project directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAC_DIR="${SCRIPT_DIR}/mac"

if [[ ! -d "${MAC_DIR}" ]]; then
    echo "Error: Directory '${MAC_DIR}' does not exist. Please run this script from the Bridge repository."
    exit 1
fi

echo "==> Checking prerequisites..."

# 2. Check for Xcode Command Line Tools
if ! xcode-select -p >/dev/null 2>&1; then
    echo "Error: Xcode Command Line Tools are not installed."
    echo "Please install them by opening Terminal and running:"
    echo "    xcode-select --install"
    exit 1
fi

# 3. Check for xcodegen (used to generate the Xcode project)
if ! command -v xcodegen >/dev/null 2>&1; then
    echo "Error: 'xcodegen' is required to configure the project, but was not found."
    echo "Please install it with Homebrew by running:"
    echo "    brew install xcodegen"
    exit 1
fi

# 4. Generate the Xcode project from project.yml
echo "==> Generating Xcode project..."
cd "${MAC_DIR}"
xcodegen generate

# 5. Build the Release version of Bridge
echo "==> Building Bridge (Release configuration)..."
xcodebuild -scheme Bridge -configuration Release build

# 6. Locate the compiled Bridge.app product
BUILT_PRODUCTS_DIR="$(xcodebuild -scheme Bridge -configuration Release -showBuildSettings 2>/dev/null | grep -m 1 "BUILT_PRODUCTS_DIR =" | awk -F '= ' '{print $2}' || true)"
BUILT_APP="${BUILT_PRODUCTS_DIR}/Bridge.app"

# Fallback search in DerivedData if needed
if [[ ! -d "${BUILT_APP}" ]]; then
    FALLBACK_APP="$(find "${HOME}/Library/Developer/Xcode/DerivedData" -path "*/Bridge-*/Build/Products/Release/Bridge.app" -type d 2>/dev/null | head -n 1 || true)"
    if [[ -n "${FALLBACK_APP}" && -d "${FALLBACK_APP}" ]]; then
        BUILT_APP="${FALLBACK_APP}"
    fi
fi

if [[ ! -d "${BUILT_APP}" ]]; then
    echo "Error: Could not find the built Bridge.app product."
    exit 1
fi

# 7. Copy Bridge.app into ~/Applications (creates the folder if needed)
echo "==> Installing Bridge.app to ~/Applications..."
mkdir -p "${HOME}/Applications"
rm -rf "${HOME}/Applications/Bridge.app"
cp -R "${BUILT_APP}" "${HOME}/Applications/"

# 8. Remove the quarantine attribute so macOS allows the locally built app to run seamlessly
xattr -dr com.apple.quarantine "${HOME}/Applications/Bridge.app" 2>/dev/null || true

# 9. Launch Bridge
open "${HOME}/Applications/Bridge.app"

# 10. Print one line success message
echo "Bridge installed and launched successfully! Enjoy!"
