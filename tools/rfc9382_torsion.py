#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Test-only independent affine Edwards arithmetic with full torsion semantics.

Unlike python-ecdsa's prime-subgroup-oriented PointEdwards convenience behavior,
this reference distinguishes all eight torsion elements, including (0,-1).
It uses Python arbitrary-precision integers and the affine curve equations;
it neither imports nor invokes the Kotlin implementation's projective arithmetic.
"""
import json
import pathlib

P = 2**255 - 19
L = 2**252 + 27742317777372353535851937790883648493
D = -121665 * pow(121666, -1, P) % P
IDENTITY = (0, 1)


def decode(encoded):
    n = int.from_bytes(encoded, "little")
    sign, y = n >> 255, n & ((1 << 255) - 1)
    assert len(encoded) == 32 and y < P
    x2 = (y * y - 1) * pow(D * y * y + 1, -1, P) % P
    x = pow(x2, (P + 3) // 8, P)
    if x * x % P != x2:
        x = x * pow(2, (P - 1) // 4, P) % P
    if x * x % P != x2:
        raise ValueError("Point is not on Edwards25519")
    assert x != 0 or sign == 0
    if x % 2 != sign:
        x = P - x
    return x, y


def encode(point):
    x, y = point
    return (y + ((x % 2) << 255)).to_bytes(32, "little")


def add(left, right):
    x, y = left
    u, v = right
    product = D * x * u * y * v % P
    return ((x * v + y * u) * pow(1 + product, -1, P) % P,
            (y * v + x * u) * pow(1 - product, -1, P) % P)


def multiply(point, scalar):
    result = IDENTITY
    while scalar:
        if scalar & 1:
            result = add(result, point)
        point = add(point, point)
        scalar >>= 1
    return result


def main():
    root = pathlib.Path(__file__).resolve().parents[1]
    base = json.loads((root / "test-vectors/rfc9382-edwards25519.json").read_text())["vectors"][0]
    m = decode(bytes.fromhex("d048032c6ea0b6d697ddc2e86bda85a33adac920f1bf18e1b0c6d166a5cecdaf"))
    n = decode(bytes.fromhex("d3bfb518f44f3430f29d0c92af503865a1ed3281dc69b35dd868ba85f886c4ab"))
    assert multiply(m, L) == IDENTITY
    assert multiply(n, L) == IDENTITY
    for y in range(2, 128):
        try:
            candidate = decode(y.to_bytes(32, "little"))
        except ValueError:
            continue
        torsion = multiply(candidate, L)
        if multiply(torsion, 8) == IDENTITY and multiply(torsion, 4) != IDENTITY:
            break
    else:
        raise AssertionError("No torsion generator found")
    pure = [multiply(torsion, i) for i in range(8)]
    assert len(set(pure)) == 8
    pb = decode(bytes.fromhex(base["pB"]))
    w, x = int(base["w"], 16), int(base["x"], 16)
    mask = multiply(n, w)
    inverse_mask = (-mask[0] % P, mask[1])
    mixed = []
    for t in pure[1:]:
        malicious = add(pb, t)
        assert multiply(malicious, L) != IDENTITY
        assert multiply(malicious, 8) != IDENTITY
        unmasked = add(malicious, inverse_mask)
        shared = encode(multiply(unmasked, 8 * x)).hex()
        assert shared == base["k"]
        mixed.append(encode(malicious).hex())
    output = {"reference": "independent Python affine Edwards25519, tools/rfc9382_torsion.py",
              "baseVector": 0, "mPrimeOrder": True, "nPrimeOrder": True,
              "pureTorsion": [encode(t).hex() for t in pure],
              "mixedOrderPeerMessages": mixed, "sharedK": base["k"]}
    (root / "test-vectors/rfc9382-edwards25519-torsion.json").write_text(json.dumps(output, indent=2) + "\n")
    lines = ["// SPDX-License-Identifier: AGPL-3.0-only",
             "// Independent affine Python reference: tools/rfc9382_torsion.py.",
             "package io.github.flyfish233.spake2", "", "internal object EdwardsTorsionVectors {",
             "    val pure: List<String> = listOf("]
    lines += [f'        "{v}",' for v in output["pureTorsion"]]
    lines += ["    )", "    val mixed: List<String> = listOf("]
    lines += [f'        "{v}",' for v in mixed]
    lines += ["    )", "}", ""]
    (root / "src/commonTest/kotlin/io/github/flyfish233/spake2/EdwardsTorsionVectors.kt").write_text("\n".join(lines))
    print("PASS: RFC M/N have prime order; all 8 torsion elements and 7 mixed-order peer messages independently verified.")


if __name__ == "__main__":
    main()
