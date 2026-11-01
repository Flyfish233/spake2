// SPDX-License-Identifier: AGPL-3.0-only
package io.github.flyfish233.spake2

import kotlin.test.Test
import kotlin.test.assertContentEquals

class ScaffoldTest {
    @Test fun entropyContractIsAnApplicationBoundary() {
        val source = EntropySource { count -> ByteArray(count) { it.toByte() } }
        assertContentEquals(byteArrayOf(0, 1, 2), source.nextBytes(3))
    }
}
