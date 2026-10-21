#!/usr/bin/env bash
set -euo pipefail

# -----------------------------------------------------------------------------
# Bridge: macOS Production Release Packaging & Signing Pipeline
#
# Automates:
# 1. Release archive build with XcodeGen & xcodebuild
# 2. Developer ID Code Signing & Hardened Runtime verification
# 3. Apple Notarization via notarytool & Ticket Stapling
# 4. ZIP Distribution Packaging (preserving signatures & staples)
# 5. Sparkle 2.x EdDSA Archive Signing & Appcast Generation
# -----------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAC_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${MAC_DIR}/.." && pwd)"

VERSION=""
BUILD_NUM=""
RELEASE_NOTES_FILE=""
NOTARY_PROFILE=""
SKIP_NOTARIZATION=false
OUTPUT_DIR="${MAC_DIR}/build/Release-Assets"
SPARKLE_VERSION="2.6.4"
CACHE_DIR="${MAC_DIR}/.build/sparkle_tools"

usage() {
    cat << EOF
Usage: $(basename "$0") [options]

Options:
  -v, --version <version>       Release version string (e.g. 1.0.1) [Required]
  -b, --build <number>          Bundle build number (e.g. 2) [Default: 1]
  -n, --notes <path>            Path to Markdown/HTML release notes file
  -p, --notary-profile <name>   Apple notarytool keychain profile name
  -o, --output <dir>            Output directory for release assets [Default: mac/build/Release-Assets]
      --skip-notarization       Skip Apple notarization step (for local test updates)
  -h, --help                    Show this help message

Environment Variables (Optional):
  DEVELOPMENT_TEAM              10-character Apple Developer Team ID
  CODE_SIGN_IDENTITY            Developer ID Application identity string
  NOTARY_KEYCHAIN_PROFILE       Keychains profile name for xcrun notarytool
  SPARKLE_ED_KEY                Private EdDSA key string (if signing in CI/CD)
EOF
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -v|--version)
            VERSION="$2"
            shift 2
            ;;
        -b|--build)
            BUILD_NUM="$2"
            shift 2
            ;;
        -n|--notes)
            RELEASE_NOTES_FILE="$2"
            shift 2
            ;;
        -p|--notary-profile)
            NOTARY_PROFILE="$2"
            shift 2
            ;;
        -o|--output)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --skip-notarization)
            SKIP_NOTARIZATION=true
            shift
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

if [[ -z "${VERSION}" ]]; then
    echo "Error: Version (-v, --version) is required."
    usage
fi

if [[ -z "${BUILD_NUM}" ]]; then
    BUILD_NUM="1"
fi

if [[ -z "${NOTARY_PROFILE}" && -n "${NOTARY_KEYCHAIN_PROFILE:-}" ]]; then
    NOTARY_PROFILE="${NOTARY_KEYCHAIN_PROFILE}"
fi

echo "=========================================================="
echo " Packaging Bridge for macOS"
echo " Version: ${VERSION} (Build ${BUILD_NUM})"
echo " Output:  ${OUTPUT_DIR}"
echo "=========================================================="

# 1. Locate Sparkle CLI tools
GENERATE_APPCAST=""
DERIVED_DATA_BIN="$(find ~/Library/Developer/Xcode/DerivedData/Bridge-*/SourcePackages/artifacts/sparkle/Sparkle/bin -name "generate_appcast" 2>/dev/null | head -n 1 || true)"

if [[ -n "${DERIVED_DATA_BIN}" && -x "${DERIVED_DATA_BIN}" ]]; then
    GENERATE_APPCAST="${DERIVED_DATA_BIN}"
else
    mkdir -p "${CACHE_DIR}"
    if [[ ! -x "${CACHE_DIR}/bin/generate_appcast" ]]; then
        echo "Downloading Sparkle CLI tools..."
        ARCHIVE_URL="https://github.com/sparkle-project/Sparkle/releases/download/${SPARKLE_VERSION}/Sparkle-${SPARKLE_VERSION}.tar.xz"
        curl -fsSL "${ARCHIVE_URL}" | tar -xJ -C "${CACHE_DIR}"
    fi
    GENERATE_APPCAST="${CACHE_DIR}/bin/generate_appcast"
fi

# 2. Prepare workspace and output directories
mkdir -p "${OUTPUT_DIR}"
BUILD_TMP="${MAC_DIR}/build/tmp"
rm -rf "${BUILD_TMP}"
mkdir -p "${BUILD_TMP}"

# 3. Regenerate Xcode project with XcodeGen
echo "--> Generating Xcode project via XcodeGen..."
(cd "${MAC_DIR}" && xcodegen generate)

# 4. Build Archive
ARCHIVE_PATH="${BUILD_TMP}/Bridge.xcarchive"
echo "--> Building Release Archive..."

BUILD_SETTINGS=(
    MARKETING_VERSION="${VERSION}"
    CURRENT_PROJECT_VERSION="${BUILD_NUM}"
)

if [[ -n "${DEVELOPMENT_TEAM:-}" ]]; then
    BUILD_SETTINGS+=(DEVELOPMENT_TEAM="${DEVELOPMENT_TEAM}")
fi

if [[ -n "${CODE_SIGN_IDENTITY:-}" ]]; then
    BUILD_SETTINGS+=(CODE_SIGN_IDENTITY="${CODE_SIGN_IDENTITY}")
fi

