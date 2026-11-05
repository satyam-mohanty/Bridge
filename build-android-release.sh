#!/usr/bin/env bash
# =============================================================================
# Bridge for Android — Production Release Build Script
#
# Builds a release APK, signs it with a local keystore, verifies the cryptographic
# signature, and outputs the final artifact to releases/bridge-vX.Y.apk.
#
# Keystores and passwords are NEVER committed (enforced by .gitignore).
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="${SCRIPT_DIR}"
ANDROID_DIR="${REPO_DIR}/android"
RELEASES_DIR="${REPO_DIR}/releases"

# -----------------------------------------------------------------------------
# 1. Version Detection
# -----------------------------------------------------------------------------
GRADLE_FILE="${ANDROID_DIR}/app/build.gradle.kts"
DETECTED_VERSION=$(grep -E 'versionName\s*=\s*' "${GRADLE_FILE}" | head -n 1 | sed -E 's/.*"([^"]+)".*/\1/')
VERSION="${DETECTED_VERSION:-1.0}"

KEYSTORE_PATH="${KEYSTORE_PATH:-}"
KEYSTORE_PASS="${KEYSTORE_PASSWORD:-}"
KEY_ALIAS="${KEY_ALIAS:-}"
KEY_PASS="${KEY_PASSWORD:-}"
CREATE_KEYSTORE=false

# -----------------------------------------------------------------------------
# 2. Argument Parsing
# -----------------------------------------------------------------------------
usage() {
    cat << EOF
Usage: $(basename "$0") [options]

Options:
  -k, --keystore <path>       Path to local release keystore (.jks or .keystore)
  -a, --alias <alias>         Keystore key alias (default: bridge)
  -p, --store-pass <pass>     Keystore store password (or set KEYSTORE_PASSWORD env)
  -kp, --key-pass <pass>      Key password (defaults to store password if omitted)
  -v, --version <version>     Release version override (default: detected ${VERSION})
      --generate-keystore     Interactively generate a new local release keystore
  -h, --help                  Show this help message

Examples:
  # Using an existing keystore
  ./build-android-release.sh -k ~/my-release.keystore -a bridge

  # With keystore.properties in android/ or repo root
  ./build-android-release.sh

  # Generate a new local keystore first
  ./build-android-release.sh --generate-keystore
EOF
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -k|--keystore)
            KEYSTORE_PATH="$2"
            shift 2
            ;;
        -a|--alias)
            KEY_ALIAS="$2"
            shift 2
            ;;
        -p|--store-pass)
            KEYSTORE_PASS="$2"
            shift 2
            ;;
        -kp|--key-pass)
            KEY_PASS="$2"
            shift 2
            ;;
        -v|--version)
            VERSION="$2"
            shift 2
            ;;
        --generate-keystore)
            CREATE_KEYSTORE=true
            shift 1
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo "Unknown argument: $1"
            usage
            ;;
    esac
done

echo "==========================================================="
echo " Bridge Android Release Build (v${VERSION})"
echo "==========================================================="

# -----------------------------------------------------------------------------
# 3. Locate Android SDK & Build Tools
# -----------------------------------------------------------------------------
if [[ -z "${ANDROID_HOME:-}" ]]; then
    if [[ -d "${HOME}/Library/Android/sdk" ]]; then
        export ANDROID_HOME="${HOME}/Library/Android/sdk"
    elif [[ -d "/usr/local/share/android-sdk" ]]; then
        export ANDROID_HOME="/usr/local/share/android-sdk"
    fi
fi

if [[ -z "${ANDROID_HOME:-}" || ! -d "${ANDROID_HOME}" ]]; then
    echo "Error: ANDROID_HOME is not set and Android SDK could not be located."
    echo "Please set ANDROID_HOME or install the Android SDK."
    exit 1
fi

# Find apksigner and zipalign in latest build-tools
BUILD_TOOLS_DIR=$(ls -1d "${ANDROID_HOME}/build-tools/"* 2>/dev/null | sort -V | tail -n 1 || true)
if [[ -z "${BUILD_TOOLS_DIR}" || ! -d "${BUILD_TOOLS_DIR}" ]]; then
    echo "Error: No build-tools found in ${ANDROID_HOME}/build-tools."
    exit 1
