#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 Flyfish233 and spake2 contributors
"""Generate independent arithmetic fixtures using Python integers and OpenSSL.

Run: python3 tools/generate_math_vectors.py
Requires Python 3.8+ and cryptography (tested with 50.0.2). Neither is a runtime
dependency. All affine P-256 scalar results are additionally cross-checked by
cryptography's OpenSSL backend, including arbitrary M/N point multiplication
via ECDH (x coordinate) and generator multiplication (both coordinates).
Generated files are checked in; running Kotlin tests does not invoke Python.
"""
from hashlib import sha256, sha512
from pathlib import Path
import platform
from cryptography import __version__ as cryptography_version
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.backends.openssl.backend import backend as openssl_backend

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'src/commonTest/kotlin/io/github/flyfish233/spake2/internal/math'
OUT.mkdir(parents=True, exist_ok=True)
P = 2**256 - 2**224 + 2**192 + 2**96 - 1
N = int('ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551', 16)
B = int('5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b', 16)
MODULI = [P, N, 2**255 - 19, 2**252 + 27742317777372353535851937790883648493]
G = (int('6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296', 16),
     int('4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5', 16))

def expand(compressed):
    x = int(compressed[2:], 16)
    y = pow((x*x*x - 3*x + B) % P, (P+1)//4, P)
    if y % 2 != int(compressed[:2], 16) % 2:
        y = P - y
    assert (y*y - x*x*x + 3*x - B) % P == 0
    return x, y

M = expand('02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f')
MASK_N = expand('03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49')

def plus(a, b):
    if a is None: return b
    if b is None: return a
    x1, y1 = a; x2, y2 = b
    if x1 == x2:
        if (y1+y2) % P == 0: return None
        slope = (3*x1*x1 - 3) * pow(2*y1, -1, P) % P
    else:
        slope = (y2-y1) * pow(x2-x1, -1, P) % P
    x3 = (slope*slope-x1-x2) % P
    return x3, (slope*(x1-x3)-y1) % P

def times(k, q):
    result = None
    while k:
        if k & 1: result = plus(result, q)
        q = plus(q, q); k >>= 1
    return result

def encoded(q):
    return '-' if q is None else '04' + ''.join(f'{c:064x}' for c in q)

rows = []
for modulus in MODULI:
    inputs = [0, 1, 2, modulus-1, modulus-2, 2**128-1, 2**128, 2**192]
    inputs += [int.from_bytes(sha256(f'math/a/{modulus}/{i}'.encode()).digest(), 'big') % modulus for i in range(12)]
    for i, a in enumerate(inputs):
        b = int.from_bytes(sha256(f'math/b/{modulus}/{i}'.encode()).digest(), 'big') % modulus
        wide = sha512(f'math/wide/{modulus}/{i}'.encode()).digest()
        values = [modulus, a, b, (a+b) % modulus, (a-b) % modulus, a*b % modulus]
        row = [f'{v:064x}' for v in values]
        row += ['-' if a == 0 else f'{pow(a,-1,modulus):064x}', wide.hex(), f'{int.from_bytes(wide,"big") % modulus:064x}']
        rows.append('|'.join(row))

header = '''// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Flyfish233 and spake2 contributors
// Generated fixtures: tools/generate_math_vectors.py (Python integer oracle).
package io.github.flyfish233.spake2.internal.math

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

'''

mod_test = header + '''class ModMathTest {
    @Test fun independentPythonIntegerVectors() {
        var previous = ""
        var arithmetic: ModMath? = null
        for (line in vectors.lines()) {
            val v = line.split('|')
            if (v[0] != previous) { arithmetic = ModMath(mathHex(v[0])); previous = v[0] }
            val f = arithmetic!!
            val a = f.fromBytes(mathHex(v[1])); val b = f.fromBytes(mathHex(v[2]))
            assertContentEquals(mathHex(v[1]), f.toBytes(a), "roundtrip")
            assertContentEquals(mathHex(v[3]), f.toBytes(f.add(a,b)), "addition")
            assertContentEquals(mathHex(v[4]), f.toBytes(f.subtract(a,b)), "subtraction")
            assertContentEquals(mathHex(v[5]), f.toBytes(f.multiply(a,b)), "multiplication")
            if (v[6] == "-") assertFailsWith<IllegalArgumentException> { f.inverse(a) }
            else assertContentEquals(mathHex(v[6]), f.toBytes(f.inverse(a)), "inverse")
            assertContentEquals(mathHex(v[8]), f.toBytes(f.reduce(mathHex(v[7]))), "512-bit reduction")
        }
    }

    @Test fun canonicalDecodingAndSelection() {
        val p = mathHex("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff")
        val f = ModMath(p)
        assertFailsWith<IllegalArgumentException> { f.fromBytes(p) }
        assertFailsWith<IllegalArgumentException> { f.fromBytes(ByteArray(32) { -1 }) }
        for (size in listOf(0, 1, 31, 33, 64))
            assertFailsWith<IllegalArgumentException> { f.fromBytes(ByteArray(size)) }
        val a = f.fromInt(123); val b = f.fromInt(456)
        assertContentEquals(a, f.select(a,b,0))
        assertContentEquals(b, f.select(a,b,1))
        assertEquals(-1, f.equalMask(a, a)); assertEquals(0, f.equalMask(a,b))
        assertEquals(-1, f.zeroMask(f.zero)); assertEquals(0, f.zeroMask(a))
        assertContentEquals(f.one, f.pow(a, ByteArray(32)))
        assertContentEquals(f.zero, f.reduce(ByteArray(0)))
        assertContentEquals(f.zero, f.add(a, f.negate(a)))
        assertFailsWith<IllegalArgumentException> { f.fromInt(-1) }
    }

    @Test fun resultsDoNotAliasInputsOrConstants() {
        val f = ModMath(mathHex("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed"))
        val a = f.fromInt(10); val snapshot = a.copyOf()
        val output = f.add(a,f.one); output.fill(0)
        assertContentEquals(snapshot, a)
        val one = f.one; one.fill(0)
        assertContentEquals(f.fromInt(1), f.one)
        val bytes = ByteArray(32).also { it[31] = 8 }
        val decoded = f.fromBytes(bytes); bytes.fill(0)
        assertContentEquals(f.fromInt(8), decoded)
    }

    private val vectors = """
''' + '\n'.join(rows) + '''
""".trimIndent()
}
'''
(OUT / 'ModMathTest.kt').write_text(mod_test)

scalars = [0,1,2,3,N-1,N,2**255,2**256-1]
scalars += [int.from_bytes(sha256(f'p256/scalar/{i}'.encode()).digest(),'big') for i in range(12)]
point_rows = []
for scalar in scalars:
    points = [times(scalar,q) for q in [G,M,MASK_N]]
    if scalar % N:
        private = ec.derive_private_key(scalar % N, ec.SECP256R1())
        public = private.public_key().public_numbers()
        assert points[0] == (public.x, public.y)
        for q, result in zip([M,MASK_N], points[1:]):
            peer = ec.EllipticCurvePublicNumbers(*q,ec.SECP256R1()).public_key()
            assert private.exchange(ec.ECDH(), peer) == result[0].to_bytes(32,'big')
    else:
        assert all(q is None for q in points)
    point_rows.append('|'.join([f'{scalar:064x}']+[encoded(q) for q in points]))

p256_test = header + '''class P256MathTest {
    @Test fun independentPythonAndOpenSslScalarVectors() {
        for (line in vectors.lines()) {
            val v = line.split('|'); val k = mathHex(v[0])
            val outputs = listOf(P256.baseMultiply(k), P256.multiply(P256.m,k), P256.multiply(P256.n,k))
            for (i in outputs.indices) {
                if (v[i+1] == "-") assertTrue(P256.isIdentity(outputs[i]))
                else {
                    assertFalse(P256.isIdentity(outputs[i]))
                    assertContentEquals(mathHex(v[i+1]), P256.encode(outputs[i]), "scalar=${v[0]} point=$i")
                }
                outputs[i].destroy()
            }
        }
    }

    @Test fun exceptionalAdditionAndInputOwnership() {
        val one = ByteArray(32).also { it[31] = 1 }
        val two = ByteArray(32).also { it[31] = 2 }
        val g = P256.baseMultiply(one); val encoding = P256.encode(g)
        val identity = P256.identity
        assertContentEquals(encoding, P256.encode(P256.add(identity,g)))
        assertContentEquals(encoding, P256.encode(P256.add(g,identity)))
        assertTrue(P256.isIdentity(P256.add(identity,identity)))
        assertTrue(P256.isIdentity(P256.subtract(g,g)))
        assertContentEquals(P256.encode(P256.baseMultiply(two)), P256.encode(P256.add(g,g)))
        val result = P256.add(g,g); result.destroy()
        assertContentEquals(encoding, P256.encode(g))
        assertFailsWith<IllegalArgumentException> { P256.encode(identity) }
        assertFailsWith<IllegalArgumentException> { P256.baseMultiply(ByteArray(31)) }
        val m = P256.m; m.destroy()
        assertFalse(P256.isIdentity(P256.m))
        val order = P256.order; order.fill(0)
        assertFalse(P256.order.all { it == 0.toByte() })
    }

    @Test fun malformedAndNonCanonicalPointEncodings() {
        val g = P256.encode(P256.baseMultiply(ByteArray(32).also { it[31] = 1 }))
        for (size in listOf(0,1,32,33,64,66,130))
            assertFailsWith<IllegalArgumentException> { P256.decode(ByteArray(size)) }
        for (prefix in listOf(0,2,3,5,6,7))
            assertFailsWith<IllegalArgumentException> { P256.decode(g.copyOf().also { it[0] = prefix.toByte() }) }
        val modulus = mathHex("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff")
        assertFailsWith<IllegalArgumentException> { P256.decode(g.copyOf().also { modulus.copyInto(it,1) }) }
        assertFailsWith<IllegalArgumentException> { P256.decode(g.copyOf().also { modulus.copyInto(it,33) }) }
        assertFailsWith<IllegalArgumentException> { P256.decode(g.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }) }
        assertFailsWith<IllegalArgumentException> { P256.decode(ByteArray(65).also { it[0] = 4 }) }
        val input = g.copyOf(); val decoded = P256.decode(input); input.fill(0)
        assertContentEquals(g, P256.encode(decoded))
    }

    private val vectors = """
''' + '\n'.join(point_rows) + '''
""".trimIndent()
}
'''
(OUT / 'P256MathTest.kt').write_text(p256_test)
print(f'Generated {len(rows)} field cases and {len(point_rows)} P-256 scalar cases × 3 points; cryptography={cryptography_version}')
print(f'Python={platform.python_version()}; {openssl_backend.openssl_version_text()}')
print('Independent Python affine formulas agree with OpenSSL for all non-identity scalar cases.')
