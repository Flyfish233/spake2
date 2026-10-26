// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2.rfc

import io.github.flyfish233.spake2.internal.math.Edwards25519
import io.github.flyfish233.spake2.internal.math.ModMath
import io.github.flyfish233.spake2.internal.math.P256

/** Implemented RFC 9382 section 6 suites. No negotiation or fallback is performed. */
public enum class Rfc9382Suite(public val protocolName: String, public val messageSize: Int) {
    P256_SHA256("SPAKE2-P256-SHA256-HKDF-HMAC", 65),
    EDWARDS25519_SHA256("SPAKE2-edwards25519-SHA256-HKDF-HMAC", 32);

    /** Large prime subgroup order, encoded in 32-byte big endian form. */
    public fun scalarOrder(): ByteArray = when (this) {
        P256_SHA256 -> P256.order
        EDWARDS25519_SHA256 -> Edwards25519.orderBE
    }

    /**
     * Mathematical reduction only; this is NOT a password hash or MHF.
     * The caller's application protocol must define normalization, a suitable MHF,
     * its salt and work parameters, and its output distribution. At least 40 bytes
     * are required to allow the 64-bit statistical-bias margin in RFC 9382 section 3.2.
     * The supplied input remains owned by the caller, who must erase it when finished.
     */
    public fun reduceWideScalar(uniformBigEndian: ByteArray): ByteArray {
        require(uniformBigEndian.size in 40..1024) { "Expected 40..1024 bytes of application-derived material" }
        val arithmetic = ModMath(scalarOrder())
        val reduced = arithmetic.reduce(uniformBigEndian)
        return try { arithmetic.toBytes(reduced) } finally { reduced.fill(0) }
    }
}
