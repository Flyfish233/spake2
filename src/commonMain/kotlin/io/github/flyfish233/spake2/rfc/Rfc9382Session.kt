// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2.rfc

import io.github.flyfish233.spake2.EntropySource
import io.github.flyfish233.spake2.Role
import io.github.flyfish233.spake2.Spake2Exception
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A single RFC 9382 exchange. Call generateMessage(), processPeerMessage(), then
 * confirm(). Only confirm() can return the authenticated 16-byte session key.
 *
 * w is an already application-derived canonical 32-byte BIG-ENDIAN scalar in
 * [0, order). This constructor performs no password hashing. Empty identities
 * require explicit implicitIdentities=true, and must be implicit in the application.
 * Identity lengths are locally capped at 65535 bytes each; AAD at RFC's 8176 bytes.
 * Buffers are defensively copied. All failures terminate this session. Concurrent
 * or reentrant calls are rejected and poison the in-flight operation.
 */
@OptIn(ExperimentalAtomicApi::class)
public class Rfc9382Session(
    public val suite: Rfc9382Suite,
    public val role: Role,
    w: ByteArray,
    identityA: ByteArray,
    identityB: ByteArray,
    aad: ByteArray = byteArrayOf(),
    private val entropy: EntropySource,
    implicitIdentities: Boolean = false,
) {
    public enum class State { NEW, MESSAGE_SENT, AWAITING_CONFIRMATION, COMPLETE, FAILED, DESTROYED }

    private val gate = AtomicInt(0)
    private val status = AtomicInt(State.NEW.ordinal)
    private var passwordScalar: ByteArray
    private val a: ByteArray
    private val b: ByteArray
    private val associated: ByteArray
    private var ephemeral = byteArrayOf()
    private var message = byteArrayOf()
    private var expectedConfirmation = byteArrayOf()
    private var pendingKey = byteArrayOf()

    init {
        require(w.size == 32) { "Non-canonical password scalar" }
        val ownedW = w.copyOf()
        try {
            require(Rfc9382Core.isCanonicalScalar(ownedW, suite.scalarOrder())) { "Non-canonical password scalar" }
            require(identityA.size <= 65535 && identityB.size <= 65535) { "Identity too long" }
            require(implicitIdentities || (identityA.isNotEmpty() && identityB.isNotEmpty())) {
                "Empty identities require explicit implicit-identity mode"
            }
            require(aad.size <= 8176) { "AAD exceeds RFC 9382 limit" }
            a = identityA.copyOf(); b = identityB.copyOf(); associated = aad.copyOf()
            passwordScalar = ownedW
        } catch (failure: Throwable) {
            ownedW.fill(0)
            throw failure
        }
    }

    public val state: State get() = State.entries[status.load()]

    public fun generateMessage(): ByteArray = guarded {
        requireState(State.NEW)
        ephemeral = sampleScalar()
        message = Rfc9382Core.publicMessage(suite, role, passwordScalar, ephemeral)
        status.store(State.MESSAGE_SENT.ordinal)
        message.copyOf()
    }

    /** Process the peer's group element and return this endpoint's confirmation MAC. */
    public fun processPeerMessage(peerMessage: ByteArray): ByteArray = guarded {
        requireState(State.MESSAGE_SENT)
        require(peerMessage.size == suite.messageSize) { "Incorrect message length" }
        val peer = peerMessage.copyOf()
        var shared = byteArrayOf()
        try {
            shared = Rfc9382Core.sharedPoint(suite, role, passwordScalar, ephemeral, peer)
            val secrets = if (role == Role.ALICE)
                Rfc9382Core.derive(a, b, message, peer, shared, passwordScalar, associated)
            else Rfc9382Core.derive(a, b, peer, message, shared, passwordScalar, associated)
            try {
                pendingKey = secrets.ke.copyOf()
                expectedConfirmation = (if (role == Role.ALICE) secrets.cB else secrets.cA).copyOf()
                status.store(State.AWAITING_CONFIRMATION.ordinal)
                (if (role == Role.ALICE) secrets.cA else secrets.cB).copyOf()
            } finally { secrets.destroy() }
        } finally {
            peer.fill(0); shared.fill(0)
            ephemeral.fill(0); passwordScalar.fill(0); message.fill(0)
            a.fill(0); b.fill(0); associated.fill(0)
        }
    }

    /** Verify the peer MAC, release the authenticated key exactly once, and erase local secrets. */
    public fun confirm(peerConfirmation: ByteArray): ByteArray = guarded {
        requireState(State.AWAITING_CONFIRMATION)
        require(peerConfirmation.size == 32) { "Incorrect confirmation length" }
        val peer = peerConfirmation.copyOf()
        try {
            if (!Rfc9382Core.constantTimeEquals(expectedConfirmation, peer))
                throw Spake2Exception("Peer confirmation failed")
            val result = pendingKey.copyOf()
            erase()
            status.store(State.COMPLETE.ordinal)
            result
        } finally { peer.fill(0) }
    }

    /** Best-effort erasure. Idempotent when no other operation is in progress. */
    public fun destroy() {
        if (gate.load() == 3) {
            status.store(State.DESTROYED.ordinal)
            return // terminal gate is published only after owned buffers are erased
        }
        guarded {
            erase()
            status.store(State.DESTROYED.ordinal)
            byteArrayOf()
        }
    }

    private fun sampleScalar(): ByteArray {
        val order = suite.scalarOrder()
        repeat(128) {
            val supplied = entropy.nextBytes(32)
            try {
                require(supplied.size == 32) { "Entropy source returned incorrect byte count" }
                val candidate = supplied.copyOf()
                // Mask only public unused bits before rejection sampling.
                if (suite == Rfc9382Suite.EDWARDS25519_SHA256)
                    candidate[0] = (candidate[0].toInt() and 31).toByte()
                // RFC 9382 section 3.3 samples the full [0, q) interval, including zero.
                // Degenerate public/shared points terminate the exchange at the protocol boundary.
                if (Rfc9382Core.isCanonicalScalar(candidate, order))
                    return candidate
                candidate.fill(0)
            } finally { supplied.fill(0) }
        }
        throw Spake2Exception("Entropy rejection limit exceeded")
    }

    private fun requireState(expected: State) {
        if (status.load() != expected.ordinal) throw Spake2Exception("Invalid session state")
    }

    private fun erase() {
        passwordScalar.fill(0); a.fill(0); b.fill(0); associated.fill(0)
        ephemeral.fill(0); message.fill(0)
        expectedConfirmation.fill(0); pendingKey.fill(0)
    }

    private inline fun guarded(operation: () -> ByteArray): ByteArray {
        if (!gate.compareAndSet(0, 1)) {
            poisonConcurrentCall()
            throw Spake2Exception("Concurrent or reentrant session use")
        }
        try {
            val output = operation()
            if (!gate.compareAndSet(1, 0)) {
                output.fill(0)
                throw Spake2Exception("Concurrent or reentrant session use")
            }
            return output
        } catch (failure: Throwable) {
            erase()
            status.store(State.FAILED.ordinal)
            gate.store(3)
            if (failure is Spake2Exception) throw failure
            throw Spake2Exception("SPAKE2 exchange failed", failure)
        }
    }

    private fun poisonConcurrentCall() {
        // 0 idle, 1 owned, 2 poisoned while owned, 3 erased and terminal,
        // 4 exclusively owned cleanup after a failed acquisition.
        // Retry after a changed observation: a failed acquisition must not miss
        // an owner releasing the gate, or accidentally resurrect a failed state.
        while (true) {
            when (gate.load()) {
                0 -> if (gate.compareAndSet(0, 4)) {
                    erase(); status.store(State.FAILED.ordinal); gate.store(3); return
                }
                1 -> if (gate.compareAndSet(1, 2)) return
                else -> return
            }
        }
    }

    /** Test-only internal check; never exposes secret contents. */
    internal fun ownedSecretsAreErased(): Boolean = arrayOf(passwordScalar, a, b, associated,
        ephemeral, message, expectedConfirmation, pendingKey).all { Rfc9382Core.isZero(it) }
}
