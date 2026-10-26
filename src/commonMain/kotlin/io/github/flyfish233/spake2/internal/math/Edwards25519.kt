// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Flyfish233 and spake2 contributors
package io.github.flyfish233.spake2.internal.math

/**
 * Edwards25519 using complete extended-coordinate formulas (a = -1).
 *
 * Original implementation of the curve equations and the complete addition
 * and doubling formulas documented in the Explicit-Formulas Database,
 * twisted/extended-1, add-2008-hwcd and dbl-2008-hwcd.
 * Scalar multiplication scans all 256 bits using a four-bit window and a full
 * masked table scan. Runtime/compiler constant-time behavior is not guaranteed.
 */
internal object Edwards25519 {
    private val field = ModMath(mathHex("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed"))
    private val orderBytes = mathHex("1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ed")
    private val scalars = ModMath(orderBytes)
    val orderBE: ByteArray get() = orderBytes.copyOf()
    val orderLE: ByteArray get() = orderBytes.reversedArray()
    private val d = field.fromBytes(mathHex("52036cee2b6ffe738cc740797779e89800700a4d4141d8ab75eb4dca135978a3"))
    private val twoD = field.add(d, d)
    private val sqrtMinusOne = field.fromBytes(mathHex("2b8324804fc1df0b2b4d00993dfbd7a72f431806ad2fe478c4ee1b274a0ea0b0"))
    private val sqrtExponent = mathHex("0ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe")
    private val basePoint: Point by lazy { decodeStrict(mathHex("5866666666666666666666666666666666666666666666666666666666666666")) }

    internal class Point internal constructor(
        internal val x: IntArray,
        internal val y: IntArray,
        internal val z: IntArray,
        internal val t: IntArray
    ) {
        internal fun copy(): Point = Point(x.copyOf(), y.copyOf(), z.copyOf(), t.copyOf())
        fun destroy() { x.fill(0); y.fill(0); z.fill(0); t.fill(0) }
    }

    fun identity(): Point = Point(field.zero, field.one, field.one, field.zero)

    /** RFC 8032 canonical compressed encoding, including the x=0 sign rule. */
    fun decodeStrict(encoded: ByteArray): Point = decode(encoded, true)

    /** Pinned BoringSSL accepts y >= q and sign=1 for x=0. Legacy only. */
    fun decodeLegacy(encoded: ByteArray): Point = decode(encoded, false)

    private fun decode(encoded: ByteArray, strict: Boolean): Point {
        require(encoded.size == 32) { "An Edwards25519 point is 32 bytes" }
        val yBytes = encoded.reversedArray()
        val sign = (yBytes[0].toInt() ushr 7) and 1
        yBytes[0] = (yBytes[0].toInt() and 127).toByte()
        val y = try { if (strict) field.fromBytes(yBytes) else field.reduce(yBytes) } finally { yBytes.fill(0) }
        val temporaries = mutableListOf<IntArray>()
        fun temp(value: IntArray): IntArray { temporaries.add(value); return value }
        var success = false
        try {
            val y2 = temp(field.square(y))
            val one = temp(field.one)
            val numerator = temp(field.subtract(y2, one))
            val denominator = temp(field.add(temp(field.multiply(d, y2)), one))
            require(field.zeroMask(denominator) != -1) { "Invalid Edwards25519 point" }
            val x2 = temp(field.multiply(numerator, temp(field.inverse(denominator))))
            val first = temp(field.pow(x2, sqrtExponent))
            val alternative = temp(field.multiply(first, sqrtMinusOne))
            val firstMatches = field.equalMask(temp(field.square(first)), x2)
            val root = temp(field.select(alternative, first, firstMatches and 1))
            require(field.equalMask(temp(field.square(root)), x2) == -1) { "Invalid Edwards25519 point" }
            require(!strict || field.zeroMask(root) != -1 || sign == 0) { "Non-canonical Edwards25519 sign" }
            val rootBytes = field.toBytes(root)
            val parity = rootBytes[31].toInt() and 1
            rootBytes.fill(0)
            val x = field.select(root, temp(field.negate(root)), parity xor sign)
            val point = Point(x, y, field.one, field.multiply(x, y))
            success = true
            return point
        } finally {
            temporaries.forEach { it.fill(0) }
            if (!success) y.fill(0)
        }
    }

    fun encode(point: Point): ByteArray {
        val inverseZ = field.inverse(point.z)
        val x = field.multiply(point.x, inverseZ)
        val y = field.multiply(point.y, inverseZ)
        val xBytes = field.toBytes(x)
        val yBytes = field.toBytes(y)
        return try {
            yBytes.reverse()
            yBytes[31] = (yBytes[31].toInt() or ((xBytes[31].toInt() and 1) shl 7)).toByte()
            yBytes
        } finally { inverseZ.fill(0); x.fill(0); y.fill(0); xBytes.fill(0) }
    }

