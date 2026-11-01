// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Flyfish233 and spake2 contributors
package io.github.flyfish233.spake2.legacy

import io.github.flyfish233.spake2.EntropySource
import io.github.flyfish233.spake2.Role
import io.github.flyfish233.spake2.Spake2Exception
import io.github.flyfish233.spake2.internal.crypto.Sha512
import io.github.flyfish233.spake2.internal.math.Edwards25519
import io.github.flyfish233.spake2.internal.math.mathHex
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Explicit wire compatibility choice. The unsafe mode is only for old peers. */
public enum class LegacyPasswordScalarMode {
    /** Corrects the historical leakage of three password bits. */
    HARDENED,
    /** Historical pre-hardening behavior; exposes three password-hash bits. */
    PRE_HARDENING_UNSAFE
}

/**
 * Explicit compatibility API for BoringSSL SPAKE25519 at
 * 9e04aed2d1652441d2c84e85ed21acc05e053260. This is not RFC 9382.
 *
 * The returned 64-byte key is **unconfirmed**. A surrounding protocol must
 * authenticate both peers before using it for application traffic. In
 * particular, successful point processing does not establish that passwords,
 * identities, or roles match. This class supplies no implicit fallback and no
 * application-specific identities or password processing.
 *
 * All inputs are bytes, including embedded NULs. Identities are bounded to
 * 65,535 bytes each and password material to 1 MiB as a resource policy.
 * The caller owns and must clear its original password buffer. Secret state
 * held here is overwritten on completion, failure, or [destroy]. Managed
 * runtimes may retain copies; physical zeroization is not guaranteed.
 *
 * Instances are single-use. Concurrent/reentrant calls are rejected. Callers
 * must not race an entropy source or mutable input arrays across threads.
 */
