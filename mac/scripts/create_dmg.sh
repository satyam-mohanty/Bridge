#!/usr/bin/env bash
set -euo pipefail

# -----------------------------------------------------------------------------
# Bridge: macOS DMG Installer Creation Script
#
# Creates a valid Drag-to-Applications .dmg installer for Bridge:
# 1. Builds the Release configuration (if no pre-built app is specified)
# 2. Re-signs embedded frameworks (Sparkle) and helper tools from inside out
# 3. Signs the main app with entitlements (including library validation disable)
# 4. Stages a Drag-to-Applications layout with an /Applications symlink
# 5. Formats as a universal, compressed HFS+ DMG (UDZO)
# 6. Signs the DMG container
# -----------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAC_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

CONFIGURATION="Release"
OUTPUT_DIR="${MAC_DIR}/build/dist"
APP_PATH=""
VOLNAME="Bridge"
DMG_NAME=""
SIGN_ID=""

usage() {
    cat << EOF
Usage: $(basename "$0") [options]

Options:
  -c, --configuration <name>    Build configuration (Release or Debug) [Default: Release]
  -o, --output <dir>            Output directory for the DMG [Default: mac/build/dist]
  -a, --app <path>              Path to pre-built Bridge.app (skips building if provided)
  -v, --volname <name>          Volume label for mounted DMG [Default: Bridge]
  -n, --name <filename>         Output DMG filename (without extension)
  -s, --sign-identity <id>      Signing identity (e.g. "-" for ad-hoc, or "Developer ID Application")
  -h, --help                    Show this help message
EOF
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -c|--configuration)
            CONFIGURATION="$2"
            shift 2
            ;;
        -o|--output)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        -a|--app)
            APP_PATH="$2"
            shift 2
            ;;
        -v|--volname)
            VOLNAME="$2"
            shift 2
            ;;
        -n|--name)
            DMG_NAME="$2"
            shift 2
            ;;
        -s|--sign-identity)
            SIGN_ID="$2"
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo "Unknown option: $1"
            usage
            ;;
    esac
done

# Determine signing identity if not explicitly specified
if [[ -z "${SIGN_ID}" ]]; then
    if [[ -n "${CODE_SIGN_IDENTITY:-}" ]]; then
        SIGN_ID="${CODE_SIGN_IDENTITY}"
    elif security find-identity -v -p codesigning 2>/dev/null | grep -q "Developer ID Application"; then
        SIGN_ID="$(security find-identity -v -p codesigning | grep "Developer ID Application" | head -n 1 | awk -F '"' '{print $2}')"
        echo "--> Detected Developer ID signing identity: ${SIGN_ID}"
    else
        SIGN_ID="-"
        echo "--> No Developer ID identity detected. Using ad-hoc signing ('-') for local testing."
    fi
fi

# Build app if not provided
if [[ -z "${APP_PATH}" ]]; then
    echo "--> Regenerating Xcode project with XcodeGen..."
    (cd "${MAC_DIR}" && xcodegen generate)

    echo "--> Building Bridge (${CONFIGURATION})..."
    BUILD_DIR="${MAC_DIR}/build/Build"
    xcodebuild -project "${MAC_DIR}/Bridge.xcodeproj" \
        -scheme Bridge \
        -configuration "${CONFIGURATION}" \
        -derivedDataPath "${MAC_DIR}/build" \
        CODE_SIGN_IDENTITY="${SIGN_ID}" \
        build | grep -E "(Compiling|Linking|Signing|SUCCEEDED|\*\*)" || true

    APP_PATH="${MAC_DIR}/build/Build/Products/${CONFIGURATION}/Bridge.app"
fi

if [[ ! -d "${APP_PATH}" ]]; then
    echo "Error: Application bundle not found at: ${APP_PATH}"
    exit 1
fi

echo "--> Inspecting application bundle: ${APP_PATH}"
MAIN_BIN="${APP_PATH}/Contents/MacOS/Bridge"
if [[ ! -x "${MAIN_BIN}" ]]; then
    echo "Error: Main executable is missing or not executable: ${MAIN_BIN}"
    exit 1
fi

