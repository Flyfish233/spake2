// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2.internal.crypto

import io.github.flyfish233.spake2.InternalSpake2Api

/** RFC 5869 extract-and-expand. Empty salt is equivalent to the specified all-zero hash-length salt. */
@InternalSpake2Api
public object HkdfSha256 {
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        checkLength(info, length)
        val prk = extract(ikm, salt)
        return try { expand(prk, info, length) } finally { prk.fill(0) }
    }

    fun extract(ikm: ByteArray, salt: ByteArray): ByteArray = HmacSha256.mac(salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        checkLength(info, length)
        require(prk.size >= 32) { "HKDF-SHA256 pseudorandom key must be at least 32 bytes" }
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var success = false
        try {
            var offset = 0
            var counter = 1
            while (offset < length) {
                val message = ByteArray(previous.size + info.size + 1)
                val next = try {
                    previous.copyInto(message)
                    info.copyInto(message, previous.size)
                    message[message.lastIndex] = counter.toByte()
                    HmacSha256.mac(prk, message)
                } finally { message.fill(0) }
                previous.fill(0)
                previous = next
                val count = minOf(32, length - offset)
                previous.copyInto(output, offset, 0, count)
                offset += count
                counter++
            }
            success = true
            return output
        } finally {
            previous.fill(0)
            if (!success) output.fill(0)
        }
    }

    private fun checkLength(info: ByteArray, length: Int) {
        require(length in 0..(255 * 32)) { "HKDF output length must be in 0..8160" }
        require(info.size <= Int.MAX_VALUE - 97) { "HKDF info is too long" }
    }
}
