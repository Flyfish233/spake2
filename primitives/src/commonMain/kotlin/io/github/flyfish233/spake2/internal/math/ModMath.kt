// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Flyfish233 and spake2 contributors
package io.github.flyfish233.spake2.internal.math

import io.github.flyfish233.spake2.InternalSpake2Api

/**
 * Fixed-width, 256-bit arithmetic for an odd prime modulus.
 *
 * An element is sixteen little-endian base-2^16 limbs, in Montgomery form.
 * Every arithmetic loop has a public bound. Selection uses masks rather than
 * secret-indexed tables. This is a source-level property, not a guarantee about
 * machine code produced by every Kotlin runtime.
 *
 * This original implementation uses the standard Montgomery REDC algorithm;
 * it does not incorporate code from a cryptographic backend.
 */
@InternalSpake2Api
public class ModMath(modulusBe: ByteArray) {
    private val modulus: IntArray
    private val inverseExponent: ByteArray
    private val n0Prime: Int
    private val rSquared: IntArray
    private val montgomeryOne: IntArray

    val zero: IntArray get() = IntArray(LIMBS)
    val one: IntArray get() = montgomeryOne.copyOf()

    init {
        require(modulusBe.size == BYTES) { "A 256-bit modulus is required" }
        require((modulusBe.last().toInt() and 1) == 1) { "The modulus must be odd" }
        modulus = readRaw(modulusBe)
        require(modulus.drop(1).any { it != 0 }) { "The modulus is too small" }

        // Newton iteration for -modulus[0]^-1 modulo 2^16. Values are public.
        var inverse = 1L
        repeat(4) { inverse = (inverse * (2L - modulus[0] * inverse)) and MASK }
        n0Prime = ((-inverse) and MASK).toInt()
        var r = IntArray(LIMBS).also { it[0] = 1 }
        repeat(256) { val next = add(r, r); r.fill(0); r = next }
        montgomeryOne = r.copyOf()
        repeat(256) { val next = add(r, r); r.fill(0); r = next }
        rSquared = r

        inverseExponent = modulusBe.copyOf()
        var borrow = 2
        for (i in inverseExponent.lastIndex downTo 0) {
            val value = (inverseExponent[i].toInt() and 255) - borrow
            inverseExponent[i] = value.toByte()
            borrow = (value shr 31) and 1
        }
    }

    /** Decode a canonical, 32-byte, big-endian representative. */
    fun fromBytes(bytes: ByteArray): IntArray {
        require(bytes.size == BYTES) { "Expected a 32-byte field element" }
        val raw = readRaw(bytes)
        try {
            require(lessThanModulus(raw) == 1) { "Non-canonical field element" }
            return multiply(raw, rSquared)
        } finally {
            raw.fill(0)
        }
    }

    fun fromInt(value: Int): IntArray {
        require(value >= 0) { "Expected an unsigned integer" }
        val raw = IntArray(LIMBS)
        raw[0] = value and 65535
        raw[1] = value ushr 16
        return try { multiply(raw, rSquared) } finally { raw.fill(0) }
    }

    /** Encode a Montgomery element as its canonical big-endian representative. */
    fun toBytes(a: IntArray): ByteArray {
        val rawOne = IntArray(LIMBS).also { it[0] = 1 }
        val raw = multiply(a, rawOne)
        return try {
            ByteArray(BYTES).also { out ->
                for (i in 0 until LIMBS) {
                    out[BYTES - 1 - i * 2] = raw[i].toByte()
                    out[BYTES - 2 - i * 2] = (raw[i] ushr 8).toByte()
                }
            }
        } finally {
            raw.fill(0)
            rawOne.fill(0)
        }
    }

    /** Reduce a big-endian integer. Runtime depends on the public input length. */
    fun reduce(bytes: ByteArray): IntArray {
        var value = zero
        for (byte in bytes) {
            for (bit in 7 downTo 0) {
                val doubled = add(value, value)
                val incremented = add(doubled, montgomeryOne)
                val next = select(doubled, incremented, (byte.toInt() ushr bit) and 1)
                value.fill(0)
                doubled.fill(0)
                incremented.fill(0)
                value = next
            }
        }
        return value
    }

    fun add(a: IntArray, b: IntArray): IntArray {
        val sum = IntArray(LIMBS)
        val difference = IntArray(LIMBS)
        var carry = 0
        for (i in 0 until LIMBS) {
            val value = a[i] + b[i] + carry
            sum[i] = value and 65535
            carry = value ushr 16
        }
        var borrow = 0
        for (i in 0 until LIMBS) {
            val value = sum[i] - modulus[i] - borrow
            difference[i] = value and 65535
            borrow = (value shr 31) and 1
        }
        borrow = ((carry - borrow) shr 31) and 1
        val mask = borrow - 1
        for (i in 0 until LIMBS) sum[i] = (difference[i] and mask) or (sum[i] and mask.inv())
        difference.fill(0)
        return sum
    }