# Ensure executable permissions are preserved
chmod -R u+w,go+rX "${APP_PATH}"
chmod +x "${MAIN_BIN}"

# Detect if someone accidentally packaged a debug preview stub
if [[ -f "${APP_PATH}/Contents/MacOS/Bridge.debug.dylib" ]]; then
    echo "WARNING: ${APP_PATH} appears to be a Debug build containing Bridge.debug.dylib."
    echo "Debug stub builds are flagged as 'incomplete' by macOS LaunchServices."
    echo "Consider building with -c Release for distribution."
fi

# Re-sign embedded frameworks inside-out
echo "--> Signing embedded frameworks & helpers with identity: ${SIGN_ID}..."
if [[ -d "${APP_PATH}/Contents/Frameworks" ]]; then
    # 1. Sign helper apps and XPC services
    find "${APP_PATH}/Contents/Frameworks" -type d \( -name "*.xpc" -o -name "*.app" \) | while read -r helper; do
        codesign --force --options runtime --sign "${SIGN_ID}" "${helper}" 2>/dev/null || \
        codesign --force --sign "${SIGN_ID}" "${helper}"
    done
    # 2. Sign frameworks
    find "${APP_PATH}/Contents/Frameworks" -maxdepth 1 -name "*.framework" | while read -r fw; do
        codesign --force --options runtime --sign "${SIGN_ID}" "${fw}" 2>/dev/null || \
        codesign --force --sign "${SIGN_ID}" "${fw}"
    done
fi

# Sign main app bundle with entitlements
ENTITLEMENTS="${MAC_DIR}/Bridge.entitlements"
echo "--> Signing main application bundle..."
if [[ -f "${ENTITLEMENTS}" ]]; then
    codesign --force --options runtime --entitlements "${ENTITLEMENTS}" --sign "${SIGN_ID}" "${APP_PATH}" 2>/dev/null || \
    codesign --force --entitlements "${ENTITLEMENTS}" --sign "${SIGN_ID}" "${APP_PATH}"
else
    codesign --force --options runtime --sign "${SIGN_ID}" "${APP_PATH}" 2>/dev/null || \
    codesign --force --sign "${SIGN_ID}" "${APP_PATH}"
fi

# Verify code signature
echo "--> Verifying code signature..."
codesign --verify --deep --strict --verbose=2 "${APP_PATH}"

# Prepare staging folder
mkdir -p "${OUTPUT_DIR}"
STAGING_DIR="$(mktemp -d /tmp/bridge_dmg_staging.XXXXXX)"
trap 'rm -rf "${STAGING_DIR}"' EXIT

echo "--> Staging DMG contents at ${STAGING_DIR}..."
# Use ditto to accurately preserve symlinks, attributes, and signatures
ditto "${APP_PATH}" "${STAGING_DIR}/Bridge.app"

# Create standard Drag-to-Applications symlink
ln -s /Applications "${STAGING_DIR}/Applications"

# Ensure global read and execute permissions across the DMG filesystem
chmod 755 "${STAGING_DIR}"
chmod -R u+rwX,go+rX "${STAGING_DIR}"

# Set output DMG filename
if [[ -z "${DMG_NAME}" ]]; then
    APP_VERSION="$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "${APP_PATH}/Contents/Info.plist" 2>/dev/null || echo "1.0")"
    DMG_NAME="Bridge-${APP_VERSION}"
fi
FINAL_DMG="${OUTPUT_DIR}/${DMG_NAME}.dmg"
rm -f "${FINAL_DMG}"

echo "--> Creating compressed HFS+ disk image at ${FINAL_DMG}..."
hdiutil create \
    -volname "${VOLNAME}" \
    -srcfolder "${STAGING_DIR}" \
    -ov \
    -format UDZO \
    -fs HFS+ \
    "${FINAL_DMG}"

echo "--> Signing DMG container..."
codesign --force --sign "${SIGN_ID}" "${FINAL_DMG}" 2>/dev/null || true

echo "=========================================================="
echo " SUCCESS: Drag-to-Applications DMG created!"
echo " Location: ${FINAL_DMG}"
echo " File Size: $(du -h "${FINAL_DMG}" | cut -f1)"
echo " Signature: ${SIGN_ID}"
echo "=========================================================="
