#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Independently verify RFC 9382 Appendix B and emit P256/Edwards test fixtures.

Test-only dependencies: python-ecdsa 0.19.1; Python hashlib/hmac. No production
Kotlin implementation is imported or called. See docs/reference-vectors.md.
"""
import argparse
import hashlib
import hmac
import json
import pathlib
import re
import sys

import ecdsa
from ecdsa.curves import Ed25519, NIST256p
from ecdsa.ellipticcurve import PointEdwards, PointJacobi, INFINITY

assert ecdsa.__version__ == "0.19.1", "Use the pinned independent reference version"

FIELDS = "a b aad w x y pA pB k tt hashTt ke ka kcA kcB cA cB".split()
P256_M = "02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"
P256_N = "03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49"
ED_M = "d048032c6ea0b6d697ddc2e86bda85a33adac920f1bf18e1b0c6d166a5cecdaf"
ED_N = "d3bfb518f44f3430f29d0c92af503865a1ed3281dc69b35dd868ba85f886c4ab"


def hx(value):
    return bytes.fromhex(value)


def transcript(fields):
    return b"".join(len(value).to_bytes(8, "little") + value for value in fields)


def hkdf_sha256(ikm, salt, info, length):
    prk = hmac.digest(salt, ikm, "sha256")
    output = previous = b""
    for counter in range(1, (length + 31) // 32 + 1):
        previous = hmac.digest(prk, previous + info + bytes([counter]), "sha256")
        output += previous
    return output[:length]


def key_schedule(a, b, aad, w, pa, pb, shared):
    tt = transcript([a, b, pa, pb, shared, w.to_bytes(32, "big")])
    digest = hashlib.sha256(tt).digest()
    ke, ka = digest[:16], digest[16:]
    confirmations = hkdf_sha256(ka, b"", b"ConfirmationKeys" + aad, 32)
    kca, kcb = confirmations[:16], confirmations[16:]
    return dict(
        tt=tt.hex(), hashTt=digest.hex(), ke=ke.hex(), ka=ka.hex(),
        kcA=kca.hex(), kcB=kcb.hex(),
        cA=hmac.digest(kca, tt, "sha256").hex(),
        cB=hmac.digest(kcb, tt, "sha256").hex(),
    )


def make_vector(curve, a, b, aad, w, x, y):
    if curve == "P256":
        c = NIST256p
        m = PointJacobi.from_bytes(c.curve, hx(P256_M), valid_encodings={"compressed"})
        n = PointJacobi.from_bytes(c.curve, hx(P256_N), valid_encodings={"compressed"})
        encode = lambda p: p.to_bytes("uncompressed")
        negate = lambda p: -p if p != INFINITY else p
        cofactor = 1
    else:
        c = Ed25519
        m = PointEdwards.from_bytes(c.curve, hx(ED_M))
        n = PointEdwards.from_bytes(c.curve, hx(ED_N))
        encode = lambda p: p.to_bytes()
        negate = lambda p: PointEdwards(c.curve, -p.x() % c.curve.p(), p.y(), 1,
                                        (-p.x() * p.y()) % c.curve.p()) if p != INFINITY else p
        cofactor = 8
    assert m * c.order == INFINITY and n * c.order == INFINITY
    pa = c.generator * x + m * w
    pb = c.generator * y + n * w
    shared_a = (pb + negate(n * w)) * (cofactor * x)
    shared_b = (pa + negate(m * w)) * (cofactor * y)
    assert shared_a == shared_b
    pa, pb, shared = encode(pa), encode(pb), encode(shared_a)
    record = dict(a=a.hex(), b=b.hex(), aad=aad.hex(), w=w.to_bytes(32, "big").hex(),
                  x=x.to_bytes(32, "big").hex(), y=y.to_bytes(32, "big").hex(),
                  pA=pa.hex(), pB=pb.hex(), k=shared.hex())
    record.update(key_schedule(a, b, aad, w, pa, pb, shared))
    return record


def parse_rfc(text):
    result = []
    rename = {"K": "k", "TT": "tt", "HASH(TT)": "hashTt", "Hash(TT)": "hashTt",
              "Ke": "ke", "Ka": "ka", "KcA": "kcA", "KcB": "kcB", "A conf": "cA", "B conf": "cB"}
    for block in re.split(r"(?m)^ spake2: ", text)[1:]:
        identities = re.match(r"A='([^']*)', B='([^']*)'", block)
        record = dict(a=identities[1].encode().hex(), b=identities[2].encode().hex(), aad="")
        current = None
        for line in block.splitlines()[1:]:
            match = re.match(r"\s*([A-Za-z() ]+) = 0x([0-9a-f]+)$", line)
            if match:
                current = rename.get(match[1], match[1])
                record[current] = match[2]
            elif current and re.fullmatch(r"\s+[0-9a-f]+", line):
                record[current] += line.strip()
            elif line.strip():
                current = None
        assert set(record) == set(FIELDS), set(record)
        result.append(record)
    assert len(result) == 4
    return result


def kotlin(records, name, include_class=False):
    copyright = (
        "// RFC 9382 official test-vector data: Copyright (c) 2023 IETF Trust and\n"
        "// the persons identified as the document authors. All rights reserved.\n"
        "// IRTF-stream terms: third-party/ietf/RFC9382-IRTF-NOTICE.\n"
        "// No section-4 BSD or original-AGPL grant is asserted for official data.\n"
    ) if name == "RfcOfficialVectors" else "// Generated independently with python-ecdsa 0.19.1; see tools/rfc9382_vectors.py.\n"
    header = "// Original fixture harness: AGPL-3.0-only; official data retains its IRTF-stream terms.\n" if name == "RfcOfficialVectors" else "// SPDX-License-Identifier: AGPL-3.0-only\n"
    lines = [header + copyright,
             "package io.github.flyfish233.spake2\n"]
    if include_class:
        lines.append("internal data class RfcVector(\n" + "".join(f"    val {field}: String,\n" for field in FIELDS) + ")\n")
    lines.extend([f"internal object {name} {{", "    val all: List<RfcVector> = listOf("])
    for record in records:
        lines.append("        RfcVector(")
        lines.extend(f'            {field} = "{record[field]}",' for field in FIELDS)
        lines.append("        ),")
    lines.extend(["    )", "}", ""])
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rfc-text", type=pathlib.Path, required=True)
    parser.add_argument("--root", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    official = parse_rfc(args.rfc_text.read_text())
    for index, record in enumerate(official):
        independent = make_vector("P256", hx(record["a"]), hx(record["b"]), b"",
                                  int(record["w"], 16), int(record["x"], 16), int(record["y"], 16))
        for key in FIELDS:
            assert independent[key] == record[key], (index, key, independent[key], record[key])
    edwards = []
    for i in range(12):
        scalar = lambda label: int.from_bytes(hashlib.sha512(f"spake2 independent RFC fixture {i} {label}".encode()).digest(), "big") % (Ed25519.order - 1) + 1
        w, x, y = (scalar(label) for label in ("w", "x", "y"))
        if i == 8: w = 0
        if i == 9: w = 1
        if i == 10: w = Ed25519.order - 1
        if i == 11: x, y = 1, Ed25519.order - 1
        a = b"server" if i % 4 in (0, 2) else b""
        b = b"client" if i % 4 in (0, 1) else b""
        aad = b"" if i < 4 else bytes(range(i + 1))
        edwards.append(make_vector("Edwards25519", a, b, aad, w, x, y))
    test_dir = args.root / "src/commonTest/kotlin/io/github/flyfish233/spake2"
    test_dir.mkdir(parents=True, exist_ok=True)
    (test_dir / "RfcOfficialVectors.kt").write_text(kotlin(official, "RfcOfficialVectors", True))
    (test_dir / "EdwardsReferenceVectors.kt").write_text(kotlin(edwards, "EdwardsReferenceVectors"))
    fixture_dir = args.root / "test-vectors"
    fixture_dir.mkdir(exist_ok=True)
    metadata = {"generator": "tools/rfc9382_vectors.py", "python": sys.version.split()[0], "reference": "python-ecdsa", "referenceVersion": ecdsa.__version__}
    for name, vectors in [("rfc9382-p256", official), ("rfc9382-edwards25519", edwards)]:
        (fixture_dir / (name + ".json")).write_text(json.dumps(dict(metadata, vectors=vectors), indent=2) + "\n")
    print(f"Verified all 4 official RFC P256 vectors in all {len(FIELDS)} fields; generated {len(edwards)} Edwards25519 vectors with python-ecdsa {ecdsa.__version__}.")


if __name__ == "__main__":
    main()
