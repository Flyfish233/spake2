// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2.internal.math

import java.math.BigInteger
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** JVM-only independent arithmetic oracle. BigInteger and deterministic Random are TEST ONLY. */
class ArithmeticDifferentialTest {
    private val zero = BigInteger.ZERO
    private val one = BigInteger.ONE
    private val two = BigInteger.valueOf(2)
    private val p256 = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val n256 = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    private val pEd = one.shiftLeft(255) - BigInteger.valueOf(19)
    private val lEd = one.shiftLeft(252) + BigInteger("27742317777372353535851937790883648493")
    private val dEd = (BigInteger.valueOf(-121665) * BigInteger.valueOf(121666).modInverse(pEd)).mod(pEd)

    private fun bytes(n: BigInteger): ByteArray {
        require(n.signum() >= 0 && n.bitLength() <= 256)
        val variable = n.toByteArray()
        val start = if (variable.size == 33 && variable[0] == 0.toByte()) 1 else 0
        return ByteArray(32).also { variable.copyInto(it, 32 - (variable.size - start), start) }
    }

    private fun assertNumber(expected: BigInteger, field: ModMath, actual: IntArray, label: String) {
        try { assertContentEquals(bytes(expected), field.toBytes(actual), label) }
        finally { actual.fill(0) }
    }

    @Test fun allFourModuliAgainstBigInteger() {
        val random = Random(0x5350414b4532L)
        for (modulus in listOf(p256, n256, pEd, lEd)) {
            val field = ModMath(bytes(modulus))
            val boundaries = listOf(zero, one, modulus - one, modulus - two,
                BigInteger.valueOf(65535), BigInteger.valueOf(65536), BigInteger.valueOf(65537),
                one.shiftLeft(127), one.shiftLeft(128) - one, one.shiftLeft(128),
                one.shiftLeft(255), one.shiftLeft(256) - one)
            for (case in 0 until 64) {
                val a = (if (case < boundaries.size) boundaries[case] else BigInteger(512, random)).mod(modulus)
                val b = (if (case < boundaries.size) boundaries[boundaries.lastIndex - case] else BigInteger(512, random)).mod(modulus)
                val fa = field.fromBytes(bytes(a))
                val fb = field.fromBytes(bytes(b))
                val faCopy = fa.copyOf()
                val fbCopy = fb.copyOf()
                val label = "m=${modulus.toString(16)} case=$case"
                try {
                    assertContentEquals(bytes(a), field.toBytes(fa), "$label canonical round trip")
                    assertNumber((a + b).mod(modulus), field, field.add(fa, fb), "$label add")
                    assertNumber((a - b).mod(modulus), field, field.subtract(fa, fb), "$label subtract")
                    assertNumber((a * b).mod(modulus), field, field.multiply(fa, fb), "$label multiply")
                    assertNumber((a * a).mod(modulus), field, field.square(fa), "$label square")
                    assertNumber((-a).mod(modulus), field, field.negate(fa), "$label negate")
                    if (a.signum() != 0 && (case < 8 || case % 8 == 0)) {
                        assertNumber(a.modInverse(modulus), field, field.inverse(fa), "$label inverse")
                    }
                    assertEquals(if (a.signum() == 0) -1 else 0, field.zeroMask(fa))
                    assertEquals(if (a == b) -1 else 0, field.equalMask(fa, fb))
                    assertContentEquals(faCopy, fa, "$label source a ownership")
                    assertContentEquals(fbCopy, fb, "$label source b ownership")
                } finally { fa.fill(0); fb.fill(0); faCopy.fill(0); fbCopy.fill(0) }
            }
            for (length in listOf(0, 1, 31, 32, 33, 64, 65, 96, 128)) {
                val wide = ByteArray(length).also { random.nextBytes(it) }
                val expected = (if (wide.isEmpty()) zero else BigInteger(1, wide)).mod(modulus)
                assertNumber(expected, field, field.reduce(wide), "wide reduction length=$length")
            }
            assertFailsWith<IllegalArgumentException> { field.fromBytes(bytes(modulus)) }
            assertFailsWith<IllegalArgumentException> { field.fromBytes(ByteArray(31)) }
            assertFailsWith<IllegalArgumentException> { field.fromBytes(ByteArray(33)) }
            assertFailsWith<IllegalArgumentException> { field.inverse(field.zero) }
        }
    }

    private data class Affine(val x: BigInteger, val y: BigInteger)

    /** Independent affine short-Weierstrass formulas; production uses Montgomery Jacobian. */
    private fun pAdd(left: Affine?, right: Affine?): Affine? {
        if (left == null) return right
        if (right == null) return left
        if (left.x == right.x && (left.y + right.y).mod(p256) == zero) return null
        val slope = if (left == right) {
            (BigInteger.valueOf(3) * left.x * left.x - BigInteger.valueOf(3)) * (two * left.y).mod(p256).modInverse(p256)
        } else (right.y - left.y) * (right.x - left.x).mod(p256).modInverse(p256)
        val x = (slope * slope - left.x - right.x).mod(p256)
        return Affine(x, (slope * (left.x - x) - left.y).mod(p256))
    }

    private fun pMultiply(point: Affine, scalar: BigInteger): Affine? {
        var result: Affine? = null
        var current: Affine? = point
        for (bit in 0 until scalar.bitLength()) {
            if (scalar.testBit(bit)) result = pAdd(result, current)
            current = pAdd(current, current)
        }
        return result
    }

