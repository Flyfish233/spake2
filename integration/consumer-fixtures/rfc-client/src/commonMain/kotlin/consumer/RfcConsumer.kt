// SPDX-License-Identifier: AGPL-3.0-only
package consumer
import io.github.flyfish233.spake2.EntropySource
import io.github.flyfish233.spake2.Role
import io.github.flyfish233.spake2.rfc.Rfc9382Session
import io.github.flyfish233.spake2.rfc.Rfc9382Suite

// This separately compiled consumer uses only public API from Maven artifacts.
fun rfcSession(suite: Rfc9382Suite, role: Role, w: ByteArray,
    alice: ByteArray, bob: ByteArray, aad: ByteArray, entropy: EntropySource,
    implicit: Boolean = false): Rfc9382Session =
    Rfc9382Session(suite, role, w, alice, bob, aad, entropy, implicit)
