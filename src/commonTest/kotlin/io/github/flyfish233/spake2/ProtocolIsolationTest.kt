// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2

import io.github.flyfish233.spake2.legacy.LegacySpake25519
import io.github.flyfish233.spake2.rfc.Rfc9382Session
import io.github.flyfish233.spake2.rfc.Rfc9382Suite
import kotlin.test.*

class ProtocolIsolationTest {
    @Test fun legacyKeyCannotSatisfyRfcConfirmationAndNoFallbackOccurs() {
        val w = ByteArray(32).also { it[31] = 1 }
        val rfc = Rfc9382Session(Rfc9382Suite.EDWARDS25519_SHA256, Role.ALICE, w,
            "alice".encodeToByteArray(), "bob".encodeToByteArray(), entropy = EntropySource {
                ByteArray(it).also { bytes -> bytes[31] = 2 }
            })
        val legacy = LegacySpake25519(Role.BOB, "bob".encodeToByteArray(), "alice".encodeToByteArray(),
            EntropySource { count -> ByteArray(count) { it.toByte() } })
        try {
            val rfcMessage = rfc.generateMessage()
            val legacyMessage = legacy.generateMessage("password".encodeToByteArray())
            rfc.processPeerMessage(legacyMessage)
            val unconfirmed = legacy.deriveUnconfirmedKey(rfcMessage)
            assertFailsWith<Spake2Exception> { rfc.confirm(unconfirmed.copyOfRange(0, 32)) }
            assertEquals(Rfc9382Session.State.FAILED, rfc.state)
            assertTrue(rfc.ownedSecretsAreErased())
            unconfirmed.fill(0)
        } finally { rfc.destroy(); legacy.destroy() }
    }
}
