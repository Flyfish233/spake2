#!/usr/bin/env python3
"""Round-three independent affine-integer adversarial inputs, test-only.

No production Kotlin arithmetic or previous vector generator is imported.
RFC 8032 curve equation and RFC 9382 masks are the only curve inputs.
"""
import hashlib
import json
from pathlib import Path

P = 2**255 - 19
L = 2**252 + 27742317777372353535851937790883648493
D = (-121665 * pow(121666, -1, P)) % P
I = (0, 1)


def add(a, b):
    x, y = a
    u, v = b
    z = D * x * u * y * v % P
    return ((x * v + y * u) * pow(1 + z, -1, P) % P,
            (y * v + x * u) * pow(1 - z, -1, P) % P)


def multiply(point, scalar):
    result = I
    while scalar:
        if scalar & 1:
            result = add(result, point)
        point = add(point, point)
        scalar >>= 1
    return result


def decode(encoded):
    value = int.from_bytes(bytes.fromhex(encoded), "little")
    y, sign = value & (2**255 - 1), value >> 255
    if y >= P:
        raise ValueError("noncanonical y")
    xx = (y*y - 1) * pow(D*y*y + 1, -1, P) % P
    x = pow(xx, (P + 3)//8, P)
    if x*x % P != xx:
        x = x * pow(2, (P-1)//4, P) % P
    if x*x % P != xx or (x == 0 and sign):
        raise ValueError("invalid root")
    if x % 2 != sign:
        x = -x % P
    return x, y


def encode(point):
    x, y = point
    return (y | ((x & 1) << 255)).to_bytes(32, "little").hex()


def main():
    masks = {
        "ALICE": "d3bfb518f44f3430f29d0c92af503865a1ed3281dc69b35dd868ba85f886c4ab",
        "BOB": "d048032c6ea0b6d697ddc2e86bda85a33adac920f1bf18e1b0c6d166a5cecdaf",
    }
    generator = None
    for y in range(2, 100):
        try:
            candidate = multiply(decode(y.to_bytes(32, "little").hex()), L)
        except ValueError:
            continue
        if multiply(candidate, 8) == I and multiply(candidate, 4) != I:
            generator = candidate
            break
    if generator is None:
        raise RuntimeError("no order-eight torsion generator")
    torsion = [multiply(generator, n) for n in range(8)]
    if len(set(torsion)) != 8:
        raise RuntimeError("incomplete torsion subgroup")
    vectors = []
    for role, encoded in masks.items():
        mask = decode(encoded)
        if multiply(mask, L) != I or mask == I:
            raise RuntimeError("RFC mask not prime order")
        wmask = multiply(mask, 42)
        for n, t in enumerate(torsion):
            point = add(wmask, t)
            # These are valid non-small-order wire points. Rejection must occur
            # after removing the password mask, not merely on receiving T.
            if multiply(point, 8) == I:
                raise RuntimeError("bad attack construction")
            unmasked = add(point, (-wmask[0] % P, wmask[1]))
            if multiply(unmasked, 8*7) != I:
                raise RuntimeError("attack does not force identity shared point")
            vectors.append({"victimRole": role, "torsionIndex": n,
                            "peerMessage": encode(point),
                            "expected": "terminal failure; no confirmation/key"})
    report = {
        "kind": "independent affine integer reference; not a Kotlin execution",
        "passwordScalar": 42, "victimEphemeral": 7,
        "source": "RFC 9382 sections 3.2, 3.3, 6 and RFC 8032 section 5.1",
        "torsionGenerator": encode(generator), "vectors": vectors,
        "scriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    }
    destination = Path(__file__).with_name("masked-torsion-vectors.json")
    destination.write_text(json.dumps(report, indent=2) + "\n")
    print("PASS: generated 16 canonical non-small-order mask+torsion inputs; all force identity only after password unmasking and cofactor clearing")
    for role in masks:
        print(role)
        for entry in vectors:
            if entry["victimRole"] == role:
                print('            "' + entry["peerMessage"] + '",')


if __name__ == "__main__":
    main()