@OptIn(ExperimentalAtomicApi::class)
public class LegacySpake25519(
    private val role: Role,
    ownIdentity: ByteArray,
    peerIdentity: ByteArray,
    private val entropySource: EntropySource,
    private val passwordScalarMode: LegacyPasswordScalarMode = LegacyPasswordScalarMode.HARDENED
) {
    private enum class State { INITIAL, MESSAGE_GENERATED, COMPLETE, FAILED, DESTROYED }
    // 0 idle, 1 owned, 2 owned+poisoned, 3 terminal, 4 cleanup in progress.
    // Terminal is published only after erasure. A failed acquisition
    // cannot miss an owner that completed between the initial CAS and poison.
    private val gate = AtomicInt(0)
    private var state = State.INITIAL
    private val ownName: ByteArray
    private val peerName: ByteArray
    private var privateScalar = ByteArray(0)
    private var passwordScalar = ByteArray(0)
    private var passwordHash = ByteArray(0)
    private var ownMessage = ByteArray(0)

    init {
        require(ownIdentity.size <= MAX_IDENTITY_BYTES && peerIdentity.size <= MAX_IDENTITY_BYTES) {
            "Legacy identities exceed the configured resource limit"
        }
        ownName = ownIdentity.copyOf()
        peerName = peerIdentity.copyOf()
    }

    /** Generates exactly one 32-byte legacy SPAKE25519 message. */
    public fun generateMessage(password: ByteArray): ByteArray = guarded {
        check(state == State.INITIAL) { "A legacy message can only be generated once" }
        require(password.size <= MAX_PASSWORD_BYTES) { "Legacy password material is too long" }
        val passwordCopy = password.copyOf()
        var randomInput: ByteArray? = null
        var ephemeral: Edwards25519.Point? = null
        var mask: Edwards25519.Point? = null
        var messagePoint: Edwards25519.Point? = null
        try {
            passwordHash = Sha512.digest(passwordCopy)
            passwordScalar = Edwards25519.reduceScalarLE(passwordHash)
            if (passwordScalarMode == LegacyPasswordScalarMode.HARDENED) hardenPasswordScalar(passwordScalar)
            randomInput = entropySource.nextBytes(64)
            require(randomInput.size == 64) { "The entropy source must supply exactly 64 bytes" }
            privateScalar = Edwards25519.reduceScalarLE(randomInput)
            shiftThree(privateScalar)
            ephemeral = Edwards25519.baseMultiply(privateScalar)
            mask = Edwards25519.multiply(if (role == Role.ALICE) mPoint else nPoint, passwordScalar)
            messagePoint = Edwards25519.add(ephemeral, mask)
            ownMessage = Edwards25519.encode(messagePoint)
            state = State.MESSAGE_GENERATED
            ownMessage.copyOf()
        } finally {
            passwordCopy.fill(0)
            randomInput?.fill(0)
            ephemeral?.destroy()
            mask?.destroy()
            messagePoint?.destroy()
        }
    }

    /**
     * Derives a 64-byte, unauthenticated legacy key and consumes this instance.
     * The peer's original encoding is retained in the transcript, including
     * non-canonical encodings accepted by the pinned BoringSSL decoder.
     */
    public fun deriveUnconfirmedKey(peerMessage: ByteArray): ByteArray = guarded {
        check(state == State.MESSAGE_GENERATED) { "Generate a legacy message before processing the peer" }
        require(peerMessage.size == 32) { "A legacy peer message must contain 32 bytes" }
        val peerCopy = peerMessage.copyOf()
        var peerPoint: Edwards25519.Point? = null
        var mask: Edwards25519.Point? = null
        var unmasked: Edwards25519.Point? = null
        var shared: Edwards25519.Point? = null
        var sharedEncoding: ByteArray? = null
        var transcript: ByteArray? = null
        try {
            peerPoint = Edwards25519.decodeLegacy(peerCopy)
            mask = Edwards25519.multiply(if (role == Role.ALICE) nPoint else mPoint, passwordScalar)
            unmasked = Edwards25519.subtract(peerPoint, mask)
            // Identity/low-order results intentionally retain historical
            // BoringSSL semantics. This API does not authenticate the peer.
            shared = Edwards25519.multiply(unmasked, privateScalar)
            sharedEncoding = Edwards25519.encode(shared)
            transcript = if (role == Role.ALICE) {
                encodeTranscript(ownName, peerName, ownMessage, peerCopy, sharedEncoding, passwordHash)
            } else {
                encodeTranscript(peerName, ownName, peerCopy, ownMessage, sharedEncoding, passwordHash)
            }
            val key = Sha512.digest(transcript)
            clear()
            state = State.COMPLETE
            key
        } finally {
            peerCopy.fill(0)
            peerPoint?.destroy()
            mask?.destroy()
            unmasked?.destroy()
            shared?.destroy()
            sharedEncoding?.fill(0)
            transcript?.fill(0)
        }
    }

    /** Idempotently overwrites the instance's owned buffers. */
    public fun destroy() {
        if (gate.load() == 3) return
        guarded { clear(); state = State.DESTROYED; ByteArray(0) }
    }

    private inline fun guarded(operation: () -> ByteArray): ByteArray {
        if (!gate.compareAndSet(0, 1)) {
            invalidateConcurrentAccess()
            throw Spake2Exception("Concurrent legacy session access")
        }
        try {
            val result = operation()
            if (!gate.compareAndSet(1, 0)) {
                result.fill(0)
                throw Spake2Exception("Concurrent legacy session access invalidated the result")
            }
            return result
        } catch (failure: Throwable) {
            clear()
            if (state != State.DESTROYED) state = State.FAILED
            gate.store(3)
            if (failure is Spake2Exception) throw failure
            throw Spake2Exception("Legacy SPAKE25519 session failed", failure)
        }
    }

    private fun invalidateConcurrentAccess() {
        while (true) {
            when (gate.load()) {
                0 -> if (gate.compareAndSet(0, 4)) {
                    clear()
                    state = State.FAILED
                    gate.store(3)
                    return
                }
                1 -> if (gate.compareAndSet(1, 2)) return
                2, 3, 4 -> return
            }
        }
    }

    private fun clear() {
        privateScalar.fill(0)
        passwordScalar.fill(0)
        passwordHash.fill(0)
        ownMessage.fill(0)
        ownName.fill(0)
        peerName.fill(0)
    }

    /** White-box lifecycle assertion; no secret value is exposed. */
    internal fun ownedSecretsAreZero(): Boolean =
        privateScalar.all { it == 0.toByte() } && passwordScalar.all { it == 0.toByte() } &&
            passwordHash.all { it == 0.toByte() } && ownMessage.all { it == 0.toByte() } &&
            ownName.all { it == 0.toByte() } && peerName.all { it == 0.toByte() }

    private companion object {
        const val MAX_IDENTITY_BYTES = 65535
        const val MAX_PASSWORD_BYTES = 1048576
        val mPoint: Edwards25519.Point by lazy {
            Edwards25519.decodeStrict(mathHex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))
        }
        val nPoint: Edwards25519.Point by lazy {
            Edwards25519.decodeStrict(mathHex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))
        }
        fun shiftThree(scalar: ByteArray) {
            var carry = 0
            for (i in 0 until 32) {
                val value = scalar[i].toInt() and 255
                scalar[i] = ((value shl 3) or carry).toByte()
                carry = value ushr 5
            }
        }
        fun hardenPasswordScalar(scalar: ByteArray) {
            val order = Edwards25519.orderLE
            try {
                for (bit in 0..2) {
                    val mask = -((scalar[0].toInt() ushr bit) and 1)
                    var carry = 0
                    for (i in 0 until 32) {
                        val value = (scalar[i].toInt() and 255) + (order[i].toInt() and mask and 255) + carry
                        scalar[i] = value.toByte()
                        carry = value ushr 8
                    }
                    var doubledCarry = 0
                    for (i in 0 until 32) {
                        val value = ((order[i].toInt() and 255) shl 1) + doubledCarry
                        order[i] = value.toByte()
                        doubledCarry = value ushr 8
                    }
                }
            } finally { order.fill(0) }
        }
        fun encodeTranscript(vararg fields: ByteArray): ByteArray {
            val out = ByteArray(fields.sumOf { it.size + 8 })
            var offset = 0
            for (field in fields) {
                var length = field.size.toLong()
                repeat(8) { out[offset++] = length.toByte(); length = length ushr 8 }
                field.copyInto(out, offset)
                offset += field.size
            }
            return out
        }
    }
}
