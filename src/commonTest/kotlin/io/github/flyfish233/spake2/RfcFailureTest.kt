// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2

import io.github.flyfish233.spake2.rfc.Rfc9382Session
import io.github.flyfish233.spake2.rfc.Rfc9382Suite
import kotlin.test.*

class RfcFailureTest {
    private val w = scalar(42)
    private val idA = "client@example".encodeToByteArray()
    private val idB = "server@example".encodeToByteArray()
    private fun session(suite: Rfc9382Suite, role: Role, password: ByteArray = w,
                        a: ByteArray = idA, b: ByteArray = idB, aad: ByteArray = byteArrayOf(),
                        entropy: EntropySource = EntropySource { scalar(if (role == Role.ALICE) 7 else 19) }): Rfc9382Session =
        Rfc9382Session(suite, role, password, a, b, aad, entropy)

    @Test fun wrongPasswordIdentityAIdentityBAndAadAllPreventKeyRelease() {
        for (suite in Rfc9382Suite.entries) for (fault in 0..3) {
            val alice = session(suite, Role.ALICE)
            val bob = session(suite, Role.BOB,
                password = if (fault == 0) scalar(43) else w,
                a = if (fault == 1) "other client".encodeToByteArray() else idA,
                b = if (fault == 2) "other server".encodeToByteArray() else idB,
                aad = if (fault == 3) byteArrayOf(1) else byteArrayOf())
            val pA = alice.generateMessage(); val pB = bob.generateMessage()
            val cA = alice.processPeerMessage(pB); val cB = bob.processPeerMessage(pA)
            assertFailsWith<Spake2Exception> { alice.confirm(cB) }
            assertFailsWith<Spake2Exception> { bob.confirm(cA) }
            failedAndErased(alice); failedAndErased(bob)
        }
    }

    @Test fun cannotReleaseAKeyBeforeConfirmationOrBeforePeerMessage() {
        for (suite in Rfc9382Suite.entries) {
            val a = session(suite, Role.ALICE)
            assertFailsWith<Spake2Exception> { a.confirm(ByteArray(32)) }; failedAndErased(a)
            val b = session(suite, Role.BOB)
            b.generateMessage()
            assertFailsWith<Spake2Exception> { b.confirm(ByteArray(32)) }; failedAndErased(b)
        }
    }

    @Test fun malformedConfirmationLengthAndContentAreTerminal() {
        for (suite in Rfc9382Suite.entries) for (length in listOf(0, 16, 31, 32, 33, 64)) {
            val a = session(suite, Role.ALICE); val b = session(suite, Role.BOB)
            val pA = a.generateMessage(); val pB = b.generateMessage()
            a.processPeerMessage(pB); b.processPeerMessage(pA)
            assertFailsWith<Spake2Exception> { a.confirm(ByteArray(length)) }
            failedAndErased(a); b.destroy()
        }
    }

    @Test fun sameRoleFailsConfirmation() {
        for (suite in Rfc9382Suite.entries) {
            val a = session(suite, Role.ALICE)
            val b = session(suite, Role.ALICE, entropy = EntropySource { scalar(31) })
            val pa = a.generateMessage(); val pb = b.generateMessage()
            val ca = a.processPeerMessage(pb); val cb = b.processPeerMessage(pa)
            assertFailsWith<Spake2Exception> { a.confirm(cb) }
            assertFailsWith<Spake2Exception> { b.confirm(ca) }
            failedAndErased(a); failedAndErased(b)
        }
    }

    @Test fun repeatedOperationsAndDestroyAreTerminal() {
        for (suite in Rfc9382Suite.entries) {
            val first = session(suite, Role.ALICE)
            first.generateMessage()
            assertFailsWith<Spake2Exception> { first.generateMessage() }; failedAndErased(first)
            val a = session(suite, Role.ALICE); val b = session(suite, Role.BOB)
            val pa = a.generateMessage(); val pb = b.generateMessage()
            a.processPeerMessage(pb)
            assertFailsWith<Spake2Exception> { a.processPeerMessage(pb) }; failedAndErased(a)
            b.processPeerMessage(pa); b.destroy(); b.destroy()
            assertEquals(Rfc9382Session.State.DESTROYED, b.state)
            assertTrue(b.ownedSecretsAreErased())
            assertFailsWith<Spake2Exception> { b.generateMessage() }; failedAndErased(b)
        }
    }

    @Test fun successfulKeyCanOnlyBeReleasedOnce() {
        for (suite in Rfc9382Suite.entries) {
            val a = session(suite, Role.ALICE); val b = session(suite, Role.BOB)
            val pa = a.generateMessage(); val pb = b.generateMessage()
            val ca = a.processPeerMessage(pb); val cb = b.processPeerMessage(pa)
            val ka = a.confirm(cb); val kb = b.confirm(ca)
            assertContentEquals(ka, kb)
            assertEquals(16, ka.size)
            assertEquals(Rfc9382Session.State.COMPLETE, a.state)
            assertTrue(a.ownedSecretsAreErased()); assertTrue(b.ownedSecretsAreErased())
            assertFailsWith<Spake2Exception> { a.confirm(cb) }
            failedAndErased(a); ka.fill(0); kb.fill(0)
        }
    }

