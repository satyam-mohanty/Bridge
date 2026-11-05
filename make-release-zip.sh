#!/usr/bin/env bash
# =============================================================================
# make-release-zip.sh — Package Bridge.app into Bridge.zip
#
# Packages the compiled Bridge.app into a release ZIP file with Bridge.app at the
# root of the archive.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_PATH="${1:-}"

# Locate Bridge.app if not provided
if [[ -z "${APP_PATH}" ]]; then
    if [[ -d "${HOME}/Applications/Bridge.app" ]]; then
        APP_PATH="${HOME}/Applications/Bridge.app"
    elif [[ -d "/Applications/Bridge.app" ]]; then
        APP_PATH="/Applications/Bridge.app"
    else
        APP_PATH="$(find "${HOME}/Library/Developer/Xcode/DerivedData" -path "*/Bridge-*/Build/Products/Release/Bridge.app" -type d 2>/dev/null | head -n 1 || true)"
    fi
fi

if [[ -z "${APP_PATH}" || ! -d "${APP_PATH}" ]]; then
    echo "Error: Bridge.app not found. Build it first or provide its path as an argument." >&2
    echo "Usage: $0 [path/to/Bridge.app]" >&2
    exit 1
fi

OUTPUT_ZIP="${SCRIPT_DIR}/Bridge.zip"
rm -f "${OUTPUT_ZIP}"

APP_PARENT_DIR="$(cd "$(dirname "${APP_PATH}")" && pwd)"
APP_BASENAME="$(basename "${APP_PATH}")"

# Create archive preserving symlinks with Bridge.app at root
(cd "${APP_PARENT_DIR}" && zip -r -y -q "${OUTPUT_ZIP}" "${APP_BASENAME}")

echo "Successfully created ${OUTPUT_ZIP} with ${APP_BASENAME} at its root."
