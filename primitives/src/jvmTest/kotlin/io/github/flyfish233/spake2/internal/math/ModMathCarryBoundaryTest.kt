// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2.internal.math

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** Direct raw-Montgomery oracle; it does not use production conversions. */
class ModMathCarryBoundaryTest {
    private val one = BigInteger.ONE
    private val r = one.shiftLeft(256)
    private val limbMask = BigInteger.valueOf(65535)

    private fun rawWords(value: BigInteger): IntArray =
        IntArray(16) { value.shiftRight(16 * it).and(limbMask).toInt() }

    @Test fun unsignedAccumulatorsAndExtraWordAgainstBigInteger() {
        val moduli = listOf(
            "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff",
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
            "7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed",
            "1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ed",
        )
        val fullThreeWords = one.shiftLeft(48) - one
        val fullFifteenWords = one.shiftLeft(240) - one
        var sawFinalSubtraction = false
        var sawExtraWord = false
        for (hex in moduli) {
            val modulus = BigInteger(hex, 16)
            val inverseR = r.modInverse(modulus)
            val negativeInverseModulus = (-modulus.modInverse(r)).mod(r)
            val field = ModMath(mathHex(hex))
            val cases = listOf(
                BigInteger.ZERO to BigInteger.ZERO,
                one to one,
                BigInteger.valueOf(32768) to limbMask,
                BigInteger.valueOf(32769) to limbMask,
                limbMask to limbMask,
                // B^3-1 squared reaches an inner sum of exactly 0xffffffff.
                fullThreeWords to fullThreeWords,
                fullFifteenWords to fullFifteenWords,
                modulus - one to modulus - one,
                modulus - BigInteger.TWO to modulus - one,
                modulus.shiftRight(1) to modulus - one,
            )
            for ((index, pair) in cases.withIndex()) {
                val (a, b) = pair
                val product = a * b
                val expected = (product * inverseR).mod(modulus)
                // A whole-integer REDC formula checks that the selected inputs
                // cover both final subtraction and the 257th intermediate bit.
                val adjustment = (product * negativeInverseModulus).mod(r)
                val unreduced = (product + adjustment * modulus).shiftRight(256)
                sawFinalSubtraction = sawFinalSubtraction || unreduced >= modulus
                sawExtraWord = sawExtraWord || unreduced >= r
                val rawA = rawWords(a)
                val rawB = rawWords(b)
                val actual = field.multiply(rawA, rawB)
                try {
                    assertTrue(actual.all { it in 0..65535 }, "$hex case $index normalized limbs")
                    assertContentEquals(rawWords(expected), actual, "$hex case $index raw product")
                    assertContentEquals(rawWords(a), rawA, "$hex case $index input a")
                    assertContentEquals(rawWords(b), rawB, "$hex case $index input b")
                } finally { rawA.fill(0); rawB.fill(0); actual.fill(0) }
            }
        }
        assertTrue(sawFinalSubtraction, "The corpus must exercise final reduction")
        assertTrue(sawExtraWord, "The corpus must exercise the explicit extra word")
    }
}