    private fun pEncode(point: Affine): ByteArray = byteArrayOf(4) + bytes(point.x) + bytes(point.y)

    @Test fun p256FullWidthScalarAndExceptionalCasesAgainstAffineOracle() {
        val g = Affine(BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16),
            BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16))
        val scalars = listOf(zero, one, two, n256 - one, n256, n256 + one,
            one.shiftLeft(255), one.shiftLeft(255) + one, one.shiftLeft(256) - one)
        for (scalar in scalars) {
            val expected = pMultiply(g, scalar)
            val actual = P256.baseMultiply(bytes(scalar))
            try {
                if (expected == null) assertTrue(P256.isIdentity(actual), "P256 scalar=$scalar")
                else assertContentEquals(pEncode(expected), P256.encode(actual), "P256 scalar=$scalar")
            } finally { actual.destroy() }
        }
        val positive = P256.decode(pEncode(g))
        val negative = P256.decode(pEncode(Affine(g.x, (-g.y).mod(p256))))
        val sum = P256.add(positive, negative)
        val doubled = P256.add(positive, positive)
        try {
            assertTrue(P256.isIdentity(sum))
            assertContentEquals(pEncode(pAdd(g, g)!!), P256.encode(doubled))
            assertFailsWith<IllegalArgumentException> { P256.encode(sum) }
        } finally { positive.destroy(); negative.destroy(); sum.destroy(); doubled.destroy() }
    }

    /** Independent affine Edwards formulas preserve all torsion, unlike a subgroup-only oracle. */
    private fun eAdd(left: Affine, right: Affine): Affine {
        val cross = (dEd * left.x * right.x * left.y * right.y).mod(pEd)
        return Affine(
            ((left.x * right.y + left.y * right.x) * (one + cross).mod(pEd).modInverse(pEd)).mod(pEd),
            ((left.y * right.y + left.x * right.x) * (one - cross).mod(pEd).modInverse(pEd)).mod(pEd),
        )
    }

    private fun eMultiply(point: Affine, scalar: BigInteger): Affine {
        var result = Affine(zero, one)
        var current = point
        for (bit in 0 until scalar.bitLength()) {
            if (scalar.testBit(bit)) result = eAdd(result, current)
            current = eAdd(current, current)
        }
        return result
    }

    private fun eEncode(point: Affine): ByteArray = bytes(point.y).reversedArray().also {
        it[31] = (it[31].toInt() or (if (point.x.testBit(0)) 128 else 0)).toByte()
    }

    @Test fun edwardsHighestBitAndMixedOrderScalarsAgainstAffineOracle() {
        val g = Affine(BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
            BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"))
        // G + the order-two point (0,-1), so reducing the scalar mod L would be incorrect.
        val mixed = Affine((-g.x).mod(pEd), (-g.y).mod(pEd))
        val scalars = listOf(zero, one, lEd, lEd + one, one.shiftLeft(255),
            one.shiftLeft(255) + one, one.shiftLeft(256) - one)
        for (referencePoint in listOf(g, mixed)) {
            val point = Edwards25519.decodeStrict(eEncode(referencePoint))
            try {
                for (scalar in scalars) {
                    val expected = eMultiply(referencePoint, scalar)
                    val actual = Edwards25519.multiply(point, bytes(scalar).reversedArray())
                    try { assertContentEquals(eEncode(expected), Edwards25519.encode(actual), "Edwards scalar=$scalar mixed=${referencePoint == mixed}") }
                    finally { actual.destroy() }
                }
                if (referencePoint == mixed) {
                    assertFalse(Edwards25519.isSmallOrder(point))
                    assertFalse(Edwards25519.isPrimeOrder(point))
                }
            } finally { point.destroy() }
        }
    }

    @Test fun edwardsCanonicalAndOffCurveEncodingsAgainstIntegerOracle() {
        for (offset in 0..18) {
            for (sign in 0..1) {
                val encoded = bytes(pEd + BigInteger.valueOf(offset.toLong())).reversedArray()
                encoded[31] = (encoded[31].toInt() or (sign shl 7)).toByte()
                assertFailsWith<IllegalArgumentException>("Non-canonical y=p+$offset sign=$sign") {
                    Edwards25519.decodeStrict(encoded)
                }
            }
        }
        val sqrtMinusOne = two.modPow((pEd - one).divide(BigInteger.valueOf(4)), pEd)
        val exponent = (pEd + BigInteger.valueOf(3)).divide(BigInteger.valueOf(8))
        for (value in 0..63) {
            val y = BigInteger.valueOf(value.toLong())
            val xSquared = ((y * y - one) * (dEd * y * y + one).mod(pEd).modInverse(pEd)).mod(pEd)
            var x = xSquared.modPow(exponent, pEd)
            if ((x * x).mod(pEd) != xSquared) x = (x * sqrtMinusOne).mod(pEd)
            for (sign in 0..1) {
                val encoded = bytes(y).reversedArray()
                encoded[31] = (encoded[31].toInt() or (sign shl 7)).toByte()
                if ((x * x).mod(pEd) != xSquared || (x == zero && sign == 1)) {
                    assertFailsWith<IllegalArgumentException>("Invalid y=$value sign=$sign") {
                        Edwards25519.decodeStrict(encoded)
                    }
                } else {
                    val point = Edwards25519.decodeStrict(encoded)
                    try { assertContentEquals(encoded, Edwards25519.encode(point), "Valid y=$value sign=$sign") }
                    finally { point.destroy() }
                }
            }
        }
    }
}
