// SPDX-License-Identifier: AGPL-3.0-only
package consumer
import io.github.flyfish233.spake2.EntropySource
import io.github.flyfish233.spake2.Role
import io.github.flyfish233.spake2.legacy.LegacyPasswordScalarMode
import io.github.flyfish233.spake2.legacy.LegacySpake25519

// Same public call sequence required by generic Legacy clients. No ADB defaults.
class LegacyConsumer(role: Role, ownIdentity: ByteArray, peerIdentity: ByteArray,
    entropy: EntropySource) {
    private val session = LegacySpake25519(role, ownIdentity, peerIdentity, entropy,
        LegacyPasswordScalarMode.HARDENED)
    fun generate(password: ByteArray): ByteArray = session.generateMessage(password)
    fun process(peerMessage: ByteArray): ByteArray = session.deriveUnconfirmedKey(peerMessage)
    fun destroy() { session.destroy() }
}
