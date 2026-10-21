#!/usr/bin/env python3
"""
Pure Python Ed25519 (RFC 8032) implementation.
Zero external dependencies. Compatible with standard library Python 3.7+.
"""

import hashlib
import os

b = 256
q = 2**255 - 19
l = 2**252 + 27742317777372353535851937790883648493

def H(m: bytes) -> bytes:
    return hashlib.sha512(m).digest()

def expmod(base: int, exp: int, mod: int) -> int:
    return pow(base, exp, mod)

def inv(x: int) -> int:
    return expmod(x, q - 2, q)

d = -121665 * inv(121666) % q
I = expmod(2, (q - 1) // 4, q)

def xrecover(y: int) -> int:
    xx = (y * y - 1) * inv(d * y * y + 1) % q
    x = expmod(xx, (q + 3) // 8, q)
    if (x * x - xx) % q != 0:
        x = (x * I) % q
    if x % 2 != 0:
        x = q - x
    return x

By = 4 * inv(5) % q
Bx = xrecover(By)
B = [Bx % q, By % q]

def edwards(P, Q):
    x1, y1 = P[0], P[1]
    x2, y2 = Q[0], Q[1]
    x3 = (x1 * y2 + x2 * y1) * inv(1 + d * x1 * x2 * y1 * y2) % q
    y3 = (y1 * y2 + x1 * x2) * inv(1 - d * x1 * x2 * y1 * y2) % q
    return [x3, y3]

def scalarmult(P, e: int):
    if e == 0:
        return [0, 1]
    Q = scalarmult(P, e // 2)
    Q = edwards(Q, Q)
    if e & 1:
        Q = edwards(Q, P)
    return Q

def encodepoint(P) -> bytes:
    x, y = P[0], P[1]
    bits = [(y >> i) & 1 for i in range(b - 1)] + [x & 1]
    return bytes([sum([bits[i * 8 + j] << j for j in range(8)]) for i in range(b // 8)])

def decodepoint(enc: bytes):
    if len(enc) != 32:
        return None
    y = int.from_bytes(enc, 'little') & ((1 << 255) - 1)
    if y >= q:
        return None
    x0 = (enc[31] >> 7) & 1
    xx = (y * y - 1) * inv(d * y * y + 1) % q
    x = expmod(xx, (q + 3) // 8, q)
    if (x * x - xx) % q != 0:
        x = (x * I) % q
    if (x * x - xx) % q != 0:
        return None
    if x % 2 != x0:
        x = q - x
    return [x, y]

def publickey(sk: bytes) -> bytes:
    """Derives 32-byte public key from 32-byte secret key seed."""
    if len(sk) != 32:
        raise ValueError("Secret key seed must be 32 bytes")
    h = H(sk)
    a = 2**(b - 2) + sum(2**i * ((h[i // 8] >> (i % 8)) & 1) for i in range(3, b - 2))
    A = scalarmult(B, a)
    return encodepoint(A)

def sign(sk: bytes, m: bytes) -> bytes:
    """Signs message m with 32-byte secret key seed. Returns 64-byte signature."""
    if len(sk) != 32:
        raise ValueError("Secret key seed must be 32 bytes")
    pk = publickey(sk)
    h = H(sk)
    a = 2**(b - 2) + sum(2**i * ((h[i // 8] >> (i % 8)) & 1) for i in range(3, b - 2))
    r = int.from_bytes(H(h[32:] + m), 'little') % l
    R = scalarmult(B, r)
    R_bytes = encodepoint(R)
    k = int.from_bytes(H(R_bytes + pk + m), 'little') % l
    S = (r + k * a) % l
    return R_bytes + S.to_bytes(32, 'little')

def verify(pk: bytes, m: bytes, sig: bytes) -> bool:
    """Verifies 64-byte signature over message m using 32-byte public key."""
    if len(pk) != 32 or len(sig) != 64:
        return False
    R_bytes = sig[:32]
    S_bytes = sig[32:]
    S = int.from_bytes(S_bytes, 'little')
    if S >= l:
        return False
    A = decodepoint(pk)
    if A is None:
        return False
    R = decodepoint(R_bytes)
    if R is None:
        return False
    k = int.from_bytes(H(R_bytes + pk + m), 'little') % l
    SB = scalarmult(B, S)
    kA = scalarmult(A, k)
    RplusKA = edwards(R, kA)
    return SB[0] == RplusKA[0] and SB[1] == RplusKA[1]

def generate_keypair():
    """Generates a cryptographically random (secret_key, public_key) pair."""
    sk = os.urandom(32)
    pk = publickey(sk)
    return sk, pk
