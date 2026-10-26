// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2.internal.crypto

/** RFC 2104 HMAC-SHA-256. Inputs remain caller owned; internal buffers are wiped best effort. */
internal object HmacSha256 {
    fun mac(key: ByteArray, message: ByteArray): ByteArray {
        require(message.size <= Int.MAX_VALUE - 64) { "Message is too long" }
        val keyBlock = ByteArray(64)
        var hashedKey: ByteArray? = null
        var innerDigest: ByteArray? = null
        val inner = ByteArray(64 + message.size)
        val outer = ByteArray(96)
        try {
            if (key.size > 64) {
                hashedKey = Sha256.digest(key)
                hashedKey.copyInto(keyBlock)
            } else {
                key.copyInto(keyBlock)
            }
            for (i in 0 until 64) {
                inner[i] = (keyBlock[i].toInt() xor 0x36).toByte()
                outer[i] = (keyBlock[i].toInt() xor 0x5c).toByte()
            }
            message.copyInto(inner, 64)
            innerDigest = Sha256.digest(inner)
            innerDigest.copyInto(outer, 64)
            return Sha256.digest(outer)
        } finally {
            keyBlock.fill(0)
            hashedKey?.fill(0)
            innerDigest?.fill(0)
            inner.fill(0)
            outer.fill(0)
        }
    }
}
