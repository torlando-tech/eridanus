#!/usr/bin/env python3
"""
Verify that critical reflection-loaded classes survive R8 minification.

Libraries like msgpack and BouncyCastle use Class.forName() internally to load
implementation classes. R8 can't see these references statically, so it strips
the classes unless ProGuard keep rules are in place. This script verifies those
classes exist in the release APK's DEX files.

Usage:
    python scripts/verify_r8_keeps.py <path-to-release.apk>

Exit codes:
    0 - All critical classes verified
    1 - Some classes are missing (likely stripped by R8)
    2 - Usage error or file not found
"""
import sys
import zipfile
from pathlib import Path

# Classes that must survive R8 minification.
# Each entry: (class_identifier, description)
# The identifier is searched as a UTF-8 string in the DEX files.
# Use '/' separators for JVM internal format (how DEX stores class names).
CRITICAL_CLASSES = [
    # msgpack uses Class.forName() to load its buffer implementation
    ("org/msgpack/core/buffer/MessageBufferU", "msgpack unsafe buffer (loaded via reflection)"),
    ("org/msgpack/core/buffer/MessageBuffer", "msgpack buffer base class"),
    ("org/msgpack/core/MessagePack", "msgpack entry point"),
    ("org/msgpack/core/MessagePacker", "msgpack packer"),
    ("org/msgpack/core/MessageUnpacker", "msgpack unpacker"),

    # BouncyCastle crypto (used by Reticulum for identity/encryption)
    ("org/bouncycastle/crypto/engines/AESEngine", "AES encryption engine"),
    ("org/bouncycastle/crypto/generators/Ed25519KeyPairGenerator", "Ed25519 key generation"),
    ("org/bouncycastle/crypto/params/Ed25519PrivateKeyParameters", "Ed25519 private key params"),
    ("org/bouncycastle/crypto/params/X25519PublicKeyParameters", "X25519 public key params"),
    ("org/bouncycastle/crypto/agreement/X25519Agreement", "X25519 key agreement"),
    ("org/bouncycastle/crypto/signers/Ed25519Signer", "Ed25519 signing"),

    # Reticulum core classes (kept by ProGuard rules, but verify)
    ("network/reticulum/Reticulum", "Reticulum core"),
    ("network/reticulum/identity/Identity", "Reticulum identity"),
    ("network/reticulum/transport/Transport", "Reticulum transport"),

    # CBOR serialization (used by RRC protocol)
    ("co/nstant/in/cbor/CborDecoder", "CBOR decoder"),
    ("co/nstant/in/cbor/CborEncoder", "CBOR encoder"),
]


def extract_dex_content(apk_path: Path) -> bytes:
    """Extract and concatenate all DEX files from APK."""
    dex_content = b""
    with zipfile.ZipFile(apk_path, "r") as apk:
        for name in apk.namelist():
            if name.endswith(".dex"):
                dex_content += apk.read(name)
    return dex_content


def main():
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <path-to-release.apk>")
        sys.exit(2)

    apk_path = Path(sys.argv[1])
    if not apk_path.exists():
        print(f"ERROR: APK not found: {apk_path}")
        sys.exit(2)

    print("=" * 60)
    print("R8 Critical Class Verification")
    print("=" * 60)
    print()

    print(f"Extracting DEX from: {apk_path}")
    dex_content = extract_dex_content(apk_path)
    print(f"DEX content size: {len(dex_content):,} bytes")
    print()

    print(f"Checking {len(CRITICAL_CLASSES)} critical classes...")
    print()

    found = []
    missing = []

    for class_id, description in CRITICAL_CLASSES:
        if class_id.encode("utf-8") in dex_content:
            found.append((class_id, description))
        else:
            missing.append((class_id, description))

    # Report results
    print("-" * 60)
    print("RESULTS")
    print("-" * 60)

    print(f"\nVerified: {len(found)}/{len(CRITICAL_CLASSES)}")
    for class_id, description in found:
        # Convert JVM internal format to dotted for readability
        print(f"  + {class_id.replace('/', '.')}  ({description})")

    if missing:
        print(f"\nMISSING: {len(missing)} classes stripped by R8:")
        for class_id, description in missing:
            print(f"  X {class_id.replace('/', '.')}  ({description})")

    print()
    print("=" * 60)

    if missing:
        print("VERIFICATION FAILED")
        print("=" * 60)
        print()
        print("Critical classes were stripped by R8 minification!")
        print("These classes are loaded via reflection and will cause")
        print("runtime crashes (ClassNotFoundException).")
        print()
        print("Fix: Add keep rules to consumer-rules.pro in the library")
        print("module, or to app/proguard-rules.pro:")
        print()
        for class_id, _ in missing:
            pkg = class_id.rsplit("/", 1)[0].replace("/", ".")
            print(f"  -keep class {pkg}.** {{ *; }}")
        sys.exit(1)
    else:
        print("VERIFICATION PASSED")
        print("=" * 60)
        print()
        print("All critical classes survived R8 minification.")
        sys.exit(0)


if __name__ == "__main__":
    main()
