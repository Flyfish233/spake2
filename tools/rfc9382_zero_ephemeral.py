#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Test-only independent known answers for canonical zero ephemeral scalars.

For w=1 and x=0, the transmitted point is exactly the RFC's fixed M or N.
P-256 y coordinates are recovered with Python integer arithmetic, independently
of the Kotlin point implementation. Edwards parameters are already in wire form.
The published parameters retain the RFC 9382 IRTF notice in third-party/ietf/.
"""
from pathlib import Path
import hashlib
import json

P256_P = int('ffffffff00000001000000000000000000000000ffffffffffffffffffffffff', 16)
P256_B = int('5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b', 16)
PARAMETERS = {
    'P256_SHA256': {
        'ALICE': '02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f',
        'BOB': '03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49',
    },
    'EDWARDS25519_SHA256': {
        'ALICE': 'd048032c6ea0b6d697ddc2e86bda85a33adac920f1bf18e1b0c6d166a5cecdaf',
        'BOB': 'd3bfb518f44f3430f29d0c92af503865a1ed3281dc69b35dd868ba85f886c4ab',
    },
}


def uncompress_p256(encoded):
    data = bytes.fromhex(encoded)
    assert len(data) == 33 and data[0] in (2, 3)
    x = int.from_bytes(data[1:], 'big')
    assert x < P256_P
    rhs = (x*x*x - 3*x + P256_B) % P256_P
    y = pow(rhs, (P256_P + 1) // 4, P256_P)
    assert y*y % P256_P == rhs
    if y & 1 != data[0] & 1:
        y = P256_P - y
    return (b'\x04' + x.to_bytes(32, 'big') + y.to_bytes(32, 'big')).hex()


def main():
    root = Path(__file__).resolve().parents[1]
    rows = []
    for suite, roles in PARAMETERS.items():
        for role, parameter in roles.items():
            rows.append({
                'suite': suite, 'role': role, 'w': (1).to_bytes(32, 'big').hex(),
                'ephemeral': bytes(32).hex(), 'published_parameter': parameter,
                'message': uncompress_p256(parameter) if suite == 'P256_SHA256' else parameter,
                'shared_point': 'identity; rejected by the public session before any key is released',
            })
    out = {
        'reference': 'RFC 9382 sections 3.3 and 6; w=1, x=0 implies pA=M / pB=N',
        'source': 'https://www.rfc-editor.org/rfc/rfc9382.html',
        'generator_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'implementation': 'Python standard-library integers; no Kotlin implementation imported',
        'cases': rows,
    }
    path = root / 'test-vectors/rfc9382-zero-ephemeral.json'
    path.write_text(json.dumps(out, indent=2) + '\n')
    print(json.dumps(rows, indent=2))


if __name__ == '__main__':
    main()
