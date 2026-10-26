// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2.rfc

import io.github.flyfish233.spake2.Role
import io.github.flyfish233.spake2.internal.crypto.HkdfSha256
import io.github.flyfish233.spake2.internal.crypto.HmacSha256
import io.github.flyfish233.spake2.internal.crypto.Sha256
import io.github.flyfish233.spake2.internal.math.Edwards25519
import io.github.flyfish233.spake2.internal.math.P256

/** Internal inspection surface for independent vectors; never part of public API. */
internal class TranscriptSecrets(
    val tt: ByteArray, val hashTt: ByteArray, val ke: ByteArray, val ka: ByteArray,
    val kcA: ByteArray, val kcB: ByteArray, val cA: ByteArray, val cB: ByteArray,
) {
    fun destroy() {
        for (bytes in arrayOf(tt, hashTt, ke, ka, kcA, kcB, cA, cB)) bytes.fill(0)
    }
}

internal object Rfc9382Core {
    fun isCanonicalScalar(bytes: ByteArray, order: ByteArray): Boolean {
        if (bytes.size != 32 || order.size != 32) return false
        var borrow = 0
        for (i in 31 downTo 0) {
            val difference = (bytes[i].toInt() and 255) - (order[i].toInt() and 255) - borrow
            borrow = (difference shr 31) and 1
        }
        return borrow == 1
    }

    fun isZero(bytes: ByteArray): Boolean {
        var acc = 0
        for (b in bytes) acc = acc or b.toInt()
        return acc == 0
    }

    fun publicMessage(suite: Rfc9382Suite, role: Role, w: ByteArray, scalar: ByteArray): ByteArray =
        when (suite) {
            Rfc9382Suite.P256_SHA256 -> {
                val fixed = if (role == Role.ALICE) P256.m else P256.n
                val mask = P256.multiply(fixed, w)
                val ephemeral = P256.baseMultiply(scalar)
                val result = P256.add(ephemeral, mask)
                try { P256.encode(result) } finally {
                    fixed.destroy(); mask.destroy(); ephemeral.destroy(); result.destroy()
                }
            }
            Rfc9382Suite.EDWARDS25519_SHA256 -> {
                val fixed = edMask(role)
                val wl = w.reversedArray()
                val xl = scalar.reversedArray()
                val mask = Edwards25519.multiply(fixed, wl)
                val ephemeral = Edwards25519.baseMultiply(xl)
                val result = Edwards25519.add(ephemeral, mask)
                try {
                    require(!Edwards25519.isSmallOrder(result)) { "Degenerate public message" }
                    Edwards25519.encode(result)
                } finally {
                    wl.fill(0); xl.fill(0)
                    fixed.destroy(); mask.destroy(); ephemeral.destroy(); result.destroy()
                }
            }
        }

    fun sharedPoint(
        suite: Rfc9382Suite, role: Role, w: ByteArray, scalar: ByteArray, peer: ByteArray,
    ): ByteArray = when (suite) {
        Rfc9382Suite.P256_SHA256 -> {
            val received = P256.decode(peer)
            val fixed = if (role == Role.ALICE) P256.n else P256.m
            val mask = P256.multiply(fixed, w)
            val unmasked = P256.subtract(received, mask)
            val shared = P256.multiply(unmasked, scalar)
            try {
                require(!P256.isIdentity(shared)) { "Degenerate shared point" }
                P256.encode(shared)
            } finally {
                received.destroy(); fixed.destroy(); mask.destroy(); unmasked.destroy(); shared.destroy()
            }
        }
        Rfc9382Suite.EDWARDS25519_SHA256 -> {
            val received = Edwards25519.decodeStrict(peer)
            try {
                require(!Edwards25519.isSmallOrder(received)) { "Low-order peer point" }
                val fixed = edMask(if (role == Role.ALICE) Role.BOB else Role.ALICE)
                val wl = w.reversedArray()
                val xl = scalar.reversedArray()
                val mask = Edwards25519.multiply(fixed, wl)
                val unmasked = Edwards25519.subtract(received, mask)
                val cleared = Edwards25519.multiplyCofactor(unmasked)
                val shared = Edwards25519.multiply(cleared, xl)
                try {
                    require(!Edwards25519.isIdentity(shared)) { "Degenerate shared point" }
                    Edwards25519.encode(shared)
                } finally {
                    wl.fill(0); xl.fill(0)
                    fixed.destroy(); mask.destroy(); unmasked.destroy(); cleared.destroy(); shared.destroy()
                }
            } finally { received.destroy() }
        }
    }

    private fun edMask(role: Role): Edwards25519.Point = Edwards25519.decodeStrict(hex(
        if (role == Role.ALICE) "d048032c6ea0b6d697ddc2e86bda85a33adac920f1bf18e1b0c6d166a5cecdaf"
        else "d3bfb518f44f3430f29d0c92af503865a1ed3281dc69b35dd868ba85f886c4ab"
    ))

    fun derive(a: ByteArray, b: ByteArray, pA: ByteArray, pB: ByteArray, k: ByteArray, w: ByteArray,
               aad: ByteArray): TranscriptSecrets {
        val tt = transcript(a, b, pA, pB, k, w)
        val hash = Sha256.digest(tt)
        val ke = hash.copyOfRange(0, 16)
        val ka = hash.copyOfRange(16, 32)
        val info = "ConfirmationKeys".encodeToByteArray() + aad
        val kc = HkdfSha256.derive(ka, byteArrayOf(), info, 32)
        val kcA = kc.copyOfRange(0, 16)
        val kcB = kc.copyOfRange(16, 32)
        kc.fill(0); info.fill(0)
        return TranscriptSecrets(tt, hash, ke, ka, kcA, kcB,
            HmacSha256.mac(kcA, tt), HmacSha256.mac(kcB, tt))
    }

    private fun transcript(vararg parts: ByteArray): ByteArray {
        var total = 0L
        for (part in parts) total += 8L + part.size
        require(total <= Int.MAX_VALUE) { "Transcript too large" }
        return ByteArray(total.toInt()).also { out ->
            var offset = 0
            for (part in parts) {
                for (i in 0..7) out[offset++] = (part.size.toLong() ushr (8 * i)).toByte()
                part.copyInto(out, offset)
                offset += part.size
            }
        }
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false // wire length is public
        var difference = 0
        for (i in a.indices) difference = difference or (a[i].toInt() xor b[i].toInt())
        return difference == 0
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) {
        value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
