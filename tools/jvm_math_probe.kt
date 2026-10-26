// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Flyfish233 and spake2 contributors
// Standalone DIFFERENTIAL TEST ONLY; never part of commonMain or published artifacts.
// Compile alongside ModMath.kt/P256.kt with kotlinc and run from repository root.
package io.github.flyfish233.spake2.internal.math

import java.io.File
import java.math.BigInteger
import java.security.MessageDigest

private fun BigInteger.encoded(): ByteArray {
    val bytes = toByteArray()
    return ByteArray(32).also { result ->
        val skip = (bytes.size - 32).coerceAtLeast(0)
        bytes.copyInto(result, 32 - (bytes.size-skip), skip)
    }
}

fun main() {
    val moduli = listOf(
        "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff",
        "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
        "7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed",
        "1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ed",
    )
    var cases = 0
    for (hex in moduli) {
        val p = BigInteger(hex, 16)
        val field = ModMath(mathHex(hex))
        for (i in 0 until 1000) {
            val a = when(i) {
                0 -> BigInteger.ZERO
                1 -> BigInteger.ONE
                2 -> p.subtract(BigInteger.ONE)
                3 -> p.subtract(BigInteger.TWO)
                else -> BigInteger(1,MessageDigest.getInstance("SHA-256").digest("a/$hex/$i".toByteArray())).mod(p)
            }
            val b = if (i < 4) a else BigInteger(1,MessageDigest.getInstance("SHA-256").digest("b/$hex/$i".toByteArray())).mod(p)
            val am = field.fromBytes(a.encoded()); val bm = field.fromBytes(b.encoded())
            fun verify(expected: BigInteger, result: IntArray, op: String) {
                val got = BigInteger(1, field.toBytes(result))
                check(expected == got) { "$hex case$i $op: expected=$expected got=$got" }
            }
            verify(a, am, "roundtrip")
            verify(a.add(b).mod(p), field.add(am,bm), "add")
            verify(a.subtract(b).mod(p), field.subtract(am,bm), "subtract")
            verify(a.multiply(b).mod(p), field.multiply(am,bm), "multiply")
            verify(a.multiply(a).mod(p), field.square(am), "square")
            if (i % 31 == 0 && a.signum() != 0) verify(a.modInverse(p),field.inverse(am),"inverse")
            val wide = MessageDigest.getInstance("SHA-512").digest("wide/$hex/$i".toByteArray())
            verify(BigInteger(1,wide).mod(p),field.reduce(wide),"reduce512")
            cases++
        }
    }
    println("PASS: $cases field cases against JVM BigInteger (4 primes; add/subtract/multiply/square/reduction, sampled inversion)")
    val vectorFile = File("src/commonTest/kotlin/io/github/flyfish233/spake2/internal/math/P256MathTest.kt").readText()
    val vectors = vectorFile.substringAfter("private val vectors = \"\"\"").substringBefore("\"\"\"").trim().lines()
    var points = 0
    for (line in vectors) {
        val v = line.split('|'); val scalar = mathHex(v[0])
        val results = listOf(P256.baseMultiply(scalar), P256.multiply(P256.m,scalar), P256.multiply(P256.n,scalar))
        for (i in results.indices) {
            val p = results[i]
            if (v[i+1] == "-") check(P256.isIdentity(p))
            else check(P256.encode(p).contentEquals(mathHex(v[i+1]))) { "P256 mismatch scalar=${v[0]} point$i" }
            p.destroy(); points++
        }
    }
    println("PASS: $points P-256 products against independently generated Python/OpenSSL fixtures")
}
