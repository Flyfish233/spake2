// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2.internal.crypto

import io.github.flyfish233.spake2.InternalSpake2Api

/** SHA-512, implemented directly from FIPS 180-4. Inputs are bounded by ByteArray's Int size. */
@InternalSpake2Api
public object Sha512 {
    private val initial = longArrayOf(
        0x6a09e667f3bcc908UL.toLong(), 0xbb67ae8584caa73bUL.toLong(),
        0x3c6ef372fe94f82bUL.toLong(), 0xa54ff53a5f1d36f1UL.toLong(),
        0x510e527fade682d1UL.toLong(), 0x9b05688c2b3e6c1fUL.toLong(),
        0x1f83d9abfb41bd6bUL.toLong(), 0x5be0cd19137e2179UL.toLong(),
    )
    private val constants = longArrayOf(
        0x428a2f98d728ae22UL.toLong(), 0x7137449123ef65cdUL.toLong(),
        0xb5c0fbcfec4d3b2fUL.toLong(), 0xe9b5dba58189dbbcUL.toLong(),
        0x3956c25bf348b538UL.toLong(), 0x59f111f1b605d019UL.toLong(),
        0x923f82a4af194f9bUL.toLong(), 0xab1c5ed5da6d8118UL.toLong(),
        0xd807aa98a3030242UL.toLong(), 0x12835b0145706fbeUL.toLong(),
        0x243185be4ee4b28cUL.toLong(), 0x550c7dc3d5ffb4e2UL.toLong(),
        0x72be5d74f27b896fUL.toLong(), 0x80deb1fe3b1696b1UL.toLong(),
        0x9bdc06a725c71235UL.toLong(), 0xc19bf174cf692694UL.toLong(),
        0xe49b69c19ef14ad2UL.toLong(), 0xefbe4786384f25e3UL.toLong(),
        0x0fc19dc68b8cd5b5UL.toLong(), 0x240ca1cc77ac9c65UL.toLong(),
        0x2de92c6f592b0275UL.toLong(), 0x4a7484aa6ea6e483UL.toLong(),
        0x5cb0a9dcbd41fbd4UL.toLong(), 0x76f988da831153b5UL.toLong(),
        0x983e5152ee66dfabUL.toLong(), 0xa831c66d2db43210UL.toLong(),
        0xb00327c898fb213fUL.toLong(), 0xbf597fc7beef0ee4UL.toLong(),
        0xc6e00bf33da88fc2UL.toLong(), 0xd5a79147930aa725UL.toLong(),
        0x06ca6351e003826fUL.toLong(), 0x142929670a0e6e70UL.toLong(),
        0x27b70a8546d22ffcUL.toLong(), 0x2e1b21385c26c926UL.toLong(),
        0x4d2c6dfc5ac42aedUL.toLong(), 0x53380d139d95b3dfUL.toLong(),
        0x650a73548baf63deUL.toLong(), 0x766a0abb3c77b2a8UL.toLong(),
        0x81c2c92e47edaee6UL.toLong(), 0x92722c851482353bUL.toLong(),
        0xa2bfe8a14cf10364UL.toLong(), 0xa81a664bbc423001UL.toLong(),
        0xc24b8b70d0f89791UL.toLong(), 0xc76c51a30654be30UL.toLong(),
        0xd192e819d6ef5218UL.toLong(), 0xd69906245565a910UL.toLong(),
        0xf40e35855771202aUL.toLong(), 0x106aa07032bbd1b8UL.toLong(),
        0x19a4c116b8d2d0c8UL.toLong(), 0x1e376c085141ab53UL.toLong(),
        0x2748774cdf8eeb99UL.toLong(), 0x34b0bcb5e19b48a8UL.toLong(),
        0x391c0cb3c5c95a63UL.toLong(), 0x4ed8aa4ae3418acbUL.toLong(),
        0x5b9cca4f7763e373UL.toLong(), 0x682e6ff3d6b2b8a3UL.toLong(),
        0x748f82ee5defb2fcUL.toLong(), 0x78a5636f43172f60UL.toLong(),
        0x84c87814a1f0ab72UL.toLong(), 0x8cc702081a6439ecUL.toLong(),
        0x90befffa23631e28UL.toLong(), 0xa4506cebde82bde9UL.toLong(),
        0xbef9a3f7b2c67915UL.toLong(), 0xc67178f2e372532bUL.toLong(),
        0xca273eceea26619cUL.toLong(), 0xd186b8c721c0c207UL.toLong(),
        0xeada7dd6cde0eb1eUL.toLong(), 0xf57d4f7fee6ed178UL.toLong(),
        0x06f067aa72176fbaUL.toLong(), 0x0a637dc5a2c898a6UL.toLong(),
        0x113f9804bef90daeUL.toLong(), 0x1b710b35131c471bUL.toLong(),
        0x28db77f523047d84UL.toLong(), 0x32caab7b40c72493UL.toLong(),
        0x3c9ebe0a15c9bebcUL.toLong(), 0x431d67c49c100d4cUL.toLong(),
        0x4cc5d4becb3e42b6UL.toLong(), 0x597f299cfc657e2aUL.toLong(),
        0x5fcb6fab3ad6faecUL.toLong(), 0x6c44198c4a475817UL.toLong(),
    )

