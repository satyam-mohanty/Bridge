#!/usr/bin/env python3
"""
Bridge Android Self-Update: Manifest Generator & Ed25519 Signer.

Calculates SHA-256 checksum and size of the release APK, builds the manifest JSON,
canonically signs it using the Ed25519 private key, and outputs the signed manifest.
"""

import os
import sys
import json
import base64
import hashlib
import argparse
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ed25519

def compute_sha256(filepath: str) -> str:
    hasher = hashlib.sha256()
    with open(filepath, 'rb') as f:
        while chunk := f.read(65536):
            hasher.update(chunk)
    return hasher.hexdigest().lower()

def build_canonical_payload(version_code: int, version_name: str, apk_url: str,
                            apk_sha256: str, apk_size_bytes: int, release_notes: str) -> bytes:
    """
    Constructs the canonical byte sequence signed by Ed25519.
    Matches com.bridge.android.update.model.UpdateManifest.getCanonicalPayload() exactly.
    """
    payload_str = f"v1\n{version_code}\n{version_name}\n{apk_url}\n{apk_sha256.lower()}\n{apk_size_bytes}\n{release_notes}"
    return payload_str.encode('utf-8')

def main():
    parser = argparse.ArgumentParser(description="Publish & Sign Bridge Update Manifest")
    parser.add_argument("--apk", required=True, help="Path to the release APK file")
    parser.add_argument("--version-code", type=int, required=True, help="Android versionCode (e.g. 2)")
    parser.add_argument("--version-name", required=True, help="Android versionName (e.g. '1.1.0')")
    parser.add_argument("--apk-url", required=True, help="HTTPS download URL for APK on GitHub Releases")
    parser.add_argument("--notes", default="", help="Release notes text")
    parser.add_argument("--notes-file", help="Path to markdown/text file containing release notes")
    parser.add_argument("--min-version-code", type=int, default=0, help="Minimum supported version code")
    parser.add_argument("--mandatory", action="store_true", help="Mark this update as mandatory")
    parser.add_argument("--private-key", help="Path to Ed25519 private key (or use BRIDGE_UPDATE_PRIVATE_KEY env var)")
    parser.add_argument("--out", default="release/update-manifest.json", help="Path to write the signed manifest")
    args = parser.parse_args()

    if not os.path.isfile(args.apk):
        print(f"Error: APK file not found at {args.apk}", file=sys.stderr)
        sys.exit(1)

    if not args.apk_url.startswith("https://"):
        print("Warning: apk-url should start with https:// for production security", file=sys.stderr)

    # Resolve release notes
    release_notes = args.notes
    if args.notes_file:
        with open(args.notes_file, 'r', encoding='utf-8') as nf:
            release_notes = nf.read().strip()
    if not release_notes:
        release_notes = f"Bridge update {args.version_name}"

    # Resolve private key
    sk_hex = None
    if args.private_key:
        with open(args.private_key, 'r') as kf:
            sk_hex = kf.read().strip()
    elif "BRIDGE_UPDATE_PRIVATE_KEY" in os.environ:
        sk_hex = os.environ["BRIDGE_UPDATE_PRIVATE_KEY"].strip()
    elif os.path.exists("scripts/release/keys/bridge_update_private.key"):
        with open("scripts/release/keys/bridge_update_private.key", 'r') as kf:
            sk_hex = kf.read().strip()
    else:
        print("Error: No Ed25519 private key provided. Set BRIDGE_UPDATE_PRIVATE_KEY env var or pass --private-key", file=sys.stderr)
        sys.exit(1)

    sk_bytes = bytes.fromhex(sk_hex)
    pk_bytes = ed25519.publickey(sk_bytes)

    apk_sha256 = compute_sha256(args.apk)
    apk_size = os.path.getsize(args.apk)

    # Canonical bytes
    canonical_bytes = build_canonical_payload(
        args.version_code, args.version_name, args.apk_url, apk_sha256, apk_size, release_notes
    )

    # Sign using Ed25519
    sig_bytes = ed25519.sign(sk_bytes, canonical_bytes)
    sig_b64 = base64.b64encode(sig_bytes).decode('ascii')

    # Self-verify signature
    if not ed25519.verify(pk_bytes, canonical_bytes, sig_bytes):
        print("Critical Error: Internal signature verification failed!", file=sys.stderr)
        sys.exit(1)

    manifest = {
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "minSupportedVersionCode": args.min_version_code,
        "apkUrl": args.apk_url,
        "apkSha256": apk_sha256,
        "apkSizeBytes": apk_size,
        "releaseNotes": release_notes,
        "publishedAt": datetime.now(timezone.utc).isoformat(),
        "mandatory": args.mandatory,
        "signature": sig_b64
    }

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, 'w', encoding='utf-8') as out_f:
        json.dump(manifest, out_f, indent=2, ensure_ascii=False)
        out_f.write("\n")

    print("=" * 70)
    print("BRIDGE UPDATE MANIFEST GENERATED & SIGNED")
    print("=" * 70)
    print(f"Manifest written to: {args.out}")
    print(f"Version:            {args.version_name} (code: {args.version_code})")
    print(f"APK Size:           {apk_size} bytes ({apk_size / (1024 * 1024):.2f} MB)")
    print(f"APK SHA-256:        {apk_sha256}")
    print(f"Signer Public Key:  {pk_bytes.hex()}")
    print(f"Signature (Base64): {sig_b64}")
    print("=" * 70)

if __name__ == "__main__":
    main()
