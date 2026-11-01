// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2

import io.github.flyfish233.spake2.rfc.Rfc9382Core
import io.github.flyfish233.spake2.rfc.Rfc9382Session
import io.github.flyfish233.spake2.rfc.Rfc9382Suite
import io.github.flyfish233.spake2.internal.math.Edwards25519
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Expected outputs are static official or independently generated fixtures, not self-agreement. */
class RfcVectorsTest {
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private fun assertHex(expected: String, actual: ByteArray, label: String) =
        assertContentEquals(hex(expected), actual, label)

    @Test fun p256EveryOfficialIntermediateField() {
        for ((index, vector) in RfcOfficialVectors.all.withIndex()) {
            verifyFields(Rfc9382Suite.P256_SHA256, vector, "RFC Appendix B #$index")
        }
    }

    @Test fun edwardsEveryIndependentIntermediateField() {
        for ((index, vector) in EdwardsReferenceVectors.all.withIndex()) {
            verifyFields(Rfc9382Suite.EDWARDS25519_SHA256, vector, "python-ecdsa Edwards #$index")
        }
    }

    private fun verifyFields(suite: Rfc9382Suite, v: RfcVector, label: String) {
        val w = hex(v.w)
        val x = hex(v.x)
        val y = hex(v.y)
        val pA = Rfc9382Core.publicMessage(suite, Role.ALICE, w, x)
        val pB = Rfc9382Core.publicMessage(suite, Role.BOB, w, y)
        assertHex(v.pA, pA, "$label pA")
        assertHex(v.pB, pB, "$label pB")
        val kA = Rfc9382Core.sharedPoint(suite, Role.ALICE, w, x, pB)
        val kB = Rfc9382Core.sharedPoint(suite, Role.BOB, w, y, pA)
        assertHex(v.k, kA, "$label Alice K")
        assertHex(v.k, kB, "$label Bob K")
        val derived = Rfc9382Core.derive(hex(v.a), hex(v.b), pA, pB, kA, w, hex(v.aad))
        try {
            assertHex(v.tt, derived.tt, "$label TT")
            assertHex(v.hashTt, derived.hashTt, "$label Hash(TT)")
            assertHex(v.ke, derived.ke, "$label Ke")
            assertHex(v.ka, derived.ka, "$label Ka")
            assertHex(v.kcA, derived.kcA, "$label KcA")
            assertHex(v.kcB, derived.kcB, "$label KcB")
            assertHex(v.cA, derived.cA, "$label cA")
            assertHex(v.cB, derived.cB, "$label cB")
        } finally {
            derived.destroy()
            for (secret in arrayOf(w, x, y, kA, kB)) secret.fill(0)
        }
        for (secret in arrayOf(derived.tt, derived.hashTt, derived.ke, derived.ka,
            derived.kcA, derived.kcB, derived.cA, derived.cB)) {
            assertTrue(secret.all { it == 0.toByte() }, "$label transcript erasure")
        }
    }

    @Test fun publicP256ApiMatchesOfficialVectorsThroughConfirmation() {
        for (v in RfcOfficialVectors.all) verifyPublicApi(Rfc9382Suite.P256_SHA256, v)
    }

    @Test fun publicEdwardsApiMatchesIndependentVectorsThroughConfirmation() {
        for (v in EdwardsReferenceVectors.all) verifyPublicApi(Rfc9382Suite.EDWARDS25519_SHA256, v)
    }

    @Test fun edwardsRejectsEveryPureTorsionElement() {
        val v = EdwardsReferenceVectors.all[0]
        for (encoded in EdwardsTorsionVectors.pure) {
            val point = Edwards25519.decodeStrict(hex(encoded))
            try {
                assertTrue(Edwards25519.isSmallOrder(point), "Pure torsion $encoded")
                assertContentEquals(hex(encoded), Edwards25519.encode(point), "Canonical torsion encoding")
            } finally { point.destroy() }
            assertFailsWith<IllegalArgumentException>("Reject pure torsion $encoded") {
                Rfc9382Core.sharedPoint(Rfc9382Suite.EDWARDS25519_SHA256, Role.ALICE,
                    hex(v.w), hex(v.x), hex(encoded))
            }
        }
    }

