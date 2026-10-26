#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Regenerate independent padding-boundary expected values using Python hashlib."""
import hashlib
import pathlib

root = pathlib.Path(__file__).resolve().parents[1]
lengths = [0, 1, 7, 31, 32, 55, 56, 57, 63, 64, 65, 111, 112, 113, 127, 128,
           129, 255, 256, 257, 511, 512, 513, 1024, 4095]
lines = [
    "// SPDX-License-Identifier: AGPL-3.0-only",
    "// Expected digests independently generated with Python hashlib; see tools/rfc_hash_boundaries.py.",
    "package io.github.flyfish233.spake2", "",
    "import io.github.flyfish233.spake2.internal.crypto.Sha256",
    "import io.github.flyfish233.spake2.internal.crypto.Sha512",
    "import kotlin.test.Test", "import kotlin.test.assertContentEquals", "",
    "class HashBoundaryTest {",
    "    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }",
    "    @Test fun independentPaddingBoundaryVectors() {", "        val vectors = listOf(",
]
for size in lengths:
    data = bytes((i * 131 + 17) % 256 for i in range(size))
    lines.append(f'            Triple({size}, "{hashlib.sha256(data).hexdigest()}", "{hashlib.sha512(data).hexdigest()}"),')
lines += [
    "        )", "        for ((size, sha256, sha512) in vectors) {",
    "            val input = ByteArray(size) { (it * 131 + 17).toByte() }",
    "            val original = input.copyOf()",
    '            assertContentEquals(hex(sha256), Sha256.digest(input), "SHA256 length=$size")',
    '            assertContentEquals(hex(sha512), Sha512.digest(input), "SHA512 length=$size")',
    "            assertContentEquals(original, input)", "        }", "    }", "}", "",
]
(root / "src/commonTest/kotlin/io/github/flyfish233/spake2/HashBoundaryTest.kt").write_text("\n".join(lines))
print(f"Generated {len(lengths)} independent SHA256 and SHA512 boundary vectors.")
