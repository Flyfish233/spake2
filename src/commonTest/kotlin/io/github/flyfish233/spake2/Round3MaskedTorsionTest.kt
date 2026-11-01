// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2

import io.github.flyfish233.spake2.internal.math.Edwards25519
import io.github.flyfish233.spake2.rfc.Rfc9382Session
import io.github.flyfish233.spake2.rfc.Rfc9382Suite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent adversarial inputs are generated with affine Python integers by
 * docs/round3/evidence/protocol-math/generate_masked_torsion.py.
 * Each point is 42 * peerMask + T for one of all eight torsion components.
 * The received point has a nonzero prime-order component, while its unmasked
 * cofactor-cleared value is the identity. Rejecting only pure torsion is insufficient.
 */
class Round3MaskedTorsionTest {
    @Test fun passwordMaskPlusEveryTorsionComponentCannotReleaseConfirmationOrKey() {
        val inputs = listOf(
            Role.ALICE to listOf(
                "9d712506f6f7c9a66ae884add400f243d7383ef4056c95efc7b887c4b93d0aff",
                "b2527570fd57942c04240962844e7c93c11bd4a8638413823c2044ea797faab8",
                "f8bef1d893508cf60726be9c7295fd5f1f168ecf08a6484be6afb1ec65d43c46",
                "2a90eb334551db73f0f9942f7d67c8f491b7744bc25b05c5b28dabcfd3024b42",
                "508edaf90908365995177b522bff0dbc28c7c10bfa936a103847783b46c2f500",
                "3bad8a8f02a86bd3fbdbf69d7bb1836c3ee42b579c7bec7dc3dfbb1586805547",
                "f5400e276caf7309f8d941638d6a02a0e0e97130f759b7b419504e139a2bc3b9",
                "c36f14ccbaae248c0f066bd08298370b6e488bb43da4fa3a4d7254302cfdb4bd",
            ),
            Role.BOB to listOf(
                "516ac986e2ce837963086259e1a79778ffae11abd37fced976714b0207a9c26d",
                "af55e461af2c2d4519b4d5afe7589f9b00222e841dfe779b6b6ec444b463215c",
                "360fe1cb1f547f4d1aaaa095bdb10e120885208e25ff2cdc8c04bdcf8af4b7d3",
                "45408b62020ec16ba5a86f25c8771a57dabdc6244c0d86bd206228c36a0bcf47",
                "9c9536791d317c869cf79da61e5868870051ee542c803126898eb4fdf8563d92",
                "3eaa1b9e50d3d2bae64b2a5018a76064ffddd17be201886494913bbb4b9cdea3",
                "b7f01e34e0ab80b2e5555f6a424ef1edf77adf71da00d32373fb4230750b482c",
                "a8bf749dfdf13e945a5790da3788e5a8254239dbb3f27942df9dd73c95f430b8",
            ),
        )
        for ((role, messages) in inputs) for ((index, encoded) in messages.withIndex()) {
            val peer = hex(encoded)
            val point = Edwards25519.decodeStrict(peer)
            try {
                assertFalse(Edwards25519.isSmallOrder(point), "$role torsion $index has a prime-order component")
            } finally { point.destroy() }
            val session = Rfc9382Session(
                Rfc9382Suite.EDWARDS25519_SHA256, role,
                scalar(42), "alice".encodeToByteArray(), "bob".encodeToByteArray(),
                entropy = EntropySource { scalar(7) },
            )
            try {
                session.generateMessage()
                assertFailsWith<Spake2Exception>("$role torsion $index must not return a confirmation") {
                    session.processPeerMessage(peer)
                }
                assertEquals(Rfc9382Session.State.FAILED, session.state)
                assertTrue(session.ownedSecretsAreErased())
                assertFailsWith<Spake2Exception> { session.confirm(ByteArray(32)) }
                assertFailsWith<Spake2Exception> { session.generateMessage() }
                assertTrue(session.ownedSecretsAreErased())
            } finally { session.destroy() }
        }
    }

    private fun scalar(value: Int) = ByteArray(32).also { it[31] = value.toByte() }
    private fun hex(value: String) = ByteArray(value.length / 2) {
        value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
