// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Flyfish233 and spake2 contributors
package io.github.flyfish233.spake2.legacy

import io.github.flyfish233.spake2.EntropySource
import io.github.flyfish233.spake2.Role
import io.github.flyfish233.spake2.Spake2Exception
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyConcurrencyTest {
    @Test fun overlappingOperationsInvalidateTheInFlightOutput() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>(null)
        val returned = AtomicReference<ByteArray?>(null)
        val session = LegacySpake25519(Role.ALICE, byteArrayOf(1), byteArrayOf(2), EntropySource {
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS))
            ByteArray(it).also { bytes -> bytes[0] = 1 }
        })
        val worker = thread {
            try { returned.set(session.generateMessage(byteArrayOf(3))) }
            catch (failure: Throwable) { thrown.set(failure) }
        }
        try {
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            assertFailsWith<Spake2Exception> { session.generateMessage(byteArrayOf(3)) }
        } finally { release.countDown(); worker.join(15000) }
        assertFalseAlive(worker)
        assertNull(returned.get())
        assertIs<Spake2Exception>(thrown.get())
        assertTrue(session.ownedSecretsAreZero())
        assertFailsWith<Spake2Exception> { session.generateMessage(byteArrayOf(3)) }
        session.destroy()
    }

    private fun assertFalseAlive(worker: Thread) = assertTrue(!worker.isAlive, "worker finished")
}