    @Test fun edwardsRfcMaskConstantsArePrimeOrder() {
        for (encoded in listOf(
            "d048032c6ea0b6d697ddc2e86bda85a33adac920f1bf18e1b0c6d166a5cecdaf",
            "d3bfb518f44f3430f29d0c92af503865a1ed3281dc69b35dd868ba85f886c4ab",
        )) {
            val point = Edwards25519.decodeStrict(hex(encoded))
            try {
                assertFalse(Edwards25519.isIdentity(point))
                assertTrue(Edwards25519.isPrimeOrder(point))
            } finally { point.destroy() }
        }
    }

    @Test fun edwardsMixedOrderUsesCofactorAndBindsExactPeerEncoding() {
        val v = EdwardsReferenceVectors.all[0]
        for (encoded in EdwardsTorsionVectors.mixed) {
            val point = Edwards25519.decodeStrict(hex(encoded))
            try {
                assertFalse(Edwards25519.isSmallOrder(point))
                assertFalse(Edwards25519.isPrimeOrder(point))
            } finally { point.destroy() }
            val shared = Rfc9382Core.sharedPoint(Rfc9382Suite.EDWARDS25519_SHA256,
                Role.ALICE, hex(v.w), hex(v.x), hex(encoded))
            assertHex(v.k, shared, "Cofactor removes mixed-order torsion")
            val secrets = Rfc9382Core.derive(hex(v.a), hex(v.b), hex(v.pA), hex(encoded),
                shared, hex(v.w), hex(v.aad))
            try {
                assertFalse(secrets.tt.contentEquals(hex(v.tt)), "Exact wire encoding is transcript-bound")
                assertFalse(secrets.cB.contentEquals(hex(v.cB)), "Original peer confirmation does not authenticate modified encoding")
            } finally { shared.fill(0); secrets.destroy() }
        }
    }

    private fun verifyPublicApi(suite: Rfc9382Suite, v: RfcVector) {
        fun source(scalar: String): EntropySource = EntropySource { count ->
            assertEquals(32, count)
            hex(scalar)
        }
        val implicit = v.a.isEmpty() || v.b.isEmpty()
        val alice = Rfc9382Session(suite, Role.ALICE, hex(v.w), hex(v.a), hex(v.b),
            hex(v.aad), source(v.x), implicit)
        val bob = Rfc9382Session(suite, Role.BOB, hex(v.w), hex(v.a), hex(v.b),
            hex(v.aad), source(v.y), implicit)
        try {
            assertEquals(Rfc9382Session.State.NEW, alice.state)
            val pA = alice.generateMessage()
            val pB = bob.generateMessage()
            assertHex(v.pA, pA, "Public API pA")
            assertHex(v.pB, pB, "Public API pB")
            val cA = alice.processPeerMessage(pB)
            val cB = bob.processPeerMessage(pA)
            assertHex(v.cA, cA, "Public API cA")
            assertHex(v.cB, cB, "Public API cB")
            assertEquals(Rfc9382Session.State.AWAITING_CONFIRMATION, alice.state)
            assertEquals(Rfc9382Session.State.AWAITING_CONFIRMATION, bob.state)
            assertHex(v.ke, alice.confirm(cB), "Public API confirmed Alice Ke")
            assertHex(v.ke, bob.confirm(cA), "Public API confirmed Bob Ke")
            assertEquals(Rfc9382Session.State.COMPLETE, alice.state)
            assertEquals(Rfc9382Session.State.COMPLETE, bob.state)
            assertTrue(alice.ownedSecretsAreErased())
            assertTrue(bob.ownedSecretsAreErased())
        } finally { alice.destroy(); bob.destroy() }
    }
}