    fun subtract(a: IntArray, b: IntArray): IntArray {
        val difference = IntArray(LIMBS)
        var borrow = 0
        for (i in 0 until LIMBS) {
            val value = a[i] - b[i] - borrow
            difference[i] = value and 65535
            borrow = (value shr 31) and 1
        }
        val mask = -borrow
        var carry = 0
        for (i in 0 until LIMBS) {
            val value = difference[i] + (modulus[i] and mask) + carry
            difference[i] = value and 65535
            carry = value ushr 16
        }
        return difference
    }

    fun negate(a: IntArray): IntArray = subtract(zero, a)
    fun square(a: IntArray): IntArray = multiply(a, a)

    /**
     * Montgomery product, with an explicit extra limb for carry propagation.
     *
     * Every stored limb and inner carry is in 0..65535. Thus each inner sum
     * is at most 65535^2 + 65535 + 65535 = 2^32 - 1. Int overflow preserves
     * its low 32 bits; `and 65535` and `ushr 16` recover the exact limb/carry.
     * Keep this secret-dependent path in Int arithmetic: Kotlin/JS Long
     * multiplication contains value-dependent branches in its runtime.
     */
    fun multiply(a: IntArray, b: IntArray): IntArray {
        val product = IntArray(2 * LIMBS + 1)
        for (i in 0 until LIMBS) {
            var carry = 0
            for (j in 0 until LIMBS) {
                val value = product[i + j] + a[i] * b[j] + carry
                product[i + j] = value and 65535
                carry = value ushr 16
            }
            product[i + LIMBS] = carry
        }
        for (i in 0 until LIMBS) {
            val factor = (product[i] * n0Prime) and 65535
            var carry = 0
            for (j in 0 until LIMBS) {
                val value = product[i + j] + factor * modulus[j] + carry
                product[i + j] = value and 65535
                carry = value ushr 16
            }
            for (j in i + LIMBS..2 * LIMBS) {
                val value = product[j] + carry
                product[j] = value and 65535
                carry = value ushr 16
            }
        }
        val out = IntArray(LIMBS)
        var borrow = 0
        for (i in 0 until LIMBS) {
            val value = product[i + LIMBS] - modulus[i] - borrow
            out[i] = value and 65535
            borrow = (value shr 31) and 1
        }
        borrow = ((product[2 * LIMBS] - borrow) shr 31) and 1
        val mask = borrow - 1
        for (i in 0 until LIMBS) out[i] = (out[i] and mask) or (product[i + LIMBS] and mask.inv())
        product.fill(0)
        return out
    }

    /** Fixed schedule for each byte of the supplied exponent. */
    fun pow(a: IntArray, exponentBe: ByteArray): IntArray {
        var result = one
        for (byte in exponentBe) {
            for (bit in 7 downTo 0) {
                val squared = square(result)
                val multiplied = multiply(squared, a)
                val next = select(squared, multiplied, (byte.toInt() ushr bit) and 1)
                result.fill(0)
                squared.fill(0)
                multiplied.fill(0)
                result = next
            }
        }
        return result
    }

    fun inverse(a: IntArray): IntArray {
        require(zeroMask(a) != -1) { "Zero has no multiplicative inverse" }
        return pow(a, inverseExponent)
    }

    /** Returns a copy of [b] when bit=1 and [a] when bit=0. */
    fun select(a: IntArray, b: IntArray, bit: Int): IntArray {
        val mask = -(bit and 1)
        return IntArray(LIMBS) { i -> a[i] xor ((a[i] xor b[i]) and mask) }
    }

    /** -1 for zero, 0 otherwise; no early exit. */
    fun zeroMask(a: IntArray): Int {
        var combined = 0
        for (i in 0 until LIMBS) combined = combined or a[i]
        return ((combined or -combined) ushr 31) - 1
    }

    fun equalMask(a: IntArray, b: IntArray): Int {
        var combined = 0
        for (i in 0 until LIMBS) combined = combined or (a[i] xor b[i])
        return ((combined or -combined) ushr 31) - 1
    }

    private fun lessThanModulus(raw: IntArray): Int {
        var borrow = 0
        for (i in 0 until LIMBS) borrow = ((raw[i] - modulus[i] - borrow) shr 31) and 1
        return borrow
    }

    private fun readRaw(bytes: ByteArray): IntArray = IntArray(LIMBS) { i ->
        (bytes[BYTES - 1 - i * 2].toInt() and 255) or
            ((bytes[BYTES - 2 - i * 2].toInt() and 255) shl 8)
    }

    private companion object {
        const val LIMBS = 16
        const val BYTES = 32
        const val MASK = 65535L
    }
}

/** Only for fixed public constants in this package. */
@InternalSpake2Api
public fun mathHex(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
