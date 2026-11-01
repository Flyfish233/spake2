// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Flyfish233 and spake2 contributors
package io.github.flyfish233.spake2.internal.math

import io.github.flyfish233.spake2.InternalSpake2Api

/**
 * P-256 using Jacobian coordinates and a fixed 256-step scalar schedule.
 *
 * Addition/doubling implement the mathematical formulas add-2007-bl and
 * dbl-2001-b documented in the Explicit-Formulas Database, with masked
 * selection for equal points, inverse points, and the point at infinity:
 * https://www.hyperelliptic.org/EFD/g1p/auto-shortw-jacobian-3.html
 * No source code is copied from another implementation.
 *
 * This is an internal arithmetic API. [multiply] accepts every 256-bit integer
 * so order tests are possible; protocol scalar validation belongs to the
 * protocol layer. External messages use strict, uncompressed SEC1 encoding.
 */
@InternalSpake2Api
public object P256 {
    private val field = ModMath(mathHex("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff"))
    private val orderBytes = mathHex("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")
    private val b = field.fromBytes(mathHex("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b"))
    val order: ByteArray get() = orderBytes.copyOf()

    public class Point internal constructor(
        internal val x: IntArray,
        internal val y: IntArray,
        internal val z: IntArray,
    ) {
        /** Best-effort erasure of this owned coordinate representation. */
        fun destroy() { x.fill(0); y.fill(0); z.fill(0) }
    }

    private val basePoint = decode(mathHex(
        "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
    ))
    // RFC 9382 section 6 gives compressed M/N. These are their unique
    // uncompressed encodings, independently expanded by tools/generate_math_vectors.py.
    private val mPoint = decode(mathHex(
        "04886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f" +
            "5ff355163e43ce224e0b0e65ff02ac8e5c7be09419c785e0ca547d55a12e2d20"
    ))
    private val nPoint = decode(mathHex(
        "04d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49" +
            "07d60aa6bfade45008a636337f5168c64d9bd36034808cd564490b1e656edbe7"
    ))
    val m: Point get() = copyPoint(mPoint)
    val n: Point get() = copyPoint(nPoint)
    val identity: Point get() = Point(P256.field.one, P256.field.one, P256.field.zero)

    fun isIdentity(point: Point): Boolean = field.zeroMask(point.z) == -1

    fun decode(bytes: ByteArray): Point {
        require(bytes.size == 65 && bytes[0] == 4.toByte()) { "Expected uncompressed P-256 encoding" }
        val x = field.fromBytes(bytes.copyOfRange(1, 33))
        var y: IntArray? = null
        try {
            y = field.fromBytes(bytes.copyOfRange(33, 65))
            val lhs = field.square(y)
            val xSquared = field.square(x)
            val xCubed = field.multiply(xSquared, x)
            val twiceX = field.add(x, x)
            val thriceX = field.add(twiceX, x)
            val reduced = field.subtract(xCubed, thriceX)
            val rhs = field.add(reduced, b)
            val valid = field.equalMask(lhs, rhs)
            lhs.fill(0); xSquared.fill(0); xCubed.fill(0)
            twiceX.fill(0); thriceX.fill(0); reduced.fill(0); rhs.fill(0)
            require(valid == -1) { "Point is not on P-256" }
            return Point(x, y, field.one)
        } catch (failure: Throwable) {
            x.fill(0); y?.fill(0)
            throw failure
        }
    }

    fun encode(point: Point): ByteArray {
        require(!isIdentity(point)) { "The identity is not a valid wire point" }
        val inverseZ = field.inverse(point.z)
        val inverseZ2 = field.square(inverseZ)
        val inverseZ3 = field.multiply(inverseZ2, inverseZ)
        val x = field.multiply(point.x, inverseZ2)
        val y = field.multiply(point.y, inverseZ3)
        val xb = field.toBytes(x)
        val yb = field.toBytes(y)
        return try {
            ByteArray(65).also { out ->
                out[0] = 4
                xb.copyInto(out, 1)
                yb.copyInto(out, 33)
            }
        } finally {
            inverseZ.fill(0); inverseZ2.fill(0); inverseZ3.fill(0)
            x.fill(0); y.fill(0); xb.fill(0); yb.fill(0)
        }
    }

    fun baseMultiply(scalar: ByteArray): Point = multiply(basePoint, scalar)

    fun multiply(point: Point, scalar: ByteArray): Point {
        require(scalar.size == 32) { "Expected a 32-byte scalar" }
        var result = identity
        for (byte in scalar) {
            for (bit in 7 downTo 0) {
                val doubled = double(result)
                val added = add(doubled, point)
                val next = select(doubled, added, (byte.toInt() ushr bit) and 1)
                result.destroy(); doubled.destroy(); added.destroy()
                result = next
            }
        }
        return result
    }

    fun subtract(left: Point, right: Point): Point {
        val negated = Point(right.x.copyOf(), field.negate(right.y), right.z.copyOf())
        return try { add(left, negated) } finally { negated.destroy() }
    }

