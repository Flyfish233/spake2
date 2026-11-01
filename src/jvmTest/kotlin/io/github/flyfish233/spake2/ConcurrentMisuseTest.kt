// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2

import io.github.flyfish233.spake2.rfc.Rfc9382Session
import io.github.flyfish233.spake2.rfc.Rfc9382Suite
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

class ConcurrentMisuseTest {
    @Test fun concurrentCallPoisonsOwnerWithoutConcurrentSecretMutation() {
        for (suite in Rfc9382Suite.entries) {
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val result = AtomicReference<Throwable?>()
            val s = Rfc9382Session(suite, Role.ALICE, ByteArray(32).also { it[31] = 42 },
                byteArrayOf(1), byteArrayOf(2), entropy = EntropySource {
                    entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                    ByteArray(32).also { it[31] = 3 }
                })
            val owner = thread {
                try { s.generateMessage(); result.set(AssertionError("Owner must fail")) }
                catch (failure: Throwable) { result.set(failure) }
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertFailsWith<Spake2Exception> { s.generateMessage() }
            } finally { release.countDown(); owner.join(10000) }
            assertFalse(owner.isAlive)
            assertTrue(result.get() is Spake2Exception)
            assertEquals(Rfc9382Session.State.FAILED, s.state)
            assertTrue(s.ownedSecretsAreErased())
        }
    }
}