    fun digest(input: ByteArray): ByteArray {
        val state = initial.copyOf()
        val words = LongArray(80)
        val block = ByteArray(128)
        try {
            val blockCount = ((input.size.toLong() + 17L + 127L) / 128L).toInt()
            val bitLength = input.size.toLong() shl 3
            for (blockIndex in 0 until blockCount) {
                block.fill(0)
                val offset = blockIndex.toLong() * 128L
                val available = (input.size.toLong() - offset).coerceIn(0L, 128L).toInt()
                if (available > 0) input.copyInto(block, 0, offset.toInt(), offset.toInt() + available)
                val paddingOffset = input.size.toLong() - offset
                if (paddingOffset in 0L..127L) block[paddingOffset.toInt()] = 0x80.toByte()
                if (blockIndex == blockCount - 1) {
                    // High 64 bits of the 128-bit bit count are zero for every possible ByteArray.
                    for (i in 0 until 8) block[127 - i] = (bitLength ushr (8 * i)).toByte()
                }
                for (i in 0 until 16) {
                    var value = 0L
                    for (j in 0 until 8) value = (value shl 8) or (block[i * 8 + j].toLong() and 255L)
                    words[i] = value
                }
                for (i in 16 until 80) {
                    val a = words[i - 15]
                    val b = words[i - 2]
                    val s0 = rotate(a, 1) xor rotate(a, 8) xor (a ushr 7)
                    val s1 = rotate(b, 19) xor rotate(b, 61) xor (b ushr 6)
                    words[i] = words[i - 16] + s0 + words[i - 7] + s1
                }
                var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
                var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
                for (i in 0 until 80) {
                    val sum1 = rotate(e, 14) xor rotate(e, 18) xor rotate(e, 41)
                    val choose = (e and f) xor (e.inv() and g)
                    val t1 = h + sum1 + choose + constants[i] + words[i]
                    val sum0 = rotate(a, 28) xor rotate(a, 34) xor rotate(a, 39)
                    val majority = (a and b) xor (a and c) xor (b and c)
                    val t2 = sum0 + majority
                    h = g; g = f; f = e; e = d + t1
                    d = c; c = b; b = a; a = t1 + t2
                }
                state[0] += a; state[1] += b; state[2] += c; state[3] += d
                state[4] += e; state[5] += f; state[6] += g; state[7] += h
            }
            return ByteArray(64) { i -> (state[i / 8] ushr (56 - 8 * (i % 8))).toByte() }
        } finally {
            state.fill(0)
            words.fill(0)
            block.fill(0)
        }
    }

    private fun rotate(x: Long, n: Int): Long = (x ushr n) or (x shl (64 - n))
}