    @Test fun sourceFailureWrongLengthAndRepeatedZeroCandidatesTerminateAndWipe() {
        for (suite in Rfc9382Suite.entries) {
            val wrong = ByteArray(31) { 99 }
            val sources = listOf(EntropySource { throw IllegalStateException("injected") },
                EntropySource { wrong }, EntropySource { ByteArray(32) }, EntropySource { ByteArray(32) { -1 } })
            for (source in sources) {
                val a = session(suite, Role.ALICE, entropy = source)
                assertFailsWith<Spake2Exception> { a.generateMessage() }
                failedAndErased(a)
            }
            assertTrue(wrong.all { it == 0.toByte() })
        }
    }

    @Test fun rejectionSamplingRejectsZeroAndOrderAndConsumesFreshEntropy() {
        for (suite in Rfc9382Suite.entries) {
            val buffers = listOf(ByteArray(32), suite.scalarOrder(), scalar(8))
            var index = 0
            val a = session(suite, Role.ALICE, entropy = EntropySource { buffers[index++] })
            a.generateMessage()
            assertEquals(3, index)
            assertTrue(buffers.all { bytes -> bytes.all { it == 0.toByte() } })
            a.destroy(); assertTrue(a.ownedSecretsAreErased())
        }
    }

    @Test fun entropyCallbackReentrancyPoisonsTheOwningOperation() {
        for (suite in Rfc9382Suite.entries) {
            lateinit var a: Rfc9382Session
            a = session(suite, Role.ALICE, entropy = EntropySource {
                assertFailsWith<Spake2Exception> { a.generateMessage() }
                scalar(8)
            })
            assertFailsWith<Spake2Exception> { a.generateMessage() }
            failedAndErased(a)
        }
    }

    @Test fun invalidScalarAndInputBoundsAreRejectedBeforeUse() {
        for (suite in Rfc9382Suite.entries) {
            for (bad in listOf(ByteArray(31), ByteArray(33), suite.scalarOrder(), ByteArray(32) { -1 }))
                assertFailsWith<IllegalArgumentException> { session(suite, Role.ALICE, password = bad) }
            assertFailsWith<IllegalArgumentException> { session(suite, Role.ALICE, a = byteArrayOf()) }
            assertFailsWith<IllegalArgumentException> { session(suite, Role.ALICE, a = ByteArray(65536)) }
            assertFailsWith<IllegalArgumentException> { session(suite, Role.ALICE, b = ByteArray(65536)) }
            assertFailsWith<IllegalArgumentException> { session(suite, Role.ALICE, aad = ByteArray(8177)) }
            session(suite, Role.ALICE, aad = ByteArray(8176)).destroy()
            assertFailsWith<IllegalArgumentException> { suite.reduceWideScalar(ByteArray(39)) }
            assertFailsWith<IllegalArgumentException> { suite.reduceWideScalar(ByteArray(1025)) }
            assertContentEquals(ByteArray(32), suite.reduceWideScalar(ByteArray(40)))
        }
    }

    @Test fun hostilePointEncodingsAreRejectedAndOwnedSecretsErased() {
        val p256 = listOf(ByteArray(65).also { it[0] = 4 }, ByteArray(65) { -1 },
            byteArrayOf(0), ByteArray(33).also { it[0] = 2 })
        val edwards = listOf(ByteArray(32), scalar(1).reversedArray(),
            hex("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
            hex("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
            hex("0100000000000000000000000000000000000000000000000000000000000080"), ByteArray(32) { -1 })
        for (suite in Rfc9382Suite.entries) {
            val badPoints = if (suite == Rfc9382Suite.P256_SHA256) p256 else edwards
            for (point in badPoints + listOf(ByteArray(0), ByteArray(31), ByteArray(66))) {
                val a = session(suite, Role.ALICE); a.generateMessage()
                assertFailsWith<Spake2Exception> { a.processPeerMessage(point) }
                failedAndErased(a)
            }
        }
    }

    @Test fun callerArraysAreDefensivelyCopiedAndMessagesHaveIndependentStorage() {
        for (suite in Rfc9382Suite.entries) {
            val originalW = w.copyOf(); val originalA = idA.copyOf(); val originalB = idB.copyOf()
            val a = session(suite, Role.ALICE, password = originalW, a = originalA, b = originalB)
            val b = session(suite, Role.BOB)
            originalW.fill(0); originalA.fill(0); originalB.fill(0)
            val pa = a.generateMessage(); val wirePa = pa.copyOf(); pa.fill(0)
            val pb = b.generateMessage(); val ca = a.processPeerMessage(pb); pb.fill(0)
            val cb = b.processPeerMessage(wirePa); wirePa.fill(0)
            assertContentEquals(a.confirm(cb), b.confirm(ca))
        }
    }

    @Test fun boundedMalformedInputFuzzIsTerminal() {
        // Deterministic fuzz data, not an entropy source used by production code.
        var value = 0x13579bdf
        for (suite in Rfc9382Suite.entries) repeat(24) { iteration ->
            val message = ByteArray((iteration * 13) % 79) {
                value = value xor (value shl 13); value = value xor (value ushr 17)
                value = value xor (value shl 5); value.toByte()
            }
            if (message.size == suite.messageSize) return@repeat
            val a = session(suite, Role.ALICE); a.generateMessage()
            assertFailsWith<Spake2Exception> { a.processPeerMessage(message) }
            failedAndErased(a)
        }
    }

    private fun failedAndErased(s: Rfc9382Session) {
        assertEquals(Rfc9382Session.State.FAILED, s.state)
        assertTrue(s.ownedSecretsAreErased())
    }
    private fun scalar(value: Int): ByteArray = ByteArray(32).also { it[31] = value.toByte() }
    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
