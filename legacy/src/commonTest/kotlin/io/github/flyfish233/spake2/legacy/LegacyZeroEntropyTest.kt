// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Flyfish233 and spake2 contributors
package io.github.flyfish233.spake2.legacy

import io.github.flyfish233.spake2.EntropySource
import io.github.flyfish233.spake2.Role
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** Expectations independently regenerated with tools/legacy_oracle.cc. */
class LegacyZeroEntropyTest {
    @Test fun historicalZeroEphemeralScalarIsPreservedExplicitly() {
        // CSPRNG quality is a caller contract. The pinned legacy protocol
        // reduces 64 bytes mod L and does not reject a zero result. This test
        // locks that historical behavior; it does not endorse weak entropy.
        val order = legacyHex("edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010")
        val twiceOrder = legacyHex("daa7ebb934c624b0ac39ef45bdf3bd2900000000000000000000000000000020")
        val expectedMessage = legacyHex("f698309d2870d4c3552c8390b02b5a7fc1211abb6546ed40e5994afe51cf6e27")
        val peerMessage = legacyHex("e814a51e5e1032ce32bd9e47f74fb58a42f02b61b355e062a36836dbd8de35c5")
        val expectedKey = legacyHex("1f3f211a6620d2c5722fb86b8b96dbc467abcc339e5ac4a10ab98d276856b491119fdc442b765a701e8c65c51627ad6ce2948b6a3e72e468339f8216499c6620")
        for (reducedZeroInput in listOf(ByteArray(32), order, twiceOrder)) {
            val session = LegacySpake25519(Role.ALICE, "alice".encodeToByteArray(), "bob".encodeToByteArray(),
                EntropySource { size -> ByteArray(size).also { reducedZeroInput.copyInto(it) } })
            try {
                assertContentEquals(expectedMessage, session.generateMessage("515109".encodeToByteArray()))
                assertContentEquals(expectedKey, session.deriveUnconfirmedKey(peerMessage))
                assertTrue(session.ownedSecretsAreZero())
            } finally { session.destroy() }
        }
    }
}
