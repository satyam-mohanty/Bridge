#!/usr/bin/env python3
"""
Generates an Ed25519 keypair for signing Bridge Android update manifests.
The private key MUST remain secret and MUST NEVER be committed to version control.
Only the public key is embedded into the Android client application.
"""

import os
import sys
import base64
import argparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ed25519

def main():
    parser = argparse.ArgumentParser(description="Generate an Ed25519 keypair for Bridge update manifests")
    parser.add_argument("--out-dir", default="scripts/release/keys", help="Directory to save private key (git-ignored)")
    parser.add_argument("--key-name", default="bridge_update_private.key", help="Private key file name")
    args = parser.parse_args()

    sk, pk = ed25519.generate_keypair()
    sk_hex = sk.hex()
    pk_hex = pk.hex()
    pk_b64 = base64.b64encode(pk).decode('ascii')

    os.makedirs(args.out_dir, exist_ok=True)
    priv_path = os.path.join(args.out_dir, args.key_name)
    with open(priv_path, "w") as f:
        f.write(sk_hex + "\n")
    try:
        os.chmod(priv_path, 0o600)
    except Exception:
        pass

    print("=" * 70)
    print("BRIDGE UPDATE KEYPAIR GENERATED SUCCESSFULLY")
    print("=" * 70)
    print(f"Private Key saved to (chmod 600): {priv_path}")
    print("WARNING: NEVER commit this private key into git! Keep it secret.")
    print("Store it in your CI/CD secrets (e.g. GitHub Secret: BRIDGE_UPDATE_PRIVATE_KEY).")
    print()
    print(f"Public Key (Hex):    {pk_hex}")
    print(f"Public Key (Base64): {pk_b64}")
    print()
    print("Kotlin configuration snippet for UpdateConfig.kt:")
    print("-" * 70)
    print(f'const val UPDATE_PUBLIC_KEY_HEX = "{pk_hex}"')
    print("-" * 70)

if __name__ == "__main__":
    main()