fi

APKSIGNER="${BUILD_TOOLS_DIR}/apksigner"
ZIPALIGN="${BUILD_TOOLS_DIR}/zipalign"

if [[ ! -x "${APKSIGNER}" ]]; then
    APKSIGNER=$(command -v apksigner || true)
fi

if [[ -z "${APKSIGNER}" || ! -x "${APKSIGNER}" ]]; then
    echo "Error: apksigner not found in ${BUILD_TOOLS_DIR} or PATH."
    exit 1
fi

# -----------------------------------------------------------------------------
# 4. Keystore Configuration & Resolution
# -----------------------------------------------------------------------------
# Check keystore.properties if keystore path not yet specified
PROP_FILE=""
if [[ -f "${ANDROID_DIR}/keystore.properties" ]]; then
    PROP_FILE="${ANDROID_DIR}/keystore.properties"
elif [[ -f "${REPO_DIR}/keystore.properties" ]]; then
    PROP_FILE="${REPO_DIR}/keystore.properties"
fi

if [[ -n "${PROP_FILE}" && -z "${KEYSTORE_PATH}" ]]; then
    echo "--> Reading signing config from ${PROP_FILE}"
    KEYSTORE_PATH=$(grep -E '^\s*storeFile\s*=' "${PROP_FILE}" | awk -F '=' '{print $2}' | tr -d ' ' || true)
    if [[ "${KEYSTORE_PATH}" != /* ]]; then
        KEYSTORE_PATH="$(dirname "${PROP_FILE}")/${KEYSTORE_PATH}"
    fi
    [[ -z "${KEYSTORE_PASS}" ]] && KEYSTORE_PASS=$(grep -E '^\s*storePassword\s*=' "${PROP_FILE}" | awk -F '=' '{print $2}' | tr -d ' ' || true)
    [[ -z "${KEY_ALIAS}" ]] && KEY_ALIAS=$(grep -E '^\s*keyAlias\s*=' "${PROP_FILE}" | awk -F '=' '{print $2}' | tr -d ' ' || true)
    [[ -z "${KEY_PASS}" ]] && KEY_PASS=$(grep -E '^\s*keyPassword\s*=' "${PROP_FILE}" | awk -F '=' '{print $2}' | tr -d ' ' || true)
fi

# Fallback auto-discovery of common local filenames
if [[ -z "${KEYSTORE_PATH}" ]]; then
    for candidate in \
        "${REPO_DIR}/release.keystore" \
        "${REPO_DIR}/bridge-release.jks" \
        "${ANDROID_DIR}/release.keystore" \
        "${ANDROID_DIR}/bridge-release.jks" \
        "${HOME}/.android/bridge-release.jks"; do
        if [[ -f "${candidate}" ]]; then
            KEYSTORE_PATH="${candidate}"
            echo "--> Auto-detected local keystore at: ${KEYSTORE_PATH}"
            break
        fi
    done
fi

# Handle interactive generation if requested or missing
if [[ "${CREATE_KEYSTORE}" == "true" ]]; then
    GEN_TARGET="${REPO_DIR}/release.keystore"
    echo "==> Generating local release keystore at: ${GEN_TARGET}"
    read -rp "Enter Keystore & Key Password: " -s USER_PASS
    echo ""
    read -rp "Confirm Password: " -s USER_PASS_CONFIRM
    echo ""
    if [[ "${USER_PASS}" != "${USER_PASS_CONFIRM}" ]]; then
        echo "Error: Passwords do not match."
        exit 1
    fi
    keytool -genkeypair -v \
        -keystore "${GEN_TARGET}" \
        -alias "bridge" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass "${USER_PASS}" \
        -keypass "${USER_PASS}" \
        -dname "CN=Bridge Open Source, OU=Bridge, O=Bridge, L=Local, ST=Local, C=US"
    KEYSTORE_PATH="${GEN_TARGET}"
    KEYSTORE_PASS="${USER_PASS}"
    KEY_ALIAS="bridge"
    KEY_PASS="${USER_PASS}"
    echo "--> Keystore generated successfully!"
fi

if [[ -z "${KEYSTORE_PATH}" || ! -f "${KEYSTORE_PATH}" ]]; then
    echo ""
    echo "-----------------------------------------------------------"
    echo " Error: No release keystore found!"
    echo "-----------------------------------------------------------"
    echo "To sign your public release APK, create a local keystore once:"
    echo ""
    echo "  keytool -genkeypair -v -keystore release.keystore \\"
    echo "          -alias bridge -keyalg RSA -keysize 2048 -validity 10000"
    echo ""
    echo "Or run this script with --generate-keystore to create it automatically:"
    echo "  ./build-android-release.sh --generate-keystore"
    echo ""
    echo "Note: release.keystore and *.jks are in .gitignore and will never be committed."
    exit 1
fi

# Ensure KEYSTORE_PATH is absolute so cd'ing into android/ does not break it
if [[ "${KEYSTORE_PATH}" != /* ]]; then
    KEYSTORE_PATH="$(cd "$(dirname "${KEYSTORE_PATH}")" && pwd)/$(basename "${KEYSTORE_PATH}")"
fi

KEY_ALIAS="${KEY_ALIAS:-bridge}"

# Prompt for password if not provided
if [[ -z "${KEYSTORE_PASS}" ]]; then
    read -rp "Enter password for keystore (${KEYSTORE_PATH}): " -s KEYSTORE_PASS
    echo ""
fi

if [[ -z "${KEY_PASS}" ]]; then
    KEY_PASS="${KEYSTORE_PASS}"
fi

# -----------------------------------------------------------------------------
# 5. Build Release APK via Gradle
# -----------------------------------------------------------------------------
echo "==> Building release APK with Gradle..."
cd "${ANDROID_DIR}"
./gradlew assembleRelease

BUILT_APK="${ANDROID_DIR}/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "${BUILT_APK}" ]]; then
    BUILT_APK="${ANDROID_DIR}/app/build/outputs/apk/release/app-release-unsigned.apk"
fi

if [[ ! -f "${BUILT_APK}" ]]; then
    echo "Error: Gradle build succeeded, but could not find APK at ${BUILT_APK}"
    exit 1
fi

# -----------------------------------------------------------------------------
# 6. Align and Sign the APK with apksigner
# -----------------------------------------------------------------------------
mkdir -p "${RELEASES_DIR}"
TARGET_APK_NAME="bridge-v${VERSION}.apk"
FINAL_APK="${RELEASES_DIR}/${TARGET_APK_NAME}"

echo "==> Signing APK with apksigner..."
# Copy to temp file for in-place signing
TMP_SIGNED="${ANDROID_DIR}/app/build/outputs/apk/release/temp-signed.apk"
cp "${BUILT_APK}" "${TMP_SIGNED}"

# Run apksigner
"${APKSIGNER}" sign \
    --ks "${KEYSTORE_PATH}" \
    --ks-key-alias "${KEY_ALIAS}" \
    --ks-pass "pass:${KEYSTORE_PASS}" \
    --key-pass "pass:${KEY_PASS}" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "${FINAL_APK}" \
    "${TMP_SIGNED}"

rm -f "${TMP_SIGNED}"

# -----------------------------------------------------------------------------
# 7. Verify Signature & Integrity
# -----------------------------------------------------------------------------
echo "==> Verifying APK signature..."
"${APKSIGNER}" verify --verbose "${FINAL_APK}"

APK_SIZE=$(ls -lh "${FINAL_APK}" | awk '{print $5}')
APK_SHA256=$(shasum -a 256 "${FINAL_APK}" | awk '{print $1}')

echo ""
echo "==========================================================="
echo " BUILD SUCCESSFUL!"
echo " Signed Release APK: ${FINAL_APK}"
echo " File Size:         ${APK_SIZE}"
echo " SHA-256 Checksum:  ${APK_SHA256}"
echo "==========================================================="