    fun add(left: Point, right: Point): Point {
        val work = mutableListOf<IntArray>()
        fun v(a: IntArray): IntArray { work.add(a); return a }
        try {
            val a = v(field.multiply(v(field.subtract(left.y, left.x)), v(field.subtract(right.y, right.x))))
            val b = v(field.multiply(v(field.add(left.y, left.x)), v(field.add(right.y, right.x))))
            val c = v(field.multiply(v(field.multiply(left.t, right.t)), twoD))
            val zz = v(field.multiply(left.z, right.z))
            val dd = v(field.add(zz, zz))
            val e = v(field.subtract(b, a))
            val f = v(field.subtract(dd, c))
            val g = v(field.add(dd, c))
            val h = v(field.add(b, a))
            return Point(field.multiply(e, f), field.multiply(g, h), field.multiply(f, g), field.multiply(e, h))
        } finally { work.forEach { it.fill(0) } }
    }

    fun double(point: Point): Point {
        val work = mutableListOf<IntArray>()
        fun v(a: IntArray): IntArray { work.add(a); return a }
        try {
            val a = v(field.square(point.x))
            val b = v(field.square(point.y))
            val z2 = v(field.square(point.z))
            val c = v(field.add(z2, z2))
            val dd = v(field.negate(a))
            val e = v(field.subtract(v(field.subtract(v(field.square(v(field.add(point.x, point.y)))), a)), b))
            val g = v(field.add(dd, b))
            val f = v(field.subtract(g, c))
            val h = v(field.subtract(dd, b))
            return Point(field.multiply(e, f), field.multiply(g, h), field.multiply(f, g), field.multiply(e, h))
        } finally { work.forEach { it.fill(0) } }
    }

    fun negate(point: Point): Point = Point(field.negate(point.x), point.y.copyOf(), point.z.copyOf(), field.negate(point.t))
    fun subtract(left: Point, right: Point): Point {
        val negative = negate(right)
        return try { add(left, negative) } finally { negative.destroy() }
    }

    private fun select(left: Point, right: Point, bit: Int): Point = Point(
        field.select(left.x, right.x, bit), field.select(left.y, right.y, bit),
        field.select(left.z, right.z, bit), field.select(left.t, right.t, bit)
    )

    /** Raw unsigned 256-bit little-endian scalar: bit 255 is always preserved. */
    fun multiply(point: Point, scalarLE: ByteArray): Point {
        require(scalarLE.size == 32) { "An Edwards25519 scalar is 32 bytes" }
        val table = ArrayList<Point>(16)
        table.add(identity())
        table.add(point.copy())
        var result = identity()
        try {
            for (i in 2..15) table.add(add(table[i - 1], point))
            for (window in 63 downTo 0) {
                repeat(4) {
                    val next = double(result)
                    result.destroy()
                    result = next
                }
                val nibble = (scalarLE[window / 2].toInt() ushr ((window and 1) * 4)) and 15
                var selected = identity()
                for (i in 1..15) {
                    val unequal = nibble xor i
                    val equalBit = ((unequal or -unequal) ushr 31) xor 1
                    val next = select(selected, table[i], equalBit)
                    selected.destroy()
                    selected = next
                }
                val next = add(result, selected)
                selected.destroy()
                result.destroy()
                result = next
            }
            return result
        } catch (failure: Throwable) {
            result.destroy()
            throw failure
        } finally { table.forEach { it.destroy() } }
    }

    fun baseMultiply(scalarLE: ByteArray): Point = multiply(basePoint, scalarLE)
    fun multiplyCofactor(point: Point): Point {
        var result = point.copy()
        repeat(3) { val next = double(result); result.destroy(); result = next }
        return result
    }
    fun isIdentity(point: Point): Boolean = (field.zeroMask(point.x) and field.equalMask(point.y, point.z)) == -1
    fun isSmallOrder(point: Point): Boolean {
        val multiplied = multiplyCofactor(point)
        return try { isIdentity(multiplied) } finally { multiplied.destroy() }
    }
    fun isPrimeOrder(point: Point): Boolean {
        val order = orderLE
        val multiplied = multiply(point, order)
        return try { isIdentity(multiplied) } finally { order.fill(0); multiplied.destroy() }
    }

    fun reduceScalarLE(input: ByteArray): ByteArray {
        val reversed = input.reversedArray()
        val reduced = try { scalars.reduce(reversed) } finally { reversed.fill(0) }
        return try { scalars.toBytes(reduced).also { it.reverse() } } finally { reduced.fill(0) }
    }
}
