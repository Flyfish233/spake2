// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Flyfish233 and spake2 contributors
package io.github.flyfish233.spake2.legacy

import io.github.flyfish233.spake2.EntropySource
import io.github.flyfish233.spake2.Role
import io.github.flyfish233.spake2.Spake2Exception
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal fun legacyHex(value: String): ByteArray = ByteArray(value.length / 2) {
    value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
}

internal fun legacyEntropy(hex: String): EntropySource = EntropySource { size ->
    legacyHex(hex).also { require(it.size == size) }
}

private fun mode(value: String): LegacyPasswordScalarMode = if (value == "1")
    LegacyPasswordScalarMode.PRE_HARDENING_UNSAFE else LegacyPasswordScalarMode.HARDENED

internal fun replayLegacyVector(line: String) {
    val v = line.split('|')
    val alice = LegacySpake25519(Role.ALICE, legacyHex(v[3]), legacyHex(v[4]), legacyEntropy(v[5]), mode(v[7]))
    val bob = LegacySpake25519(Role.BOB, legacyHex(v[4]), legacyHex(v[3]), legacyEntropy(v[6]), mode(v[8]))
    try {
        val a = alice.generateMessage(legacyHex(v[1]))
        val b = bob.generateMessage(legacyHex(v[2]))
        assertContentEquals(legacyHex(v[9]), a, "${v[0]} Alice message")
        assertContentEquals(legacyHex(v[10]), b, "${v[0]} Bob message")
        assertContentEquals(legacyHex(v[11]), alice.deriveUnconfirmedKey(b), "${v[0]} Alice key")
        assertContentEquals(legacyHex(v[12]), bob.deriveUnconfirmedKey(a), "${v[0]} Bob key")
        assertTrue(alice.ownedSecretsAreZero(), "${v[0]} Alice cleanup")
        assertTrue(bob.ownedSecretsAreZero(), "${v[0]} Bob cleanup")
    } finally { alice.destroy(); bob.destroy() }
}

internal fun replayLegacyProcessCase(line: String) {
    val v = line.split('|')
    val alice = LegacySpake25519(Role.ALICE, "alice".encodeToByteArray(), "bob".encodeToByteArray(),
        EntropySource { ByteArray(it).also { bytes -> bytes[0] = 1 } })
    try {
        alice.generateMessage("515109".encodeToByteArray())
        if (v[2] == "1") {
            assertContentEquals(legacyHex(v[3]), alice.deriveUnconfirmedKey(legacyHex(v[1])), v[0])
        } else {
            assertFailsWith<Spake2Exception>(v[0]) { alice.deriveUnconfirmedKey(legacyHex(v[1])) }
        }
        assertTrue(alice.ownedSecretsAreZero(), "${v[0]} cleanup")
    } finally { alice.destroy() }
}
