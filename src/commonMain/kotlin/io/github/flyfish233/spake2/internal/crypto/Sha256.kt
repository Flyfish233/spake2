// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2.internal.crypto

/** SHA-256, implemented directly from FIPS 180-4. No platform cryptographic backend. */
internal object Sha256 {
    private val initial = intArrayOf(
        0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
        0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
    )
    private val constants = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    fun digest(input: ByteArray): ByteArray {
        val state = initial.copyOf()
        val words = IntArray(64)
        val block = ByteArray(64)
        try {
            val blockCount = ((input.size.toLong() + 9L + 63L) / 64L).toInt()
            val bitLength = input.size.toLong() shl 3
            for (blockIndex in 0 until blockCount) {
                block.fill(0)
                val offset = blockIndex.toLong() * 64L
                val available = (input.size.toLong() - offset).coerceIn(0L, 64L).toInt()
                if (available > 0) input.copyInto(block, 0, offset.toInt(), offset.toInt() + available)
                val paddingOffset = input.size.toLong() - offset
                if (paddingOffset in 0L..63L) block[paddingOffset.toInt()] = 0x80.toByte()
                if (blockIndex == blockCount - 1) {
                    for (i in 0 until 8) block[63 - i] = (bitLength ushr (8 * i)).toByte()
                }
                for (i in 0 until 16) {
                    val j = i * 4
                    words[i] = ((block[j].toInt() and 255) shl 24) or
                        ((block[j + 1].toInt() and 255) shl 16) or
                        ((block[j + 2].toInt() and 255) shl 8) or (block[j + 3].toInt() and 255)
                }
                for (i in 16 until 64) {
                    val a = words[i - 15]
                    val b = words[i - 2]
                    val s0 = rotate(a, 7) xor rotate(a, 18) xor (a ushr 3)
                    val s1 = rotate(b, 17) xor rotate(b, 19) xor (b ushr 10)
                    words[i] = words[i - 16] + s0 + words[i - 7] + s1
                }
                var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
                var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
                for (i in 0 until 64) {
                    val sum1 = rotate(e, 6) xor rotate(e, 11) xor rotate(e, 25)
                    val choose = (e and f) xor (e.inv() and g)
                    val t1 = h + sum1 + choose + constants[i] + words[i]
                    val sum0 = rotate(a, 2) xor rotate(a, 13) xor rotate(a, 22)
                    val majority = (a and b) xor (a and c) xor (b and c)
                    val t2 = sum0 + majority
                    h = g; g = f; f = e; e = d + t1
                    d = c; c = b; b = a; a = t1 + t2
                }
                state[0] += a; state[1] += b; state[2] += c; state[3] += d
                state[4] += e; state[5] += f; state[6] += g; state[7] += h
            }
            return ByteArray(32) { i -> (state[i / 4] ushr (24 - 8 * (i % 4))).toByte() }
        } finally {
            state.fill(0)
            words.fill(0)
            block.fill(0)
        }
    }

    private fun rotate(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))
}