xcodebuild archive \
    -project "${MAC_DIR}/Bridge.xcodeproj" \
    -scheme Bridge \
    -configuration Release \
    -archivePath "${ARCHIVE_PATH}" \
    "${BUILD_SETTINGS[@]}" \
    | xcbeautify 2>/dev/null || xcodebuild archive \
        -project "${MAC_DIR}/Bridge.xcodeproj" \
        -scheme Bridge \
        -configuration Release \
        -archivePath "${ARCHIVE_PATH}" \
        "${BUILD_SETTINGS[@]}"

APP_PATH="${ARCHIVE_PATH}/Products/Applications/Bridge.app"
if [[ ! -d "${APP_PATH}" ]]; then
    echo "Error: Bridge.app not found in archive at ${APP_PATH}"
    exit 1
fi

echo "--> Verifying code signature..."
codesign -dvv "${APP_PATH}" 2>&1 | head -n 10

# 5. Apple Notarization
if [[ "${SKIP_NOTARIZATION}" == false ]]; then
    if [[ -z "${NOTARY_PROFILE}" ]]; then
        echo ""
        echo "WARNING: No notarytool keychain profile specified (--notary-profile)."
        echo "Skipping Apple Notarization. (To enable, run: xcrun notarytool store-credentials ...)"
        echo "To test locally without notarization, use: --skip-notarization"
        echo ""
    else
        echo "--> Submitting for Apple Notarization using profile: ${NOTARY_PROFILE}..."
        NOTARY_ZIP="${BUILD_TMP}/Bridge-notarize.zip"
        ditto -c -k --keepParent "${APP_PATH}" "${NOTARY_ZIP}"
        
        xcrun notarytool submit "${NOTARY_ZIP}" \
            --keychain-profile "${NOTARY_PROFILE}" \
            --wait
        
        echo "--> Stapling notarization ticket to Bridge.app..."
        xcrun stapler staple "${APP_PATH}"
        xcrun stapler validate "${APP_PATH}"
    fi
else
    echo "--> Skipping Apple Notarization (--skip-notarization flag present)."
fi

# 6. Compress App into distribution ZIP
FINAL_ZIP="${OUTPUT_DIR}/Bridge-${VERSION}.zip"
echo "--> Compressing Bridge.app into ${FINAL_ZIP}..."
rm -f "${FINAL_ZIP}"
# Use ditto to preserve symlinks, code signatures, resource forks, and extended attributes
ditto -c -k --keepParent "${APP_PATH}" "${FINAL_ZIP}"

# 7. Prepare Release Notes
NOTES_DEST="${OUTPUT_DIR}/Bridge-${VERSION}.md"
if [[ -n "${RELEASE_NOTES_FILE}" && -f "${RELEASE_NOTES_FILE}" ]]; then
    cp "${RELEASE_NOTES_FILE}" "${NOTES_DEST}"
else
    cat << EOF > "${NOTES_DEST}"
## Bridge ${VERSION}
- Production auto-update support via Sparkle 2.x
- Security and stability enhancements
EOF
fi

# 8. Copy or reference existing appcast.xml if present
if [[ -f "${REPO_DIR}/appcast.xml" ]]; then
    cp "${REPO_DIR}/appcast.xml" "${OUTPUT_DIR}/appcast.xml"
fi

# 9. Sign update archive & generate Sparkle appcast.xml
DOWNLOAD_URL_PREFIX="https://github.com/satyam-mohanty/Bridge/releases/download/v${VERSION}/"
echo "--> Running Sparkle generate_appcast..."

GENERATE_ARGS=(
    --download-url-prefix "${DOWNLOAD_URL_PREFIX}"
    -o "appcast.xml"
)

if [[ -n "${SPARKLE_ED_KEY:-}" ]]; then
    echo "Signing with private EdDSA key provided in environment..."
    echo "${SPARKLE_ED_KEY}" | "${GENERATE_APPCAST}" "${GENERATE_ARGS[@]}" --ed-key-file - "${OUTPUT_DIR}"
else
    echo "Signing with EdDSA key stored in macOS Keychain..."
    "${GENERATE_APPCAST}" "${GENERATE_ARGS[@]}" "${OUTPUT_DIR}"
fi

# Copy updated appcast to repo root for easy git commit
if [[ -f "${OUTPUT_DIR}/appcast.xml" ]]; then
    cp "${OUTPUT_DIR}/appcast.xml" "${REPO_DIR}/appcast.xml"
    echo "--> Updated appcast.xml copied to repository root: ${REPO_DIR}/appcast.xml"
fi

echo ""
echo "=========================================================="
echo " SUCCESS: Release assets generated!"
echo " Assets located in: ${OUTPUT_DIR}"
echo "   - ${FINAL_ZIP}"
echo "   - ${NOTES_DEST}"
echo "   - ${OUTPUT_DIR}/appcast.xml"
echo ""
echo " Publishing Steps:"
echo " 1. Create a Git tag and release on GitHub:"
echo "      git tag v${VERSION}"
echo "      git push origin v${VERSION}"
echo " 2. Upload ${FINAL_ZIP} as a binary asset to GitHub Release v${VERSION}."
echo " 3. Commit and push the updated appcast.xml to the main branch:"
echo "      git add appcast.xml"
echo "      git commit -m 'release: update appcast for v${VERSION}'"
echo "      git push origin main"
echo "=========================================================="
