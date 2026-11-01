// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2.internal.math

import kotlin.test.Test
import kotlin.test.assertContentEquals

/** Independent Python integer fixtures: (a*a*pow(2**256,-1,p)) % p. */
class ModMathIntCarryTest {
    private fun rawWords(hex: String): IntArray {
        val bytes = mathHex(hex)
        return IntArray(16) { i ->
            (bytes[31 - 2 * i].toInt() and 255) or
                ((bytes[30 - 2 * i].toInt() and 255) shl 8)
        }
    }

    @Test fun maximumUnsignedInnerSumAndCarryChains() {
        for (line in vectors.trim().lines()) {
            val row = line.split('|')
            val field = ModMath(mathHex(row[0]))
            val cases = listOf(
                // This exact input reaches 0xffffffff in the schoolbook sum.
                IntArray(16) { if (it < 3) 65535 else 0 },
                IntArray(16) { if (it < 15) 65535 else 0 },
                // Every listed modulus has a nonzero low word.
                rawWords(row[0]).also { it[0]-- },
            )
            for ((index, input) in cases.withIndex()) {
                val result = field.multiply(input, input)
                try {
                    assertContentEquals(rawWords(row[index + 1]), result,
                        "modulus=${row[0]} input=$index")
                } finally { input.fill(0); result.fill(0) }
            }
        }
    }

    // Each row: p | Montgomery square of (2^48-1) | (2^240-1) | (p-1).
    // Expected raw residues were generated independently with Python integers;
    // no ModMath fromBytes/toBytes result is used as an expected value.
    private val vectors = """
ffffffff00000001000000000000000000000000ffffffffffffffffffffffff|fffbfffe00040004fffbfffbfffe000300020002fff9fffcfffe000500020000|fffdffff00020003fffdfffd0000000200000001fffdfffe0000000300000000|fffffffe00000003fffffffd0000000200000001fffffffe0000000300000000
ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551|d6208b2c2ca555e713e16358cc88bc8b8f1e192d82c7115459277b16cb49c439|e8326633c1a3c1e98f5ab6041e6077259763127fcceddb52998810040dd6631b|60d066334905c1e907f8b6041e607725badef3e243566fafce1bc8f79c197c79
7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed|75e50d79435e50d79435e50d79435e50d79435e51435e50d794350d79435e4fc|4d7935e60d79435e50d79435e50d79435e50d79435e50d79435e50d79435e502|179435e50d79435e50d79435e50d79435e50d79435e50d79435e50d79435e50a
1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ed|06cee090e79191fe23acc75f4c4728e19480e76d7abd2cc55b8e6113cf9564c9|0a180c7026fe9183614e75438ffa36beb844db495cd4f3850b90824fbf434899|09db6c6f26fe9183614e75438ffa36beb7f5c66a973f754cc766cca43d5f0d24
""".trimIndent()
}
