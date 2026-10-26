#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Reproducible pinned Wycheproof subset for P256 raw point arithmetic.

Input vector data is Apache-2.0 and remains separately attributed. This original
selection/generation harness is AGPL-3.0-only. It never computes expected outputs
using the implementation under test.
"""
import argparse
import hashlib
import json
import pathlib
import urllib.request

REVISION = "d17cc89fa5c8f4f4d378b5385ebf75da5d0926c9"
NAME = "testvectors_v1/ecdh_secp256r1_ecpoint_test.json"
SHA256 = "648f16d077caf2400d02331ca51f44744c72c799830c8d0595d0b18b6dd9f886"
URL = f"https://raw.githubusercontent.com/C2SP/wycheproof/{REVISION}/{NAME}"


def select(tests):
    chosen = {t["tcId"] for t in tests if t["result"] != "valid"}
    for tag in ["Normal", "AdditionChain", "CVE-2017-8932"]:
        chosen.update(t["tcId"] for t in tests if tag in t["flags"])
    for tag, count in [("EdgeCaseDoubling", 16), ("EdgeCaseEphemeralKey", 12), ("EdgeCaseSharedSecret", 16)]:
        candidates = [t for t in tests if t["result"] == "valid" and tag in t["flags"]]
        chosen.update(candidates[i * (len(candidates) - 1) // (count - 1)]["tcId"] for i in range(count))
    return [t for t in tests if t["tcId"] in chosen]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=pathlib.Path)
    parser.add_argument("--root", type=pathlib.Path, required=True)
    parser.add_argument("--test-source", type=pathlib.Path, required=True)
    args = parser.parse_args()
    data = args.input.read_bytes() if args.input else urllib.request.urlopen(URL, timeout=30).read()
    assert hashlib.sha256(data).hexdigest() == SHA256, "Pinned input digest mismatch"
    decoded = json.loads(data)
    selected = select([t for g in decoded["testGroups"] for t in g["tests"]])
    metadata = {"source": URL, "commit": REVISION, "inputSha256": SHA256,
                "license": "Apache-2.0", "generator": "tools/generate_wycheproof_subset.py",
                "scope": "All invalid raw-point cases, compressed acceptable case rejected by RFC encoding policy, representative valid edge cases",
                "tests": selected}
    vectors = args.root / "test-vectors"
    vectors.mkdir(parents=True, exist_ok=True)
    (vectors / "wycheproof-p256-subset.json").write_text(json.dumps(metadata, indent=2) + "\n")
    valid = [t for t in selected if t["result"] == "valid"]
    rejected = [t for t in selected if t["result"] != "valid"]
    lines = [
        "// Original harness: AGPL-3.0-only; Wycheproof test data: Apache-2.0.",
        "// Wycheproof contributors (test data). See third-party/WYCHEPROOF-NOTICE.txt.",
        f"// Pinned source: C2SP/wycheproof {REVISION}; generator tools/generate_wycheproof_subset.py.",
        "package io.github.flyfish233.spake2.internal.math", "",
        "import kotlin.test.Test", "import kotlin.test.assertContentEquals", "import kotlin.test.assertFailsWith", "",
        "class WycheproofP256Test {",
        "    @Test fun independentValidRawPointEdgeCases() {",
        "        for (line in valid.lines()) {",
        "            val fields = line.split('|')",
        "            val point = P256.decode(mathHex(fields[1]))",
        "            val result = try { P256.multiply(point, mathHex(fields[2])) } finally { point.destroy() }",
        '            try { assertContentEquals(mathHex(fields[3]), P256.encode(result).copyOfRange(1,33), "Wycheproof tcId=${fields[0]}") }',
        "            finally { result.destroy() }",
        "        }", "    }", "",
        "    @Test fun allInvalidRawPointsAndUncompressedEncodingPolicy() {",
        "        for (line in rejected.lines()) {",
        "            val fields = line.split('|')",
        '            assertFailsWith<IllegalArgumentException>("Wycheproof tcId=${fields[0]}") { P256.decode(mathHex(fields[1])) }',
        "        }", "    }", "",
        '    private val valid = """',
    ]
    lines += [f'{t["tcId"]}|{t["public"]}|{int(t["private"],16):064x}|{t["shared"]}' for t in valid]
    lines += ['""".trim()', '', '    private val rejected = """']
    lines += [f'{t["tcId"]}|{t["public"]}' for t in rejected]
    lines += ['""".trim()', '}', '']
    target = args.test_source / "kotlin/io/github/flyfish233/spake2/internal/math/WycheproofP256Test.kt"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("\n".join(lines))
    print(f"Generated pinned Wycheproof subset: {len(valid)} valid edge cases, {len(rejected)} rejected encodings (24 invalid plus 1 unsupported compressed point).")


if __name__ == "__main__":
    main()