    fun add(left: Point, right: Point): Point {
        val z1z1 = field.square(left.z)
        val z2z2 = field.square(right.z)
        val u1 = field.multiply(left.x, z2z2)
        val u2 = field.multiply(right.x, z1z1)
        val z2cubed = field.multiply(right.z, z2z2)
        val z1cubed = field.multiply(left.z, z1z1)
        val s1 = field.multiply(left.y, z2cubed)
        val s2 = field.multiply(right.y, z1cubed)
        val h = field.subtract(u2, u1)
        val twoH = field.add(h, h)
        val i = field.square(twoH)
        val j = field.multiply(h, i)
        val sDifference = field.subtract(s2, s1)
        val r = field.add(sDifference, sDifference)
        val v = field.multiply(u1, i)
        val rSquared = field.square(r)
        val rSquaredMinusJ = field.subtract(rSquared, j)
        val twoV = field.add(v, v)
        val x = field.subtract(rSquaredMinusJ, twoV)
        val vMinusX = field.subtract(v, x)
        val rTimesDifference = field.multiply(r, vMinusX)
        val s1j = field.multiply(s1, j)
        val twoS1j = field.add(s1j, s1j)
        val y = field.subtract(rTimesDifference, twoS1j)
        val zSum = field.add(left.z, right.z)
        val zSumSquared = field.square(zSum)
        val zDifference1 = field.subtract(zSumSquared, z1z1)
        val zDifference2 = field.subtract(zDifference1, z2z2)
        val z = field.multiply(zDifference2, h)
        val candidate = Point(x, y, z)
        val doubled = double(left)
        val same = (field.zeroMask(h) and field.zeroMask(r)) and 1
        val selectedSame = select(candidate, doubled, same)
        val selectedLeftIdentity = select(selectedSame, right, field.zeroMask(left.z) and 1)
        val result = select(selectedLeftIdentity, left, field.zeroMask(right.z) and 1)
        candidate.destroy(); doubled.destroy(); selectedSame.destroy(); selectedLeftIdentity.destroy()
        z1z1.fill(0); z2z2.fill(0); u1.fill(0); u2.fill(0)
        z2cubed.fill(0); z1cubed.fill(0); s1.fill(0); s2.fill(0)
        h.fill(0); twoH.fill(0); i.fill(0); j.fill(0); sDifference.fill(0)
        r.fill(0); v.fill(0); rSquared.fill(0); rSquaredMinusJ.fill(0)
        twoV.fill(0); vMinusX.fill(0); rTimesDifference.fill(0); s1j.fill(0)
        twoS1j.fill(0); zSum.fill(0); zSumSquared.fill(0)
        zDifference1.fill(0); zDifference2.fill(0)
        return result
    }

    private fun double(point: Point): Point {
        val delta = field.square(point.z)
        val gamma = field.square(point.y)
        val beta = field.multiply(point.x, gamma)
        val xMinusDelta = field.subtract(point.x, delta)
        val xPlusDelta = field.add(point.x, delta)
        val product = field.multiply(xMinusDelta, xPlusDelta)
        val twiceProduct = field.add(product, product)
        val alpha = field.add(twiceProduct, product)
        val alphaSquared = field.square(alpha)
        val twiceBeta = field.add(beta, beta)
        val fourBeta = field.add(twiceBeta, twiceBeta)
        val eightBeta = field.add(fourBeta, fourBeta)
        val x = field.subtract(alphaSquared, eightBeta)
        val yPlusZ = field.add(point.y, point.z)
        val yPlusZSquared = field.square(yPlusZ)
        val zPart = field.subtract(yPlusZSquared, gamma)
        val z = field.subtract(zPart, delta)
        val difference = field.subtract(fourBeta, x)
        val alphaDifference = field.multiply(alpha, difference)
        val gammaSquared = field.square(gamma)
        val twiceGammaSquared = field.add(gammaSquared, gammaSquared)
        val fourGammaSquared = field.add(twiceGammaSquared, twiceGammaSquared)
        val eightGammaSquared = field.add(fourGammaSquared, fourGammaSquared)
        val y = field.subtract(alphaDifference, eightGammaSquared)
        delta.fill(0); gamma.fill(0); beta.fill(0); xMinusDelta.fill(0)
        xPlusDelta.fill(0); product.fill(0); twiceProduct.fill(0); alpha.fill(0)
        alphaSquared.fill(0); twiceBeta.fill(0); fourBeta.fill(0); eightBeta.fill(0)
        yPlusZ.fill(0); yPlusZSquared.fill(0); zPart.fill(0); difference.fill(0)
        alphaDifference.fill(0); gammaSquared.fill(0); twiceGammaSquared.fill(0)
        fourGammaSquared.fill(0); eightGammaSquared.fill(0)
        return Point(x, y, z)
    }

    private fun select(a: Point, b: Point, bit: Int): Point = Point(
        field.select(a.x, b.x, bit), field.select(a.y, b.y, bit), field.select(a.z, b.z, bit)
    )

    private fun copyPoint(point: Point): Point = Point(point.x.copyOf(), point.y.copyOf(), point.z.copyOf())
}
