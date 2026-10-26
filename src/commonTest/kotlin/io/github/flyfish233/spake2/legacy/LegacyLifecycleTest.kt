// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Flyfish233 and spake2 contributors
package io.github.flyfish233.spake2.legacy

import io.github.flyfish233.spake2.EntropySource
import io.github.flyfish233.spake2.Role
import io.github.flyfish233.spake2.Spake2Exception
import io.github.flyfish233.spake2.rfc.Rfc9382Session
import io.github.flyfish233.spake2.rfc.Rfc9382Suite
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyLifecycleTest {
    private val password = "515109".encodeToByteArray()
    private fun entropy() = EntropySource { ByteArray(it).also { bytes -> bytes[0] = 1 } }
    private fun session(source: EntropySource = entropy(), role: Role = Role.ALICE): LegacySpake25519 =
        LegacySpake25519(role, "alice".encodeToByteArray(), "bob".encodeToByteArray(), source)

    @Test fun repeatGenerationFailsAndErasesSession() {
        val s = session()
        s.generateMessage(password)
        assertFailsWith<Spake2Exception> { s.generateMessage(password) }
        assertTrue(s.ownedSecretsAreZero())
        assertFailsWith<Spake2Exception> { s.deriveUnconfirmedKey(ByteArray(32)) }
        s.destroy()
    }

    @Test fun processBeforeGenerateFailsPermanently() {
        val s = session()
        assertFailsWith<Spake2Exception> { s.deriveUnconfirmedKey(ByteArray(32)) }
        assertFailsWith<Spake2Exception> { s.generateMessage(password) }
        assertTrue(s.ownedSecretsAreZero())
        s.destroy()
    }

    @Test fun destroyIsIdempotentAndTerminal() {
        val s = session()
        s.generateMessage(password)
        s.destroy(); s.destroy()
        assertTrue(s.ownedSecretsAreZero())
        assertFailsWith<Spake2Exception> { s.generateMessage(password) }
        assertFailsWith<Spake2Exception> { s.deriveUnconfirmedKey(ByteArray(32)) }
    }

    @Test fun entropyExceptionsAndIncorrectLengthsEraseState() {
        for (source in listOf(EntropySource { throw IllegalStateException("entropy fault") },
            EntropySource { ByteArray(0) }, EntropySource { ByteArray(63) }, EntropySource { ByteArray(65) })) {
            val s = session(source)
            assertFailsWith<Spake2Exception> { s.generateMessage(password) }
            assertTrue(s.ownedSecretsAreZero())
            assertFailsWith<Spake2Exception> { s.generateMessage(password) }
            s.destroy()
        }
    }

    @Test fun returnedEntropyIsWipedAndPasswordInputRemainsOwnedByCaller() {
        val random = ByteArray(64).also { it[0] = 1 }
        val s = session(EntropySource { random })
        val copy = password.copyOf()
        s.generateMessage(copy)
        assertTrue(random.all { it == 0.toByte() })
        assertContentEquals(password, copy)
        s.destroy()
    }

    @Test fun reentrantEntropyCallPoisonsOuterSessionEvenIfCallbackCatchesError() {
        lateinit var s: LegacySpake25519
        s = session(EntropySource {
            assertFailsWith<Spake2Exception> { s.generateMessage(password) }
            ByteArray(64).also { bytes -> bytes[0] = 1 }
        })
        assertFailsWith<Spake2Exception> { s.generateMessage(password) }
        assertTrue(s.ownedSecretsAreZero())
        assertFailsWith<Spake2Exception> { s.generateMessage(password) }
        s.destroy()
    }

    @Test fun callerMutationCannotAliasIdentityOrGeneratedMessage() {
        val name = "alice".encodeToByteArray()
        val peer = "bob".encodeToByteArray()
        val s = LegacySpake25519(Role.ALICE, name, peer, entropy())
        name.fill(0); peer.fill(0)
        val msg = s.generateMessage(password)
        assertContentEquals(legacyHex("1876811ed2c78beb885abcfeee9174e822e056d67474d7f924e5c019d087dc71"), msg)
        msg.fill(0)
        val incoming = legacyHex("e814a51e5e1032ce32bd9e47f74fb58a42f02b61b355e062a36836dbd8de35c5")
        val incomingCopy = incoming.copyOf()
        assertContentEquals(legacyHex("f1e5cb026ca1dc0780d629cd1747b834bceed4b05417b80f2133a2bd0a0e8b21c1467782f8cab09ba0a20c4a785029dc95c028e20deff35812c01a3a0d8e9cb2"), s.deriveUnconfirmedKey(incoming))
        assertContentEquals(incomingCopy, incoming)
        assertTrue(s.ownedSecretsAreZero())
        assertFailsWith<Spake2Exception> { s.deriveUnconfirmedKey(incoming) }
        s.destroy()
    }

    @Test fun wrongPasswordsAndRolesDoNotCreateAuthentication() {
        val a = session()
        val b = LegacySpake25519(Role.BOB, "bob".encodeToByteArray(), "alice".encodeToByteArray(), entropy())
        val am = a.generateMessage(password)
        val bm = b.generateMessage("wrong password".encodeToByteArray())
        assertFalse(a.deriveUnconfirmedKey(bm).contentEquals(b.deriveUnconfirmedKey(am)))
        a.destroy(); b.destroy()
        val aa = session()
        val otherA = session()
        val first = aa.generateMessage(password)
        val second = otherA.generateMessage(password)
        // Same role has no reliable authenticated outcome; the explicit API
        // intentionally returns only an UNCONFIRMED key, never a session key.
        assertTrue(aa.deriveUnconfirmedKey(second).size == 64)
        assertTrue(otherA.deriveUnconfirmedKey(first).size == 64)
        aa.destroy(); otherA.destroy()
    }

    @Test fun lengthsFailBeforeUnboundedAllocation() {
        assertFailsWith<IllegalArgumentException> { LegacySpake25519(Role.ALICE, ByteArray(65536), ByteArray(0), entropy()) }
        val s = session()
        assertFailsWith<Spake2Exception> { s.generateMessage(ByteArray(1048577)) }
        assertTrue(s.ownedSecretsAreZero())
        s.destroy()
    }

    @Test fun legacyKeyCannotSatisfyRfcConfirmationAndNoFallbackOccurs() {
        val w = ByteArray(32).also { it[31] = 1 }
        val rfc = Rfc9382Session(Rfc9382Suite.EDWARDS25519_SHA256, Role.ALICE, w,
            "alice".encodeToByteArray(), "bob".encodeToByteArray(), entropy = EntropySource {
                ByteArray(it).also { bytes -> bytes[31] = 2 }
            })
        val legacy = LegacySpake25519(Role.BOB, "bob".encodeToByteArray(), "alice".encodeToByteArray(), entropy())
        try {
            val rfcMessage = rfc.generateMessage()
            val legacyMessage = legacy.generateMessage(password)
            rfc.processPeerMessage(legacyMessage)
            val unconfirmed = legacy.deriveUnconfirmedKey(rfcMessage)
            assertFailsWith<Spake2Exception> { rfc.confirm(unconfirmed.copyOfRange(0, 32)) }
            assertTrue(rfc.state == Rfc9382Session.State.FAILED)
            unconfirmed.fill(0)
        } finally { rfc.destroy(); legacy.destroy() }
    }
}
