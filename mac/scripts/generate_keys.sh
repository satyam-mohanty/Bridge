#!/usr/bin/env bash
set -euo pipefail

# -----------------------------------------------------------------------------
# Sparkle EdDSA Key Generation Tool for Bridge
#
# This script generates a public/private keypair for signing Bridge auto-updates.
# The private key is securely stored in your macOS Keychain and must NEVER be
# checked into version control.
# -----------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAC_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SPARKLE_VERSION="2.6.4"
CACHE_DIR="${MAC_DIR}/.build/sparkle_tools"

# Locate Sparkle's generate_keys binary from DerivedData or download
GENERATE_KEYS=""
DERIVED_DATA_BIN="$(find ~/Library/Developer/Xcode/DerivedData/Bridge-*/SourcePackages/artifacts/sparkle/Sparkle/bin -name "generate_keys" 2>/dev/null | head -n 1 || true)"

if [[ -n "${DERIVED_DATA_BIN}" && -x "${DERIVED_DATA_BIN}" ]]; then
    GENERATE_KEYS="${DERIVED_DATA_BIN}"
else
    mkdir -p "${CACHE_DIR}"
    if [[ ! -x "${CACHE_DIR}/bin/generate_keys" ]]; then
        echo "Downloading Sparkle ${SPARKLE_VERSION} command line tools..."
        ARCHIVE_URL="https://github.com/sparkle-project/Sparkle/releases/download/${SPARKLE_VERSION}/Sparkle-${SPARKLE_VERSION}.tar.xz"
        curl -fsSL "${ARCHIVE_URL}" | tar -xJ -C "${CACHE_DIR}"
    fi
    GENERATE_KEYS="${CACHE_DIR}/bin/generate_keys"
fi

echo "=========================================================="
echo " Bridge: Sparkle 2.x EdDSA Key Generator"
echo "=========================================================="
echo ""

# Check if a key already exists in Keychain
if "${GENERATE_KEYS}" -p >/dev/null 2>&1; then
    echo "Found existing EdDSA key in your macOS Keychain:"
    EXISTING_KEY="$("${GENERATE_KEYS}" -p)"
    echo "Public Key (SUPublicEDKey): ${EXISTING_KEY}"
    echo ""
    echo "To configure this key for Bridge:"
    echo "1. Create or open ${MAC_DIR}/Config/Local.xcconfig"
    echo "2. Add: SPARKLE_PUBLIC_ED_KEY = ${EXISTING_KEY}"
    echo ""
    echo "To export the private key for CI/CD secrets (e.g. GitHub Actions):"
    echo "  ${GENERATE_KEYS} -x /path/to/private_key.pem"
    echo "  (Ensure private_key.pem is NEVER committed to git!)"
    exit 0
fi

echo "Generating new EdDSA keypair in your macOS Keychain..."
echo "(Keychain may prompt you for permission to store the key)"
echo ""
"${GENERATE_KEYS}"

echo ""
echo "=========================================================="
echo " Next Steps:"
echo " 1. Copy the SUPublicEDKey string above."
echo " 2. In ${MAC_DIR}/Config/Local.xcconfig (or CI environment):"
echo "    SPARKLE_PUBLIC_ED_KEY = <your_public_key>"
echo " 3. The private key remains secure in your Keychain."
echo "    If deploying via CI (e.g. GitHub Actions), export the private key:"
echo "      ${GENERATE_KEYS} -x sparkle_key.pem"
echo "    and add its content to your GitHub repository secret 'SPARKLE_ED_KEY'."
echo "=========================================================="
