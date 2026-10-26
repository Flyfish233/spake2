// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Flyfish233 and spake2 contributors
package io.github.flyfish233.spake2.internal.math

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Edwards25519MathTest {
    @Test fun rfc8032BasePointAndPublicKey() {
        val one = ByteArray(32).also { it[0] = 1 }
        val base = Edwards25519.baseMultiply(one)
        assertContentEquals(mathHex("5866666666666666666666666666666666666666666666666666666666666666"), Edwards25519.encode(base))
        // RFC 8032 section 7.1 test 1: SHA-512(seed), pruned first 32 bytes.
        val pruned = mathHex("307c83864f2833cb427a2ef1c00a013cfdff2768d980c0a3a520f006904de94f")
        val publicKey = Edwards25519.baseMultiply(pruned)
        assertContentEquals(mathHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"), Edwards25519.encode(publicKey))
        base.destroy(); publicKey.destroy()
    }

    @Test fun fullWidthHighBitIsPreserved() {
        val scalar = ByteArray(32).also { it[31] = 0x80.toByte() }
        val point = Edwards25519.baseMultiply(scalar)
        // Pinned BoringSSL oracle high-bit-carry-2^252 ephemeral = 2^255 * B.
        assertContentEquals(mathHex("6e4b94a41ca8bd8c8e68ae0970e953eea2824beb01692cfd821625e07f828b41"), Edwards25519.encode(point))
        point.destroy()
    }

    @Test fun strictAndHistoricalPointDecodersAreSeparated() {
        val nonCanonicalIdentity = mathHex("eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f")
        assertFailsWith<IllegalArgumentException> { Edwards25519.decodeStrict(nonCanonicalIdentity) }
        val legacy = Edwards25519.decodeLegacy(nonCanonicalIdentity)
        assertTrue(Edwards25519.isIdentity(legacy))
        legacy.destroy()
        val negativeZero = ByteArray(32).also { it[0] = 1; it[31] = 0x80.toByte() }
        assertFailsWith<IllegalArgumentException> { Edwards25519.decodeStrict(negativeZero) }
        val old = Edwards25519.decodeLegacy(negativeZero)
        assertTrue(Edwards25519.isIdentity(old))
        old.destroy()
        assertFailsWith<IllegalArgumentException> { Edwards25519.decodeStrict(ByteArray(32) { 0xff.toByte() }) }
        val accepted = Edwards25519.decodeLegacy(ByteArray(32) { 0xff.toByte() })
        accepted.destroy()
        for (length in listOf(0, 1, 31, 33, 64)) {
            assertFailsWith<IllegalArgumentException> { Edwards25519.decodeStrict(ByteArray(length)) }
        }
    }

    @Test fun torsionAndPrimeOrderChecks() {
        val identity = Edwards25519.identity()
        val orderTwo = Edwards25519.decodeStrict(mathHex("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"))
        val orderFour = Edwards25519.decodeStrict(ByteArray(32))
        assertTrue(Edwards25519.isSmallOrder(identity))
        assertTrue(Edwards25519.isSmallOrder(orderTwo))
        assertTrue(Edwards25519.isSmallOrder(orderFour))
        assertFalse(Edwards25519.isPrimeOrder(orderTwo))
        assertFalse(Edwards25519.isPrimeOrder(orderFour))
        val base = Edwards25519.baseMultiply(ByteArray(32).also { it[0] = 1 })
        assertTrue(Edwards25519.isPrimeOrder(base))
        assertFalse(Edwards25519.isSmallOrder(base))
        val mixed = Edwards25519.add(base, orderTwo)
        assertFalse(Edwards25519.isSmallOrder(mixed))
        assertFalse(Edwards25519.isPrimeOrder(mixed))
        val zero = Edwards25519.baseMultiply(ByteArray(32))
        assertTrue(Edwards25519.isIdentity(zero))
        val byOrder = Edwards25519.baseMultiply(Edwards25519.orderLE)
        assertTrue(Edwards25519.isIdentity(byOrder))
        listOf(identity, orderTwo, orderFour, base, mixed, zero, byOrder).forEach { it.destroy() }
    }

    @Test fun scalarReductionBoundaries() {
        assertContentEquals(ByteArray(32), Edwards25519.reduceScalarLE(Edwards25519.orderLE))
        val orderPlusOne = Edwards25519.orderLE.also { it[0] = (it[0] + 1).toByte() }
        assertContentEquals(ByteArray(32).also { it[0] = 1 }, Edwards25519.reduceScalarLE(orderPlusOne))
        // Python integer reference: (2^512 - 1) mod L, independently generated.
        assertContentEquals(mathHex("000f9c44e31106a447938568a71b0ed065bef517d273ecce3d9a307c1b419903"),
            Edwards25519.reduceScalarLE(ByteArray(64) { 0xff.toByte() }))
    }
}
